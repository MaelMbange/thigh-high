package io.github.maelmbange.thighhigh.genre

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Decodes the model's excerpt (30 s from the middle) with Android's codecs,
 * as mono samples at [Genre.sampleRate]: what ffmpeg does for the terminal app.
 */
fun decodeExcerpt(path: String, durationMs: Long): FloatArray {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val knownMs = durationMs.takeIf { it > 0 }
            ?: format.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
            ?: 0L
        val startUs = (Genre.excerptStart(knownMs) * 1_000_000).toLong()
        val endUs = startUs + (Genre.excerptSeconds() * 1_000_000).toLong()
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            return decode(extractor, codec, startUs, endUs)
        } finally {
            codec.release()
        }
    } finally {
        extractor.release()
    }
}

private fun decode(extractor: MediaExtractor, codec: MediaCodec, startUs: Long, endUs: Long): FloatArray {
    var sampleRate = 44_100
    var channels = 2
    var floatPcm = false
    val mono = FloatList()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    while (true) {
        if (!inputDone) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0 || extractor.sampleTime > endUs) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val out = codec.dequeueOutputBuffer(info, 10_000)
        when {
            out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = codec.outputFormat
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
            }
            out >= 0 -> {
                // Skip what precedes the excerpt (the seek lands on an earlier sync point).
                if (info.size > 0 && info.presentationTimeUs >= startUs - 100_000) {
                    val data = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    val bytes = if (floatPcm) 4 else 2
                    val frames = info.size / (bytes * channels)
                    repeat(frames) {
                        var sum = 0f
                        repeat(channels) { sum += if (floatPcm) data.float else data.short / 32768f }
                        mono.add(sum / channels)
                    }
                }
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }
    return resample(mono.toArray(), sampleRate, Genre.sampleRate())
}

/** Averages over each output sample's span (a simple anti-alias filter), then interpolates. */
private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || input.isEmpty()) return input
    val ratio = from.toDouble() / to
    val out = FloatArray((input.size / ratio).toInt())
    val half = max(ratio / 2, 0.5)
    for (i in out.indices) {
        val center = i * ratio
        val lo = max(0, (center - half).toInt())
        val hi = min(input.size - 1, (center + half).toInt())
        var sum = 0f
        for (j in lo..hi) sum += input[j]
        out[i] = sum / (hi - lo + 1)
    }
    return out
}

/** A growable float array, to avoid boxing millions of samples. */
private class FloatList {
    private var data = FloatArray(1 shl 16)
    private var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}
