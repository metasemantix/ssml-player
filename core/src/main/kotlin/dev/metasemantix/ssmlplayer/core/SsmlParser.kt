package dev.metasemantix.ssmlplayer.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

sealed interface Segment {
    data class Speech(val text: String, val rate: Float, val pitch: Float) : Segment
    data class Silence(val milliseconds: Long) : Segment
}

data class Document(val segments: List<Segment>)
class SsmlException(message: String) : IllegalArgumentException(message)

/** Strict, bounded subset. Unknown markup is rejected, never flattened into plain text. */
class SsmlParser {
    companion object {
        const val MAX_INPUT_CHARS = 131072
        const val MAX_SEGMENTS = 512
        const val MAX_BREAK_MS = 10000L
        const val MAX_TOTAL_BREAK_MS = 120000L
        private const val SSML_NS = "http://www.w3.org/2001/10/synthesis"
    }
    private data class Frame(val name: String, val rate: Float, val pitch: Float)

    fun parse(xml: String): Document {
        requireSsml(xml.length <= MAX_INPUT_CHARS, "Document exceeds 128 Ki characters.")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // kXML2 does not implement this optional feature and throws even when setting false.
        // Leave it alone only when the parser reports that DTD processing is already off.
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
        requireSsml(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL), "DTD processing cannot be disabled.")
        parser.setInput(StringReader(xml))
        val frames = mutableListOf<Frame>()
        val result = mutableListOf<Segment>()
        val text = StringBuilder()
        var rootSeen = false
        var totalBreak = 0L
        fun append(segment: Segment) {
            requireSsml(result.size < MAX_SEGMENTS, "Document exceeds $MAX_SEGMENTS audio segments.")
            result += segment
        }
        fun flush() {
            val normalized = text.toString().replace(Regex("\\s+"), " ").trim()
            text.setLength(0)
            if (normalized.isEmpty()) return
            val frame = frames.lastOrNull() ?: throw SsmlException("Text must be inside <speak>.")
            var offset = 0
            while (offset < normalized.length) {
                var end = minOf(offset + 2000, normalized.length)
                if (end < normalized.length && normalized[end - 1].isHighSurrogate()) end--
                append(Segment.Speech(normalized.substring(offset, end), frame.rate, frame.pitch))
                offset = end
            }
        }
        try {
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL -> throw SsmlException("DTDs and external entities are forbidden.")
                    XmlPullParser.PROCESSING_INSTRUCTION -> throw SsmlException("Processing instructions are unsupported.")
                    XmlPullParser.START_TAG -> {
                        flush()
                        requireSsml(frames.size < 32, "Markup nesting exceeds 32 levels.")
                        requireSsml(parser.namespace.isNullOrEmpty() || parser.namespace == SSML_NS, "Unsupported XML namespace.")
                        val name = parser.name
                        requireSsml(name in setOf("speak", "p", "s", "prosody", "break"), "Unsupported element <$name>.")
                        requireSsml(frames.lastOrNull()?.name != "break", "<break> must be empty.")
                        if (frames.isEmpty()) {
                            requireSsml(name == "speak" && !rootSeen, "One <speak> root is required.")
                            rootSeen = true
                        } else requireSsml(name != "speak", "Nested <speak> is unsupported.")
                        val allowed = when (name) {
                            "speak" -> setOf("version")
                            "prosody" -> setOf("rate", "pitch")
                            "break" -> setOf("time")
                            else -> emptySet()
                        }
                        for (i in 0 until parser.attributeCount) {
                            requireSsml(parser.getAttributeNamespace(i).isNullOrEmpty() && parser.getAttributeName(i) in allowed,
                                "Unsupported attribute ${parser.getAttributeName(i)} on <$name>.")
                        }
                        parser.getAttributeValue(null, "version")?.let { requireSsml(it == "1.0", "Only SSML version 1.0 is supported.") }
                        val parent = frames.lastOrNull() ?: Frame("", 1f, 1f)
                        val rate = parent.rate * factor(parser.getAttributeValue(null, "rate"), false)
                        val pitch = parent.pitch * factor(parser.getAttributeValue(null, "pitch"), true)
                        requireSsml(rate in 0.25f..4f && pitch in 0.25f..4f, "Combined prosody must stay between 0.25 and 4 times normal.")
                        frames += Frame(name, rate, pitch)
                        if (name == "break") {
                            val duration = duration(parser.getAttributeValue(null, "time") ?: throw SsmlException("<break> requires time."))
                            totalBreak += duration
                            requireSsml(totalBreak <= MAX_TOTAL_BREAK_MS, "Total explicit silence exceeds 120 seconds.")
                            append(Segment.Silence(duration))
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        flush()
                        requireSsml(frames.isNotEmpty() && frames.last().name == parser.name, "Unbalanced markup.")
                        frames.removeAt(frames.lastIndex)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        val value = parser.text ?: throw SsmlException("Unresolved entity is forbidden.")
                        requireSsml(frames.lastOrNull()?.name != "break" || value.isBlank(), "<break> must be empty.")
                        requireSsml(frames.isNotEmpty() || value.isBlank(), "Text must be inside <speak>.")
                        text.append(value)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            requireSsml(rootSeen && frames.isEmpty(), "A complete <speak> document is required.")
            requireSsml(result.any { it is Segment.Speech }, "Document contains no speech.")
            return Document(result)
        } catch (e: SsmlException) {
            throw e
        } catch (e: Exception) {
            throw SsmlException("Invalid XML: ${e.message?.take(180) ?: "parse failed"}")
        }
    }

    private fun duration(value: String): Long {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)(ms|s)").matchEntire(value)
            ?: throw SsmlException("Break time must use milliseconds or seconds, e.g. 1500ms or 1.5s.")
        val millis = match.groupValues[1].toDouble() * if (match.groupValues[2] == "s") 1000 else 1
        requireSsml(millis.isFinite() && millis in 0.0..MAX_BREAK_MS.toDouble(), "Break must be between 0 and 10 seconds.")
        return kotlin.math.round(millis).toLong()
    }

    private fun factor(value: String?, pitch: Boolean): Float {
        if (value == null) return 1f
        val names = if (pitch) mapOf("x-low" to .5f, "low" to .75f, "medium" to 1f, "high" to 1.5f, "x-high" to 2f, "default" to 1f)
            else mapOf("x-slow" to .5f, "slow" to .75f, "medium" to 1f, "fast" to 1.5f, "x-fast" to 2f, "default" to 1f)
        names[value]?.let { return it }
        val numeric = Regex("([+-]?[0-9]+(?:\\.[0-9]+)?)%").matchEntire(value)
            ?: throw SsmlException("Unsupported ${if (pitch) "pitch" else "rate"} value: $value.")
        val number = numeric.groupValues[1].toFloat() / 100f
        val factor = if (value.startsWith('+') || value.startsWith('-')) 1f + number else number
        requireSsml(factor.isFinite() && factor in .25f..4f, "Prosody multiplier must be between 0.25 and 4.")
        return factor
    }
}

private fun requireSsml(condition: Boolean, message: String) {
    if (!condition) throw SsmlException(message)
}
