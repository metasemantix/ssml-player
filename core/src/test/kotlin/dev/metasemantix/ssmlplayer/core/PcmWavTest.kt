package dev.metasemantix.ssmlplayer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class PcmWavTest {
    private fun wave(test: (File) -> Unit) {
        val file = File.createTempFile("speech", ".wav")
        try {
            file.outputStream().use { SilenceWav.write(it, 1500) }
            test(file)
        } finally { file.delete() }
    }
    @Test fun acceptsSupportedPcm() = wave { PcmWav.validate(it) }
    @Test fun rejectsTruncatedAndEmptyAudio() = wave { file ->
        RandomAccessFile(file,"rw").use { it.setLength(44) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
        file.writeBytes(byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
    }
    @Test fun rejectsUnsupportedEncoding() = wave { file ->
        RandomAccessFile(file,"rw").use { it.seek(20); it.write(3) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
    }
    @Test fun rejectsIncorrectBlockAlignment() = wave { file ->
        RandomAccessFile(file,"rw").use { it.seek(32); it.write(4) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
    }
    @Test fun rejectsInvalidContainerAndChunkSize() = wave { file ->
        RandomAccessFile(file,"rw").use { it.seek(40); it.writeInt(-1) }
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
        file.writeText("not a WAV file".repeat(10))
        assertThrows(IllegalArgumentException::class.java) { PcmWav.validate(file) }
    }
}
