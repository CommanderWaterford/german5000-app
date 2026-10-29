package com.vocab5000.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.ScreenHeader
import com.vocab5000.core.designsystem.StatRow
import com.vocab5000.core.learning.ProgressMetrics
import java.time.format.TextStyle

@Composable
fun ProgressScreen(viewModel: ProgressViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Progress", subtitle = "Your streak, your words and how well they stick")
        if (!s.loaded) {
            LoadingState()
            return@Column
        }

        // Streak
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Streak", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("🔥 ${s.streak} ${if (s.streak == 1) "day" else "days"}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${s.calendar.count { it.second }} study days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.calendar.chunked(7).forEach { week ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            week.forEach { (date, studied) ->
                                val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                                Box(
                                    Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (studied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .semantics { contentDescription = "$day ${date.dayOfMonth}: ${if (studied) "study day" else "no study"}" },
                                )
                            }
                        }
                    }
                }
                Text(
                    "Last 4 weeks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Words
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Words", style = MaterialTheme.typography.titleMedium)
                StatRow("Introduced", "${s.introduced} / ${s.totalEntries}")
                LinearProgressIndicator(
                    progress = { if (s.totalEntries > 0) s.introduced / s.totalEntries.toFloat() else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                StatRow("Currently strong", "${s.mastered}")
                StatRow("In learning", "${s.inLearning}")
                StatRow("Due now", "${s.due}")
                if (s.suspended > 0) StatRow("Paused", "${s.suspended}")
                Text(
                    "${s.introducedFree} of ${s.freeEntries} free words introduced · ${s.masteredFree} currently strong",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Accuracy
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recall accuracy", style = MaterialTheme.typography.titleMedium)
                val pct = s.accuracy.percent
                if (pct == null) {
                    Text(
                        "Not enough reviews yet (${s.accuracy.attempts} of ${ProgressMetrics.ACCURACY_MIN_SAMPLE} needed).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$pct %", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "of first attempts recalled (${s.accuracy.successes} of ${s.accuracy.attempts})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Text(
                    "Last 30 days",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Skills
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Skills", style = MaterialTheme.typography.titleMedium)
                StatRow("Recall (typing or flashcards)", "${s.mastered} strong")
                if (s.listeningCards == 0) {
                    Text(
                        "Listening practice is off. You can turn it on in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    StatRow("Listening", "${s.listeningMastered} of ${s.listeningCards} well established")
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How these are counted", style = MaterialTheme.typography.titleSmall)
                Text("“Currently strong” means a word's review interval is at least 21 days and your last two reviews were Good or Easy. It can go down after a slip — that's normal.", style = MaterialTheme.typography.bodySmall)
                Text("A study day needs 5 graded reviews or one new word. Practice and the assessment don't change these numbers. Premium words you can't currently open are not counted as failures.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
