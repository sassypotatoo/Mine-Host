package com.example.marketplace

/** Small strict constraint evaluator for signed catalog metadata. Unknown syntax is rejected. */
object CompatibilityConstraint {
    data class Check(val matches: Boolean, val understood: Boolean, val reason: String? = null)

    fun version(actual: String, expression: String?): Check {
        if (expression.isNullOrBlank() || expression == "*") return Check(true, true)
        val actualParts = parts(actual) ?: return Check(false, false, "Cannot parse actual version '$actual'")
        val clauses = expression.split(',').map(String::trim).filter(String::isNotBlank)
        if (clauses.isEmpty()) return Check(false, false, "Empty version constraint")
        for (clause in clauses) {
            val range = RANGE.matchEntire(clause)
            if (range != null) {
                val low = parts(range.groupValues[1]) ?: return Check(false, false, "Invalid range '$clause'")
                val high = parts(range.groupValues[2]) ?: return Check(false, false, "Invalid range '$clause'")
                if (compare(actualParts, low) < 0 || compare(actualParts, high) > 0) return Check(false, true)
                continue
            }
            val match = COMPARATOR.matchEntire(clause)
                ?: return Check(false, false, "Unsupported version constraint '$clause'")
            val expected = parts(match.groupValues[2])
                ?: return Check(false, false, "Invalid version in '$clause'")
            val cmp = compare(actualParts, expected)
            val ok = when (match.groupValues[1].ifBlank { "=" }) {
                "=", "==" -> cmp == 0
                ">" -> cmp > 0
                ">=" -> cmp >= 0
                "<" -> cmp < 0
                "<=" -> cmp <= 0
                else -> false
            }
            if (!ok) return Check(false, true)
        }
        return Check(true, true)
    }

    fun java(actual: Int, expression: String?): Check = version(actual.toString(), expression)

    private fun parts(value: String): List<Int>? {
        val match = VERSION.find(value.trim()) ?: return null
        return match.value.split('.', '-', '_').mapNotNull { token -> token.toIntOrNull() }
            .takeIf(List<Int>::isNotEmpty)
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (index in 0 until max) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private val VERSION = Regex("\\d+(?:[._-]\\d+)*")
    private val COMPARATOR = Regex("^(>=|<=|==|>|<|=)?\\s*(\\d+(?:[._-]\\d+)*)$")
    private val RANGE = Regex("^(\\d+(?:[._]\\d+)*)\\s*-\\s*(\\d+(?:[._]\\d+)*)$")
}
