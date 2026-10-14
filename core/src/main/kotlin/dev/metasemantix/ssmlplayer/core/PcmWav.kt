package dev.metasemantix.ssmlplayer.core

import java.io.File
import java.io.RandomAccessFile

/** Validate engine output before handing it to the decoder; no audio is loaded into memory. */
object PcmWav {
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    data class Format(val sampleRate: Int, val channels: Int) { val blockAlign: Int get() = channels * 2 }
    data class Audio(val format: Format, val offset: Long, val bytes: Long)
    fun validate(file: File) { read(file) }
    fun read(file: File): Audio {
        require(file.length() in 44..MAX_FILE_BYTES) { "Engine audio is empty or exceeds 32 MiB." }
        RandomAccessFile(file, "r").use { input ->
            fun text(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            fun u32(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            fun u16(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
            require(text() == "RIFF") { "Engine output must be RIFF/WAVE PCM16." }
            require(u32() + 8 == input.length()) { "Engine WAV has an invalid RIFF length." }
            require(text() == "WAVE") { "Engine output must be WAVE." }
            var formatSeen = false
            var dataSeen = false
            var alignment = 0
            var format: Format? = null
            var audio: Audio? = null
            while (input.filePointer + 8 <= input.length()) {
                val name = text()
                val size = u32()
                val end = input.filePointer + size
                require(end <= input.length()) { "Engine WAV contains a truncated chunk." }
                when (name) {
                    "fmt " -> {
                        require(!formatSeen && size >= 16) { "Invalid WAV format chunk." }
                        val encoding = u16()
                        val channels = u16()
                        val rate = u32()
                        val byteRate = u32()
                        alignment = u16()
                        val bits = u16()
                        require(encoding == 1 && channels in 1..2 && bits == 16 && rate in 8000..48000 &&
                            alignment == channels * 2 && byteRate == rate * alignment) {
                            "Unsupported engine audio: requires PCM16 mono/stereo at 8–48 kHz."
                        }
                        format = Format(rate.toInt(), channels)
                        formatSeen = true
                    }
                    "data" -> {
                        require(formatSeen && !dataSeen && size > 0 && size % alignment == 0L) { "Engine WAV has invalid audio data." }
                        audio = Audio(requireNotNull(format), input.filePointer, size)
                        dataSeen = true
                    }
                }
                input.seek(end + (size % 2))
            }
            require(formatSeen && dataSeen && input.filePointer == input.length()) { "Engine WAV is incomplete." }
            return requireNotNull(audio)
        }
    }
}
