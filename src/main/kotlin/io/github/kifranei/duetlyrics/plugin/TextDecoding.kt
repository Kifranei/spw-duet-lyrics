package io.github.kifranei.duetlyrics.plugin

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object TextDecoding {
    /** BOM → strict UTF-8 → GB18030 (common for Chinese LRC files). */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return strict(bytes, Charsets.UTF_8)
            ?: runCatching { strict(bytes, Charset.forName("GB18030")) }.getOrNull()
            ?: String(bytes, Charsets.UTF_8)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
