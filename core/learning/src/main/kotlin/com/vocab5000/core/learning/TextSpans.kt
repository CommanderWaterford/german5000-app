package com.vocab5000.core.learning

import com.vocab5000.core.model.CodepointSpan

/** Code point span helpers. Content spans are code point offsets, not UTF-16 indices. */
object TextSpans {
    fun toUtf16Range(text: String, span: CodepointSpan): IntRange? {
        val count = text.codePointCount(0, text.length)
        if (span.start < 0 || span.end > count || span.start >= span.end) return null
        val start = text.offsetByCodePoints(0, span.start)
        val end = text.offsetByCodePoints(0, span.end)
        return start until end
    }

    fun extract(text: String, span: CodepointSpan): String? = toUtf16Range(text, span)?.let { text.substring(it) }

    /**
     * Finds the single whole-word occurrence of [target]; returns null if absent or
     * ambiguous. Word boundaries prevent `in` from matching inside `Berlin`.
     */
    fun locateUnique(text: String, target: String): CodepointSpan? {
        val matches = wholeWordOccurrences(text, target)
        val first = matches.singleOrNull() ?: return null
        val start = text.codePointCount(0, first)
        return CodepointSpan(start, start + target.codePointCount(0, target.length))
    }

    /** Finds all whole-word occurrences of [target] in appearance order. */
    fun locateAll(text: String, target: String): List<CodepointSpan> {
        val len = target.codePointCount(0, target.length)
        return wholeWordOccurrences(text, target).map { idx ->
            val start = text.codePointCount(0, idx)
            CodepointSpan(start, start + len)
        }
    }

    private fun wholeWordOccurrences(text: String, target: String): List<Int> {
        if (target.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var index = text.indexOf(target)
        while (index >= 0) {
            val before = if (index == 0) null else text.codePointBefore(index)
            val afterIndex = index + target.length
            val after = if (afterIndex >= text.length) null else text.codePointAt(afterIndex)
            if ((before == null || !Character.isLetterOrDigit(before)) && (after == null || !Character.isLetterOrDigit(after))) {
                result += index
            }
            index = text.indexOf(target, index + 1)
        }
        return result
    }
}
