package com.vocab5000.core.content

import com.vocab5000.core.content.PackFormat.toModel
import com.vocab5000.core.model.AudioAsset
import com.vocab5000.core.model.ContentManifest
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.core.model.MediaLookup
import com.vocab5000.core.model.PartOfSpeech
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Immutable, validated content pack held in memory. 5,000 entries are small enough that
 * an indexed in-memory store meets the search budget while staying strictly read-only;
 * user data lives in the separate Room database and references entries by stable ID.
 */
class ContentPack private constructor(
    val manifest: ContentManifest,
    val entries: List<Entry>,
    private val audio: Map<String, AudioAsset>,
) : MediaLookup {
    private val byId: Map<String, Entry> = entries.associateBy { it.entryId }
    val byRank: List<Entry> = entries.sortedBy { it.curriculumRank }
    private val searchIndex: List<Pair<Entry, List<String>>> = byRank.map { entry ->
        entry to (listOf(entry.lemma) + entry.searchAliases + entry.senses.flatMap { listOf(it.glossEn) + it.acceptedMeaningsEn })
            .map(::searchKey)
    }

    fun entry(entryId: String): Entry? = byId[entryId]

    fun requireEntry(entryId: String): Entry = byId[entryId] ?: error("Unknown entry $entryId")

    fun audioAsset(audioId: String): AudioAsset? = audio[audioId]

    override fun audioPath(audioId: String): String? = audio[audioId]?.path

    fun rankOf(entryId: String): Int? = byId[entryId]?.curriculumRank

    /** Ranked, bounded search over lemma, aliases (e.g. separated verb forms) and glosses. */
    fun search(query: String, limit: Int = 200, filter: (Entry) -> Boolean = { true }): List<Entry> {
        val key = searchKey(query)
        if (key.isEmpty()) return byRank.asSequence().filter(filter).take(limit).toList()
        val exact = ArrayList<Entry>()
        val prefix = ArrayList<Entry>()
        val contains = ArrayList<Entry>()
        for ((entry, keys) in searchIndex) {
            if (!filter(entry)) continue
            when {
                keys.any { it == key } -> exact += entry
                keys.any { it.startsWith(key) } -> prefix += entry
                keys.any { it.contains(key) } -> contains += entry
            }
        }
        return (exact + prefix + contains).take(limit)
    }

    fun entriesWithPos(pos: PartOfSpeech): List<Entry> = byRank.filter { it.pos == pos }

    companion object {
        fun searchKey(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFC).trim().lowercase(Locale.GERMAN)

        /**
         * Parses and validates a pack. Throws [ContentValidationException] without
         * returning a partially usable pack, so callers keep their existing content.
         */
        fun load(text: String, languagePack: LanguagePack, requireRelease: Boolean = false): ContentPack {
            val dto = try {
                PackFormat.decode(text)
            } catch (e: Exception) {
                throw ContentValidationException(listOf("Pack JSON could not be parsed: ${e.message}"))
            }
            val manifest = dto.manifest.toModel(sha256(text))
            val entries = try {
                dto.entries.map { it.toModel() }
            } catch (e: IllegalArgumentException) {
                throw ContentValidationException(listOf("Invalid entry record: ${e.message}"))
            }
            val audio = dto.audio.map { it.toModel() }
            val report = PackValidator(languagePack).validate(manifest, entries, audio, if (requireRelease) PackValidator.Mode.RELEASE else PackValidator.Mode.fromManifest(manifest))
            if (report.errors.isNotEmpty()) throw ContentValidationException(report.errors)
            return ContentPack(manifest, entries, audio.associateBy { it.audioId })
        }

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

class ContentValidationException(val errors: List<String>) :
    Exception("Content pack failed validation (${errors.size} errors): ${errors.take(5).joinToString("; ")}")
