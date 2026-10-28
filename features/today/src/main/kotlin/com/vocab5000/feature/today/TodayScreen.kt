package com.vocab5000.feature.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.settings.UserPreferences
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.roundToInt

private const val INTRO_MILLIS = 1800

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    clockChanged: Boolean,
    pendingAssessment: Boolean,
    onStart: (SessionMode?) -> Unit,
    onAssessment: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmNewWithBacklog by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf(false) }

    // The dial, counters and streak map count up once when the screen opens.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(state.loaded) {
        if (state.loaded) intro.animateTo(1f, tween(INTRO_MILLIS, easing = LinearEasing))
    }
    val p = intro.value
    val counted = segment(p, 0f, 0.85f)

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                ScreenTitle("Today", Modifier.weight(1f).alignByBaseline())
                state.date?.let {
                    Text(
                        it.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            if (!state.loaded) {
                LoadingState()
                return@Column
            }
            if (clockChanged) {
                InfoCard { Text("Your device clock changed recently, so the number of due reviews may have shifted.") }
            }
            if (pendingAssessment) {
                InfoCard {
                    Text("You chose to check what you already know.")
                    SecondaryAction("Take the short assessment", onClick = onAssessment)
                }
            }
            GoalDial(state, p, counted, onChangeGoal = { editGoal = true })
            StreakCard(state, p, counted)
            if (state.freeLimitReached) {
                InfoCard {
                    Text("You've reached the end of the free words. Your progress, reviews, practice and backups stay free.")
                    SecondaryAction("About Premium", onClick = onOpenPremium)
                }
            }
            if (state.backlogWarning) {
                InfoCard {
                    Text("You have a large review backlog. Short batches help — nothing has been reset.")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Text("Pause new words", Modifier.weight(1f))
                        Switch(checked = state.newWordsPaused, onCheckedChange = viewModel::setPaused)
                    }
                }
            } else if (state.newWordsPaused) {
                InfoCard {
                    Text("New words are paused.")
                    SecondaryAction("Resume new words", onClick = { viewModel.setPaused(false) })
                }
            }
            if (!state.hasActiveSession && state.dueCount == 0 && state.newAllowance == 0) {
                InfoCard { Text("You're all caught up. Come back later for reviews, or practise words you already know.") }
            }
        }
        if (state.loaded) {
            SessionActions(
                state = state,
                onStart = onStart,
                onLearnNew = { if (state.backlogWarning) confirmNewWithBacklog = true else onStart(SessionMode.LEARN_NEW) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }

    if (confirmNewWithBacklog) {
        AlertDialog(
            onDismissRequest = { confirmNewWithBacklog = false },
            title = { Text("Add new words?") },
            text = { Text("You have ${state.dueCount} reviews due. New words will add to that backlog. You can still learn a few if you like.") },
            confirmButton = { TextButton(onClick = { confirmNewWithBacklog = false; onStart(SessionMode.LEARN_NEW) }) { Text("Learn new words") } },
            dismissButton = { TextButton(onClick = { confirmNewWithBacklog = false; onStart(SessionMode.REVIEW_ONLY) }) { Text("Review first") } },
        )
    }
    if (editGoal) {
        AlertDialog(
            onDismissRequest = { editGoal = false },
            title = { Text("New words per day") },
            text = {
                Column {
                    UserPreferences.GOAL_PRESETS.forEach { goal ->
                        TextButton(onClick = { viewModel.setGoal(goal); editGoal = false }) {
                            Text(if (goal == state.dailyGoal) "✓ $goal" else "$goal")
                        }
                    }
                    Text("More options in Settings.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { editGoal = false }) { Text("Close") } },
        )
    }
}

/** Twin rings: new words against the daily goal outside, today's reviews against all of today's reviews inside. */
@Composable
private fun GoalDial(state: TodayState, p: Float, counted: Float, onChangeGoal: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val newFraction = if (state.dailyGoal > 0) (state.introducedToday.toFloat() / state.dailyGoal).coerceIn(0f, 1f) else 0f
    val reviewTotal = state.gradedToday + state.dueCount
    val reviewFraction = if (reviewTotal > 0) state.gradedToday.toFloat() / reviewTotal else 0f
    val newSweep = newFraction * counted
    val reviewSweep = reviewFraction * segment(p, 0.1f, 0.95f)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.size(176.dp).clearAndSetSemantics {
                contentDescription = "${state.introducedToday} of ${state.dailyGoal} new words learned today, " +
                    "${state.gradedToday} reviews done, ${state.dueCount} reviews due"
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                ring(inset = 0.dp, stroke = 14.dp, sweep = newSweep, color = colors.primary, track = colors.surfaceContainerHigh)
                ring(inset = 20.dp, stroke = 12.dp, sweep = reviewSweep, color = colors.secondary, track = colors.surfaceContainerHigh)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        (state.introducedToday * counted).roundToInt().toString(),
                        color = colors.primary,
                        fontSize = 40.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("/${state.dailyGoal}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                Text("new words", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(colors.primary, "${(state.newAllowance + state.introducedToday - (state.introducedToday * counted).roundToInt()).coerceAtLeast(0)} left", "of today's new words")
            LegendItem(colors.secondary, "${(state.gradedToday * counted).roundToInt()} done", "${(state.dueCount * counted).roundToInt()} reviews still due")
            TextButton(onClick = onChangeGoal, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("Goal ${state.dailyGoal} · Change")
            }
        }
    }
}

private fun DrawScope.ring(inset: Dp, stroke: Dp, sweep: Float, color: Color, track: Color) {
    val half = stroke.toPx() / 2
    val offset = inset.toPx() + half
    val topLeft = Offset(offset, offset)
    val arcSize = Size(size.width - offset * 2, size.height - offset * 2)
    val style = Stroke(stroke.toPx())
    drawArc(track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
    if (sweep > 0f) drawArc(color, startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = style)
}

@Composable
private fun LegendItem(dot: Color, value: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.padding(top = 7.dp).size(10.dp).background(dot, CircleShape))
        Column {
            Text(value, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The streak count above a 12-week map of study days; the map fades in left to right. */
@Composable
private fun StreakCard(state: TodayState, p: Float, counted: Float) {
    val colors = MaterialTheme.colorScheme
    val levels = listOf(
        colors.surfaceContainerHigh,
        colors.primary.copy(alpha = 0.3f).compositeOver(colors.surface),
        colors.primary.copy(alpha = 0.6f).compositeOver(colors.surface),
        colors.primary,
    )
    val todayIndex = state.heat.indexOfLast { it >= 0 }
    Column(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(20.dp))
            Text(
                "${(state.streak * counted).roundToInt()}-day streak",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 6.dp).weight(1f),
            )
            Text("$STREAK_MAP_WEEKS weeks", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Study days over the last $STREAK_MAP_WEEKS weeks" },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            state.heat.chunked(7).forEachIndexed { week, days ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    days.forEachIndexed { day, level ->
                        val i = week * 7 + day
                        val start = i / state.heat.size.toFloat() * 0.6f
                        val shape = RoundedCornerShape(3.dp)
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .alpha(segment(p, start, start + 0.25f))
                                .background(if (level < 0) colors.surfaceContainerLow else levels[level], shape)
                                .then(if (i == todayIndex) Modifier.border(BorderStroke(2.dp, colors.secondary), shape) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionActions(state: TodayState, onStart: (SessionMode?) -> Unit, onLearnNew: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val primaryLabel = when {
            state.hasActiveSession -> "Continue session"
            state.dueCount > 0 || state.newAllowance > 0 -> {
                val verb = if (state.introducedToday + state.gradedToday > 0) "Continue" else "Start"
                val parts = listOfNotNull(
                    state.dueCount.takeIf { it > 0 }?.let { if (it == 1) "1 review" else "$it reviews" },
                    state.newAllowance.takeIf { it > 0 }?.let { "$it new" },
                )
                "$verb: ${parts.joinToString(", ")}"
            }
            else -> null
        }
        if (primaryLabel != null) {
            Button(
                onClick = { onStart(if (state.hasActiveSession) null else SessionMode.MIXED) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 8.dp),
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
        val showReviews = state.dueCount > 0
        val showNew = state.newAllowance > 0
        if (showReviews || showNew) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showReviews) PillAction("Reviews only", Modifier.weight(1f)) { onStart(SessionMode.REVIEW_ONLY) }
                if (showNew) PillAction("A few new words", Modifier.weight(1f), onLearnNew)
            }
        }
    }
}

@Composable
private fun PillAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ease-out cubic progress of [p] between [from] and [to]. */
private fun segment(p: Float, from: Float, to: Float): Float {
    val x = ((p - from) / (to - from)).coerceIn(0f, 1f)
    return 1f - (1f - x).pow(3)
}
