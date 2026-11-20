package com.cooper.wheellog.voice

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the Ogg/Opus stream recorded by the watch to mono 16-bit PCM at [targetRate]
 * with MediaExtractor + MediaCodec (Ogg Opus extraction is supported on API 29+).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class OggOpusDecoder(
    private val cacheDir: File,
    private val targetRate: Int = 16_000,
) : AudioDecoder {

    override fun decode(bytes: ByteArray): Pcm {
        val file = File.createTempFile("fedvoice", ".ogg", cacheDir)
        try {
            file.writeBytes(bytes)
            return decodeFile(file)
        } finally {
            file.delete()
        }
    }

    fun decodeFile(file: File): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw VoiceException("Не удалось прочитать аудио", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw VoiceException("В записи нет аудио")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            Timber.tag(VoicePipeline.TAG).i("decode: track format $format")
            extractor.selectTrack(track)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val out = ShortArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        Timber.tag(VoicePipeline.TAG).i("decode: output format $of")
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort())
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.hasRemaining()) out.add(sb.get())
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val mono = PcmUtils.downmix(out.toArray(), channels)
            Timber.tag(VoicePipeline.TAG).i("decode: ${mono.size} samples @ $sampleRate Hz, $channels ch -> $targetRate Hz mono")
            return Pcm(PcmUtils.resample(mono, sampleRate, targetRate), targetRate)
        } catch (e: VoiceException) {
            throw e
        } catch (e: Exception) {
            throw VoiceException("Ошибка декодирования аудио", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(16_000)
        private var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
