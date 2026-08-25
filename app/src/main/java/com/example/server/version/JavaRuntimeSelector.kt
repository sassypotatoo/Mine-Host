package com.example.server.version

/** Selects the smallest packaged JVM that satisfies an engine's minimum Java requirement. */
object JavaRuntimeSelector {
    private val packaged = listOf(17, 21, 25)

    fun select(requiredMinimum: Int): Int? = packaged.firstOrNull { it >= requiredMinimum }

    fun isValid(requiredMinimum: Int, runtimeMajor: Int): Boolean =
        runtimeMajor in packaged && runtimeMajor >= requiredMinimum
}
