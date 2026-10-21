package io.github.kifranei.duetlyrics.core

internal fun sample(name: String): String =
    requireNotNull(Samples::class.java.getResource("/samples/$name")) { name }.readText()

internal object Samples
