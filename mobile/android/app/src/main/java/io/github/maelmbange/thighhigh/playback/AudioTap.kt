package io.github.maelmbange.thighhigh.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The audio the player is about to hand to the speaker, kept for the
 * oscilloscope (the Android side of the core crate's `Tap`).
 *
 * Samples reach this tap before the audio track's buffer, so the waveform is
 * read [OUTPUT_LAG_MS] behind the newest sample to line up with what is heard.
 */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    /** Same size as the terminal app's scope window. */
    const val WINDOW = 1024

    /** The audio track buffer (fixed in PlaybackService) plus typical output latency. */
    const val AUDIO_BUFFER_MS = 250
    /** How far the samples tapped here are ahead of what is heard. */
    const val OUTPUT_LAG_MS = AUDIO_BUFFER_MS + 40

    /** Where the tapped audio also goes while broadcasting (see CastController). */
    interface CastSink {
        fun push(buffer: ByteBuffer, rate: Int, channels: Int, float: Boolean)
    }

    @Volatile
    var castSink: CastSink? = null

    private const val CAPACITY = 1 shl 16 // ~1.4 s at 48 kHz, mono
    private val ring = FloatArray(CAPACITY)
    private val lock = Any()

    /** Set by the player: the tap is only drawn while the music is actually heard. */
    @Volatile
    var playing = false

    /**
     * Set while listening to a broadcast: the waveform is then the audio heard
     * from it ([pushLive]), [liveLagMs] ahead of the speaker.
     */
    @Volatile
    private var live = false
    @Volatile
    private var liveRate = 44_100
    @Volatile
    private var liveLagMs = OUTPUT_LAG_MS

    private var written = 0L
    private var lastWriteNanos = 0L
    @Volatile
    private var sampleRate = 44_100

    /** The rate of the audio being heard, for filters tuned in Hz. */
    val rate: Int get() = if (live) liveRate else sampleRate
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            sampleRate = sampleRateHz
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        castSink?.let { sink ->
            val encoding = encoding
            if (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) {
                sink.push(buffer, sampleRate, channels, encoding == C.ENCODING_PCM_FLOAT)
            }
        }
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        synchronized(lock) {
            // A broadcast is heard: the (paused) player's buffers don't count.
            if (live) return
            val bytes = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val frames = data.remaining() / (bytes * channels)
            repeat(frames) {
                var sum = 0f
                repeat(channels) {
                    sum += if (bytes == 2) data.short / 32768f else data.float
                }
                ring[(written and (CAPACITY - 1).toLong()).toInt()] = sum / channels
                written++
            }
            lastWriteNanos = System.nanoTime()
        }
    }

    /** Starts or ends showing a broadcast's audio instead of the player's. */
    fun setLive(on: Boolean, lagMs: Int = OUTPUT_LAG_MS) = synchronized(lock) {
        live = on
        liveLagMs = lagMs
        written = 0L
    }

    /** Interleaved 16-bit stereo just written to the speaker, while listening. */
    fun pushLive(samples: ShortArray, count: Int, rate: Int) = synchronized(lock) {
        if (!live) return
        liveRate = rate
        var i = 0
        while (i + 1 < count) {
            ring[(written and (CAPACITY - 1).toLong()).toInt()] = (samples[i] + samples[i + 1]) / 65536f
            written++
            i += 2
        }
        lastWriteNanos = System.nanoTime()
    }

    /**
     * Fills [out] with the samples being heard now, oldest first.
     * Returns false (and fills silence) when nothing is playing.
     */
    fun waveform(out: FloatArray): Boolean = synchronized(lock) {
        val sinceWrite = (System.nanoTime() - lastWriteNanos) / 1_000_000
        val lag = if (live) liveLagMs else OUTPUT_LAG_MS
        val sampleRate = if (live) liveRate else sampleRate
        // Paused or stopped: the player may still fill its buffer (after a seek,
        // for instance), but none of it is heard, so the trace goes flat at once.
        if (!(playing || live) || written == 0L || sinceWrite > 150 + lag) {
            out.fill(0f)
            return false
        }
        // Writes arrive in bursts: move the read point on with the clock in between.
        val ahead = sinceWrite * sampleRate / 1000
        val end = (written + ahead - lag.toLong() * sampleRate / 1000)
            .coerceAtMost(written)
        val start = end - out.size
        for (i in out.indices) {
            val index = start + i
            out[i] = if (index < 0 || index < written - CAPACITY) 0f
            else ring[(index and (CAPACITY - 1).toLong()).toInt()]
        }
        true
    }
}
