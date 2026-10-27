package com.vocab5000.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.model.ScheduledAnswerStyle
import com.vocab5000.core.settings.ReminderScheduler
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

data class AppInfo(val versionName: String, val contentVersion: String, val pilotContent: Boolean)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, appInfo: AppInfo, onOpenPremium: () -> Unit) {
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val entitlement by viewModel.entitlement.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val restoreMessage by viewModel.restoreMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var page by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::previewImport) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setReminder(true, prefs.reminderTime, prefs.reminderDays) else permissionDenied = true
    }

    page?.let { (title, body) ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle(title)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            SecondaryAction("Back", onClick = { page = null })
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Settings")

        SectionTitle("Study")
        Text("New words per day: ${prefs.dailyNewGoal}")
        Slider(
            value = prefs.dailyNewGoal.toFloat(),
            onValueChange = { viewModel.setGoal(it.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
        )
        ToggleRow("Pause new words", prefs.newWordsPaused, viewModel::setPaused)
        Text("Scheduled reviews", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(prefs.answerStyle == ScheduledAnswerStyle.TYPED, { viewModel.setAnswerStyle(ScheduledAnswerStyle.TYPED) }, { Text("Type the answer") }, Modifier.heightIn(min = MinTouchTarget))
            FilterChip(prefs.answerStyle == ScheduledAnswerStyle.FLASHCARD, { viewModel.setAnswerStyle(ScheduledAnswerStyle.FLASHCARD) }, { Text("Flashcards (self-grade)") }, Modifier.heightIn(min = MinTouchTarget))
        }
        ToggleRow("Listening reviews (optional, separate from recall)", prefs.listeningEnabled, viewModel::setListening)

        SectionTitle("Reminders")
        ToggleRow("Daily reminder", prefs.reminderEnabled) { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderScheduler.canPostNotifications(context)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setReminder(enabled, prefs.reminderTime, prefs.reminderDays)
            }
        }
        if (prefs.reminderEnabled) {
            TextButton(onClick = { pickTime = true }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text("Around %02d:%02d (approximate)".format(prefs.reminderTime.hour, prefs.reminderTime.minute))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val selected = day in prefs.reminderDays
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setReminder(true, prefs.reminderTime, if (selected) prefs.reminderDays - day else prefs.reminderDays + day) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    )
                }
            }
            if (!ReminderScheduler.canPostNotifications(context)) {
                Text("Notifications are turned off for German5000, so reminders can't appear.", color = MaterialTheme.colorScheme.secondary)
                SecondaryAction("Open notification settings", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
            }
        }
        if (permissionDenied) {
            Text("Notifications weren't allowed, so reminders stay off. You can change this in system settings at any time.", style = MaterialTheme.typography.bodySmall)
        }

        SectionTitle("Audio")
        ToggleRow("Slower playback", prefs.slowAudio, viewModel::setSlowAudio)
        ToggleRow("Use device speech when a recording is missing", prefs.speechFallback, viewModel::setSpeechFallback)
        Text("Device speech is synthetic and may sound different from the recordings.", style = MaterialTheme.typography.bodySmall)

        SectionTitle("Display")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (value, label) ->
                FilterChip(prefs.theme == value, { viewModel.setTheme(value) }, { Text(label) }, Modifier.heightIn(min = MinTouchTarget))
            }
        }

        SectionTitle("Backup")
        Text("Progress is stored only on this device. A backup is an unencrypted file with your learning history — store it privately. If you save it to a cloud folder, it is uploaded there.", style = MaterialTheme.typography.bodySmall)
        SecondaryAction("Export progress", onClick = {
            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exportLauncher.launch("german5000-backup-$stamp.json")
        })
        SecondaryAction("Import progress (replaces current progress)", onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
        when (val b = backup) {
            BackupUiState.Working -> Text("Working…")
            is BackupUiState.Message -> {
                if (b.isError) ErrorMessage(b.text) else Text(b.text)
                TextButton(onClick = viewModel::dismissBackupMessage) { Text("OK") }
            }
            else -> Unit
        }

        SectionTitle("Premium")
        Text(if (entitlement.isPremium) "Premium is active." else "Free version: ranks 1–1,000 in the full release.")
        SecondaryAction("Premium details", onClick = onOpenPremium)
        SecondaryAction("Restore purchases", onClick = viewModel::restorePurchases)
        restoreMessage?.let { Text(it) }

        SectionTitle("Your data")
        SecondaryAction("Reset learning progress", onClick = { confirmReset = true })
        SecondaryAction("Delete all local app data", onClick = { confirmDelete = true })

        SectionTitle("About")
        SecondaryAction(InfoPages.PRIVACY_TITLE, onClick = { page = InfoPages.PRIVACY_TITLE to InfoPages.PRIVACY })
        SecondaryAction(InfoPages.METHOD_TITLE, onClick = { page = InfoPages.METHOD_TITLE to InfoPages.METHOD })
        SecondaryAction(InfoPages.HELP_TITLE, onClick = { page = InfoPages.HELP_TITLE to InfoPages.HELP })
        SecondaryAction(InfoPages.LICENSES_TITLE, onClick = { page = InfoPages.LICENSES_TITLE to InfoPages.LICENSES })
        Text("App ${appInfo.versionName} · content ${appInfo.contentVersion}", style = MaterialTheme.typography.bodySmall)
        if (appInfo.pilotContent) {
            InfoCard { Text("Development build: this content pack is an unreviewed pilot and is not the final German5000 word list.", style = MaterialTheme.typography.bodySmall) }
        }
    }

    (backup as? BackupUiState.Preview)?.let { p ->
        val pv = p.preview
        AlertDialog(
            onDismissRequest = viewModel::dismissBackupMessage,
            title = { Text("Replace current progress?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Backup from ${SimpleDateFormat.getDateTimeInstance().format(Date(pv.exportedAt))} (content ${pv.contentVersion})")
                    Text("${pv.introducedEntries} words introduced · ${pv.cards} cards · ${pv.reviewEvents} reviews · ${pv.favorites} favourites")
                    if (pv.unknownEntryIds > 0) Text("${pv.unknownEntryIds} words aren't in this content version; their history is kept but they won't appear.")
                    if (pv.futureDatedEvents > 0) Text("${pv.futureDatedEvents} reviews are dated in the future. Check the device clock before importing.", color = MaterialTheme.colorScheme.error)
                    Text("Your current progress on this device will be replaced. Export it first if you may need it. Reminders will be off afterwards; Premium is restored only through Google Play.")
                }
            },
            confirmButton = { TextButton(onClick = viewModel::confirmImport) { Text("Replace") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { exportLauncher.launch("german5000-before-import.json") }) { Text("Export current first") }
                    TextButton(onClick = viewModel::dismissBackupMessage) { Text("Cancel") }
                }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset learning progress?") },
            text = { Text("This deletes your learning history, sessions, assessment results and favourites on this device. Premium and display settings are kept. This can't be undone unless you have a backup.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetProgress() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local app data?") },
            text = { Text("This clears progress and settings and cancels reminders. It doesn't delete backup files you exported, Google Play purchase records or system records. A Premium purchase can be restored from Google Play.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.deleteAllLocalData() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (pickTime) {
        val state = rememberTimePickerState(prefs.reminderTime.hour, prefs.reminderTime.minute)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    viewModel.setReminder(true, LocalTime.of(state.hour, state.minute), prefs.reminderDays)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
