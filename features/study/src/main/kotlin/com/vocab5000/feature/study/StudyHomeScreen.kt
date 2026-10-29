package com.vocab5000.feature.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.LockBadge
import com.vocab5000.core.designsystem.OptionCard
import com.vocab5000.core.designsystem.ScreenHeader
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.model.SessionMode

data class BandOption(val band: Int, val label: String, val unlocked: Boolean, val selected: Boolean)

private val TileMinHeight = 104.dp

@Composable
fun StudyHomeScreen(
    bands: List<BandOption>,
    onStartSession: (SessionMode) -> Unit,
    onPractice: () -> Unit,
    onAssessment: () -> Unit,
    onSelectBand: (Int) -> Unit,
    onOpenPremium: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("Study", subtitle = "Choose how you want to practise")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { onStartSession(SessionMode.MIXED) },
                shape = MaterialTheme.shapes.large,
                color = colors.primary,
                contentColor = colors.onPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(min = TileMinHeight),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Filled.PlayCircleFilled, contentDescription = null, modifier = Modifier.size(28.dp))
                    Text("Review and learn", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeTile(Icons.Filled.AddCircleOutline, "Learn new words", Modifier.weight(1f)) { onStartSession(SessionMode.LEARN_NEW) }
                ModeTile(Icons.Filled.Replay, "Reviews only", Modifier.weight(1f)) { onStartSession(SessionMode.REVIEW_ONLY) }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeTile(Icons.Filled.FitnessCenter, "Practice", Modifier.weight(1f), detail = "Doesn't affect your schedule", onClick = onPractice)
                ModeTile(Icons.Filled.Quiz, "Check what I know", Modifier.weight(1f), onClick = onAssessment)
            }
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("New words come from")
            Text(
                "New words are introduced in rank order from the band you choose.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bands.forEach { b ->
                val status = when {
                    b.selected -> "selected"
                    !b.unlocked -> "Premium"
                    else -> null
                }
                OptionCard(
                    title = "Ranks ${b.label}",
                    selected = b.selected,
                    detail = if (b.unlocked) null else "Part of Premium",
                    trailing = if (b.unlocked) null else ({ LockBadge() }),
                    onClick = { if (b.unlocked) onSelectBand(b.band) else onOpenPremium() },
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull("Ranks ${b.label}", status).joinToString(", ")
                        selected = b.selected
                    },
                )
            }
        }
    }
}

@Composable
private fun ModeTile(icon: ImageVector, title: String, modifier: Modifier = Modifier, detail: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxHeight().heightIn(min = TileMinHeight).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(top = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
