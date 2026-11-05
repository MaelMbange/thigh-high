package io.github.maelmbange.thighhigh.cast

/**
 * Broadcasting from the Rust crate `crates/cast`, through the JNI bridge in
 * `crates/android/src/cast.rs`: the same protocol as the terminal app, so a
 * phone and a PC can listen to each other.
 *
 * Handles are Rust objects: each one is freed exactly once, after its last
 * use ([serverStop], [clientFree]).
 */
object CastNative {
    init {
        System.loadLibrary("thigh_high_android")
    }

    // Diffuser.
    @JvmStatic external fun serverStart(dir: String, name: String, open: Boolean): Long
    @JvmStatic external fun serverStop(handle: Long)
    @JvmStatic external fun serverPort(handle: Long): Int
    /** Start of the key's fingerprint, announced so devices can be told apart. */
    @JvmStatic external fun serverId(handle: Long): String
    @JvmStatic external fun serverCode(handle: Long): String
    @JvmStatic external fun serverSetOpen(handle: Long, open: Boolean)
    /** The listening devices, one per line: paired id (empty if none), a tab, the channel heard, a tab, the name. */
    @JvmStatic external fun serverListeners(handle: Long): String
    /** Forgets every paired device; those listening go, unless the broadcast is open. */
    @JvmStatic external fun serverForgetListeners(handle: Long)
    /** Paired listeners, one per line: id, a tab, then the name. */
    @JvmStatic external fun serverPaired(handle: Long): String
    @JvmStatic external fun serverForgetPaired(handle: Long, id: String)
    /** The radios on offer, one per line: id (not [LIVE]), a tab, the name. */
    @JvmStatic external fun serverSetRadios(handle: Long, lines: String)
    /** The radios someone listens to now: only those need to play. */
    @JvmStatic external fun serverRadiosListened(handle: Long): IntArray
    // [channel] is [LIVE] (what this phone plays) or a radio's id.
    @JvmStatic external fun serverSetTrack(
        handle: Long,
        channel: Int,
        title: String,
        artist: String?,
        album: String?,
        durationMs: Long,
        cover: ByteArray?,
        lyrics: String?,
    )
    @JvmStatic external fun serverSetPosition(handle: Long, channel: Int, positionMs: Long, playing: Boolean)
    /** PCM as the player outputs it (16-bit or float, interleaved). Never blocks. */
    @JvmStatic external fun serverPush(handle: Long, channel: Int, bytes: ByteArray, length: Int, rate: Int, channels: Int, float: Boolean)

    // S'abonner. Errors are thrown as "kind|message": see [errorKind].
    /** Blocking (a few seconds at most): call off the main thread. */
    @JvmStatic external fun clientConnect(dir: String, host: String, port: Int, name: String, channel: Int): Long
    @JvmStatic external fun clientNeedsCode(handle: Long): Boolean
    /** Blocking. On a wrong code the connection is spent: connect again. */
    @JvmStatic external fun clientPair(handle: Long, code: String)
    @JvmStatic external fun clientName(handle: Long): String
    /** Waits for the next message: a sample count (> 0) or [ENDED], [TRACK], [STATE], [FAILED], [CHANNELS]. */
    @JvmStatic external fun clientRead(handle: Long, out: ShortArray): Int
    @JvmStatic external fun clientPosMs(handle: Long): Long
    @JvmStatic external fun clientRate(handle: Long): Int
    @JvmStatic external fun clientPlaying(handle: Long): Boolean
    @JvmStatic external fun clientError(handle: Long): String
    /** Title, artist, album and duration (ms), one per line. */
    @JvmStatic external fun clientTrack(handle: Long): String
    /** The channel heard and live's listeners (tab apart) first, then each radio: id, listeners, name, tab apart. */
    @JvmStatic external fun clientChannels(handle: Long): String
    @JvmStatic external fun clientCover(handle: Long): ByteArray?
    @JvmStatic external fun clientLyrics(handle: Long): String?
    /** Unblocks a waiting read or pairing, from any thread. */
    @JvmStatic external fun clientClose(handle: Long)
    @JvmStatic external fun clientFree(handle: Long)

    const val ENDED = -1
    const val TRACK = -2
    const val STATE = -3
    const val FAILED = -4
    const val CHANNELS = -5

    /** The channel of what the broadcaster itself plays. */
    const val LIVE = 0

    /** Same as `thigh_high_cast::SERVICE_TYPE` and `wire::PROTOCOL`. */
    const val SERVICE_TYPE = "_thighhigh._tcp"
    const val PROTOCOL = "3"

    /** The kind of a thrown or reported error: "wrong_code", "code_changed", "full", "protocol", "io". */
    fun errorKind(message: String?): String = message?.substringBefore('|', "io") ?: "io"
}
