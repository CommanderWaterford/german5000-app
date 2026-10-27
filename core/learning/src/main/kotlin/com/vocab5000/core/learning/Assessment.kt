package com.vocab5000.core.learning

import kotlin.random.Random

enum class AssessmentItemKind { RECOGNITION, PRODUCTION }

data class AssessmentItemPlan(val band: Int, val entryId: String, val kind: AssessmentItemKind)

data class AssessmentItemResult(val band: Int, val kind: AssessmentItemKind, val correct: Boolean)

data class BandScore(
    val band: Int,
    val recognitionCorrect: Int,
    val productionCorrect: Int,
    val itemCount: Int,
) {
    /** (recognition + 2 × production) / 8, the spec's weighting for 4 + 2 items. */
    val score: Double get() = (recognitionCorrect + 2.0 * productionCorrect) / 8.0
    val provisionallyFamiliar: Boolean get() = score >= 0.75 && productionCorrect >= 1
    val fullyAssessed: Boolean get() = itemCount == Assessment.RECOGNITION_PER_BAND + Assessment.PRODUCTION_PER_BAND
}

data class AssessmentRecommendation(
    val suggestedBand: Int,
    val allBandsFamiliar: Boolean,
    val bandScores: List<BandScore>,
)

/** Placement assessment (spec §7.1): estimates a starting band, never awards mastery. */
object Assessment {
    const val RECOGNITION_PER_BAND = 4
    const val PRODUCTION_PER_BAND = 2

    /**
     * Picks 4 recognition and 2 production items per band from each band's eligible pool,
     * deterministically from [seed] so an interrupted assessment resumes identically.
     * [recognitionEligible] filters entries that have safe distractors.
     */
    fun plan(
        seed: Long,
        poolsByBand: Map<Int, List<String>>,
        recognitionEligible: (String) -> Boolean,
    ): List<AssessmentItemPlan> {
        val random = Random(seed)
        return poolsByBand.keys.sorted().flatMap { band ->
            val shuffled = poolsByBand.getValue(band).shuffled(random)
            val recognition = shuffled.filter(recognitionEligible).take(RECOGNITION_PER_BAND)
            val production = shuffled.filterNot { it in recognition }.take(PRODUCTION_PER_BAND)
            recognition.map { AssessmentItemPlan(band, it, AssessmentItemKind.RECOGNITION) } +
                production.map { AssessmentItemPlan(band, it, AssessmentItemKind.PRODUCTION) }
        }
    }

    fun score(results: List<AssessmentItemResult>, bandCount: Int): AssessmentRecommendation {
        val scores = (1..bandCount).map { band ->
            val inBand = results.filter { it.band == band }
            BandScore(
                band = band,
                recognitionCorrect = inBand.count { it.kind == AssessmentItemKind.RECOGNITION && it.correct },
                productionCorrect = inBand.count { it.kind == AssessmentItemKind.PRODUCTION && it.correct },
                itemCount = inBand.size,
            )
        }
        val firstUnfamiliar = scores.firstOrNull { !it.provisionallyFamiliar }
        return AssessmentRecommendation(
            suggestedBand = firstUnfamiliar?.band ?: bandCount,
            allBandsFamiliar = firstUnfamiliar == null,
            bandScores = scores,
        )
    }
}
