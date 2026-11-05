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
import android.os.SystemClock
import android.provider.Settings
import io.github.maelmbange.thighhigh.R
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** A device listening now; [pairedId] is its entry among [PairedDevice]s, null if it came in open. */
data class CastListener(
    val pairedId: String?,
    val name: String,
    /** The radio it hears, or null for what this phone plays. */
    val radio: String? = null,
)

/** A playlist that can be offered as a radio. */
data class RadioSource(val playlistId: String, val name: String, val tracks: List<Track>)

/** A playlist on the broadcast screen, with whether it is offered as a radio. */
data class RadioOption(val playlistId: String, val name: String, val trackCount: Int, val on: Boolean)

/** A channel of the broadcaster being heard: [id] is [CastNative.LIVE] or a radio. */
data class CastChannel(val id: Int, val name: String, val listeners: Int = 0)

/** A listener that entered the code once and comes back without it. */
data class PairedDevice(val id: String, val name: String)

/** Something to tell the user once, as a short message. */
enum class CastNotice { WrongCode, CodeChanged, Full, OtherVersion, Unreachable, Ended, Lost, CantStart, NeedsCodeAgain }

sealed interface CastUi {
    data object Idle : CastUi
    /** "Diffuser" was chosen: the code or open mode is picked, nothing runs yet. */
    data class ReadyToBroadcast(val name: String, val open: Boolean) : CastUi
    data class Broadcasting(
        val name: String,
        val open: Boolean,
        val code: String,
        val listeners: List<CastListener>,
        val paired: List<PairedDevice> = emptyList(),
        /** False when the network announcement failed (nobody can find it). */
        val visible: Boolean,
    ) : CastUi
    data class Browsing(val found: List<Found>) : CastUi
    data class Connecting(val name: String) : CastUi
    data class AskingCode(val name: String, val error: CastNotice?) : CastUi
    data class Listening(
        val name: String,
        val track: CastTrack?,
        val playing: Boolean,
        /** The broadcaster's radios (live aside), and the channel heard. */
        val radios: List<CastChannel> = emptyList(),
        val channel: Int = CastNative.LIVE,
        /** Devices listening to live (each radio carries its own count). */
        val liveListeners: Int = 0,
        /** Set while switching to that channel. */
        val tuning: Int? = null,
    ) : CastUi
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
) {
    // Set by the screen's ViewModel; the controller itself lives as long as
    // the app (ThighHighApp), so closing the screen doesn't end a broadcast.
    @Volatile var position: () -> Long = { 0L }
    @Volatile var isPlaying: () -> Boolean = { false }
    @Volatile var onListenStart: () -> Unit = {}

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

    init {
        // A foreground service keeps the connection alive in the background.
        // Called on the main thread (notification button, app swiped away).
        CastService.onStopAsked = { stopAll() }
        scope.launch {
            ui.map { ui ->
                when (ui) {
                    is CastUi.Listening -> app.getString(R.string.cast_notif_listening, ui.name)
                    is CastUi.Broadcasting -> if (ui.listeners.isEmpty()) app.getString(R.string.cast_notif_broadcasting)
                        else app.getString(R.string.cast_notif_broadcasting_to, ui.listeners.size)
                    else -> null
                }
            }.distinctUntilChanged().collect { text ->
                if (text != null) CastService.show(app, text) else CastService.hide(app)
            }
        }
    }

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
    /** Set while the server starts, so a second tap starts nothing. */
    private var starting = false

    private val sink = object : AudioTap.CastSink {
        override fun push(buffer: ByteBuffer, rate: Int, channels: Int, float: Boolean) {
            val length = buffer.remaining()
            synchronized(serverLock) {
                if (server == 0L || length == 0) return
                if (pushBuffer.size < length) pushBuffer = ByteArray(length)
                buffer.duplicate().get(pushBuffer, 0, length)
                CastNative.serverPush(server, CastNative.LIVE, pushBuffer, length, rate, channels, float)
            }
        }
    }

    /** Shows the broadcast settings; [startBroadcast] then really starts it. */
    fun prepareBroadcast() {
        if (_ui.value is CastUi.Broadcasting) return
        stopListening()
        stopBrowsing()
        _ui.value = CastUi.ReadyToBroadcast(deviceName, open = false)
    }

    /** Back from the broadcast settings without starting. */
    fun cancelBroadcast() {
        if (_ui.value is CastUi.ReadyToBroadcast) _ui.value = CastUi.Idle
    }

    fun startBroadcast(current: Track?, cover: Bitmap?) {
        val open = (_ui.value as? CastUi.ReadyToBroadcast)?.open ?: return
        if (starting) return
        starting = true
        stopListening()
        stopBrowsing()
        scope.launch {
            val handle = withContext(Dispatchers.IO) {
                runCatching { CastNative.serverStart(dir, deviceName, open) }.getOrNull()
            }
            starting = false
            if (handle == null) {
                _notice.value = CastNotice.CantStart
                return@launch
            }
            synchronized(serverLock) { server = handle }
            AudioTap.castSink = sink
            _ui.value = CastUi.Broadcasting(deviceName, open = open, code = CastNative.serverCode(handle), listeners = emptyList(), visible = true)
            announce(handle, open = open)
            setTrack(current, cover)
            radioIds.clear()
            offerRadios()
            broadcastJob = scope.launch {
                var tick = 0
                while (isActive) {
                    if (tick++ % 4 == 0) runRadios()
                    // The tap gets the audio before the speaker's buffer: stamp
                    // it with where playback will be when it is heard.
                    synchronized(serverLock) {
                        if (server != 0L) CastNative.serverSetPosition(server, CastNative.LIVE, position() + AudioTap.OUTPUT_LAG_MS, isPlaying())
                    }
                    _ui.update { ui ->
                        if (ui is CastUi.Broadcasting && server != 0L) ui.copy(
                            code = CastNative.serverCode(server),
                            listeners = listeners(server),
                            paired = paired(server),
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
            sendTrack(CastNative.LIVE, track, cover)
        }
    }

    /** Title, cover, then the lyrics once found, to those hearing [channel]. */
    private suspend fun sendTrack(channel: Int, track: Track, cover: Bitmap?) {
        val jpeg = cover?.let(::smallJpeg)
        fun send(lyrics: String?) = synchronized(serverLock) {
            if (server != 0L) CastNative.serverSetTrack(
                server, channel, track.title, track.artist, track.album,
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

    // -------------------------------------------------------------- Radios

    private val prefs = app.getSharedPreferences("cast", Context.MODE_PRIVATE)
    private val sources = MutableStateFlow<List<RadioSource>>(emptyList())
    private val chosen = MutableStateFlow(prefs.getStringSet(RADIOS_KEY, null).orEmpty().toSet())

    /** The playlists that can be offered as radios, and which ones are. */
    val radioOptions: StateFlow<List<RadioOption>> = combine(sources, chosen) { list, on ->
        list.map { RadioOption(it.playlistId, it.name, it.tracks.size, it.playlistId in on && it.tracks.isNotEmpty()) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The playlists and their tracks, kept up to date by the library. */
    fun setRadioSources(list: List<RadioSource>) {
        val changed = sources.value != list
        sources.value = list
        if (changed) offerRadios()
    }

    /** Offers a playlist as a radio, or takes it off (also before the start). */
    fun setRadio(playlistId: String, on: Boolean) {
        chosen.update { if (on) it + playlistId else it - playlistId }
        prefs.edit().putStringSet(RADIOS_KEY, chosen.value).apply()
        offerRadios()
    }

    /** The radio number of each playlist, for this broadcast. */
    private val radioIds = HashMap<String, Int>()
    /** Radios playing now, and since when nobody listens to each one. */
    private val stations = HashMap<Int, RadioStation>()
    private val idleSince = HashMap<Int, Long>()

    private fun offered(): List<Pair<Int, RadioSource>> = sources.value
        .filter { it.playlistId in chosen.value && it.tracks.isNotEmpty() }
        .map { source -> radioIds.getOrPut(source.playlistId) { radioIds.size + 1 } to source }

    private fun offerRadios() {
        val handle = server.takeIf { it != 0L } ?: return
        val radios = offered()
        CastNative.serverSetRadios(handle, radios.joinToString("\n") { (id, source) -> "$id\t${source.name}" })
        // A radio taken off, or whose playlist changed, starts afresh when listened to.
        val wanted = radios.associate { (id, source) -> id to source.tracks }
        stations.keys.filter { wanted[it] == null || wanted[it] != stationTracks[it] }.forEach(::stopStation)
    }

    private val stationTracks = HashMap<Int, List<Track>>()

    /** Starts the radios someone listens to, stops those left alone for a while. */
    private fun runRadios() {
        val handle = server.takeIf { it != 0L } ?: return
        val listened = CastNative.serverRadiosListened(handle).toSet()
        val radios = offered().toMap()
        val now = SystemClock.elapsedRealtime()
        for (id in listened) {
            idleSince.remove(id)
            if (id in stations) continue
            val source = radios[id] ?: continue
            val station = RadioStation(app, id, source.tracks, radioSink)
            stations[id] = station
            stationTracks[id] = source.tracks
            station.start()
        }
        for (id in stations.keys.toList()) {
            if (id in listened) continue
            val since = idleSince.getOrPut(id) { now }
            // Kept a little: switching away and back doesn't restart it.
            if (now - since > RADIO_IDLE_MS) stopStation(id)
        }
    }

    private fun stopStation(id: Int) {
        stations.remove(id)?.stop()
        stationTracks.remove(id)
        idleSince.remove(id)
    }

    private fun stopStations() {
        stations.keys.toList().forEach(::stopStation)
    }

    private val radioSink = object : RadioStation.Sink {
        private var buffers = HashMap<Int, ByteArray>()

        override fun track(radio: Int, track: Track) {
            scope.launch(Dispatchers.IO) {
                val cover = try {
                    app.contentResolver.loadThumbnail(track.uri, android.util.Size(COVER_SIZE, COVER_SIZE), null)
                } catch (_: Exception) {
                    null // no embedded cover
                }
                sendTrack(radio, track, cover)
            }
        }

        override fun position(radio: Int, positionMs: Long) = synchronized(serverLock) {
            if (server != 0L) CastNative.serverSetPosition(server, radio, positionMs, true)
        }

        override fun audio(radio: Int, bytes: ByteArray, length: Int, rate: Int, channels: Int, float: Boolean) =
            synchronized(serverLock) {
                if (server != 0L) CastNative.serverPush(server, radio, bytes, length, rate, channels, float)
            }
    }

    fun setOpen(open: Boolean) {
        if (_ui.value is CastUi.ReadyToBroadcast) {
            _ui.update { if (it is CastUi.ReadyToBroadcast) it.copy(open = open) else it }
            return
        }
        val handle = server.takeIf { it != 0L } ?: return
        // Closing lets go of the devices that came in without the code.
        CastNative.serverSetOpen(handle, open)
        _ui.update { if (it is CastUi.Broadcasting) it.copy(open = open, listeners = listeners(handle)) else it }
        announce(handle, open)
    }

    private fun paired(handle: Long): List<PairedDevice> =
        CastNative.serverPaired(handle).lines().mapNotNull { line ->
            val id = line.substringBefore('\t', "")
            if (id.isEmpty()) null else PairedDevice(id, line.substringAfter('\t'))
        }

    private fun listeners(handle: Long): List<CastListener> {
        val names = offered().associate { (id, source) -> id to source.name }
        return CastNative.serverListeners(handle).lines().filter { it.isNotEmpty() }.map { line ->
            val fields = line.split('\t', limit = 3)
            CastListener(
                pairedId = fields[0].ifEmpty { null },
                name = fields.getOrElse(2) { "" },
                radio = fields.getOrNull(1)?.toIntOrNull()?.let(names::get),
            )
        }
    }

    /** Every paired device must type the code again (those listening go, unless open). */
    fun forgetAllPaired() {
        val handle = server.takeIf { it != 0L } ?: return
        runCatching { CastNative.serverForgetListeners(handle) }
        _ui.update { if (it is CastUi.Broadcasting) it.copy(paired = paired(handle), listeners = listeners(handle)) else it }
    }

    /** That device must type the code again; it is let go if it listens now. */
    fun forgetPaired(id: String) {
        val handle = server.takeIf { it != 0L } ?: return
        runCatching { CastNative.serverForgetPaired(handle, id) }
        _ui.update { if (it is CastUi.Broadcasting) it.copy(paired = paired(handle), listeners = listeners(handle)) else it }
    }

    fun stopBroadcast() {
        broadcastJob?.cancel()
        trackJob?.cancel()
        stopStations()
        unannounce()
        AudioTap.castSink = null
        val handle = synchronized(serverLock) { server.also { server = 0L } }
        if (handle != 0L) scope.launch(Dispatchers.IO) { CastNative.serverStop(handle) }
        // Back to the start button, with the same choice of open or code.
        (_ui.value as? CastUi.Broadcasting)?.let { _ui.value = CastUi.ReadyToBroadcast(it.name, it.open) }
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

    /** The connection waiting for its code, and the device and channel it goes to. */
    private var pending = 0L
    private var pendingTo: Found? = null
    private var pendingChannel = CastNative.LIVE
    /** The device heard, to tune to its other channels. */
    private var listeningTo: Found? = null

    fun connect(to: Found, error: CastNotice? = null, channel: Int = CastNative.LIVE) {
        if (!to.compatible) {
            _notice.value = CastNotice.OtherVersion
            return
        }
        stopBrowsing()
        _ui.value = CastUi.Connecting(to.name)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastNative.clientConnect(dir, to.host, to.port, deviceName, channel) }
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
                        pendingChannel = channel
                        _ui.value = CastUi.AskingCode(to.name, error)
                    } else {
                        listen(handle, to, channel)
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
            val channel = pendingChannel
            result.fold(
                onSuccess = { listen(handle, to, channel) },
                onFailure = { err ->
                    CastNative.clientFree(handle)
                    when (CastNative.errorKind(err.message)) {
                        // The connection is spent: connect again and ask again.
                        "wrong_code" -> connect(to, CastNotice.WrongCode, channel)
                        "code_changed" -> connect(to, CastNotice.CodeChanged, channel)
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

    private var receiver: Thread? = null

    private fun listen(handle: Long, to: Found, channel: Int) {
        session = handle
        listeningTo = to
        stopping = false
        onListenStart()
        val name = CastNative.clientName(handle)
        // Switching channels keeps the list of radios meanwhile.
        val radios = (_ui.value as? CastUi.Listening)?.radios.orEmpty()
        _ui.value = CastUi.Listening(name, track = null, playing = true, radios = radios, channel = channel)
        AudioTap.setLive(true, lagMs = BUFFER_MS + 40)
        receiver = Thread({ receive(handle, name) }, "cast-listen").apply { start() }
    }

    /**
     * Hears another channel of the same device: live or one of its radios.
     * The current one plays on until the new connection is ready.
     */
    fun tune(channel: Int) {
        val listening = _ui.value as? CastUi.Listening ?: return
        val to = listeningTo ?: return
        val old = session.takeIf { it != 0L } ?: return
        if (listening.tuning != null || listening.channel == channel) return
        _ui.update { if (it is CastUi.Listening) it.copy(tuning = channel) else it }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastNative.clientConnect(dir, to.host, to.port, deviceName, channel) }
            }
            val handle = result.getOrNull()
            if (session != old) {
                // Stopped meanwhile.
                handle?.let { CastNative.clientClose(it); CastNative.clientFree(it) }
                return@launch
            }
            if (handle == null || CastNative.clientNeedsCode(handle)) {
                // No longer paired (or unreachable): this channel plays on.
                handle?.let { CastNative.clientClose(it); CastNative.clientFree(it) }
                _notice.value = if (handle == null) CastNotice.Unreachable else CastNotice.NeedsCodeAgain
                _ui.update { if (it is CastUi.Listening) it.copy(tuning = null) else it }
                return@launch
            }
            // The old session's thread now sees it is no longer the one heard.
            session = handle
            CastNative.clientClose(old)
            val previous = receiver
            withContext(Dispatchers.IO) { previous?.join(2_000) }
            listen(handle, to, channel)
        }
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
                        // The player's animations follow what is heard here.
                        AudioTap.pushLive(samples, n, rate)
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
                        if (session == handle) _ui.update { if (it is CastUi.Listening) it.copy(track = track) else it }
                    }
                    n == CastNative.CHANNELS -> {
                        val lines = CastNative.clientChannels(handle).lines()
                        val head = lines.firstOrNull().orEmpty().split('\t')
                        val current = head.getOrNull(0)?.toIntOrNull() ?: CastNative.LIVE
                        val live = head.getOrNull(1)?.toIntOrNull() ?: 0
                        val radios = lines.drop(1).mapNotNull { line ->
                            val fields = line.split('\t', limit = 3)
                            val id = fields[0].toIntOrNull() ?: return@mapNotNull null
                            CastChannel(id, fields.getOrElse(2) { "" }, fields.getOrNull(1)?.toIntOrNull() ?: 0)
                        }
                        if (session == handle) _ui.update {
                            if (it is CastUi.Listening) it.copy(radios = radios, channel = current, liveListeners = live, tuning = null) else it
                        }
                    }
                    n == CastNative.STATE -> {
                        val playing = CastNative.clientPlaying(handle)
                        if (session == handle) _ui.update { if (it is CastUi.Listening) it.copy(playing = playing) else it }
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
            // Unless another broadcast is already being heard.
            if (session == 0L || session == handle) AudioTap.setLive(false)
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

    /** Leaves this broadcast and shows the devices again, to pick another one. */
    fun switchChannel() {
        stopListening()
        startBrowsing()
    }

    val isListening: Boolean get() = session != 0L

    /** Back to the start screen, ending whatever runs. */
    fun stopAll() {
        stopBroadcast()
        cancelBroadcast()
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
        const val RADIOS_KEY = "radios"
        /** A radio nobody listens to plays on this long, then stops. */
        const val RADIO_IDLE_MS = 15_000L
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
