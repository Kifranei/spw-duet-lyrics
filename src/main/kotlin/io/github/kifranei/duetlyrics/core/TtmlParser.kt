package io.github.kifranei.duetlyrics.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Apple Music / AMLL style TTML reader.
 *
 * Handles `<tt><head><metadata><ttm:agent xml:id="v1" type="person"><ttm:name>…`,
 * `<body><div><p begin end ttm:agent itunes:key><span begin end>…</span>`,
 * background vocals `<span ttm:role="x-bg">`, inline translations / romanisations
 * (`x-translation`, `x-roman`), and Apple `<iTunesMetadata><translations>` /
 * `<transliterations>` blocks keyed by `itunes:key`. External entities are never resolved.
 */
object TtmlParser {
    const val MAX_CHARS = 4_000_000

    private val XML_DECL = Regex("^<\\?xml\\b[^?]*\\?>", RegexOption.IGNORE_CASE)
    private val COMMENTS = Regex("^(?:\\s*<!--[\\s\\S]*?-->)*")
    private val DOCTYPE = Regex("<!DOCTYPE[^>\\[]*(\\[[\\s\\S]*?\\])?\\s*>", RegexOption.IGNORE_CASE)
    private val TT_ROOT = Regex("^<(?:[A-Za-z_][\\w.-]*:)?tt(?=[\\s>/])", RegexOption.IGNORE_CASE)
    private val WS = Regex("\\s+")
    private val TRANSLATION_ROLES = setOf("x-translation", "translation")
    private val ROMAN_ROLES = setOf("x-roman", "x-romanization", "x-transliteration")

    fun looksLikeTtml(raw: String): Boolean {
        val head = raw.take(8192).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .replaceFirst(XML_DECL, "").replace(COMMENTS, "").trimStart()
            .replaceFirst(DOCTYPE, "").replace(COMMENTS, "").trimStart()
        return TT_ROOT.containsMatchIn(head)
    }

    /** @return parsed document, or null when the XML is not usable TTML with timing. */
    fun parse(raw: String): LyricsDocument? {
        if (raw.length > MAX_CHARS) return null
        val text = raw.trimStart('\uFEFF').replaceFirst(DOCTYPE, "")
        val root = runCatching { parseXml(text) }.getOrNull() ?: return null
        if (root.local() != "tt") return null
        return Reader(root).read()
    }

    private fun parseXml(text: String): Element {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val builder = f.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) = throw e
                override fun fatalError(e: SAXParseException) = throw e
                override fun warning(e: SAXParseException) {}
            })
        }
        return builder.parse(InputSource(StringReader(text))).documentElement
    }

    /** A piece of text with optional timing, collected in document order. */
    private data class Piece(val start: Long?, val end: Long?, val text: String)

    private class Collected {
        val pieces = mutableListOf<Piece>()
        val translations = mutableListOf<String>()
        val romanizations = mutableListOf<String>()
        val backgrounds = mutableListOf<Pair<Element, Collected>>()
    }

    private class Reader(val root: Element) {
        val frameRate = root.attr("frameRate").toDoubleOrNull() ?: 30.0
        val tickRate = root.attr("tickRate").toDoubleOrNull() ?: 1.0

        fun time(e: Element, name: String): Long? = TimeFormat.parseTtml(e.attr(name).ifBlank { null }, frameRate, tickRate)

        fun end(e: Element, start: Long?): Long? =
            time(e, "end") ?: time(e, "dur")?.let { d -> start?.plus(d) }

        fun read(): LyricsDocument? {
            val all = root.descendants()
            val agents = all.filter { it.local() == "agent" }.map { a ->
                val name = a.descendants().firstOrNull { it.local() == "name" }?.textContent?.trim()?.ifEmpty { null }
                Agent(a.attr("id"), a.attr("type").ifBlank { "person" }, name)
            }.filter { it.id.isNotBlank() }

            // Apple head translations / transliterations keyed by itunes:key
            val headTranslations = HashMap<String, MutableList<Element>>()
            val headRomans = HashMap<String, MutableList<Element>>()
            all.filter { it.local() == "text" && it.attr("for").isNotBlank() }.forEach { t ->
                val parent = (t.parentNode as? Element)?.local()
                val target = when (parent) {
                    "translation" -> headTranslations
                    "transliteration" -> headRomans
                    else -> null
                }
                target?.getOrPut(t.attr("for")) { mutableListOf() }?.add(t)
            }

            val metadata = linkedMapOf<String, String>()
            all.firstOrNull { it.local() == "title" && it.isInHead() }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { metadata["ti"] = it }
            all.filter { it.local() == "meta" && it.attr("key").isNotBlank() }.forEach { m ->
                when (m.attr("key")) {
                    "musicName" -> metadata.putIfAbsent("ti", m.attr("value"))
                    "artists" -> metadata.putIfAbsent("ar", m.attr("value"))
                    "album" -> metadata.putIfAbsent("al", m.attr("value"))
                }
            }

            val lines = mutableListOf<Line>()
            for (p in all.filter { it.local() == "p" && !it.isInHead() }) {
                val voice = p.inheritedAttr("agent").ifBlank { null }
                val c = collect(p)
                val line = toLine(p, c, voice, explicit = voice != null) ?: continue
                val key = p.attr("key")
                val headTr = headTranslations[key].orEmpty()
                val headRo = headRomans[key].orEmpty()
                val translations = (c.translations + headTr.map { mainText(it) }).filter { it.isNotBlank() }.distinct()
                val romans = (c.romanizations + headRo.map { mainText(it) }).filter { it.isNotBlank() }.distinct()
                val headBgTr = headTr.mapNotNull { bgText(it) }
                val headBgRo = headRo.mapNotNull { bgText(it) }
                val bgs = c.backgrounds.mapIndexedNotNull { i, (el, bc) ->
                    val bgVoice = el.attr("agent").ifBlank { voice }
                    toLine(el, bc, bgVoice, explicit = bgVoice != null, parentStart = line.start)?.let { bl ->
                        bl.copy(
                            translations = (bl.translations + listOfNotNull(headBgTr.getOrNull(i))).distinct(),
                            romanizations = (bl.romanizations + listOfNotNull(headBgRo.getOrNull(i))).distinct(),
                        )
                    }
                }
                lines += line.copy(translations = translations, romanizations = romans, backgrounds = bgs)
            }
            if (lines.isEmpty()) return null
            return LyricsDocument(SourceFormat.TTML, lines.sortedBy { it.start }, agents, metadata)
        }

        private fun toLine(e: Element, c: Collected, voice: String?, explicit: Boolean, parentStart: Long? = null): Line? {
            val begin = time(e, "begin")
            val endTime = end(e, begin)
            val timed = c.pieces.filter { it.start != null && it.text.isNotBlank() }
            val text: String
            val words: List<Word>
            if (timed.isEmpty()) {
                text = normalize(c.pieces.joinToString("") { it.text }).trim()
                words = emptyList()
            } else {
                // merge untimed pieces (spaces, punctuation) into neighbouring timed words
                val out = mutableListOf<Piece>()
                var pendingLead = ""
                for (pc in c.pieces) {
                    if (pc.start != null && pc.text.isNotBlank()) {
                        out += pc.copy(text = pendingLead + pc.text); pendingLead = ""
                    } else if (out.isEmpty()) pendingLead += pc.text
                    else out[out.lastIndex] = out.last().copy(text = out.last().text + pc.text)
                }
                val norm = out.map { it.copy(text = normalize(it.text)) }.toMutableList()
                // avoid double spaces at boundaries
                for (i in 1 until norm.size) {
                    if (norm[i - 1].text.endsWith(" ") && norm[i].text.startsWith(" ")) {
                        norm[i] = norm[i].copy(text = norm[i].text.trimStart())
                    }
                }
                if (norm.isNotEmpty()) {
                    norm[0] = norm[0].copy(text = norm[0].text.trimStart())
                    norm[norm.lastIndex] = norm.last().copy(text = norm.last().text.trimEnd())
                }
                val fallbackEnd = endTime
                words = norm.mapIndexed { i, pc ->
                    val s = pc.start!!
                    val en = pc.end ?: norm.getOrNull(i + 1)?.start ?: fallbackEnd ?: (s + 1_000)
                    Word(s, maxOf(s, en), pc.text)
                }.filter { it.text.isNotEmpty() }
                text = words.joinToString("") { it.text }
            }
            if (text.isEmpty()) return null
            val start = begin ?: words.firstOrNull()?.start ?: parentStart ?: return null
            val lineEnd = endTime ?: words.maxOfOrNull { it.end }
            return Line(
                start = start,
                end = lineEnd,
                text = text,
                words = words,
                voice = voice,
                explicitVoice = explicit,
                translations = c.translations.filter { it.isNotBlank() },
                romanizations = c.romanizations.filter { it.isNotBlank() },
            )
        }

        fun collect(e: Element, into: Collected = Collected(), inherited: Pair<Long?, Long?> = null to null): Collected {
            val children = e.childNodes
            for (i in 0 until children.length) {
                val n = children.item(i)
                when (n.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                        n.nodeValue?.takeIf { it.isNotEmpty() }?.let { into.pieces += Piece(inherited.first, inherited.second, it) }
                    Node.ELEMENT_NODE -> {
                        val el = n as Element
                        val role = el.attr("role")
                        when {
                            role in TRANSLATION_ROLES -> into.translations += normalize(el.textContent).trim()
                            role in ROMAN_ROLES -> into.romanizations += normalize(el.textContent).trim()
                            role == "x-bg" -> into.backgrounds += el to collect(el)
                            el.local() == "br" -> into.pieces += Piece(null, null, " ")
                            el.attr("ruby") in setOf("text", "textContainer", "delimiter") -> Unit
                            else -> {
                                val b = time(el, "begin")
                                if (b != null) {
                                    val en = end(el, b)
                                    val sub = collect(el, Collected(), b to en)
                                    into.translations += sub.translations
                                    into.romanizations += sub.romanizations
                                    into.backgrounds += sub.backgrounds
                                    into.pieces += Piece(b, en, sub.pieces.joinToString("") { it.text })
                                } else {
                                    collect(el, into, inherited)
                                }
                            }
                        }
                    }
                }
            }
            return into
        }

        /** Head translation text without background-vocal parts. */
        fun mainText(t: Element): String {
            val sb = StringBuilder()
            fun walk(n: Node) {
                if (n is Element && n.attr("role") == "x-bg") return
                if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) sb.append(n.nodeValue)
                val ch = n.childNodes
                for (i in 0 until ch.length) walk(ch.item(i))
            }
            walk(t)
            return normalize(sb.toString()).trim()
        }

        fun bgText(t: Element): String? =
            t.descendants().firstOrNull { it.attr("role") == "x-bg" }?.textContent?.let { normalize(it).trim() }?.ifEmpty { null }
    }

    private fun normalize(s: String) = s.replace(WS, " ")
}

private fun Element.local(): String = localName ?: tagName.substringAfter(':')

/** Attribute lookup by local name, ignoring namespace prefixes (ttm:agent, xml:id, itunes:key…). */
internal fun Element.attr(name: String): String {
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if ((a.localName ?: a.nodeName.substringAfter(':')) == name) return a.nodeValue.orEmpty()
    }
    return ""
}

private fun Element.inheritedAttr(name: String): String {
    var cur: Element? = this
    while (cur != null) {
        cur.attr(name).takeIf { it.isNotBlank() }?.let { return it }
        cur = cur.parentNode as? Element
    }
    return ""
}

private fun Element.isInHead(): Boolean {
    var cur = parentNode
    while (cur is Element) {
        if (cur.local() == "head") return true
        cur = cur.parentNode
    }
    return false
}

private fun Element.descendants(): List<Element> {
    val out = mutableListOf<Element>()
    fun walk(n: Node) {
        val ch = n.childNodes
        for (i in 0 until ch.length) {
            val c = ch.item(i)
            if (c is Element) { out += c; walk(c) }
        }
    }
    walk(this)
    return out
}
