package dev.metasemantix.ssmlplayer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException

class PcmAssemblerTest {
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("pcm-assembly").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
    private fun speech(directory: File, name: String, samples: ShortArray, rate: Int = 22050, channels: Int = 1): File {
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(data.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
        data.putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { data.putShort(it) }
        return File(directory, name).apply { writeBytes(data.array()) }
    }
    private fun samples(file: File): List<Short> {
        val info = PcmWav.read(file)
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        bytes.position(info.offset.toInt())
        return List(info.bytes.toInt() / 2) { bytes.short }
    }
    @Test fun speechBoundariesAndZeroBreakAddNoFrames() = inDirectory { dir ->
        val first = speech(dir, "first.wav", shortArrayOf(11, 0, 22))
        val second = speech(dir, "second.wav", shortArrayOf(0, 33))
        val result = File(dir, "lesson.wav")
        PcmAssembler(result).use { it.appendSpeech(first); it.appendSilence(0); it.appendSpeech(second); it.finish() }
        assertEquals(listOf<Short>(11, 0, 22, 0, 33), samples(result))
        assertEquals(54L, result.length())
    }
    @Test fun breaksUseSpeechRateAndStereoFrameAlignmentIncludingLeadingAndTrailing() = inDirectory { dir ->
        val source = speech(dir, "speech.wav", shortArrayOf(100, -100), rate = 48000, channels = 2)
        val result = File(dir, "lesson.wav")
        PcmAssembler(result).use { it.appendSilence(1); it.appendSpeech(source); it.appendSilence(1500); it.appendSpeech(source); it.appendSilence(1); it.finish() }
        assertEquals(PcmWav.Format(48000, 2), PcmWav.read(result).format)
        val expected = MutableList<Short>(48 * 2) { 0 }
        expected += listOf<Short>(100, -100)
        expected += List<Short>(72000 * 2) { 0 }
        expected += listOf<Short>(100, -100)
        expected += List<Short>(48 * 2) { 0 }
        assertEquals(expected, samples(result))
    }
    @Test fun eachBreakRoundsToNearestWholeFrame() = inDirectory { dir ->
        val source = speech(dir, "speech.wav", shortArrayOf(9))
        val result = File(dir, "lesson.wav")
        PcmAssembler(result).use {
            repeat(10) { _ -> it.appendSilence(1) }
            it.appendSpeech(source)
            repeat(10) { _ -> it.appendSilence(1) }
            it.finish()
        }
        assertEquals(441, samples(result).size) // 20 * 22 frames, plus the one speech frame
        assertEquals(9.toShort(), samples(result)[220])
    }
    @Test fun copiesOnlyAudioDataAndSkipsEngineMetadataChunks() = inDirectory { dir ->
        val source = speech(dir, "speech.wav", shortArrayOf(71, 72))
        val original = source.readBytes()
        val metadata = byteArrayOf(74, 85, 78, 75, 4, 0, 0, 0, 10, 20, 30, 40) // JUNK
        val extended = original.copyOfRange(0, 12) + metadata + original.copyOfRange(12, original.size)
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putInt(4, extended.size - 8)
        source.writeBytes(extended)
        val result = File(dir, "lesson.wav")
        PcmAssembler(result).use { it.appendSpeech(source); it.appendSpeech(source); it.finish() }
        assertEquals(listOf<Short>(71, 72, 71, 72), samples(result))
        assertEquals(52L, result.length())
    }
    @Test fun rejectsSampleRateAndChannelChangesRatherThanCorruptingAudio() = inDirectory { dir ->
        val first = speech(dir, "first.wav", shortArrayOf(1))
        val differentRate = speech(dir, "rate.wav", shortArrayOf(2), rate = 16000)
        val stereo = speech(dir, "stereo.wav", shortArrayOf(3, 4), channels = 2)
        PcmAssembler(File(dir, "lesson.wav")).use {
            it.appendSpeech(first)
            assertThrows(IllegalArgumentException::class.java) { it.appendSpeech(differentRate) }
            assertThrows(IllegalArgumentException::class.java) { it.appendSpeech(stereo) }
        }
    }
    @Test fun boundsIncludeInsertedSilenceAndCancellationStopsStreaming() = inDirectory { dir ->
        val source = speech(dir, "speech.wav", ShortArray(20000) { 123 })
        val output = File(dir, "lesson.wav")
        PcmAssembler(output, maxBytes = 64).use {
            assertThrows(IllegalArgumentException::class.java) { it.appendSpeech(source) }
        }
        PcmAssembler(output).use {
            var calls = 0
            assertThrows(CancellationException::class.java) {
                it.appendSpeech(source) { if (++calls == 3) throw CancellationException("cancelled") }
            }
            assertTrue(output.length() < source.length())
        }
        PcmAssembler(output, maxBytes = 64).use {
            it.appendSpeech(speech(dir, "tiny.wav", shortArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) { it.appendSilence(1) }
        }
    }
    @Test fun rejectsCorruptInputAndSilenceOnlyLesson() = inDirectory { dir ->
        val bad = File(dir, "bad.wav").apply { writeText("invalid audio") }
        PcmAssembler(File(dir, "lesson.wav")).use {
            assertThrows(IllegalArgumentException::class.java) { it.appendSpeech(bad) }
            it.appendSilence(500)
            assertThrows(IllegalArgumentException::class.java) { it.finish() }
        }
    }
}
