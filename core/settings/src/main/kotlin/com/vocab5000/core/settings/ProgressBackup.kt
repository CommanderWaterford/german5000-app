package com.vocab5000.core.settings

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.vocab5000.core.database.CardStateEntity
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.ReviewEventEntity
import com.vocab5000.core.database.UserEntryEntity
import com.vocab5000.core.learning.SrsV1
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.ScheduledAnswerStyle
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.ZoneId

/** Identity of the app and pack that a backup must match. */
data class BackupIdentity(val appFamily: String, val packId: String, val languageId: String, val contentVersion: String)

data class ImportPreview(
    val exportedAt: Long,
    val contentVersion: String,
    val introducedEntries: Int,
    val cards: Int,
    val reviewEvents: Int,
    val favorites: Int,
    /** Entry IDs not present in the installed content (retired or newer content). */
    val unknownEntryIds: Int,
    /** Events dated in the future beyond the clock-error allowance; need confirmation. */
    val futureDatedEvents: Int,
    val preferences: ImportedPreferences,
)

class BackupValidationException(val problems: List<String>) :
    Exception(problems.take(3).joinToString("; "))

class BackupLimitException(message: String) : Exception(message)

/**
 * Versioned UTF-8 JSON progress backup (spec §13.2–13.3). Streaming on both sides so the
 * size limit never implies loading the whole document into memory. Purchase tokens,
 * entitlement caches, media and notification state are never exported.
 */
class ProgressBackup(
    private val identity: BackupIdentity,
    private val knownEntryIds: Set<String>,
    private val nowMillis: () -> Long,
) {
    // ---- Export --------------------------------------------------------------------------

    suspend fun export(out: OutputStream, repository: LearningRepository, prefs: UserPreferences) {
        val eventCount = repository.reviewEventCount()
        if (eventCount > MAX_REVIEW_EVENTS) {
            throw BackupLimitException("This history has $eventCount reviews, more than the $MAX_REVIEW_EVENTS supported by backup format $SCHEMA_VERSION. Nothing was changed; a newer app version is required to export it.")
        }
        val writer = JsonWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer.setIndent("")
        writer.beginObject()
        writer.name("format").value(FORMAT)
        writer.name("exportSchemaVersion").value(SCHEMA_VERSION.toLong())
        writer.name("appFamily").value(identity.appFamily)
        writer.name("packId").value(identity.packId)
        writer.name("languageId").value(identity.languageId)
        writer.name("contentVersion").value(identity.contentVersion)
        writer.name("schedulerVersion").value(SrsV1.VERSION)
        writer.name("exportedAt").value(nowMillis())

        writer.name("preferences").beginObject()
        writer.name("dailyNewGoal").value(prefs.dailyNewGoal.toLong())
        writer.name("selectedBand").value(prefs.selectedBand.toLong())
        writer.name("newWordsPaused").value(prefs.newWordsPaused)
        writer.name("listeningEnabled").value(prefs.listeningEnabled)
        writer.name("slowAudio").value(prefs.slowAudio)
        writer.name("answerStyle").value(prefs.answerStyle.name)
        writer.endObject()

        writer.name("userEntries").beginArray()
        repository.exportUserEntries().forEach { e ->
            writer.beginObject()
            writer.name("entryId").value(e.entryId)
            writer.name("introducedAt").valueOrNull(e.introducedAt)
            writer.name("introductionLocalDate").value(e.introductionLocalDate)
            writer.name("favorite").value(e.favorite)
            writer.endObject()
        }
        writer.endArray()

        writer.name("cards").beginArray()
        repository.exportCards().forEach { c ->
            writer.beginObject()
            writer.name("cardId").value(c.cardId)
            writer.name("entryId").value(c.entryId)
            writer.name("skill").value(c.skill.name)
            writer.name("state").value(c.state.name)
            writer.name("step").value(c.step.toLong())
            writer.name("easePermille").value(c.easePermille.toLong())
            writer.name("intervalDays").value(c.intervalDays.toLong())
            writer.name("recoveryIntervalDays").value(c.recoveryIntervalDays.toLong())
            writer.name("dueAt").value(c.dueAt)
            writer.name("lapses").value(c.lapses.toLong())
            writer.name("consecutiveReviewSuccesses").value(c.consecutiveReviewSuccesses.toLong())
            writer.name("priorState").value(c.priorState?.name)
            writer.name("schedulerVersion").value(c.schedulerVersion)
            writer.endObject()
        }
        writer.endArray()

        writer.name("reviewEvents").beginArray()
        var offset = 0
        while (offset < eventCount) {
            val page = repository.reviewEventPage(PAGE, offset)
            if (page.isEmpty()) break
            page.forEach { writeEvent(writer, it) }
            offset += page.size
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
    }

    private fun writeEvent(w: JsonWriter, e: ReviewEventEntity) {
        w.beginObject()
        w.name("eventId").value(e.eventId)
        w.name("attemptId").value(e.attemptId)
        w.name("cardId").value(e.cardId)
        w.name("entryId").value(e.entryId)
        w.name("sessionId").value(e.sessionId)
        w.name("promptRevision").value(e.promptRevision.toLong())
        w.name("contentVersion").value(e.contentVersion)
        w.name("mode").value(e.mode.name)
        w.name("correct").value(e.correct)
        w.name("success").value(e.success)
        w.name("rating").value(e.rating.name)
        w.name("usedHint").value(e.usedHint)
        w.name("revealed").value(e.revealed)
        w.name("validAnswerOverride").value(e.validAnswerOverride)
        w.name("overrideReason").value(e.overrideReason)
        w.name("beforeState").value(e.beforeState.name)
        w.name("beforeStep").value(e.beforeStep.toLong())
        w.name("beforeIntervalDays").value(e.beforeIntervalDays.toLong())
        w.name("beforeEasePermille").value(e.beforeEasePermille.toLong())
        w.name("beforeDueAt").value(e.beforeDueAt)
        w.name("afterState").value(e.afterState.name)
        w.name("afterStep").value(e.afterStep.toLong())
        w.name("afterIntervalDays").value(e.afterIntervalDays.toLong())
        w.name("afterEasePermille").value(e.afterEasePermille.toLong())
        w.name("afterDueAt").value(e.afterDueAt)
        w.name("schedulerVersion").value(e.schedulerVersion)
        w.name("occurredAt").value(e.occurredAt)
        w.name("localDate").value(e.localDate)
        w.name("zoneId").value(e.zoneId)
        w.name("durationMs").value(e.durationMs)
        w.endObject()
    }

    // ---- Import ----------------------------------------------------------------------------

    /** Pass 1: validates the whole file without touching local data. */
    suspend fun validate(input: InputStream): ImportPreview {
        val collector = Collector()
        parseSuspending(input, collector, null)
        return collector.preview()
    }

    /** Pass 2: streams validated records into an atomic replace transaction. */
    suspend fun apply(input: InputStream, repository: LearningRepository) {
        repository.replaceLearningData { sink ->
            val collector = Collector()
            parseSuspending(input, collector, sink)
        }
    }

    private inner class Collector {
        val problems = mutableListOf<String>()
        var exportedAt = 0L
        var contentVersion = ""
        var introduced = 0
        var favorites = 0
        var cards = 0
        var events = 0
        var unknownIds = 0
        var future = 0
        var prefs = ImportedPreferences()
        val eventHashes = HashSet<Long>()
        val attemptHashes = HashSet<Long>()
        val cardIds = HashSet<String>()
        val entryIds = HashSet<String>()

        fun problem(message: String) {
            if (problems.size < 50) problems += message
        }

        fun preview(): ImportPreview {
            if (problems.isNotEmpty()) throw BackupValidationException(problems)
            return ImportPreview(exportedAt, contentVersion, introduced, cards, events, favorites, unknownIds, future, prefs)
        }
    }

    private suspend fun parseSuspending(input: InputStream, c: Collector, sink: LearningRepository.ImportSink?) {
        val reader = JsonReader(InputStreamReader(LimitedInputStream(input, MAX_BYTES), Charsets.UTF_8))
        reader.isLenient = false
        val seen = mutableSetOf<String>()
        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw BackupValidationException(listOf("Not a German5000 backup file"))
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                seen += name
                when (name) {
                    "format" -> if (reader.nextString() != FORMAT) throw BackupValidationException(listOf("Not a German5000 progress backup"))
                    "exportSchemaVersion" -> reader.nextInt().let { if (it !in 1..SCHEMA_VERSION) throw BackupValidationException(listOf("Backup format version $it is not supported by this app version")) }
                    "appFamily" -> reader.nextString().let { if (it != identity.appFamily) c.problem("Backup is from a different app family ($it)") }
                    "packId" -> reader.nextString().let { if (it != identity.packId) throw BackupValidationException(listOf("This backup belongs to a different course ($it), not ${identity.packId}")) }
                    "languageId" -> reader.nextString().let { if (it != identity.languageId) throw BackupValidationException(listOf("This backup is for a different language ($it)")) }
                    "contentVersion" -> c.contentVersion = boundedString(reader, c, "contentVersion")
                    "schedulerVersion" -> reader.nextString().let { if (it != SrsV1.VERSION) c.problem("Scheduler $it is not compatible") }
                    "exportedAt" -> c.exportedAt = timestamp(reader, c, "exportedAt", allowFuture = true)
                    "preferences" -> c.prefs = readPreferences(reader, c)
                    "userEntries" -> readArray(reader, c, MAX_ENTRIES, "userEntries", sink?.let { s -> { b -> s.userEntries(b) } }) {
                        val entity = readUserEntry(reader, c)
                        if (entity != null) {
                            if (!c.entryIds.add(entity.entryId)) c.problem("Duplicate entry ${entity.entryId}")
                            if (entity.entryId !in knownEntryIds) c.unknownIds++
                            if (entity.introducedAt != null) c.introduced++
                            if (entity.favorite) c.favorites++
                        }
                        entity
                    }
                    "cards" -> readArray(reader, c, MAX_CARDS, "cards", sink?.let { s -> { b -> s.cards(b) } }) {
                        readCard(reader, c)?.also { card ->
                            if (!c.cardIds.add(card.cardId)) c.problem("Duplicate card ${card.cardId}")
                            c.cards++
                        }
                    }
                    "reviewEvents" -> readArray(reader, c, MAX_REVIEW_EVENTS, "reviewEvents", sink?.let { s -> { b -> s.reviewEvents(b) } }) {
                        readEvent(reader, c)?.also { e ->
                            if (!c.eventHashes.add(hash64(e.eventId))) c.problem("Duplicate review event ${e.eventId}")
                            if (!c.attemptHashes.add(hash64(e.attemptId))) c.problem("Duplicate attempt ${e.attemptId}")
                            c.events++
                        }
                    }
                    else -> reader.skipValue() // forward-compatible: ignore unknown top-level fields
                }
                if (c.problems.isNotEmpty() && sink != null) throw BackupValidationException(c.problems)
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) c.problem("Unexpected data after the backup document")
        } catch (e: BackupValidationException) {
            throw e
        } catch (e: BackupLimitException) {
            throw e
        } catch (e: IOException) {
            throw BackupValidationException(listOf("The file is not a valid backup: ${e.message}"))
        } catch (e: IllegalStateException) {
            throw BackupValidationException(listOf("The file is not a valid backup: ${e.message}"))
        } catch (e: NumberFormatException) {
            throw BackupValidationException(listOf("The file contains an invalid number: ${e.message}"))
        }
        listOf("format", "exportSchemaVersion", "packId", "languageId", "userEntries", "cards", "reviewEvents")
            .filterNot { it in seen }.forEach { c.problem("Missing required field '$it'") }
        // Cross-check references once all IDs are known.
        if (sink != null && c.problems.isNotEmpty()) throw BackupValidationException(c.problems)
    }

    /**
     * Streams an array. During validation items are discarded; during apply they are
     * flushed into the open transaction in [PAGE]-sized batches, so memory stays bounded.
     */
    private suspend fun <T> readArray(
        reader: JsonReader,
        c: Collector,
        max: Int,
        label: String,
        flush: (suspend (List<T>) -> Unit)?,
        item: () -> T?,
    ) {
        val buffer = ArrayList<T>(PAGE)
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            count++
            if (count > max) throw BackupLimitException("The backup has more than $max $label, the documented limit. Nothing was changed.")
            val value = item()
            if (flush != null && value != null) {
                if (c.problems.isNotEmpty()) throw BackupValidationException(c.problems)
                buffer += value
                if (buffer.size >= PAGE) {
                    flush(buffer.toList())
                    buffer.clear()
                }
            }
        }
        reader.endArray()
        if (flush != null && buffer.isNotEmpty()) flush(buffer.toList())
    }

    private fun readPreferences(reader: JsonReader, c: Collector): ImportedPreferences {
        var prefs = ImportedPreferences()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "dailyNewGoal" -> prefs = prefs.copy(dailyNewGoal = boundedInt(reader, c, "dailyNewGoal", 0, 30))
                "selectedBand" -> prefs = prefs.copy(selectedBand = boundedInt(reader, c, "selectedBand", 1, 1000))
                "newWordsPaused" -> prefs = prefs.copy(newWordsPaused = reader.nextBoolean())
                "listeningEnabled" -> prefs = prefs.copy(listeningEnabled = reader.nextBoolean())
                "slowAudio" -> prefs = prefs.copy(slowAudio = reader.nextBoolean())
                "answerStyle" -> prefs = prefs.copy(answerStyle = enumValue<ScheduledAnswerStyle>(reader, c, "answerStyle"))
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return prefs
    }

    private fun readUserEntry(reader: JsonReader, c: Collector): UserEntryEntity? {
        var entryId: String? = null
        var introducedAt: Long? = null
        var localDate: String? = null
        var favorite = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "entryId" -> entryId = id(reader, c, "entryId")
                "introducedAt" -> introducedAt = if (reader.peek() == JsonToken.NULL) reader.nextNull().let { null } else timestamp(reader, c, "introducedAt")
                "introductionLocalDate" -> localDate = optionalDate(reader, c)
                "favorite" -> favorite = reader.nextBoolean()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (entryId == null) {
            c.problem("A learning entry is missing its ID")
            return null
        }
        return UserEntryEntity(entryId, introducedAt, localDate, favorite)
    }

    private fun readCard(reader: JsonReader, c: Collector): CardStateEntity? {
        val f = HashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "cardId", "entryId" -> f[name] = id(reader, c, name)
                "skill" -> f[name] = enumValue<CardSkill>(reader, c, name)
                "state" -> f[name] = enumValue<CardStateType>(reader, c, name)
                "priorState" -> f[name] = if (reader.peek() == JsonToken.NULL) reader.nextNull().let { null } else enumValue<CardStateType>(reader, c, name)
                "step" -> f[name] = boundedInt(reader, c, name, 0, 1)
                "easePermille" -> f[name] = boundedInt(reader, c, name, SrsV1.MIN_EASE, SrsV1.MAX_EASE)
                "intervalDays", "recoveryIntervalDays" -> f[name] = boundedInt(reader, c, name, 0, SrsV1.MAX_INTERVAL_DAYS)
                "lapses", "consecutiveReviewSuccesses" -> f[name] = boundedInt(reader, c, name, 0, 1_000_000)
                "dueAt" -> f[name] = timestamp(reader, c, name, allowFuture = true, maxFutureDays = SrsV1.MAX_INTERVAL_DAYS.toLong() + 2)
                "schedulerVersion" -> f[name] = boundedString(reader, c, name)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return try {
            CardStateEntity(
                cardId = f["cardId"] as String,
                entryId = f["entryId"] as String,
                skill = f["skill"] as CardSkill,
                state = f["state"] as CardStateType,
                step = f["step"] as Int,
                easePermille = f["easePermille"] as Int,
                intervalDays = f["intervalDays"] as Int,
                recoveryIntervalDays = f["recoveryIntervalDays"] as Int,
                dueAt = f["dueAt"] as Long,
                lapses = f["lapses"] as Int,
                consecutiveReviewSuccesses = f["consecutiveReviewSuccesses"] as Int,
                priorState = f["priorState"] as CardStateType?,
                schedulerVersion = (f["schedulerVersion"] as String?) ?: SrsV1.VERSION,
            ).also {
                if (it.schedulerVersion != SrsV1.VERSION) c.problem("Card ${it.cardId} uses unsupported scheduler ${it.schedulerVersion}")
                if (it.state == CardStateType.SUSPENDED && it.priorState == null) c.problem("Suspended card ${it.cardId} lacks its prior state")
            }
        } catch (e: RuntimeException) {
            c.problem("A card record is incomplete or invalid")
            null
        }
    }

    private fun readEvent(reader: JsonReader, c: Collector): ReviewEventEntity? {
        val f = HashMap<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "eventId", "attemptId", "cardId", "entryId" -> f[name] = id(reader, c, name)
                "sessionId", "overrideReason" -> f[name] = if (reader.peek() == JsonToken.NULL) reader.nextNull().let { null } else boundedString(reader, c, name)
                "contentVersion", "schedulerVersion" -> f[name] = boundedString(reader, c, name)
                "zoneId" -> f[name] = boundedString(reader, c, name).also { z -> if (runCatching { ZoneId.of(z) }.isFailure) c.problem("Invalid time zone $z") }
                "localDate" -> f[name] = boundedString(reader, c, name).also { d -> if (runCatching { LocalDate.parse(d) }.isFailure) c.problem("Invalid date $d") }
                "promptRevision" -> f[name] = boundedInt(reader, c, name, 0, 1_000_000)
                "mode" -> f[name] = enumValue<ExerciseMode>(reader, c, name)
                "rating" -> f[name] = enumValue<Rating>(reader, c, name)
                "beforeState", "afterState" -> f[name] = enumValue<CardStateType>(reader, c, name)
                "correct", "success", "usedHint", "revealed", "validAnswerOverride" -> f[name] = reader.nextBoolean()
                "beforeStep", "afterStep" -> f[name] = boundedInt(reader, c, name, 0, 1)
                "beforeIntervalDays", "afterIntervalDays" -> f[name] = boundedInt(reader, c, name, 0, SrsV1.MAX_INTERVAL_DAYS)
                "beforeEasePermille", "afterEasePermille" -> f[name] = boundedInt(reader, c, name, SrsV1.MIN_EASE, SrsV1.MAX_EASE)
                "beforeDueAt", "afterDueAt" -> f[name] = timestamp(reader, c, name, allowFuture = true, maxFutureDays = SrsV1.MAX_INTERVAL_DAYS.toLong() + 2)
                "occurredAt" -> {
                    val t = timestamp(reader, c, name, allowFuture = true, maxFutureDays = 3650)
                    if (t > nowMillis() + CLOCK_ALLOWANCE_MS) c.future++
                    f[name] = t
                }
                "durationMs" -> f[name] = boundedLong(reader, c, name, 0, 24L * 60 * 60 * 1000)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return try {
            ReviewEventEntity(
                eventId = f["eventId"] as String,
                attemptId = f["attemptId"] as String,
                cardId = f["cardId"] as String,
                entryId = f["entryId"] as String,
                sessionId = f["sessionId"] as String?,
                promptRevision = (f["promptRevision"] as Int?) ?: 0,
                contentVersion = f["contentVersion"] as String,
                mode = f["mode"] as ExerciseMode,
                correct = f["correct"] as Boolean,
                success = f["success"] as Boolean,
                rating = f["rating"] as Rating,
                usedHint = f["usedHint"] as Boolean,
                revealed = f["revealed"] as Boolean,
                validAnswerOverride = f["validAnswerOverride"] as Boolean,
                overrideReason = f["overrideReason"] as String?,
                beforeState = f["beforeState"] as CardStateType,
                beforeStep = f["beforeStep"] as Int,
                beforeIntervalDays = f["beforeIntervalDays"] as Int,
                beforeEasePermille = f["beforeEasePermille"] as Int,
                beforeDueAt = f["beforeDueAt"] as Long,
                afterState = f["afterState"] as CardStateType,
                afterStep = f["afterStep"] as Int,
                afterIntervalDays = f["afterIntervalDays"] as Int,
                afterEasePermille = f["afterEasePermille"] as Int,
                afterDueAt = f["afterDueAt"] as Long,
                schedulerVersion = f["schedulerVersion"] as String,
                occurredAt = f["occurredAt"] as Long,
                localDate = f["localDate"] as String,
                zoneId = f["zoneId"] as String,
                durationMs = f["durationMs"] as Long,
            )
        } catch (e: RuntimeException) {
            c.problem("A review event is incomplete or invalid")
            null
        }
    }

    // ---- Field validators -------------------------------------------------------------------

    private fun id(reader: JsonReader, c: Collector, field: String): String {
        val value = reader.nextString()
        if (value.isEmpty() || value.length > MAX_ID_LENGTH || value.any { it.isISOControl() }) c.problem("Invalid $field")
        return value
    }

    private fun boundedString(reader: JsonReader, c: Collector, field: String): String {
        val value = reader.nextString()
        if (value.length > MAX_STRING_LENGTH) c.problem("$field is too long")
        return value
    }

    private fun boundedInt(reader: JsonReader, c: Collector, field: String, min: Int, max: Int): Int {
        val value = reader.nextDouble()
        if (!value.isFinite() || value != Math.floor(value) || value < min || value > max) {
            c.problem("$field out of range")
            return min
        }
        return value.toInt()
    }

    private fun boundedLong(reader: JsonReader, c: Collector, field: String, min: Long, max: Long): Long {
        val value = reader.nextLong()
        if (value < min || value > max) c.problem("$field out of range")
        return value.coerceIn(min, max)
    }

    private fun timestamp(reader: JsonReader, c: Collector, field: String, allowFuture: Boolean = false, maxFutureDays: Long = 0): Long {
        val value = reader.nextLong()
        val limit = nowMillis() + CLOCK_ALLOWANCE_MS + if (allowFuture) maxFutureDays * SrsV1.DAY_MS else 0
        if (value < MIN_TIMESTAMP || value > limit) c.problem("$field has an invalid timestamp")
        return value
    }

    private fun optionalDate(reader: JsonReader, c: Collector): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val value = reader.nextString()
        if (runCatching { LocalDate.parse(value) }.isFailure) c.problem("Invalid date $value")
        return value
    }

    private inline fun <reified E : Enum<E>> enumValue(reader: JsonReader, c: Collector, field: String): E? {
        val value = reader.nextString()
        return enumValues<E>().firstOrNull { it.name == value } ?: run {
            c.problem("$field has unknown value '$value'")
            null
        }
    }

    private fun JsonWriter.valueOrNull(v: Long?): JsonWriter = if (v == null) nullValue() else value(v)

    /** Throws once more than [limit] bytes have been read. */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        private fun bump(n: Long) {
            count += n
            if (count > limit) throw BackupLimitException("The backup file is larger than the ${limit / (1024 * 1024)} MB limit. Nothing was changed.")
        }
    }

    companion object {
        const val FORMAT = "vocab5000-progress-backup"
        const val SCHEMA_VERSION = 1
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_REVIEW_EVENTS = 1_000_000
        const val MAX_CARDS = 20_000
        const val MAX_ENTRIES = 20_000
        const val MAX_ID_LENGTH = 200
        const val MAX_STRING_LENGTH = 500
        const val PAGE = 500
        const val CLOCK_ALLOWANCE_MS = 2 * SrsV1.DAY_MS
        /** 2020-01-01T00:00:00Z — no German5000 history predates this. */
        const val MIN_TIMESTAMP = 1_577_836_800_000L

        /** 64-bit FNV-1a; a collision only rejects a file, it never accepts a bad one. */
        fun hash64(text: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (ch in text) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
