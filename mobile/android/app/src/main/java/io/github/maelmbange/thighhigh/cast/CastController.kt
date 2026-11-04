package io.github.maelmbange.thighhigh.cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import io.github.maelmbange.thighhigh.library.Track
import io.github.maelmbange.thighhigh.lyrics.Lyrics
import io.github.maelmbange.thighhigh.lyrics.LyricsRepository
import io.github.maelmbange.thighhigh.lyrics.parseLrc
import io.github.maelmbange.thighhigh.playback.AudioTap
import io.github.maelmbange.thighhigh.ui.CoverPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.nio.ByteBuffer
import kotlin.math.min

/** A broadcasting device seen on the network. */
data class Found(
    /** The service name, unique on the network. */
    val key: String,
    val name: String,
    val host: String,
    val port: Int,
    val id: String,
    val open: Boolean,
    /** Runs the same protocol version as this app. */
    val compatible: Boolean,
)

/** What is heard from a broadcaster. */
data class CastTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val cover: Bitmap?,
    val palette: CoverPalette,
    val lyrics: Lyrics?,
)

/** Something to tell the user once, as a short message. */
enum class CastNotice { WrongCode, CodeChanged, Full, OtherVersion, Unreachable, Ended, Lost, CantStart }

sealed interface CastUi {
    data object Idle : CastUi
    data class Broadcasting(
        val name: String,
        val open: Boolean,
        val code: String,
        val listeners: List<String>,
        /** False when the network announcement failed (nobody can find it). */
        val visible: Boolean,
    ) : CastUi
    data class Browsing(val found: List<Found>) : CastUi
    data class Connecting(val name: String) : CastUi
    data class AskingCode(val name: String, val error: CastNotice?) : CastUi
    data class Listening(val name: String, val track: CastTrack?, val playing: Boolean) : CastUi
}

/**
 * The two sides of broadcasting on the local network ("Diffuser" and
 * "S'abonner"), on top of [CastNative] (TLS, code, audio frames, all in
 * Rust) and Android's NsdManager (finding devices with mDNS / DNS-SD).
 *
 * The player tells it where playback is ([position], [isPlaying]); it
 * calls [onListenStart] so local playback pauses while listening.
 */
class CastController(
    context: Context,
    private val scope: CoroutineScope,
    private val position: () -> Long,
    private val isPlaying: () -> Boolean,
    private val onListenStart: () -> Unit,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val dir = File(app.filesDir, "cast").apply { mkdirs() }.path
    private val lyricsRepository = LyricsRepository(app)

    private val _ui = MutableStateFlow<CastUi>(CastUi.Idle)
    val ui: StateFlow<CastUi> = _ui.asStateFlow()

    private val _notice = MutableStateFlow<CastNotice?>(null)
    /** Shown once by the screen, then cleared with [clearNotice]. */
    val notice: StateFlow<CastNotice?> = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }

    /** The name other devices see: the phone's name in Settings. */
    val deviceName: String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // ------------------------------------------------------------ Diffuser

    /** Guards [server]: the audio thread pushes while the UI may stop it. */
    private val serverLock = Any()
    private var server = 0L
    private var registration: NsdManager.RegistrationListener? = null
    private var broadcastJob: Job? = null
    private var trackJob: Job? = null
    private var pushBuffer = ByteArray(0)

    private val sink = object : AudioTap.CastSink {
        override fun push(buffer: ByteBuffer, rate: Int, channels: Int, float: Boolean) {
            val length = buffer.remaining()
            synchronized(serverLock) {
                if (server == 0L || length == 0) return
                if (pushBuffer.size < length) pushBuffer = ByteArray(length)
                buffer.duplicate().get(pushBuffer, 0, length)
                CastNative.serverPush(server, pushBuffer, length, rate, channels, float)
            }
        }
    }

    fun startBroadcast(current: Track?, cover: Bitmap?) {
        if (_ui.value is CastUi.Broadcasting) return
        stopListening()
        stopBrowsing()
        scope.launch {
            val handle = withContext(Dispatchers.IO) {
                runCatching { CastNative.serverStart(dir, deviceName, false) }.getOrNull()
            }
            if (handle == null) {
                _notice.value = CastNotice.CantStart
                return@launch
            }
            synchronized(serverLock) { server = handle }
            AudioTap.castSink = sink
            _ui.value = CastUi.Broadcasting(deviceName, open = false, code = CastNative.serverCode(handle), listeners = emptyList(), visible = true)
            announce(handle, open = false)
            setTrack(current, cover)
            broadcastJob = scope.launch {
                while (isActive) {
                    // The tap gets the audio before the speaker's buffer: stamp
                    // it with where playback will be when it is heard.
                    synchronized(serverLock) {
                        if (server != 0L) CastNative.serverSetPosition(server, position() + AudioTap.OUTPUT_LAG_MS, isPlaying())
                    }
                    _ui.update { ui ->
                        if (ui is CastUi.Broadcasting && server != 0L) ui.copy(
                            code = CastNative.serverCode(server),
                            listeners = CastNative.serverListeners(server).lines().filter { it.isNotEmpty() },
                        ) else ui
                    }
                    delay(250)
                }
            }
        }
    }

    /** The playing track (and its cover) for the listeners; the lyrics follow when found. */
    fun setTrack(track: Track?, cover: Bitmap?) {
        if (server == 0L) return
        trackJob?.cancel()
        trackJob = scope.launch(Dispatchers.IO) {
            if (track == null) return@launch
            val jpeg = cover?.let(::smallJpeg)
            fun send(lyrics: String?) = synchronized(serverLock) {
                if (server != 0L) CastNative.serverSetTrack(
                    server, track.title, track.artist, track.album,
                    track.duration?.inWholeMilliseconds ?: 0L, jpeg, lyrics,
                )
            }
            send(null)
            val lyrics = try {
                lyricsRepository.load(track).toText()
            } catch (_: Exception) {
                null
            }
            if (lyrics != null) send(lyrics)
        }
    }

    fun setOpen(open: Boolean) {
        val handle = server.takeIf { it != 0L } ?: return
        CastNative.serverSetOpen(handle, open)
        _ui.update { if (it is CastUi.Broadcasting) it.copy(open = open) else it }
        announce(handle, open)
    }

    fun forgetListeners() {
        server.takeIf { it != 0L }?.let { runCatching { CastNative.serverForgetListeners(it) } }
    }

    fun stopBroadcast() {
        broadcastJob?.cancel()
        trackJob?.cancel()
        unannounce()
        AudioTap.castSink = null
        val handle = synchronized(serverLock) { server.also { server = 0L } }
        if (handle != 0L) scope.launch(Dispatchers.IO) { CastNative.serverStop(handle) }
        if (_ui.value is CastUi.Broadcasting) _ui.value = CastUi.Idle
    }

    private fun announce(handle: Long, open: Boolean) {
        unannounce()
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = CastNative.SERVICE_TYPE
            port = CastNative.serverPort(handle)
            setAttribute("v", CastNative.PROTOCOL)
            setAttribute("id", CastNative.serverId(handle))
            setAttribute("open", if (open) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = setVisible(true)
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = setVisible(false)
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { setVisible(false) }
    }

    private fun setVisible(visible: Boolean) =
        _ui.update { if (it is CastUi.Broadcasting) it.copy(visible = visible) else it }

    private fun unannounce() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    // ----------------------------------------------------------- S'abonner

    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, Found>()
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun startBrowsing() {
        if (_ui.value is CastUi.Broadcasting || _ui.value is CastUi.Listening) return
        stopBrowsing()
        found.clear()
        _ui.value = CastUi.Browsing(emptyList())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = scope.launch { queueResolve(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = scope.launch {
                found.remove(info.serviceName)
                publishFound()
            }.let {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch { _notice.value = CastNotice.Unreachable }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(CastNative.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _notice.value = CastNotice.Unreachable }
    }

    fun stopBrowsing() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        toResolve.clear()
        if (_ui.value is CastUi.Browsing) _ui.value = CastUi.Idle
    }

    /** Older Android versions resolve one service at a time. */
    private fun queueResolve(info: NsdServiceInfo) {
        toResolve.addLast(info)
        if (!resolving) resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = toResolve.removeFirstOrNull()
        if (next == null || discovery == null) {
            resolving = false
            return
        }
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = scope.launch {
                resolved(info)
                resolveNext()
            }.let {}
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = scope.launch { resolveNext() }.let {}
        }
        runCatching { nsd.resolveService(next, listener) }.onFailure { resolveNext() }
    }

    @Suppress("DEPRECATION")
    private fun resolved(info: NsdServiceInfo) {
        if (discovery == null) return
        val ipv4 = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull { it is Inet4Address } else null
        val host = (ipv4 ?: info.host)?.hostAddress ?: return
        fun attr(key: String) = info.attributes[key]?.toString(Charsets.UTF_8)
        found[info.serviceName] = Found(
            key = info.serviceName,
            name = info.serviceName,
            host = host,
            port = info.port,
            id = attr("id").orEmpty(),
            open = attr("open") == "1",
            compatible = attr("v") == CastNative.PROTOCOL,
        )
        publishFound()
    }

    private fun publishFound() {
        _ui.update { if (it is CastUi.Browsing) it.copy(found = found.values.sortedBy { f -> f.name.lowercase() }) else it }
    }

    /** The connection waiting for its code, and the device it goes to. */
    private var pending = 0L
    private var pendingTo: Found? = null

    fun connect(to: Found, error: CastNotice? = null) {
        if (!to.compatible) {
            _notice.value = CastNotice.OtherVersion
            return
        }
        stopBrowsing()
        _ui.value = CastUi.Connecting(to.name)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastNative.clientConnect(dir, to.host, to.port, deviceName) }
            }
            if (_ui.value !is CastUi.Connecting) {
                // Cancelled meanwhile.
                result.getOrNull()?.let(CastNative::clientFree)
                return@launch
            }
            result.fold(
                onSuccess = { handle ->
                    if (CastNative.clientNeedsCode(handle)) {
                        pending = handle
                        pendingTo = to
                        _ui.value = CastUi.AskingCode(to.name, error)
                    } else {
                        listen(handle)
                    }
                },
                onFailure = { failed(it.message) },
            )
        }
    }

    fun submitCode(code: String) {
        val handle = pending.takeIf { it != 0L } ?: return
        val to = pendingTo ?: return
        pending = 0L
        _ui.value = CastUi.Connecting(to.name)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { CastNative.clientPair(handle, code) } }
            if (_ui.value !is CastUi.Connecting) {
                CastNative.clientFree(handle)
                return@launch
            }
            result.fold(
                onSuccess = { listen(handle) },
                onFailure = { err ->
                    CastNative.clientFree(handle)
                    when (CastNative.errorKind(err.message)) {
                        // The connection is spent: connect again and ask again.
                        "wrong_code" -> connect(to, CastNotice.WrongCode)
                        "code_changed" -> connect(to, CastNotice.CodeChanged)
                        else -> failed(err.message)
                    }
                },
            )
        }
    }

    private fun failed(message: String?) {
        _notice.value = when (CastNative.errorKind(message)) {
            "full" -> CastNotice.Full
            "protocol" -> CastNotice.OtherVersion
            else -> CastNotice.Unreachable
        }
        _ui.value = CastUi.Idle
        startBrowsing()
    }

    /** Gives up on connecting or on typing the code. */
    fun cancelConnecting() {
        pending.takeIf { it != 0L }?.let {
            CastNative.clientClose(it)
            CastNative.clientFree(it)
        }
        pending = 0L
        _ui.value = CastUi.Idle
        startBrowsing()
    }

    // ------------------------------------------------------------ Listening

    @Volatile private var session = 0L
    @Volatile private var stopping = false
    @Volatile private var output: AudioTrack? = null
    @Volatile private var outputRate = 0
    @Volatile private var framesWritten = 0L
    /** Where the last chunk written starts in the broadcaster's track. */
    @Volatile private var lastPosMs = 0L

    /** Frame (of the speaker's stream) where the last chunk written starts. */
    @Volatile private var lastChunkStart = 0L

    /** Position in the broadcaster's track of what is heard now, for the lyrics. */
    fun heardMs(): Long {
        val track = output ?: return lastPosMs
        val rate = outputRate.takeIf { it > 0 } ?: return lastPosMs
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        return (lastPosMs + (head - lastChunkStart) * 1000 / rate).coerceAtLeast(0)
    }

    private fun listen(handle: Long) {
        session = handle
        stopping = false
        onListenStart()
        val name = CastNative.clientName(handle)
        _ui.value = CastUi.Listening(name, track = null, playing = true)
        Thread({ receive(handle, name) }, "cast-listen").start()
    }

    private fun receive(handle: Long, name: String) {
        val samples = ShortArray(1 shl 16)
        var started = false
        var reason: CastNotice? = null
        try {
            while (true) {
                val n = CastNative.clientRead(handle, samples)
                when {
                    n > 0 -> {
                        val rate = CastNative.clientRate(handle)
                        if (rate != outputRate || output == null) {
                            output?.release()
                            output = newOutput(rate)
                            outputRate = rate
                            framesWritten = 0
                            started = false
                        }
                        val track = output ?: continue
                        val start = framesWritten
                        track.write(samples, 0, n, AudioTrack.WRITE_BLOCKING)
                        framesWritten += n / 2
                        lastChunkStart = start
                        lastPosMs = CastNative.clientPosMs(handle)
                        // A small cushion first, so the start never stutters.
                        if (!started && framesWritten >= rate * PREBUFFER_MS / 1000) {
                            track.play()
                            started = true
                        }
                    }
                    n == CastNative.TRACK -> {
                        val track = readTrack(handle)
                        _ui.update { if (it is CastUi.Listening) it.copy(track = track) else it }
                    }
                    n == CastNative.STATE -> {
                        val playing = CastNative.clientPlaying(handle)
                        _ui.update { if (it is CastUi.Listening) it.copy(playing = playing) else it }
                    }
                    n == CastNative.ENDED -> {
                        reason = CastNotice.Ended
                        break
                    }
                    else -> {
                        reason = CastNotice.Lost
                        break
                    }
                }
            }
        } finally {
            output?.let {
                runCatching { it.pause(); it.flush() }
                it.release()
            }
            output = null
            outputRate = 0
            CastNative.clientFree(handle)
            scope.launch {
                if (session == handle) {
                    session = 0L
                    if (!stopping) _notice.value = reason
                    if (_ui.value is CastUi.Listening) _ui.value = CastUi.Idle
                }
            }
        }
    }

    private fun readTrack(handle: Long): CastTrack {
        val fields = CastNative.clientTrack(handle).split('\n')
        val cover = CastNative.clientCover(handle)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val lyrics = CastNative.clientLyrics(handle)?.let { text ->
            parseLrc(text).takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it) } ?: Lyrics.Plain(text)
        }
        return CastTrack(
            title = fields.getOrElse(0) { "" },
            artist = fields.getOrElse(1) { "" },
            album = fields.getOrElse(2) { "" },
            durationMs = fields.getOrNull(3)?.toLongOrNull() ?: 0L,
            cover = cover,
            palette = cover?.let(CoverPalette::from) ?: CoverPalette.Default,
            lyrics = lyrics,
        )
    }

    /** The speaker for a broadcast: no other app may record it. */
    private fun newOutput(rate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build()
            )
            .setAudioFormat(format)
            // ~0.5 s: room for the cushion, little delay on top.
            .setBufferSizeInBytes(maxOf(minimum, rate * 4 * BUFFER_MS / 1000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun stopListening() {
        val handle = session.takeIf { it != 0L } ?: return
        stopping = true
        CastNative.clientClose(handle)
        session = 0L
        if (_ui.value is CastUi.Listening) _ui.value = CastUi.Idle
    }

    val isListening: Boolean get() = session != 0L

    /** Back to the start screen, ending whatever runs. */
    fun stopAll() {
        stopBroadcast()
        stopBrowsing()
        if (pending != 0L) cancelConnecting()
        stopListening()
        stopBrowsing()
        _ui.value = CastUi.Idle
    }

    private companion object {
        const val PREBUFFER_MS = 400
        const val BUFFER_MS = 600
        const val COVER_SIZE = 320
    }

    private fun smallJpeg(bitmap: Bitmap): ByteArray {
        val scale = min(1f, COVER_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        return ByteArrayOutputStream().use {
            small.compress(Bitmap.CompressFormat.JPEG, 85, it)
            it.toByteArray()
        }
    }
}

/** Lyrics as sent to listeners: LRC when timed, the words otherwise. */
fun Lyrics.toText(): String? = when (this) {
    is Lyrics.Synced -> lines.joinToString("\n") { line ->
        val cs = line.timeMs / 10
        "[%02d:%02d.%02d]%s".format(cs / 6000, cs / 100 % 60, cs % 100, line.text)
    }
    is Lyrics.Plain -> text
    else -> null
}
