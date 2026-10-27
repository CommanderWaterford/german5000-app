package com.vocab5000.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.designsystem.StatTile
import com.vocab5000.core.learning.ProgressMetrics
import java.time.format.TextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(viewModel: ProgressViewModel) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Progress")
        if (!s.loaded) {
            LoadingState()
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("words introduced", "${s.introduced}", detail = "of ${s.totalEntries}")
            StatTile("currently strong", "${s.mastered}", detail = "of ${s.totalEntries}")
            StatTile("in learning", "${s.inLearning}")
            StatTile("due now", "${s.due}")
            StatTile("day streak", "${s.streak}")
        }
        Text("Free words: ${s.introducedFree} of ${s.freeEntries} introduced, ${s.masteredFree} currently strong.", style = MaterialTheme.typography.bodyMedium)

        SectionTitle("Recall accuracy (last 30 days)")
        val pct = s.accuracy.percent
        Text(
            if (pct == null) {
                "Not enough reviews yet (${s.accuracy.attempts} of ${ProgressMetrics.ACCURACY_MIN_SAMPLE} needed)."
            } else {
                "$pct% of first review attempts recalled (${s.accuracy.successes} of ${s.accuracy.attempts})."
            },
        )

        SectionTitle("Last 4 weeks")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.calendar.forEach { (date, studied) ->
                val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                Box(
                    Modifier.size(36.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .background(if (studied) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .semantics { contentDescription = "$day ${date.dayOfMonth}: ${if (studied) "study day" else "no study"}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (studied) "✓" else "${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        SectionTitle("Skills")
        Text("Recall (typing or flashcards): ${s.mastered} currently strong")
        Text(
            if (s.listeningCards == 0) "Listening practice is off. You can turn it on in Settings." else "Listening: ${s.listeningMastered} of ${s.listeningCards} well established",
        )
        if (s.suspended > 0) Text("${s.suspended} paused words")

        InfoCard {
            Text("How these are counted", style = MaterialTheme.typography.titleSmall)
            Text("“Currently strong” means a word's review interval is at least 21 days and your last two reviews were Good or Easy. It can go down after a slip — that's normal.", style = MaterialTheme.typography.bodySmall)
            Text("A study day needs 5 graded reviews or one new word. Practice and the assessment don't change these numbers. Premium words you can't currently open are not counted as failures.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
