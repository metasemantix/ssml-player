package dev.metasemantix.ssmlplayer.core

import java.io.OutputStream

/** PCM16 mono silence, streamed in small buffers; 1 ms is rounded to the nearest sample. */
object SilenceWav {
    const val SAMPLE_RATE = 22050
    fun write(output: OutputStream, milliseconds: Long) {
        require(milliseconds in 0..SsmlParser.MAX_BREAK_MS)
        val samples = (milliseconds * SAMPLE_RATE + 500) / 1000
        val bytes = samples * 2
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun le(value: Long, count: Int) { repeat(count) { output.write((value shr (it * 8)).toInt() and 255) } }
        ascii("RIFF"); le(36 + bytes, 4); ascii("WAVEfmt "); le(16, 4)
        le(1, 2); le(1, 2); le(SAMPLE_RATE.toLong(), 4); le(SAMPLE_RATE * 2L, 4)
        le(2, 2); le(16, 2); ascii("data"); le(bytes, 4)
        val zeros = ByteArray(4096)
        var remaining = bytes
        while (remaining > 0) {
            val count = minOf(remaining, zeros.size.toLong()).toInt()
            output.write(zeros, 0, count)
            remaining -= count
        }
    }
}
