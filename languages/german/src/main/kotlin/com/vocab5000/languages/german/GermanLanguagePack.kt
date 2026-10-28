package com.vocab5000.languages.german

import com.vocab5000.core.model.AnswerNormalizer
import com.vocab5000.core.model.AnswerSpec
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.ExerciseProvider
import com.vocab5000.core.model.GrammarLine
import com.vocab5000.core.model.GrammarRenderer
import com.vocab5000.core.model.GrammarValidator
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.model.Sense
import com.vocab5000.core.model.TypedPrompt
import com.vocab5000.languages.german.GermanGrammar as G
import java.util.Locale

object GermanLanguagePack : LanguagePack {
    override val languageId = "de-DE"
    override val displayName = "German"
    override val grammarRenderer: GrammarRenderer = GermanGrammarRenderer
    override val answerNormalizer: AnswerNormalizer = GermanAnswerNormalizer
    override val exerciseProvider: ExerciseProvider = GermanExerciseProvider
    override val grammarValidator: GrammarValidator = GermanGrammarValidator
    override val specialCharacters = listOf("ä", "ö", "ü", "ß", "Ä", "Ö", "Ü")
    override val speechLocaleTag = "de-DE"
}

object GermanAnswerNormalizer : AnswerNormalizer {
    private val apostrophes = Regex("[‘’ʼ]")

    /** Typographic apostrophes are unified; umlauts and ß are preserved. */
    override fun normalize(input: String): String = input.replace(apostrophes, "'")

    /** Lowercasing only: `ß` must never be folded to `ss`. */
    override fun foldCase(input: String): String = input.lowercase(Locale.GERMAN)
}

internal fun Map<String, String>.value(key: String): String? = this[key]?.takeIf { it.isNotBlank() && it != G.NOT_APPLICABLE }

object GermanGrammarRenderer : GrammarRenderer {

    override fun headline(entry: Entry): String {
        val g = entry.grammar
        if (entry.pos == PartOfSpeech.NOUN && g[G.PLURAL_STATUS] != "plural_only" && g.value(G.ARTICLE) == null) return entry.lemma
        return when (entry.pos) {
            PartOfSpeech.NOUN -> when (g[G.PLURAL_STATUS]) {
                "plural_only" -> "die ${entry.lemma} (plural only)"
                "no_plural" -> "${g[G.ARTICLE]} ${entry.lemma} · no plural"
                else -> listOfNotNull("${g[G.ARTICLE]} ${entry.lemma}", g.value(G.PLURAL)?.let { "die $it" }).joinToString(" · ")
            }
            PartOfSpeech.VERB -> citationForm(entry)
            else -> entry.lemma
        }
    }

    override fun citationForm(entry: Entry): String {
        val g = entry.grammar
        return when (entry.pos) {
            PartOfSpeech.NOUN -> if (g[G.PLURAL_STATUS] == "plural_only") "die ${entry.lemma}" else listOfNotNull(g.value(G.ARTICLE), entry.lemma).joinToString(" ")
            PartOfSpeech.VERB -> if (g[G.REFLEXIVE] == "required") "sich ${entry.lemma}" else entry.lemma
            else -> entry.lemma
        }
    }

    override fun grammarLines(entry: Entry): List<GrammarLine> {
        val g = entry.grammar
        val lines = mutableListOf<GrammarLine>()
        fun add(label: String, value: String?) { if (value != null) lines += GrammarLine(label, value) }
        when (entry.pos) {
            PartOfSpeech.NOUN -> {
                add("Gender", g.value(G.GENDER)?.replace('_', ' '))
                add("Article", g.value(G.ARTICLE))
                add(
                    "Plural",
                    when (g[G.PLURAL_STATUS]) {
                        "no_plural" -> "no plural"
                        "plural_only" -> "plural only"
                        else -> g.value(G.PLURAL)?.let { "die $it" }
                    },
                )
                if (g[G.WEAK_NOUN] == "true") add("Declension", "weak noun (-n/-en in all cases except nominative singular)")
            }
            PartOfSpeech.VERB -> {
                val aux = g.value(G.AUXILIARY)
                add("Present (er/sie/es)", g.value(G.PRESENT_3SG))
                add("Preterite", g.value(G.PRETERITE))
                val auxForm = aux?.split("|")?.joinToString("/") { if (it == "sein") "ist" else "hat" }
                add("Perfect", g.value(G.PARTICIPLE_2)?.let { if (auxForm == null) it else "$auxForm $it" })
                add("Auxiliary", aux?.replace("|", " or "))
                if (G.SEPARABLE_PREFIX in g) add("Separable", g.value(G.SEPARABLE_PREFIX)?.let { "yes ($it-)" } ?: "no")
                when (g[G.REFLEXIVE]) {
                    "required" -> add("Reflexive", "always with sich")
                    "optional" -> add("Reflexive", "sometimes with sich")
                }
                add("Usage", g.value(G.VALENCY))
            }
            PartOfSpeech.ADJECTIVE -> {
                if (g[G.GRADABLE] == "false") {
                    add("Comparison", "not gradable")
                } else {
                    add("Comparative", g.value(G.COMPARATIVE))
                    add("Superlative", g.value(G.SUPERLATIVE))
                }
                add("Spelling", g.value(G.SPELLING_NOTE))
            }
            PartOfSpeech.PREPOSITION -> add("Case", g.value(G.GOVERNED_CASES)?.let(::describeCases))
            PartOfSpeech.PRONOUN, PartOfSpeech.DETERMINER, PartOfSpeech.ARTICLE -> add("Forms", g.value(G.FORMS))
            else -> Unit
        }
        return lines
    }

    fun describeCases(value: String): String = when (value) {
        "accusative|dative" -> "two-way: accusative (direction) or dative (location)"
        else -> "+ " + value.replace("|", " or ")
    }

    override fun partOfSpeechLabel(pos: PartOfSpeech): String = if (pos == PartOfSpeech.UNSPECIFIED) "word" else pos.name.lowercase(Locale.ENGLISH)
}

object GermanExerciseProvider : ExerciseProvider {
    override fun recallAnswerSpec(entry: Entry, sense: Sense): AnswerSpec {
        val g = entry.grammar
        val optional = mutableListOf<String>()
        val forbidden = mutableListOf<String>()
        if (entry.pos == PartOfSpeech.NOUN) {
            val article = if (g[G.PLURAL_STATUS] == "plural_only") "die" else g[G.ARTICLE]
            if (article != null) {
                optional += article
                forbidden += G.ARTICLES - article
            }
        }
        if (entry.pos == PartOfSpeech.VERB && g[G.REFLEXIVE] != "no" && g[G.REFLEXIVE] != null) optional += "sich"
        return AnswerSpec(
            canonical = entry.lemma,
            accepted = sense.acceptedAnswers,
            keyboardAlternatives = sense.keyboardAlternatives,
            caseSensitive = false,
            optionalPrefixes = optional,
            forbiddenPrefixes = forbidden,
        )
    }

    override fun grammarPrompts(entry: Entry, revision: Int): List<Prompt> {
        val g = entry.grammar
        val gloss = entry.primarySense.glossEn
        val prompts = mutableListOf<Prompt>()
        fun choice(instruction: String, cue: String, options: List<String>, correct: String, explanation: String) {
            prompts += ChoicePrompt(
                entryId = entry.entryId, cardId = null, scheduled = false, mode = ExerciseMode.GRAMMAR,
                promptRevision = revision, instruction = instruction, cue = cue, options = options,
                correctIndex = options.indexOf(correct), explanation = explanation,
            )
        }
        fun typed(instruction: String, cue: String, answer: String, explanation: String) {
            prompts += TypedPrompt(
                entryId = entry.entryId, cardId = null, scheduled = false, mode = ExerciseMode.GRAMMAR,
                promptRevision = revision, instruction = instruction, cue = cue,
                answer = AnswerSpec(canonical = answer), explanation = explanation,
            )
        }
        when (entry.pos) {
            PartOfSpeech.NOUN -> {
                val article = g[G.ARTICLE]
                if (g[G.PLURAL_STATUS] != "plural_only" && article in G.ARTICLES) {
                    choice("Choose the article", "${entry.lemma} ($gloss)", G.ARTICLES, article!!, "${article} ${entry.lemma}")
                }
                val plural = g.value(G.PLURAL)
                if (g[G.PLURAL_STATUS] == "has_plural" && plural != null) {
                    typed("Type the plural (without article)", "${GermanGrammarRenderer.citationForm(entry)} → die …", plural, "die $plural")
                }
            }
            PartOfSpeech.VERB -> {
                val aux = g.value(G.AUXILIARY)
                if ((aux == "haben" || aux == "sein") && g.value(G.PARTICIPLE_2) != null) {
                    choice("Which auxiliary forms the perfect?", "${entry.lemma} ($gloss)", listOf("haben", "sein"), aux, "Perfect: ${if (aux == "haben") "hat" else "ist"} ${g[G.PARTICIPLE_2]}")
                }
                g.value(G.PARTICIPLE_2)?.let { typed("Type the participle II", "${entry.lemma} → hat/ist …", it, "Participle II: $it") }
                if (g.value(G.SEPARABLE_PREFIX) != null) {
                    choice("Is this verb separable?", entry.lemma, listOf("separable", "not separable"), "separable", "${entry.lemma} separates: ${g[G.PRESENT_3SG]}")
                }
            }
            PartOfSpeech.PREPOSITION -> {
                val cases = g.value(G.GOVERNED_CASES)
                val options = listOf("accusative", "dative", "genitive", "accusative|dative")
                if (cases in options) {
                    choice(
                        "Which case does it govern?", "${entry.lemma} ($gloss)",
                        options.map(GermanGrammarRenderer::describeCases), GermanGrammarRenderer.describeCases(cases!!),
                        "${entry.lemma}: ${GermanGrammarRenderer.describeCases(cases)}",
                    )
                }
            }
            PartOfSpeech.ADJECTIVE -> g.value(G.COMPARATIVE)?.takeIf { g[G.GRADABLE] != "false" }?.let {
                typed("Type the comparative", "${entry.lemma} ($gloss)", it, listOfNotNull(entry.lemma, it, g.value(G.SUPERLATIVE)).joinToString(" → "))
            }
            else -> Unit
        }
        return prompts
    }
}

object GermanGrammarValidator : GrammarValidator {
    private val genders = setOf("masculine", "feminine", "neuter", "plural_only")
    private val pluralStatuses = setOf("has_plural", "no_plural", "plural_only")
    private val auxiliaries = setOf("haben", "sein", "haben|sein")
    private val reflexive = setOf("no", "required", "optional")
    private val cases = setOf("accusative", "dative", "genitive", "accusative|dative")

    override fun validate(entry: Entry): List<String> {
        val g = entry.grammar
        val errors = mutableListOf<String>()
        fun require(key: String, allowed: Set<String>? = null) {
            val v = g[key]
            when {
                v.isNullOrBlank() -> errors += "missing grammar field '$key'"
                allowed != null && v !in allowed -> errors += "grammar field '$key' has invalid value '$v'"
            }
        }
        when (entry.pos) {
            PartOfSpeech.NOUN -> {
                require(G.GENDER, genders)
                require(G.PLURAL_STATUS, pluralStatuses)
                require(G.WEAK_NOUN, setOf("true", "false"))
                require(G.SINGULAR)
                require(G.PLURAL)
                if (g[G.PLURAL_STATUS] != "plural_only") require(G.ARTICLE, G.ARTICLES.toSet())
                val expected = mapOf("masculine" to "der", "feminine" to "die", "neuter" to "das")[g[G.GENDER]]
                if (expected != null && g[G.ARTICLE] != expected) errors += "article '${g[G.ARTICLE]}' does not match gender '${g[G.GENDER]}'"
                if (g[G.PLURAL_STATUS] == "has_plural" && g.value(G.PLURAL) == null) errors += "noun with has_plural needs a plural form"
                if (entry.lemma.firstOrNull()?.isUpperCase() != true) errors += "noun lemma must be capitalised"
            }
            PartOfSpeech.VERB -> {
                listOf(G.PRESENT_3SG, G.PRETERITE, G.PARTICIPLE_2, G.SEPARABLE_PREFIX).forEach { require(it) }
                require(G.AUXILIARY, auxiliaries)
                require(G.REFLEXIVE, reflexive)
                g.value(G.SEPARABLE_PREFIX)?.let { if (!entry.lemma.startsWith(it)) errors += "separable prefix '$it' is not a prefix of '${entry.lemma}'" }
            }
            PartOfSpeech.ADJECTIVE -> {
                require(G.GRADABLE, setOf("true", "false"))
                require(G.COMPARATIVE)
                require(G.SUPERLATIVE)
            }
            PartOfSpeech.PREPOSITION -> require(G.GOVERNED_CASES, cases)
            PartOfSpeech.PRONOUN, PartOfSpeech.DETERMINER, PartOfSpeech.ARTICLE -> require(G.FORMS)
            else -> Unit
        }
        return errors
    }
}
