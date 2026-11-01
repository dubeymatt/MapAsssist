package com.example.mapasssist

import java.util.Locale

/** Shared matching and natural-order rules for DNC identifiers such as 1, C1, and L1. */
object MapNumber {
    private val parts = Regex("^([A-Z]*)(\\d+)(.*)$")

    /** Normalises outer whitespace and letter case, but never discards a letter prefix. */
    fun identifier(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun compare(left: String, right: String): Int = sortKey(left).compareTo(sortKey(right))

    private fun sortKey(value: String): SortKey {
        val normalised = identifier(value)
        val match = parts.matchEntire(normalised) ?: return SortKey(2, normalised, Long.MAX_VALUE, "")
        val prefix = match.groupValues[1]
        return SortKey(
            if (prefix.isEmpty()) 0 else 1,
            prefix,
            match.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE,
            match.groupValues[3]
        )
    }

    private data class SortKey(
        val prefixGroup: Int,
        val prefix: String,
        val number: Long,
        val suffix: String
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int = compareValuesBy(
            this, other, SortKey::prefixGroup, SortKey::prefix, SortKey::number, SortKey::suffix
        )
    }
}
