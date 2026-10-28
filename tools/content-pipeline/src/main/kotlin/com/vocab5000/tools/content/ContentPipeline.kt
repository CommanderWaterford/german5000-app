package com.vocab5000.tools.content

import com.vocab5000.core.content.AudioDto
import com.vocab5000.core.content.ContentPack
import com.vocab5000.core.content.EntryDto
import com.vocab5000.core.content.ExampleDto
import com.vocab5000.core.content.ManifestDto
import com.vocab5000.core.content.PackDto
import com.vocab5000.core.content.PackFormat
import com.vocab5000.core.content.PackFormat.toModel
import com.vocab5000.core.content.PackValidator
import com.vocab5000.core.content.SenseDto
import com.vocab5000.core.learning.SrsV1
import com.vocab5000.core.learning.TextSpans
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.languages.german.GermanLanguagePack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.system.exitProcess

/** Editorial source format (`content/<pack>/source/entries.json`). */
@Serializable
data class SourceFile(val entries: List<SourceEntry>)

@Serializable
data class SourceEntry(
    val entryId: String,
    val lemma: String,
    val pos: String,
    val grammar: Map<String, String> = emptyMap(),
    val senses: List<SourceSense>,
    val examples: List<SourceExample>,
    val aliases: List<String> = emptyList(),
    val editorialStatus: String,
    val rightsIds: List<String> = emptyList(),
    val revision: Int = 1,
    val register: String? = null,
    val region: String? = null,
    val cefrTag: String? = null,
    val notes: String? = null,
    val assessmentEligible: Boolean = true,
    val corpusDraft: Boolean = false,
)

@Serializable
data class SourceSense(
    val glossEn: String,
    val register: String? = null,
    val region: String? = null,
    val acceptedAnswers: List<String> = emptyList(),
    /** Null means "derive umlaut/ß alternatives"; an explicit list (even empty) wins. */
    val keyboardAlternatives: List<String>? = null,
    val acceptedMeaningsEn: List<String> = emptyList(),
)

@Serializable
data class SourceExample(
    val textDe: String,
    val textEn: String,
    /** Surface tokens of the target, located as whole words (several for separable verbs). */
    val target: List<String>,
    /** 1-based sense index. */
    val sense: Int = 1,
)

@Serializable
data class PackSettings(
    val packId: String,
    val contentVersion: String,
    val languageId: String,
    val freeEntryLimit: Int,
    val bandSize: Int,
    val rankSource: String,
    val rightsManifestId: String,
)

class PipelineResult(val pack: PackDto, val errors: List<String>, val warnings: List<String>, val audioFiles: Map<String, File>)

/**
 * Builds the read-only release pack from editorial sources: assigns curriculum ranks from
 * the ordered curriculum list, derives stable sense/example/audio IDs, resolves target
 * spans to code point offsets, and validates the result (spec §5, §9.2, §16.1).
 */
class ContentPipeline(private val languagePack: LanguagePack) {
    private val json = Json { ignoreUnknownKeys = false }

    fun build(sourceDir: File, mode: PackValidator.Mode): PipelineResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val settings = json.decodeFromString(PackSettings.serializer(), File(sourceDir, "pack-settings.json").readText())
        val source = json.decodeFromString(SourceFile.serializer(), File(sourceDir, "entries.json").readText())
        val order = File(sourceDir, "curriculum.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val audioRoot = File(sourceDir.parentFile, "audio")

        val byId = source.entries.associateBy { it.entryId }
        order.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "curriculum lists $it more than once" }
        (order - byId.keys).forEach { errors += "curriculum references unknown entry $it" }
        (byId.keys - order.toSet()).forEach { errors += "entry $it is not ranked in curriculum.txt" }

        val foldedLemmas = source.entries.map { fold(it.lemma) }.toSet()
        val audio = mutableListOf<AudioDto>()
        val audioFiles = mutableMapOf<String, File>()

        val entries = order.mapIndexedNotNull { index, id ->
            val src = byId[id] ?: return@mapIndexedNotNull null
            val where = src.entryId
            if (!Normalizer.isNormalized(src.lemma, Normalizer.Form.NFC)) errors += "$where: lemma not NFC"
            val senses = src.senses.mapIndexed { i, s ->
                SenseDto(
                    senseId = "${src.entryId}.s${i + 1}",
                    glossEn = s.glossEn,
                    register = s.register,
                    region = s.region,
                    acceptedAnswers = s.acceptedAnswers,
                    keyboardAlternatives = s.keyboardAlternatives ?: deriveAlternatives(src.lemma, foldedLemmas, warnings, where),
                    acceptedMeaningsEn = s.acceptedMeaningsEn,
                )
            }
            val examples = src.examples.mapIndexed { i, ex ->
                val exampleId = "${src.entryId}.ex${i + 1}"
                val spans = ex.target.mapIndexedNotNull { tokenIndex, token ->
                    val all = TextSpans.locateAll(ex.textDe, token)
                    when {
                        all.size == 1 -> all.first()
                        all.size > 1 -> if (ex.target.size > 1 && tokenIndex == ex.target.lastIndex) all.last() else all.first()
                        else -> {
                            errors += "$where: target '$token' is not a unique whole word in \"${ex.textDe}\""
                            null
                        }
                    }
                }
                val sense = senses.getOrNull(ex.sense - 1)
                if (sense == null) errors += "$where: example ${i + 1} references missing sense ${ex.sense}"
                val audioId = "$exampleId.audio"
                audio += audioRecord(audioId, "audio/${src.entryId}/ex${i + 1}.m4a", ex.textDe, audioRoot, audioFiles, mode, errors, warnings)
                ExampleDto(exampleId, sense?.senseId ?: "", ex.textDe, ex.textEn, spans.map { listOf(it.start, it.end) }, audioId)
            }
            val wordAudioId = "${src.entryId}.word"
            EntryDto(
                entryId = src.entryId,
                curriculumRank = index + 1,
                lemma = src.lemma,
                pos = src.pos,
                languageId = settings.languageId,
                primarySenseId = senses.firstOrNull()?.senseId ?: "",
                grammar = src.grammar,
                senses = senses,
                examples = examples,
                wordAudioId = wordAudioId,
                revision = src.revision,
                editorialStatus = src.editorialStatus,
                register = src.register,
                region = src.region,
                cefrTag = src.cefrTag,
                notes = src.notes,
                searchAliases = src.aliases,
                rightsIds = src.rightsIds,
                assessmentEligible = src.assessmentEligible,
                corpusDraft = src.corpusDraft,
            ).also { dto ->
                val spoken = runCatching { languagePack.grammarRenderer.citationForm(dto.toModel()) }.getOrDefault(src.lemma)
                audio += audioRecord(wordAudioId, "audio/${src.entryId}/word.m4a", spoken, audioRoot, audioFiles, mode, errors, warnings)
            }
        }

        val manifest = ManifestDto(
            packId = settings.packId,
            schemaVersion = PackFormat.SCHEMA_VERSION,
            contentVersion = settings.contentVersion,
            languageId = settings.languageId,
            entryCount = entries.size,
            schedulerCompatibility = listOf(SrsV1.VERSION),
            rightsManifestId = settings.rightsManifestId,
            freeEntryLimit = settings.freeEntryLimit,
            bandSize = settings.bandSize,
            buildMode = if (mode == PackValidator.Mode.RELEASE) "release" else "pilot",
            rankSource = settings.rankSource,
        )
        val pack = PackDto(manifest, entries, audio)
        try {
            val models = entries.map { it.toModel() }
            val report = PackValidator(languagePack).validate(manifest.toModel(null), models, audio.map { it.toModel() }, mode)
            errors += report.errors
            warnings += report.warnings
        } catch (e: IllegalArgumentException) {
            errors += "invalid record: ${e.message}"
        }
        return PipelineResult(pack, errors, warnings, audioFiles)
    }

    private fun audioRecord(
        audioId: String,
        path: String,
        spokenText: String,
        audioRoot: File,
        files: MutableMap<String, File>,
        mode: PackValidator.Mode,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ): AudioDto {
        val file = File(audioRoot, path.removePrefix("audio/"))
        val checksum = if (file.isFile) {
            files[path] = file
            sha256(file)
        } else {
            val message = "audio file missing for $audioId ($path)"
            if (mode == PackValidator.Mode.RELEASE) errors += message else warnings += message
            null
        }
        return AudioDto(audioId = audioId, path = path, spokenText = spokenText, checksumSha256 = checksum)
    }

    /** ä→ae, ö→oe, ü→ue, ß→ss — only when the result does not collide with another lemma. */
    private fun deriveAlternatives(lemma: String, foldedLemmas: Set<String>, warnings: MutableList<String>, where: String): List<String> {
        if (lemma.none { it in "äöüÄÖÜß" }) return emptyList()
        val alt = lemma.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
            .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss")
        if (fold(alt) in foldedLemmas) {
            warnings += "$where: keyboard alternative '$alt' collides with another lemma and was not derived"
            return emptyList()
        }
        return listOf(alt)
    }

    private fun fold(text: String) = text.lowercase(Locale.GERMAN)

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

private fun usage(): Nothing {
    System.err.println(
        """
        Usage:
          validate <sourceDir> [pilot|release]
          build <sourceDir> <assetsOutDir> <reportFile> [pilot|release]
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val pipeline = ContentPipeline(GermanLanguagePack)
    when (args[0]) {
        "validate" -> {
            val source = File(args.getOrNull(1) ?: usage())
            val mode = parseMode(args.getOrNull(2))
            val result = pipeline.build(source, mode)
            println(report(result, mode))
            if (result.errors.isNotEmpty()) exitProcess(1)
        }
        "build" -> {
            val source = File(args.getOrNull(1) ?: usage())
            val out = File(args.getOrNull(2) ?: usage())
            val reportFile = File(args.getOrNull(3) ?: usage())
            val mode = parseMode(args.getOrNull(4))
            val result = pipeline.build(source, mode)
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(report(result, mode))
            if (result.errors.isNotEmpty()) {
                System.err.println(report(result, mode))
                exitProcess(1)
            }
            out.deleteRecursively()
            val packDir = File(out, "content/${result.pack.manifest.packId}").apply { mkdirs() }
            val text = PackFormat.encode(result.pack)
            File(packDir, "pack.json").writeText(text)
            result.audioFiles.forEach { (path, file) -> file.copyTo(File(out, path), overwrite = true) }
            // Round-trip through the runtime loader so the app never receives a pack it would reject.
            ContentPack.load(text, GermanLanguagePack, requireRelease = mode == PackValidator.Mode.RELEASE)
            println("Built ${result.pack.entries.size} entries (${mode.name.lowercase()}) → ${packDir.path}; ${result.warnings.size} warnings")
        }
        else -> usage()
    }
}

private fun parseMode(value: String?) = when (value) {
    null, "pilot" -> PackValidator.Mode.PILOT
    "release" -> PackValidator.Mode.RELEASE
    else -> usage()
}

private fun report(result: PipelineResult, mode: PackValidator.Mode): String = buildString {
    appendLine("German5000 content validation report")
    appendLine("Mode: ${mode.name.lowercase()}")
    appendLine("Entries: ${result.pack.entries.size}; audio records: ${result.pack.audio.size}; audio files present: ${result.audioFiles.size}")
    appendLine("Content version: ${result.pack.manifest.contentVersion}; rank source: ${result.pack.manifest.rankSource}")
    appendLine("Errors (${result.errors.size}):")
    result.errors.forEach { appendLine("  ERROR $it") }
    appendLine("Warnings (${result.warnings.size}):")
    result.warnings.take(200).forEach { appendLine("  WARN  $it") }
    if (result.warnings.size > 200) appendLine("  … ${result.warnings.size - 200} more")
}
