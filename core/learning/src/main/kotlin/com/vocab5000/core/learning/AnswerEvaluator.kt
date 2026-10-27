package com.vocab5000.core.learning

import com.vocab5000.core.model.AnswerNormalizer
import com.vocab5000.core.model.AnswerSpec
import java.text.Normalizer

enum class AnswerVerdict(val isSuccess: Boolean) {
    CORRECT(true),
    /** Correct except letter case; counted correct with corrective feedback. */
    CORRECT_CASE_ONLY(true),
    /** An explicitly approved keyboard alternative such as `schoen`. */
    CORRECT_KEYBOARD_ALTERNATIVE(true),
    /** A near typo: suggestion only, never an automatic success. */
    NEAR_MISS(false),
    INCORRECT(false),
    EMPTY(false),
}

data class AnswerResult(val verdict: AnswerVerdict, val canonical: String, val normalizedInput: String)

/** Generic, language-neutral answer evaluation (spec §7.3). */
class AnswerEvaluator(private val normalizer: AnswerNormalizer) {

    fun evaluate(input: String, spec: AnswerSpec): AnswerResult {
        val normalized = normalizer.normalize(basicNormalize(input))
        if (normalized.isEmpty()) return AnswerResult(AnswerVerdict.EMPTY, spec.canonical, normalized)

        val stripped = stripPrefix(normalized, spec) ?: return AnswerResult(AnswerVerdict.INCORRECT, spec.canonical, normalized)
        val accepted = (listOf(spec.canonical) + spec.accepted).map { normalizer.normalize(basicNormalize(it)) }
        val candidates = listOf(normalized, stripped).distinct()

        if (candidates.any { it in accepted }) return result(AnswerVerdict.CORRECT, spec, normalized)

        if (!spec.caseSensitive) {
            val folded = accepted.map(normalizer::foldCase)
            if (candidates.any { normalizer.foldCase(it) in folded }) {
                return result(AnswerVerdict.CORRECT_CASE_ONLY, spec, normalized)
            }
        }

        val alternatives = spec.keyboardAlternatives.map { normalizer.normalize(basicNormalize(it)) }
        val altMatch = if (spec.caseSensitive) {
            candidates.any { it in alternatives }
        } else {
            val folded = alternatives.map(normalizer::foldCase)
            candidates.any { normalizer.foldCase(it) in folded }
        }
        if (altMatch) return result(AnswerVerdict.CORRECT_KEYBOARD_ALTERNATIVE, spec, normalized)

        val nearMiss = candidates.any { c ->
            accepted.any { a -> isNearMiss(normalizer.foldCase(c), normalizer.foldCase(a)) }
        }
        return result(if (nearMiss) AnswerVerdict.NEAR_MISS else AnswerVerdict.INCORRECT, spec, normalized)
    }

    private fun result(verdict: AnswerVerdict, spec: AnswerSpec, normalized: String) =
        AnswerResult(verdict, spec.canonical, normalized)

    /**
     * Removes an optional leading word (article, `sich`). Returns null when the answer
     * starts with a forbidden word such as a wrong article.
     */
    private fun stripPrefix(normalized: String, spec: AnswerSpec): String? {
        val firstSpace = normalized.indexOf(' ')
        if (firstSpace <= 0) return normalized
        val head = normalizer.foldCase(normalized.substring(0, firstSpace))
        val rest = normalized.substring(firstSpace + 1)
        if (spec.forbiddenPrefixes.any { normalizer.foldCase(it) == head }) {
            // A wrong article is only wrong if the remaining word is otherwise the target.
            return null
        }
        if (spec.optionalPrefixes.any { normalizer.foldCase(it) == head }) return rest
        return normalized
    }

    companion object {
        private val whitespace = Regex("\\s+")

        /** NFC, trim, collapse internal whitespace. Umlauts and ß are preserved. */
        fun basicNormalize(input: String): String =
            Normalizer.normalize(input, Normalizer.Form.NFC).trim().replace(whitespace, " ")

        /** Edit distance threshold for typo suggestions. */
        fun isNearMiss(input: String, target: String): Boolean {
            if (input == target) return false
            val length = target.codePointCount(0, target.length)
            val allowed = when {
                length >= 8 -> 2
                length >= 4 -> 1
                else -> 0
            }
            if (allowed == 0) return false
            return damerauLevenshtein(input, target) <= allowed
        }

        fun damerauLevenshtein(a: String, b: String): Int {
            val s = a.codePoints().toArray()
            val t = b.codePoints().toArray()
            val d = Array(s.size + 1) { IntArray(t.size + 1) }
            for (i in 0..s.size) d[i][0] = i
            for (j in 0..t.size) d[0][j] = j
            for (i in 1..s.size) {
                for (j in 1..t.size) {
                    val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && s[i - 1] == t[j - 2] && s[i - 2] == t[j - 1]) {
                        d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                    }
                }
            }
            return d[s.size][t.size]
        }
    }
}
