package io.github.maelmbange.thighhigh.cast

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import io.github.maelmbange.thighhigh.library.Track

/**
 * One radio of a broadcast: a playlist played on its own, shuffled and
 * looped, for the devices tuned to it. Nothing is heard on this phone: the
 * files are decoded by Android (MediaExtractor + MediaCodec, so Opus, AAC,
 * MP3, FLAC… all work) and the sound goes straight to the listeners, at the
 * pace it would be played.
 *
 * It runs on its own thread from [start] to [stop]. The broadcast only
 * starts a radio while someone listens to it.
 */
internal class RadioStation(
    private val context: Context,
    val id: Int,
    private val tracks: List<Track>,
    private val sink: Sink,
) {
    interface Sink {
        /** A new track starts on this radio. */
        fun track(radio: Int, track: Track)
        fun position(radio: Int, positionMs: Long)
        fun audio(radio: Int, bytes: ByteArray, length: Int, rate: Int, channels: Int, float: Boolean)
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "radio-$id").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        try {
            while (running) {
                var played = 0
                for (track in tracks.shuffled()) {
                    if (!running) return
                    if (play(track)) played++
                }
                // Nothing could be read: wait rather than spin.
                if (played == 0) Thread.sleep(5_000)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        }
    }

    /** Plays one file to the end; false when it can't be read. */
    private fun play(track: Track): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            sink.track(id, track)

            val info = MediaCodec.BufferInfo()
            var bytes = ByteArray(0)
            var inputDone = false
            val started = SystemClock.elapsedRealtime()
            // Audio already sent, in ms: it may run ahead of the clock by [LEAD_MS].
            var sentMs = 0.0
            while (running) {
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (output < 0) continue
                if (info.size > 0 && rate > 0 && channels > 0) {
                    val buffer = decoder.getOutputBuffer(output)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (bytes.size < info.size) bytes = ByteArray(info.size)
                    buffer.get(bytes, 0, info.size)
                    val ahead = sentMs - (SystemClock.elapsedRealtime() - started)
                    if (ahead > LEAD_MS) Thread.sleep((ahead - LEAD_MS).toLong())
                    sink.position(id, info.presentationTimeUs / 1000)
                    sink.audio(id, bytes, info.size, rate, channels, float)
                    val frameBytes = channels * if (float) 4 else 2
                    sentMs += info.size.toDouble() / frameBytes * 1000.0 / rate
                }
                decoder.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return true
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            return false
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** Sent ahead of time, so the listeners' cushion fills at once. */
        const val LEAD_MS = 500
    }
}
