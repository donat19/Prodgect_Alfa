package com.whispercppdemo.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.floor

/** Decodes MP3/AAC/etc. with Android's built-in codecs and returns 16 kHz mono floats. */
fun decodeAudioToMono16k(context: Context, uri: Uri): FloatArray {
    val extractor = MediaExtractor()
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Не удалось открыть аудиофайл")
    try {
        extractor.setDataSource(descriptor.fileDescriptor)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("В файле нет аудиодорожки")
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Неизвестный формат аудио")
        inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        try {
            return decode(codec, extractor)
        } finally {
            codec.stop(); codec.release()
        }
    } finally {
        extractor.release(); descriptor.close()
    }
}

private fun decode(codec: MediaCodec, extractor: MediaExtractor): FloatArray {
    val raw = ByteArrayOutputStream()
    val info = MediaCodec.BufferInfo()
    var inputEnded = false
    var outputEnded = false
    var sampleRate = 0
    var channels = 0
    while (!outputEnded) {
        if (!inputEnded) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            else -> if (index >= 0) {
                if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size); buffer.get(bytes); raw.write(bytes)
                }
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
            }
        }
    }
    check(sampleRate > 0 && channels > 0) { "Декодер не вернул параметры PCM" }
    val bytes = raw.toByteArray()
    val shorts = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
    val frames = shorts.remaining() / channels
    val mono = FloatArray(frames)
    for (frame in 0 until frames) {
        var sum = 0f
        repeat(channels) { sum += shorts.get().toFloat() / 32768f }
        mono[frame] = sum / channels
    }
    return if (sampleRate == TARGET_RATE) mono else resampleLinear(mono, sampleRate, TARGET_RATE)
}

private fun resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
    if (input.isEmpty()) return input
    val outputSize = (input.size.toLong() * targetRate / sourceRate).toInt()
    return FloatArray(outputSize) { outIndex ->
        val sourcePosition = outIndex.toDouble() * sourceRate / targetRate
        val left = floor(sourcePosition).toInt().coerceAtMost(input.lastIndex)
        val right = (left + 1).coerceAtMost(input.lastIndex)
        val fraction = (sourcePosition - left).toFloat()
        input[left] * (1f - fraction) + input[right] * fraction
    }
}

private const val TARGET_RATE = 16_000
