package io.github.kifranei.duetlyrics.align

import androidx.compose.ui.text.style.TextAlign
import io.github.kifranei.duetlyrics.core.Side
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds a copy of SPW's (obfuscated, immutable) lyric style object with a different
 * `TextAlign`, through its own constructor, so SPW's code keeps doing all the rendering.
 */
internal object StyleRewriter {
    private class Shape(val fields: List<Field>, val alignIndex: Int, val ctor: Constructor<*>)

    private val cache = ConcurrentHashMap<Class<*>, Any>()
    private val NONE = Any()

    // Boxed TextAlign instances (value class → boxed when stored as Any).
    private val LEFT: Any = TextAlign.Left
    private val RIGHT: Any = TextAlign.Right

    fun boxed(side: Side): Any = if (side == Side.RIGHT) RIGHT else LEFT

    fun withAlign(style: Any, side: Side): Any? {
        val shape = cache.getOrPut(style.javaClass) { shapeOf(style.javaClass) ?: NONE } as? Shape ?: return null
        val values = shape.fields.map { it.get(style) }.toMutableList()
        val target = boxed(side)
        if (values[shape.alignIndex] == target) return style
        values[shape.alignIndex] = target
        return shape.ctor.newInstance(*values.toTypedArray())
    }

    private fun shapeOf(c: Class<*>): Shape? = runCatching {
        val fields = c.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val alignIndex = fields.indexOfFirst { it.type == TextAlign::class.java }
        if (alignIndex < 0) return null
        val types = fields.map { it.type }
        val ctor = c.declaredConstructors.firstOrNull { it.parameterTypes.toList() == types } ?: return null
        fields.forEach { it.isAccessible = true }
        ctor.isAccessible = true
        Shape(fields, alignIndex, ctor)
    }.getOrNull()
}
