package com.vocab5000.feature.today

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.ArticleText
import com.vocab5000.core.designsystem.Bricolage
import com.vocab5000.core.designsystem.GoalPill
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.QuickLinkCard
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SpeakerButton
import com.vocab5000.core.designsystem.StatChip
import com.vocab5000.core.designsystem.Wordmark
import com.vocab5000.core.designsystem.articleColor
import com.vocab5000.core.designsystem.articleTint
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.settings.UserPreferences
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    clockChanged: Boolean,
    pendingAssessment: Boolean,
    onStart: (SessionMode?) -> Unit,
    onAssessment: () -> Unit,
    onPractice: () -> Unit,
    onOpenPremium: () -> Unit,
    onPlay: (audioId: String?, fallbackText: String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmNewWithBacklog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Wordmark(Modifier.weight(1f))
            state.date?.let {
                Text(
                    it.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(greeting(), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your 5,000 most useful German words",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!state.loaded) {
            LoadingState()
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(
                "🔥 ${state.streak} ${if (state.streak == 1) "day" else "days"}",
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
            StatChip(
                "📚 ${state.dueCount} due",
                container = MaterialTheme.colorScheme.primaryContainer,
            )
            StatChip(
                "✨ ${state.introducedToday} new today",
                container = MaterialTheme.colorScheme.secondaryContainer,
            )
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

        DailyGoalCard(
            state = state,
            onStart = onStart,
            onLearnNew = { if (state.backlogWarning) confirmNewWithBacklog = true else onStart(SessionMode.LEARN_NEW) },
            onGoalSelected = viewModel::setGoal,
        )

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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickLinkCard(Icons.Filled.FitnessCenter, "Practice", onClick = onPractice, modifier = Modifier.weight(1f))
            QuickLinkCard(Icons.Filled.Quiz, "Check what I know", onClick = onAssessment, modifier = Modifier.weight(1f))
        }

        state.wordOfDay?.let { word ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Word of the day",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArticleText(word.headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        SpeakerButton(onClick = { onPlay(word.wordAudioId, word.citation) }, contentDescription = "Play word")
                    }
                    Text(word.gloss, style = MaterialTheme.typography.titleMedium)
                    if (word.exampleDe != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "“${word.exampleDe}”" + if (word.exampleEn != null) " — ${word.exampleEn}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            SpeakerButton(onClick = { onPlay(word.exampleAudioId, word.exampleDe) }, contentDescription = "Play example")
                        }
                    }
                }
            }
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
}

/**
 * Daily goal as tiles: one per new word of today's goal. Learned words fill their
 * tile in the der/die/das color, and the next tile pulses as a dashed slot.
 */
@Composable
private fun DailyGoalCard(
    state: TodayState,
    onStart: (SessionMode?) -> Unit,
    onLearnNew: () -> Unit,
    onGoalSelected: (Int) -> Unit,
) {
    val dailyGoal = state.dailyGoal
    val count = minOf(state.introducedToday, dailyGoal)
    val left = dailyGoal - count
    val done = state.goalMet
    // Up to 10 tiles get big labelled tiles; larger goals get compact color chips.
    val large = dailyGoal <= 10
    val columns = if (large) 5 else 10

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Daily goal",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp),
                    )
                    Text(
                        when {
                            dailyGoal == 0 -> "New words are off; reviews keep coming"
                            done -> "All tiles filled for today!"
                            count == 0 -> "Every new word you learn fills a tile"
                            left == 1 -> "1 tile left to fill today"
                            else -> "$left tiles left to fill today"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    buildAnnotatedString {
                        append("$count")
                        withStyle(SpanStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("/$dailyGoal")
                        }
                    },
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 30.sp,
                        lineHeight = 32.sp,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }

            if (dailyGoal > 0) {
                Column(
                    Modifier.clearAndSetSemantics {
                        contentDescription = "$count of $dailyGoal new words learned today"
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (0 until dailyGoal).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { i ->
                                GoalTile(
                                    index = i,
                                    count = count,
                                    word = state.goalWords.getOrNull(i),
                                    large = large,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Text(
                "${state.gradedToday} reviews done · ${state.dueCount} reviews still due",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        primaryLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "New words per day",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UserPreferences.GOAL_PRESETS.forEach { goal ->
                        GoalPill("$goal", selected = goal == dailyGoal) { onGoalSelected(goal) }
                    }
                }
            }
        }
    }
}

/** One tile of the goal grid: filled (learned), the pulsing next slot, or empty. */
@Composable
private fun GoalTile(index: Int, count: Int, word: GoalWord?, large: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val tileModifier = modifier
        .height(if (large) 52.dp else 24.dp)
        .clip(shape)
    when {
        index < count -> {
            val article = word?.article
            val color = articleColor(article).takeOrElse { MaterialTheme.colorScheme.primary }
            val tint = articleTint(article).takeOrElse { MaterialTheme.colorScheme.primaryContainer }
            Column(
                tileModifier
                    .background(if (large) tint else color)
                    .padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
            ) {
                if (large && word != null) {
                    article?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                    }
                    Text(
                        word.word,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = Bricolage,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        index == count -> {
            val pulse = rememberInfiniteTransition(label = "nextTile")
            val scale by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 0.93f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                label = "nextTileScale",
            )
            val dash = MaterialTheme.colorScheme.primary
            Box(
                tileModifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - (1f - scale) / 0.07f * 0.4f
                    }
                    .drawBehind {
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            color = dash,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(10.dp.toPx() - stroke / 2),
                            style = Stroke(
                                width = stroke,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (large) {
                    Text(
                        "?",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = Bricolage,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }
        }

        else -> Box(tileModifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
private fun PillAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning!"
    in 12..17 -> "Good afternoon!"
    else -> "Good evening!"
}
