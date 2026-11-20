package com.cooper.wheellog.voice

object PcmUtils {
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear-interpolation resampler; good enough for speech going to a recognizer. */
    fun resample(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || src.isEmpty()) return src
        val outSize = (src.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return ShortArray(outSize) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            (src[i0] * (1 - frac) + src[i1] * frac).toInt().toShort()
        }
    }

    fun toBytesLE(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}
