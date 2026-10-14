package dev.metasemantix.ssmlplayer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SsmlParserTest {
    private val parser = SsmlParser()
    private fun rejects(xml: String) { assertThrows(SsmlException::class.java) { parser.parse(xml) } }
    @Test fun paragraphsEntitiesAndPauses() {
        val parts = parser.parse("""<speak xmlns="http://www.w3.org/2001/10/synthesis" version="1.0"><p>Hello &amp; welcome.</p><break time="1.5s"/><s>Next.</s></speak>""").segments
        assertEquals(listOf(Segment.Speech("Hello & welcome.",1f,1f), Segment.Silence(1500), Segment.Speech("Next.",1f,1f)), parts)
    }
    @Test fun nestedProsodyIsScoped() {
        val parts = parser.parse("""<speak>A<prosody rate="slow" pitch="+20%">B<prosody rate="200%" pitch="50%">C</prosody>D</prosody>E</speak>""").segments.filterIsInstance<Segment.Speech>()
        assertEquals(listOf(1f,.75f,1.5f,.75f,1f),parts.map { it.rate })
        assertEquals(listOf(1f,1.2f,.6f,1.2f,1f),parts.map { it.pitch })
    }
    @Test fun rejectsMalformedAndEmptyInput() {
        listOf("", "text", "<speak/>", "<speak><p>oops</speak>", "<p>text</p>", "<speak>A</speak><speak>B</speak>", "<speak><speak>A</speak></speak>").forEach(::rejects)
    }
    @Test fun rejectsDtdAndEntities() {
        listOf("""<!DOCTYPE speak [<!ENTITY x SYSTEM "file:///etc/passwd">]><speak>&x;</speak>""", """<!DOCTYPE speak SYSTEM "https://example.invalid/evil.dtd"><speak>safe</speak>""", """<!DOCTYPE speak [<!ENTITY x "expanded">]><speak>&x;</speak>""", "<speak>&unknown;</speak>").forEach(::rejects)
    }
    @Test fun unsupportedMarkupNeverDisappears() {
        listOf("<speak><phoneme alphabet='ipa' ph='x'>word</phoneme></speak>", "<speak><say-as>1</say-as></speak>", "<speak xml:lang='de'>Hallo</speak>", "<speak><prosody volume='loud'>A</prosody></speak>", "<speak xmlns='urn:other'>A</speak>", "<speak><break time='1s'>A</break></speak>").forEach(::rejects)
    }
    @Test fun pauseBoundsAndUnits() {
        val parts = parser.parse("<speak>A<break time='0ms'/><break time='10000ms'/></speak>").segments
        assertEquals(listOf(0L,10000L),parts.filterIsInstance<Segment.Silence>().map { it.milliseconds })
        listOf("-1s", "NaNs", "11s", "10", "1m").forEach { rejects("<speak>A<break time='$it'/></speak>") }
        rejects("<speak>A" + "<break time='10s'/>".repeat(13) + "</speak>")
    }
    @Test fun limitsInputDepthSegmentsAndProsody() {
        rejects("<speak>"+"A".repeat(SsmlParser.MAX_INPUT_CHARS)+"</speak>")
        rejects("<speak>"+"<p>".repeat(32)+"A"+"</p>".repeat(32)+"</speak>")
        rejects("<speak>"+"<s>A</s>".repeat(513)+"</speak>")
        rejects("<speak><prosody rate='400%'><prosody rate='200%'>A</prosody></prosody></speak>")
        rejects("<speak><prosody pitch='NaN%'>A</prosody></speak>")
    }
    @Test fun longTextChunksPreserveSurrogates() {
        val input = "a".repeat(1999) + "😀" + "b".repeat(2000)
        val chunks = parser.parse("<speak>$input</speak>").segments.filterIsInstance<Segment.Speech>()
        assertEquals(input,chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 2000 && !it.text.last().isHighSurrogate() })
    }
    @Test fun silenceHasAccuratePcmHeaderAndZeroSamples() {
        val out = ByteArrayOutputStream()
        SilenceWav.write(out,1500)
        val bytes = out.toByteArray()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",String(bytes,0,4,Charsets.US_ASCII))
        assertEquals(22050,header.getInt(24))
        assertEquals(66150,header.getInt(40))
        assertEquals(66194,bytes.size)
        assertTrue(bytes.drop(44).all { it == 0.toByte() })
    }
    @Test fun silenceRejectsOversizeAndNegativeDurations() {
        listOf(-1L,10001L).forEach { value -> assertThrows(IllegalArgumentException::class.java) { SilenceWav.write(ByteArrayOutputStream(), value) } }
    }
}
