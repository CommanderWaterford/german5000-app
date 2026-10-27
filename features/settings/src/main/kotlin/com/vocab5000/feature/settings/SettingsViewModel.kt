package com.vocab5000.feature.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.billing.EntitlementRepository
import com.vocab5000.core.billing.OwnedQuery
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.model.ScheduledAnswerStyle
import com.vocab5000.core.settings.BackupLimitException
import com.vocab5000.core.settings.BackupValidationException
import com.vocab5000.core.settings.ImportPreview
import com.vocab5000.core.settings.PreferencesRepository
import com.vocab5000.core.settings.ProgressBackup
import com.vocab5000.core.settings.ReminderScheduler
import com.vocab5000.core.settings.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Working : BackupUiState
    data class Preview(val uri: Uri, val preview: ImportPreview) : BackupUiState
    data class Message(val text: String, val isError: Boolean = false) : BackupUiState
}

class SettingsViewModel(
    private val preferences: PreferencesRepository,
    private val reminders: ReminderScheduler,
    private val learning: LearningRepository,
    private val entitlements: EntitlementRepository,
    private val backupFactory: () -> ProgressBackup,
    private val resolver: ContentResolver,
    private val onLocalDataCleared: () -> Unit,
) : ViewModel() {
    val prefs: StateFlow<UserPreferences> = preferences.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())
    val entitlement = entitlements.status

    private val _backup = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backup: StateFlow<BackupUiState> = _backup.asStateFlow()

    private val _restoreMessage = MutableStateFlow<String?>(null)
    val restoreMessage: StateFlow<String?> = _restoreMessage.asStateFlow()

    fun setGoal(goal: Int) = viewModelScope.launch { preferences.setDailyGoal(goal) }
    fun setPaused(paused: Boolean) = viewModelScope.launch { preferences.setNewWordsPaused(paused) }
    fun setAnswerStyle(style: ScheduledAnswerStyle) = viewModelScope.launch { preferences.setAnswerStyle(style) }
    fun setSlowAudio(slow: Boolean) = viewModelScope.launch { preferences.setSlowAudio(slow) }
    fun setSpeechFallback(enabled: Boolean) = viewModelScope.launch { preferences.setSpeechFallback(enabled) }
    fun setTheme(theme: String) = viewModelScope.launch { preferences.setTheme(theme) }

    fun setListening(enabled: Boolean) = viewModelScope.launch {
        preferences.setListeningEnabled(enabled)
        if (enabled) learning.ensureListeningCards()
    }

    fun setReminder(enabled: Boolean, time: LocalTime, days: Set<DayOfWeek>) = viewModelScope.launch {
        preferences.setReminder(enabled, time, days)
        reminders.reschedule()
    }

    fun restorePurchases() = viewModelScope.launch {
        _restoreMessage.value = when (entitlements.restore()) {
            is OwnedQuery.Success -> if (entitlements.isPremium.value) "Premium is active." else "No purchase found for this Google Play account."
            is OwnedQuery.Unavailable -> "Couldn't reach Google Play. Your access is unchanged."
        }
    }

    fun export(uri: Uri) = viewModelScope.launch {
        _backup.value = BackupUiState.Working
        _backup.value = try {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { out -> backupFactory().export(out, learning, preferences.current()) }
                    ?: error("The selected location could not be opened")
            }
            BackupUiState.Message("Backup saved. It contains your learning history, so keep it somewhere private.")
        } catch (e: BackupLimitException) {
            BackupUiState.Message(e.message ?: "Backup limit exceeded", isError = true)
        } catch (e: Exception) {
            BackupUiState.Message("The backup could not be written: ${e.message}. Your progress on this device is unchanged.", isError = true)
        }
    }

    fun previewImport(uri: Uri) = viewModelScope.launch {
        _backup.value = BackupUiState.Working
        _backup.value = try {
            val size = withContext(Dispatchers.IO) { fileSize(uri) }
            if (size != null && size > ProgressBackup.MAX_BYTES) throw BackupLimitException("The file is larger than 256 MB. Nothing was changed.")
            val preview = withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { backupFactory().validate(it) } ?: error("The file could not be opened")
            }
            BackupUiState.Preview(uri, preview)
        } catch (e: BackupValidationException) {
            BackupUiState.Message("This file can't be imported: ${e.problems.take(3).joinToString("; ")}. Nothing was changed.", isError = true)
        } catch (e: BackupLimitException) {
            BackupUiState.Message(e.message ?: "Too large", isError = true)
        } catch (e: Exception) {
            BackupUiState.Message("This file can't be imported: ${e.message}. Nothing was changed.", isError = true)
        }
    }

    /** Replace, not merge (spec §13.3). Atomic: on any failure the old data remains. */
    fun confirmImport() = viewModelScope.launch {
        val preview = _backup.value as? BackupUiState.Preview ?: return@launch
        _backup.value = BackupUiState.Working
        _backup.value = try {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(preview.uri)?.use { backupFactory().apply(it, learning) } ?: error("The file could not be opened")
            }
            preferences.applyImported(preview.preview.preferences)
            reminders.reschedule()
            BackupUiState.Message("Progress imported. Reminders are off until you turn them on again. Premium is restored only through Google Play.")
        } catch (e: Exception) {
            BackupUiState.Message("Import failed: ${e.message}. Your previous progress was kept.", isError = true)
        }
    }

    fun dismissBackupMessage() {
        _backup.value = BackupUiState.Idle
    }

    fun resetProgress() = viewModelScope.launch {
        learning.resetLearningProgress()
        preferences.resetLearningPreferences()
        _backup.value = BackupUiState.Message("Learning progress was reset. Premium and display settings were kept.")
    }

    fun deleteAllLocalData() = viewModelScope.launch {
        reminders.cancel()
        learning.deleteAllLocalData()
        preferences.clearAll()
        onLocalDataCleared()
    }

    private fun fileSize(uri: Uri): Long? = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
    }
}
