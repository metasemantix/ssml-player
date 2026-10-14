package dev.metasemantix.ssmlplayer.core

import java.io.File
import java.io.RandomAccessFile

/** One PCM16 stream: no playback-item boundaries, extra silence, resampling, or RAM-sized lesson. */
class PcmAssembler(destination: File, private val maxBytes: Long = 128L * 1024 * 1024) : AutoCloseable {
    private val output = RandomAccessFile(destination, "rw").apply { setLength(0) }
    private var format: PcmWav.Format? = null
    private val leadingBreaks = mutableListOf<Long>()
    private var dataBytes = 0L
    private var finished = false
    private val buffer = ByteArray(8192)

    fun appendSpeech(source: File, checkActive: () -> Unit = {}) {
        check(!finished)
        checkActive()
        val audio = PcmWav.read(source)
        if (format == null) {
            format = audio.format
            output.write(ByteArray(44))
            leadingBreaks.forEach { appendSilence(it, checkActive) }
            leadingBreaks.clear()
        }
        require(format == audio.format) { "Engine changed PCM sample rate or channels mid-lesson. Choose another offline voice." }
        reserve(audio.bytes)
        RandomAccessFile(source, "r").use { input ->
            input.seek(audio.offset)
            var remaining = audio.bytes
            while (remaining > 0) {
                checkActive()
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        dataBytes += audio.bytes
    }
    fun appendSilence(milliseconds: Long, checkActive: () -> Unit = {}) {
        check(!finished)
        require(milliseconds in 0..SsmlParser.MAX_BREAK_MS)
        checkActive()
        val pcm = format ?: run {
            require(leadingBreaks.size < SsmlParser.MAX_SEGMENTS) { "Too many leading breaks." }
            leadingBreaks += milliseconds
            return
        }
        val bytes = ((milliseconds * pcm.sampleRate + 500) / 1000) * pcm.blockAlign
        reserve(bytes)
        buffer.fill(0)
        var remaining = bytes
        while (remaining > 0) {
            checkActive()
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            output.write(buffer, 0, count)
            remaining -= count
        }
        dataBytes += bytes
    }
    fun finish() {
        check(!finished)
        val pcm = requireNotNull(format) { "Lesson contains no speech." }
        output.seek(0)
        fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
        fun le(value: Long, count: Int) { repeat(count) { output.write((value shr (it * 8)).toInt() and 255) } }
        ascii("RIFF"); le(dataBytes + 36, 4); ascii("WAVEfmt "); le(16, 4)
        le(1, 2); le(pcm.channels.toLong(), 2); le(pcm.sampleRate.toLong(), 4)
        le(pcm.sampleRate.toLong() * pcm.blockAlign, 4); le(pcm.blockAlign.toLong(), 2)
        le(16, 2); ascii("data"); le(dataBytes, 4)
        finished = true
    }
    private fun reserve(bytes: Long) {
        require(bytes <= maxBytes - 44 - dataBytes) { "Rendered lesson exceeds 128 MiB. Split it into smaller lessons." }
    }
    override fun close() { output.close() }
}
