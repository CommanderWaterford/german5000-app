package com.vocab5000.feature.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.model.SessionMode

data class BandOption(val band: Int, val label: String, val unlocked: Boolean, val selected: Boolean)

private val TileShape = RoundedCornerShape(16.dp)
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "STUDY",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 40.dp).padding(top = 10.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { onStartSession(SessionMode.MIXED) },
                shape = TileShape,
                color = colors.primary,
                contentColor = colors.onPrimary,
                modifier = Modifier.fillMaxWidth().heightIn(min = TileMinHeight),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
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
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("New words come from", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("New words are introduced in rank order from the band you choose.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            bands.forEachIndexed { index, b ->
                BandRung(b, first = index == 0, onClick = { if (b.unlocked) onSelectBand(b.band) else onOpenPremium() })
            }
        }
    }
}

@Composable
private fun ModeTile(icon: ImageVector, title: String, modifier: Modifier = Modifier, detail: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = TileShape, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().heightIn(min = TileMinHeight).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(top = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One rung of the rank ladder; later bands show only their upper rank to keep the column narrow. */
@Composable
private fun BandRung(band: BandOption, first: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shortLabel = if (first) band.label else "–" + band.label.substringAfter('–')
    val status = when {
        band.selected -> "selected"
        !band.unlocked -> "Premium"
        else -> null
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (band.selected) colors.surface else colors.surfaceContainerLow,
        border = if (band.selected) BorderStroke(2.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull("Ranks ${band.label}", status).joinToString(", ")
            selected = band.selected
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                shortLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (band.selected) FontWeight.Bold else FontWeight.Medium,
                color = if (band.selected) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
            )
            Box(Modifier.weight(1f).height(4.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(2.dp)))
            when {
                band.selected -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                !band.unlocked -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(16.dp))
                    Text("Premium", style = MaterialTheme.typography.labelMedium, color = colors.secondary)
                }
                else -> Box(Modifier.size(20.dp))
            }
        }
    }
}
