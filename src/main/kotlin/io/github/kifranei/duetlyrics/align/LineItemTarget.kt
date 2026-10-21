package io.github.kifranei.duetlyrics.align

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile

/**
 * The call that draws ONE lyric line in SPW's own lyric list:
 * `MultiLyrics { … LyricsLineItem(state, time, line, onClick, modifier, …, style, …) }`.
 *
 * In SPW 1.19.0 (verified against the real app jar, `app/ffmpeg-x64.dll`):
 *  - `LyricsLineItem` = `androidx.compose.ui.є.Ϳ` (source marker
 *    "com.xuncorp.spc.lyrics.component.LyricsLineItem (LyricsLineItem.kt:72)")
 *  - called once per line from the lambda class `androidx.compose.ui.ھ$ׯ` (MultiLyrics.kt)
 *  - the style argument `androidx.compose.ui.ń` carries the global "歌词对齐" as a `TextAlign`
 *    field; LyricsLineItem uses it for the main text, the translation and the scale origin.
 *
 * Obfuscated names change between builds, so everything is found from the Compose source
 * markers and the call shape, never from hard-coded names.
 *
 * @property styleIndex index of the style argument among the business parameters
 */
data class LineItemTarget(
    val owner: String,
    val method: String,
    val descriptor: String,
    val caller: String,
    val parameters: List<String>,
    val styleIndex: Int,
    val styleClass: String,
) {
    companion object {
        const val ITEM_MARKER = "com.xuncorp.spc.lyrics.component.LyricsLineItem ("
        const val LIST_MARKER = "com.xuncorp.spc.lyrics.component.MultiLyrics ("
        const val MEASURE_MARKER = "com.xuncorp.spc.lyrics.component.MeasureLyricsLineItem ("
        private const val COMPOSER = "androidx.compose.runtime.Composer"
        private const val MODIFIER = "androidx.compose.ui.Modifier"
        private const val TEXT_ALIGN_DESC = "Landroidx/compose/ui/text/style/TextAlign;"

        /**
         * Scans SPW's app jar(s). The 1.19.0 app jar is named `ffmpeg-x64.dll`: any extension is
         * accepted. Two streaming passes keep memory low: first find the LyricsLineItem class,
         * then keep only classes that reference it or declare a TextAlign field.
         */
        fun discover(files: List<File>): LineItemTarget {
            val zips = files.filter { it.isFile }
            val owners = HashSet<String>()
            forEachClass(zips) { name, bytes ->
                if (ITEM_MARKER in String(bytes, Charsets.ISO_8859_1)) owners += name
            }
            require(owners.isNotEmpty()) { "LyricsLineItem not found in ${zips.map { it.name }}" }
            val needles = owners.map(::modifiedUtf8) + TEXT_ALIGN_DESC.toByteArray()
            val classes = HashMap<String, ByteArray>()
            forEachClass(zips) { name, bytes ->
                if (needles.any { indexOf(bytes, it) >= 0 }) classes.putIfAbsent(name, bytes)
            }
            return discover(classes)
        }

        private fun forEachClass(zips: List<File>, block: (String, ByteArray) -> Unit) {
            for (f in zips) runCatching {
                ZipFile(f).use { z ->
                    for (e in z.entries()) {
                        if (e.isDirectory || !e.name.endsWith(".class")) continue
                        val name = e.name.removeSuffix(".class")
                        if (name.startsWith("kotlin/") || name.startsWith("java/") || name.startsWith("META-INF/")) continue
                        block(name, z.getInputStream(e).use { it.readBytes() })
                    }
                }
            }
        }

        internal fun discover(classes: Map<String, ByteArray>): LineItemTarget {
            require(classes.isNotEmpty()) { "no host classes readable" }
            val owners = HashSet<String>()
            val measureCallers = HashSet<String>()
            val listClasses = HashSet<String>()
            for ((name, bytes) in classes) {
                val text = String(bytes, Charsets.ISO_8859_1)
                if (ITEM_MARKER in text) owners += name
                if (MEASURE_MARKER in text) measureCallers += name
                if (LIST_MARKER in text) listClasses += name
            }
            require(owners.isNotEmpty()) { "LyricsLineItem not found" }
            val ownerNeedles = owners.associateWith { modifiedUtf8(it) }

            val styleCache = HashMap<String, Boolean>()
            fun isStyle(internal: String): Boolean = styleCache.getOrPut(internal) {
                val b = classes[internal] ?: return@getOrPut false
                var has = false
                ClassReader(b).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                        if (access and Opcodes.ACC_STATIC == 0 && descriptor == TEXT_ALIGN_DESC) has = true
                        return null
                    }
                }, ClassReader.SKIP_CODE)
                has
            }

            val found = LinkedHashSet<LineItemTarget>()
            for ((callerName, bytes) in classes) {
                if (callerName in owners || callerName in measureCallers) continue
                if (ownerNeedles.values.none { indexOf(bytes, it) >= 0 }) continue
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                        object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(opcode: Int, owner: String, mName: String, desc: String, itf: Boolean) {
                                if (opcode != Opcodes.INVOKESTATIC || owner !in owners) return
                                if (Type.getReturnType(desc) != Type.VOID_TYPE) return
                                val types = Type.getArgumentTypes(desc)
                                val names = types.map { it.className }
                                val composer = names.indexOf(COMPOSER)
                                if (composer < 1 || names.drop(composer + 1).any { it != "int" }) return
                                val business = names.take(composer)
                                if (MODIFIER !in business) return
                                val styles = types.take(composer).withIndex()
                                    .filter { it.value.sort == Type.OBJECT && isStyle(it.value.internalName) }
                                if (styles.size != 1) return
                                found += LineItemTarget(
                                    owner.replace('/', '.'), mName, desc, callerName.replace('/', '.'), business,
                                    styles.single().index, styles.single().value.className,
                                )
                            }
                        }
                }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            }
            require(found.isNotEmpty()) { "no LyricsLineItem call site with a TextAlign style argument" }
            // SPW also draws LyricsLineItem in the settings preview (LyricsScreen.kt); the real
            // player list is the lambda of the class that composes MultiLyrics.
            if (found.size > 1) {
                val inList = found.filter { it.caller.replace('.', '/').substringBefore('$') in listClasses }
                if (inList.size == 1) return inList.single()
            }
            require(found.size == 1) { "ambiguous LyricsLineItem call sites: ${found.map { "${it.caller}->${it.owner}.${it.method}" }}" }
            return found.single()
        }

        /** JVM modified UTF-8 as stored in the constant pool (BMP chars only in class names). */
        private fun modifiedUtf8(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for (c in s) {
                val v = c.code
                when {
                    v in 1..0x7F -> out.write(v)
                    v <= 0x7FF -> { out.write(0xC0 or (v shr 6)); out.write(0x80 or (v and 0x3F)) }
                    else -> { out.write(0xE0 or (v shr 12)); out.write(0x80 or ((v shr 6) and 0x3F)); out.write(0x80 or (v and 0x3F)) }
                }
            }
            return out.toByteArray()
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || hay.size < needle.size) return -1
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
