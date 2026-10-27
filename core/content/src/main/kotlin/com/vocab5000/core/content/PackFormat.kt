package com.vocab5000.core.content

import com.vocab5000.core.model.AudioAsset
import com.vocab5000.core.model.CodepointSpan
import com.vocab5000.core.model.ContentManifest
import com.vocab5000.core.model.EditorialStatus
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.Example
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.Sense
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format of a generated, read-only content pack (`content/<packId>/pack.json`). */
@Serializable
data class PackDto(
    val manifest: ManifestDto,
    val entries: List<EntryDto>,
    val audio: List<AudioDto>,
)

@Serializable
data class ManifestDto(
    val packId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val languageId: String,
    val entryCount: Int,
    val schedulerCompatibility: List<String>,
    val rightsManifestId: String,
    val freeEntryLimit: Int,
    val bandSize: Int,
    val buildMode: String,
    val rankSource: String,
)

@Serializable
data class SenseDto(
    val senseId: String,
    val glossEn: String,
    val register: String? = null,
    val region: String? = null,
    val acceptedAnswers: List<String> = emptyList(),
    val keyboardAlternatives: List<String> = emptyList(),
    val acceptedMeaningsEn: List<String> = emptyList(),
)

@Serializable
data class ExampleDto(
    val exampleId: String,
    val senseId: String,
    val textDe: String,
    val textEn: String,
    /** Zero-based, half-open code point offsets: [[start, end], ...]. */
    val targetSpanCodepoints: List<List<Int>>,
    val audioId: String? = null,
)

@Serializable
data class EntryDto(
    val entryId: String,
    val curriculumRank: Int,
    val lemma: String,
    val pos: String,
    val languageId: String,
    val primarySenseId: String,
    val grammar: Map<String, String> = emptyMap(),
    val senses: List<SenseDto>,
    val examples: List<ExampleDto>,
    val wordAudioId: String? = null,
    val revision: Int,
    val editorialStatus: String,
    val register: String? = null,
    val region: String? = null,
    val cefrTag: String? = null,
    val notes: String? = null,
    val searchAliases: List<String> = emptyList(),
    val rightsIds: List<String> = emptyList(),
    val assessmentEligible: Boolean = true,
)

@Serializable
data class AudioDto(
    val audioId: String,
    val path: String,
    val spokenText: String,
    val format: String = "m4a",
    val durationMs: Long? = null,
    val checksumSha256: String? = null,
    val rightsId: String? = null,
)

object PackFormat {
    const val SCHEMA_VERSION = 1

    val json = Json {
        ignoreUnknownKeys = false
        prettyPrint = false
        encodeDefaults = true
    }

    fun decode(text: String): PackDto = json.decodeFromString(PackDto.serializer(), text)

    fun encode(pack: PackDto): String = json.encodeToString(PackDto.serializer(), pack)

    fun ManifestDto.toModel(checksum: String?) = ContentManifest(
        packId = packId,
        schemaVersion = schemaVersion,
        contentVersion = contentVersion,
        languageId = languageId,
        entryCount = entryCount,
        schedulerCompatibility = schedulerCompatibility,
        rightsManifestId = rightsManifestId,
        freeEntryLimit = freeEntryLimit,
        bandSize = bandSize,
        buildMode = buildMode,
        rankSource = rankSource,
        packChecksumSha256 = checksum,
    )

    fun EntryDto.toModel(): Entry = Entry(
        entryId = entryId,
        curriculumRank = curriculumRank,
        lemma = lemma,
        pos = PartOfSpeech.valueOf(pos),
        languageId = languageId,
        primarySenseId = primarySenseId,
        grammar = grammar,
        senses = senses.map { Sense(it.senseId, it.glossEn, it.register, it.region, it.acceptedAnswers, it.keyboardAlternatives, it.acceptedMeaningsEn) },
        examples = examples.map { ex ->
            Example(
                exampleId = ex.exampleId,
                senseId = ex.senseId,
                textDe = ex.textDe,
                textEn = ex.textEn,
                targetSpans = ex.targetSpanCodepoints.map {
                    require(it.size == 2) { "Span must have two offsets in ${ex.exampleId}" }
                    CodepointSpan(it[0], it[1])
                },
                audioId = ex.audioId,
            )
        },
        wordAudioId = wordAudioId,
        revision = revision,
        editorialStatus = EditorialStatus.fromWire(editorialStatus),
        register = register,
        region = region,
        cefrTag = cefrTag,
        notes = notes,
        searchAliases = searchAliases,
        rightsIds = rightsIds,
        assessmentEligible = assessmentEligible,
    )

    fun AudioDto.toModel() = AudioAsset(audioId, path, spokenText, format, durationMs, checksumSha256, rightsId)
}
