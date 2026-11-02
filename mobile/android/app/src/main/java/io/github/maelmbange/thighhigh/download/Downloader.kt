package io.github.maelmbange.thighhigh.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.maelmbange.thighhigh.library.MUSIC_RELATIVE_PATH
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DownloadStatus { Waiting, Downloading, Saving, Done, Failed, Canceled }

data class DownloadJob(
    val id: String,
    val url: String,
    val title: String? = null,
    /** 0..100 while downloading, `null` when unknown. */
    val percent: Float? = null,
    val status: DownloadStatus = DownloadStatus.Waiting,
    val error: String? = null,
) {
    val isActive get() = status in setOf(DownloadStatus.Waiting, DownloadStatus.Downloading, DownloadStatus.Saving)
}

/** Same formats the terminal app treats as music. */
private val AUDIO_EXTENSIONS = mapOf(
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "opus" to "audio/ogg",
    "ogg" to "audio/ogg",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
)

// Prefixes on yt-dlp's printed lines so they can be told apart.
private const val TITLE = "[t]"
private const val FILE = "[f]"

/**
 * Downloads links with yt-dlp (bundled by youtubedl-android, with Python and
 * ffmpeg), using the same options as the terminal app, then files the result
 * in Music/thigh-high through MediaStore.
 */
class Downloader(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Newest first. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _added = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emits whenever a new track lands in the music folder. */
    val added: SharedFlow<Unit> = _added.asSharedFlow()

    /** Unpacks Python/yt-dlp/ffmpeg once, then brings yt-dlp up to date (sites change often). */
    private val ready = scope.async(start = CoroutineStart.LAZY) {
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        try {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
        } catch (_: Exception) {
            // Offline or GitHub unreachable: the bundled yt-dlp still works for most links.
        }
    }

    fun start(url: String) {
        val job = DownloadJob(id = UUID.randomUUID().toString(), url = url.trim())
        _jobs.update { listOf(job) + it }
        scope.launch {
            try {
                ready.await()
            } catch (e: Exception) {
                set(job.id) { it.copy(status = DownloadStatus.Failed, error = "yt-dlp n'a pas pu démarrer : ${e.message}") }
                return@launch
            }
            slots.withPermit { run(job) }
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
        try {
            val file = fetch(job, dir)
            set(job.id) { it.copy(status = DownloadStatus.Saving, percent = 100f) }
            save(file)
            set(job.id) { it.copy(status = DownloadStatus.Done) }
            _added.tryEmit(Unit)
        } catch (_: YoutubeDL.CanceledException) {
            set(job.id) { it.copy(status = DownloadStatus.Canceled) }
        } catch (e: Exception) {
            if (current(job.id)?.status != DownloadStatus.Canceled) {
                set(job.id) { it.copy(status = DownloadStatus.Failed, error = errorMessage(e)) }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Runs yt-dlp; retries without the cover if embedding it is what failed. */
    private fun fetch(job: DownloadJob, dir: File): File = try {
        fetchOnce(job, dir, embedThumbnail = true)
    } catch (e: YoutubeDLException) {
        val audio = dir.listFiles()?.firstOrNull { it.extension.lowercase() in AUDIO_EXTENSIONS }
        if (audio != null && e.message.orEmpty().contains("thumbnail", ignoreCase = true)) audio
        else throw e
    }

    private fun fetchOnce(job: DownloadJob, dir: File, embedThumbnail: Boolean): File {
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
            addOption("--print", "before_dl:$TITLE%(title)s")
            addOption("--print", "after_move:$FILE%(filepath)s")
            addOption("-P", dir.absolutePath)
            addOption("-o", "%(title)s.%(ext)s")
        }
        var path: String? = null
        YoutubeDL.getInstance().execute(request, job.id) { progress, _, line ->
            when {
                line.startsWith(TITLE) -> set(job.id) { it.copy(title = line.removePrefix(TITLE).trim()) }
                line.startsWith(FILE) -> path = line.removePrefix(FILE).trim()
                progress > 0f -> set(job.id) { it.copy(percent = progress) }
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
    private suspend fun save(file: File) {
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
    }

    private fun current(id: String) = _jobs.value.firstOrNull { it.id == id }

    private fun set(id: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}

private fun errorMessage(e: Exception): String {
    val text = e.message.orEmpty()
    val last = text.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
    return when {
        last != null && "audio codec" in last -> "cette vidéo n'a pas de piste audio"
        last != null -> last
        text.isNotBlank() -> text.lines().last { it.isNotBlank() }
        else -> e.javaClass.simpleName
    }
}
