package io.github.maelmbange.thighhigh.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.maelmbange.thighhigh.genre.Genre
import io.github.maelmbange.thighhigh.genre.decodeExcerpt
import io.github.maelmbange.thighhigh.genre.existingGenre
import io.github.maelmbange.thighhigh.genre.tagGenre
import io.github.maelmbange.thighhigh.library.MUSIC_RELATIVE_PATH
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.library.scanLibrary
import kotlin.math.abs
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class DownloadStatus { Waiting, Downloading, Analyzing, Saving, Comparing, Done, Duplicate, Failed, Canceled }

/** A track of the library, as shown on a download's card. */
data class Match(val trackId: Long, val title: String, val artist: String?)

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String? = null,
    /** 0..100 while downloading, `null` when unknown. */
    val percent: Float? = null,
    val status: DownloadStatus = DownloadStatus.Waiting,
    val error: String? = null,
    /** Downloaded anyway, although this link is already in the library. */
    val force: Boolean = false,
    /** The new track, once saved. */
    val trackId: Long? = null,
    /** [DownloadStatus.Duplicate]: the track this link already gave, null when it is being downloaded right now. */
    val existing: Match? = null,
    /** An older track that sounds like the new one: the user picks one or keeps both. */
    val similar: Match? = null,
    /** The older version was deleted for this one. */
    val replaced: Boolean = false,
) {
    val isActive get() = status in setOf(
        DownloadStatus.Waiting, DownloadStatus.Downloading, DownloadStatus.Analyzing,
        DownloadStatus.Saving, DownloadStatus.Comparing,
    )
}

/** How the last yt-dlp update check ended. */
enum class UpdateResult { Updated, UpToDate, Failed }

/** The bundled yt-dlp: its version and the state of its updates. */
data class YtDlpState(
    val version: String? = null,
    val updating: Boolean = false,
    /** When yt-dlp was last checked for an update, 0 if never. */
    val lastCheckMs: Long = 0L,
    /** The result of the check made since the app started, if any. */
    val result: UpdateResult? = null,
)

/** Automatic checks run at most this often (sites change, yt-dlp follows within days). */
private const val AUTO_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Same formats the terminal app treats as music. */
private val AUDIO_EXTENSIONS = mapOf(
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "opus" to "audio/ogg",
    "ogg" to "audio/ogg",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
)

private const val TAG = "Downloader"

private const val LAST_CHECK = "last_check"

// Prefixes on yt-dlp's printed lines so they can be told apart.
private const val TITLE = "[t]"
private const val FILE = "[f]"
private const val SOURCE = "[i]"

/** Tracks this much longer or shorter can still be another upload of the same song (intro, outro). */
private const val DURATION_SLACK_MS = 6_000L

/** At most this many tracks of a close length are listened to after a download. */
private const val MAX_CANDIDATES = 8

/**
 * Downloads links with yt-dlp (bundled by youtubedl-android, with Python and
 * ffmpeg), using the same options as the terminal app, then files the result
 * in Music/thigh-high through MediaStore.
 */
class Downloader(context: Context) {
    private val app = context.applicationContext
    // A failure must end up on the job's card, never crash the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "download coroutine failed", e)
        }
    )
    private val slots = Semaphore(2)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Newest first. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _added = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits whenever a new track lands in the music folder. */
    val added: SharedFlow<Unit> = _added.asSharedFlow()

    /** Unpacks Python/yt-dlp/ffmpeg, once per launch. */
    private val ready = scope.async(start = CoroutineStart.LAZY) {
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
    }

    private val prefs = app.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)

    private val _ytdlp = MutableStateFlow(YtDlpState(lastCheckMs = prefs.getLong(LAST_CHECK, 0L)))
    val ytdlp: StateFlow<YtDlpState> = _ytdlp.asStateFlow()

    /** Held while yt-dlp is being replaced: downloads wait for it to finish. */
    private val updating = Mutex()

    private val sources = SourceIndex(app)

    /** The videos being downloaded right now, so the same one pasted twice runs once. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Downloads running yt-dlp right now; an update waits until there are none. */
    private val running = AtomicInteger(0)

    /**
     * Called when the app opens: brings yt-dlp up to date in the background,
     * at most once a day, since sites keep changing and an old yt-dlp soon
     * fails on them.
     */
    fun autoUpdate() {
        val due = System.currentTimeMillis() - _ytdlp.value.lastCheckMs >= AUTO_UPDATE_INTERVAL_MS
        if (due) {
            update()
        } else {
            scope.launch {
                runCatching { ready.await() }
                _ytdlp.update { it.copy(version = YoutubeDL.getInstance().version(app)) }
            }
        }
    }

    /** Checks for a new yt-dlp now (the button on the download screen). */
    fun update() {
        if (_ytdlp.value.updating) return
        _ytdlp.update { it.copy(updating = true) }
        scope.launch {
            updating.withLock {
                // Never swap yt-dlp under a running download.
                while (running.get() > 0) delay(500)
                val result = try {
                    ready.await()
                    when (YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)) {
                        YoutubeDL.UpdateStatus.DONE -> UpdateResult.Updated
                        else -> UpdateResult.UpToDate
                    }
                } catch (e: Exception) {
                    // Offline or GitHub unreachable: the current yt-dlp keeps working.
                    Log.w(TAG, "yt-dlp update failed", e)
                    UpdateResult.Failed
                }
                val now = System.currentTimeMillis()
                // A failed check is retried at the next launch, not a day later.
                if (result != UpdateResult.Failed) prefs.edit().putLong(LAST_CHECK, now).apply()
                val version = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()
                _ytdlp.update {
                    YtDlpState(
                        version = version,
                        updating = false,
                        lastCheckMs = if (result != UpdateResult.Failed) now else it.lastCheckMs,
                        result = result,
                    )
                }
            }
        }
    }

    fun start(url: String) = start(DownloadJob(id = UUID.randomUUID().toString(), url = url.trim()))

    /** Downloads a link that was stopped for being already in the library. */
    fun downloadAnyway(id: String) {
        val old = current(id)?.takeIf { it.status == DownloadStatus.Duplicate } ?: return
        dismiss(id)
        start(DownloadJob(id = UUID.randomUUID().toString(), url = old.url, title = old.title, force = true))
    }

    /** The user keeps both versions of a song that was downloaded again. */
    fun keepBoth(id: String) = set(id) { it.copy(similar = null) }

    /** The older version of [id]'s song was deleted: its link now counts for the new one. */
    fun replaced(id: String) {
        val job = current(id) ?: return
        val old = job.similar ?: return
        job.trackId?.let { sources.repoint(old.trackId, it) }
        set(id) { it.copy(similar = null, replaced = true) }
    }

    private fun start(job: DownloadJob) {
        _jobs.update { listOf(job) + it }
        scope.launch {
            try {
                ready.await()
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp init failed", e)
                set(job.id) { it.copy(status = DownloadStatus.Failed, error = "yt-dlp n'a pas pu démarrer : ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            slots.withPermit {
                // Wait for an update in progress, then keep it off while yt-dlp runs.
                updating.withLock { running.incrementAndGet() }
                try {
                    run(job)
                } finally {
                    running.decrementAndGet()
                }
            }
        }
    }

    fun cancel(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
        set(id) { if (it.isActive) it.copy(status = DownloadStatus.Canceled) else it }
    }

    fun dismiss(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && !it.isActive } }
    }

    private suspend fun run(job: DownloadJob) {
        if (current(job.id)?.status == DownloadStatus.Canceled) return
        set(job.id) { it.copy(status = DownloadStatus.Downloading) }
        val dir = File(app.cacheDir, "downloads/${job.id}").apply { mkdirs() }
        var source: String? = null
        try {
            val file = fetch(job, dir) { source = it }
            detectGenre(job, file)
            set(job.id) { it.copy(status = DownloadStatus.Saving, percent = 100f) }
            val trackId = save(file)
            source?.let { sources.put(it, trackId) }
            set(job.id) { it.copy(status = DownloadStatus.Comparing, trackId = trackId) }
            _added.tryEmit(Unit)
            val similar = try {
                findSimilar(trackId)
            } catch (e: Throwable) {
                Log.w(TAG, "duplicate check failed", e)
                null
            }
            set(job.id) { it.copy(status = DownloadStatus.Done, similar = similar) }
        } catch (e: Throwable) {
            when (current(job.id)?.status) {
                // Stopped on purpose: the card already says why.
                DownloadStatus.Duplicate, DownloadStatus.Canceled -> Unit
                else -> if (e is YoutubeDL.CanceledException) {
                    set(job.id) { it.copy(status = DownloadStatus.Canceled) }
                } else {
                    Log.e(TAG, "download failed", e)
                    set(job.id) { it.copy(status = DownloadStatus.Failed, error = errorMessage(e)) }
                }
            }
        } finally {
            source?.let { inFlight.remove(it, job.id) }
            dir.deleteRecursively()
        }
    }

    /**
     * Like the terminal app, new downloads get their genre detected when the
     * site gave none (YouTube's "Music" doesn't count). Never fails the download.
     */
    private fun detectGenre(job: DownloadJob, file: File) {
        if (existingGenre(file.path) != null) return
        set(job.id) { it.copy(status = DownloadStatus.Analyzing) }
        try {
            tagGenre(file.path)
        } catch (e: Throwable) {
            Log.w(TAG, "genre detection failed", e)
        }
    }

    /**
     * Called with the video's key as soon as yt-dlp knows it, before the
     * download: stops it when this video is already in the library or being
     * downloaded by another card.
     */
    private fun checkSource(job: DownloadJob, source: String): Boolean {
        val existing = if (job.force) null else sources.get(source)?.let(::trackInfo)
        val other = inFlight.putIfAbsent(source, job.id)
        if (existing == null && (other == null || other == job.id)) return true
        if (other == null) inFlight.remove(source, job.id)
        set(job.id) { it.copy(status = DownloadStatus.Duplicate, existing = existing, percent = null) }
        YoutubeDL.getInstance().destroyProcessById(job.id)
        return false
    }

    /** The library's track [id], or null if it was deleted since. */
    private fun trackInfo(id: Long): Match? {
        val projection = arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        return app.contentResolver.query(Track.uriOf(id), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Match(id, c.getString(0).orEmpty(), c.getString(1)?.takeIf { it != MediaStore.UNKNOWN_STRING })
        }
    }

    /**
     * Listens to the new track and to the library's tracks of about the same
     * length: another upload of the same song (other link, other channel,
     * re-encoded) has the same spectrum over time, whatever its title says.
     */
    private fun findSimilar(trackId: Long): Match? {
        val library = scanLibrary(app)
        val new = library.firstOrNull { it.id == trackId } ?: return null
        val length = new.duration?.inWholeMilliseconds ?: return null
        val candidates = library
            .filter { it.id != trackId && it.path != null && it.duration != null }
            .filter { abs(it.duration!!.inWholeMilliseconds - length) <= DURATION_SLACK_MS }
            .sortedBy { abs(it.duration!!.inWholeMilliseconds - length) }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val mine = fingerprintOf(new) ?: return null
        return candidates
            .mapNotNull { t -> fingerprintOf(t)?.let { t to mine.similarity(it) } }
            .filter { it.second >= Fingerprint.SAME_TRACK }
            .maxByOrNull { it.second }
            ?.first
            ?.let { Match(it.id, it.title, it.artist) }
    }

    private fun fingerprintOf(track: Track): Fingerprint? = try {
        Fingerprint.of(decodeExcerpt(track.path!!, track.duration!!.inWholeMilliseconds), Genre.sampleRate())
    } catch (e: Throwable) {
        Log.w(TAG, "could not listen to ${track.path}", e)
        null
    }

    /** Runs yt-dlp; retries without the cover if embedding it is what failed. */
    private fun fetch(job: DownloadJob, dir: File, onSource: (String) -> Unit): File = try {
        fetchOnce(job, dir, embedThumbnail = true, onSource)
    } catch (e: YoutubeDLException) {
        val audio = dir.listFiles()?.firstOrNull { it.extension.lowercase() in AUDIO_EXTENSIONS }
        if (audio != null && e.message.orEmpty().contains("thumbnail", ignoreCase = true)) audio
        else throw e
    }

    private fun fetchOnce(job: DownloadJob, dir: File, embedThumbnail: Boolean, onSource: (String) -> Unit): File {
        val request = YoutubeDLRequest(job.url).apply {
            addOption("--no-playlist")
            // Only the audio is kept: the best audio-only stream as-is (often
            // Opus); when a site only serves video+audio, --extract-audio copies
            // the audio track out with ffmpeg.
            addOption("-f", "bestaudio/best")
            addOption("--extract-audio")
            if (embedThumbnail) {
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
            }
            addOption("--embed-metadata")
            // Many videos have no artist field: fall back to the channel name.
            addOption("--parse-metadata", "%(artist,creator,uploader|)s:%(meta_artist)s")
            // --print implies --quiet: ask for the progress lines back.
            addOption("--newline")
            addOption("--progress")
            addOption("--print", "before_dl:$SOURCE%(extractor_key)s %(id)s")
            addOption("--print", "before_dl:$TITLE%(title)s")
            addOption("--print", "after_move:$FILE%(filepath)s")
            addOption("-P", dir.absolutePath)
            addOption("-o", "%(title)s.%(ext)s")
        }
        var path: String? = null
        YoutubeDL.getInstance().execute(request, job.id) { progress, _, line ->
            // Runs on the library's reader thread: an exception here would kill the app.
            runCatching {
                when {
                    line.startsWith(SOURCE) -> sourceKey(line.removePrefix(SOURCE))?.let { key ->
                        if (checkSource(job, key)) onSource(key)
                    }
                    line.startsWith(TITLE) -> set(job.id) { it.copy(title = line.removePrefix(TITLE).trim()) }
                    line.startsWith(FILE) -> path = line.removePrefix(FILE).trim()
                    progress > 0f -> set(job.id) { it.copy(percent = progress) }
                }
            }
        }
        val file = path?.let(::File)
            ?: dir.listFiles()?.firstOrNull { it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?: throw IllegalStateException("aucun fichier audio n'est sorti de ce lien")
        if (file.extension.lowercase() !in AUDIO_EXTENSIONS) {
            // Never put a video in the music folder.
            throw IllegalStateException("aucun fichier audio n'est sorti de ce lien (.${file.extension})")
        }
        return file
    }

    /** Copies the track into Music/thigh-high and has Android index its tags. */
    private suspend fun save(file: File): Long {
        val resolver = app.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_EXTENSIONS.getValue(file.extension.lowercase()))
            put(MediaStore.Audio.Media.RELATIVE_PATH, MUSIC_RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("impossible d'écrire dans $MUSIC_RELATIVE_PATH")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // Make sure title/artist/duration are read before the list refreshes.
        @Suppress("DEPRECATION")
        val path = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (path != null) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(app, arrayOf(path), null) { _, _ -> cont.resume(Unit) }
            }
        }
        return ContentUris.parseId(uri)
    }

    private fun current(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun set(id: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}

private fun errorMessage(e: Throwable): String {
    val text = e.message.orEmpty()
    val last = text.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
    return when {
        last != null && "audio codec" in last -> "cette vidéo n'a pas de piste audio"
        last != null -> last
        text.isNotBlank() -> text.lines().last { it.isNotBlank() }
        else -> e.javaClass.simpleName
    }
}
