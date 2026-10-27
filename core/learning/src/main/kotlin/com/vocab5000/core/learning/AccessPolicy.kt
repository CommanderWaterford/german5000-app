package com.vocab5000.core.learning

/** Premium lock is an entitlement condition, never an SRS state (spec §8.1, §12.1). */
class AccessPolicy(val freeEntryLimit: Int) {
    fun isUnlocked(curriculumRank: Int, premium: Boolean): Boolean = premium || curriculumRank <= freeEntryLimit
}

/** Rank bands of fixed width, numbered from 1. */
class RankBands(val bandSize: Int, val entryCount: Int) {
    val count: Int get() = (entryCount + bandSize - 1) / bandSize

    fun bandOf(rank: Int): Int = (rank - 1) / bandSize + 1

    fun range(band: Int): IntRange {
        val start = (band - 1) * bandSize + 1
        return start..minOf(entryCount, band * bandSize)
    }

    fun label(band: Int): String = range(band).let { "${it.first}–${it.last}" }
}
