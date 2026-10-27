package com.vocab5000.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.model.SessionMode

data class BandOption(val band: Int, val label: String, val unlocked: Boolean, val selected: Boolean)

@Composable
fun StudyHomeScreen(
    bands: List<BandOption>,
    onStartSession: (SessionMode) -> Unit,
    onPractice: () -> Unit,
    onAssessment: () -> Unit,
    onSelectBand: (Int) -> Unit,
    onOpenPremium: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Study")
        PrimaryAction("Review and learn", onClick = { onStartSession(SessionMode.MIXED) })
        SecondaryAction("Learn new words", onClick = { onStartSession(SessionMode.LEARN_NEW) })
        SecondaryAction("Reviews only", onClick = { onStartSession(SessionMode.REVIEW_ONLY) })
        SecondaryAction("Practice (doesn't affect your schedule)", onClick = onPractice)
        SecondaryAction("Check what I know", onClick = onAssessment)
        SectionTitle("New words come from")
        Text("New words are introduced in rank order from the band you choose.", style = MaterialTheme.typography.bodySmall)
        bands.forEach { b ->
            val label = buildString {
                if (b.selected) append("✓ ")
                append("Ranks ${b.label}")
                if (!b.unlocked) append(" — Premium")
            }
            SecondaryAction(label, onClick = { if (b.unlocked) onSelectBand(b.band) else onOpenPremium() })
        }
    }
}
