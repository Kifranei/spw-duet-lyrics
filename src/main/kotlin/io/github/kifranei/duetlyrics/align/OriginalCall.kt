package io.github.kifranei.duetlyrics.align

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Calls the original (unmodified) static composable with business arguments, the current
 * Composer and zero `$changed` / `$default` masks (every argument is passed explicitly).
 */
class OriginalCall(private val method: Method) {
    private val composerIndex = method.parameterTypes.indexOf(Composer::class.java)
    private val trailingInts = method.parameterCount - composerIndex - 1

    init {
        require(Modifier.isStatic(method.modifiers)) { "not static: $method" }
        require(composerIndex >= 0) { "no Composer parameter: $method" }
        method.isAccessible = true
    }

    val businessCount: Int get() = composerIndex

    @Composable
    fun Invoke(args: List<Any?>) {
        require(args.size == composerIndex) { "expected $composerIndex arguments, got ${args.size}" }
        val all = arrayOfNulls<Any>(method.parameterCount)
        args.forEachIndexed { i, a -> all[i] = a }
        all[composerIndex] = currentComposer
        for (i in 0 until trailingInts) all[composerIndex + 1 + i] = 0
        try {
            method.invoke(null, *all)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        fun resolve(owner: Class<*>, name: String, descriptor: String): OriginalCall {
            val m = owner.declaredMethods.firstOrNull {
                it.name == name && Modifier.isStatic(it.modifiers) && org.objectweb.asm.Type.getMethodDescriptor(it) == descriptor
            } ?: error("method $name$descriptor not found in ${owner.name}")
            return OriginalCall(m)
        }
    }
}
