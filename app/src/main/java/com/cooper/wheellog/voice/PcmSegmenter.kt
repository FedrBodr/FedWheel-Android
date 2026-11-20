package com.cooper.wheellog.voice

/**
 * Splits long speech into recognizer-sized pieces. Android's recognizer fed via EXTRA_AUDIO_SOURCE
 * may stop at the first long pause and drop the rest, so audio longer than [maxMs] is cut into
 * segments of at most [maxMs], each cut at the quietest moment of the allowed range (a pause between
 * words), and the transcripts are joined in order.
 */
object PcmSegmenter {
    const val MAX_MS = 20_000
    private const val MIN_MS = 5_000      // never cut closer than this to the segment start
    private const val FRAME_MS = 20
    private const val QUIET_WINDOW_MS = 300 // a cut is placed in the quietest 300 ms stretch

    fun split(pcm: ShortArray, sampleRate: Int, maxMs: Int = MAX_MS): List<IntRange> {
        if (pcm.isEmpty()) return emptyList()
        val maxLen = (sampleRate.toLong() * maxMs / 1000).toInt()
        if (pcm.size <= maxLen) return listOf(pcm.indices)
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val energy = frameEnergy(pcm, frame)
        val window = (QUIET_WINDOW_MS / FRAME_MS).coerceAtLeast(1)
        val minLen = (sampleRate.toLong() * MIN_MS / 1000).toInt()
        val out = ArrayList<IntRange>()
        var start = 0
        while (pcm.size - start > maxLen) {
            // Leave at least minLen for the rest so the last piece is not a useless sliver.
            val lo = start + minLen
            val hi = minOf(start + maxLen, pcm.size - minLen)
            val cut = if (hi <= lo) start + maxLen else quietest(energy, frame, window, lo, hi)
            out += start until cut
            start = cut
        }
        out += start until pcm.size
        return out
    }

    fun join(parts: List<String>): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun frameEnergy(pcm: ShortArray, frame: Int): LongArray {
        val n = (pcm.size + frame - 1) / frame
        return LongArray(n) { f ->
            var sum = 0L
            val end = minOf((f + 1) * frame, pcm.size)
            for (i in f * frame until end) sum += pcm[i].toLong() * pcm[i]
            sum
        }
    }

    /** Sample index in [lo, hi] at the centre of the quietest window of frames. */
    private fun quietest(energy: LongArray, frame: Int, window: Int, lo: Int, hi: Int): Int {
        val fLo = lo / frame
        val fHi = (hi / frame - window).coerceAtLeast(fLo)
        var best = fLo
        var bestSum = Long.MAX_VALUE
        var sum = 0L
        for (f in fLo until minOf(fLo + window, energy.size)) sum += energy[f]
        for (f in fLo..fHi) {
            // later cuts win ties: longer segments, fewer recognizer runs
            if (sum <= bestSum) {
                bestSum = sum
                best = f
            }
            if (f + window < energy.size) sum += energy[f + window]
            sum -= energy[f]
        }
        return ((best + window / 2) * frame).coerceIn(lo, hi)
    }
}
