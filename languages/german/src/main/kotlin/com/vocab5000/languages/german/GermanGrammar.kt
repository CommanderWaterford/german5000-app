package com.vocab5000.languages.german

/** Grammar payload keys used by German content (spec §5.3). */
object GermanGrammar {
    const val NOT_APPLICABLE = "not_applicable"

    // Nouns
    const val GENDER = "gender" // masculine | feminine | neuter | plural_only
    const val ARTICLE = "article" // der | die | das
    const val SINGULAR = "singular"
    const val PLURAL = "plural"
    const val PLURAL_STATUS = "pluralStatus" // has_plural | no_plural | plural_only
    const val WEAK_NOUN = "weakNoun" // true | false

    // Verbs
    const val PRESENT_3SG = "present3sg"
    const val PRETERITE = "preterite"
    const val PARTICIPLE_2 = "participle2"
    const val AUXILIARY = "auxiliary" // haben | sein | haben|sein
    const val SEPARABLE_PREFIX = "separablePrefix" // prefix or not_applicable
    const val REFLEXIVE = "reflexive" // no | required | optional
    const val VALENCY = "valency" // free text, optional

    // Adjectives
    const val GRADABLE = "gradable" // true | false
    const val COMPARATIVE = "comparative"
    const val SUPERLATIVE = "superlative"
    const val SPELLING_NOTE = "spellingNote"

    // Prepositions
    const val GOVERNED_CASES = "governedCases" // accusative | dative | genitive | accusative|dative

    // Pronouns / determiners / articles
    const val FORMS = "forms"

    val ARTICLES = listOf("der", "die", "das")
}
