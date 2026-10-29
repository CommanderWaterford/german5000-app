package com.vocab5000.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimum touch target per spec §15.1. */
val MinTouchTarget = 48.dp

/** The brand wordmark shown on the home and onboarding screens. */
const val BRAND_WORDMARK = "GERMAN5000"

private val Articles = setOf("der", "die", "das")

// ---- Signature colours -----------------------------------------------------------------

@Composable
fun articleColor(article: String?): Color {
    val dark = MaterialTheme.colorScheme.isDark
    return when (article) {
        "der" -> if (dark) ArticleDerDark else ArticleDerLight
        "die" -> if (dark) ArticleDieDark else ArticleDieLight
        "das" -> if (dark) ArticleDasDark else ArticleDasLight
        else -> Color.Unspecified
    }
}

/** Pale container tint of an article color, for filled tiles and chips. */
@Composable
fun articleTint(article: String?): Color {
    if (MaterialTheme.colorScheme.isDark) return articleColor(article).copy(alpha = 0.22f)
    return when (article) {
        "der" -> CobaltContainer
        "die" -> ArticleDieContainer
        "das" -> EmeraldContainer
        else -> Color.Unspecified
    }
}

@Composable
fun correctColor(): Color = if (MaterialTheme.colorScheme.isDark) CorrectGreenDark else CorrectGreen

@Composable
fun partialColor(): Color = if (MaterialTheme.colorScheme.isDark) PartialAmberDark else PartialAmber

/** The leading `der`/`die`/`das` of a headword such as `der Tisch · die Tische`, or null. */
fun leadingArticle(text: String): String? {
    val first = text.substringBefore(' ')
    return if (first != text && first in Articles) first else null
}

/** German headword with its article rendered in the der/die/das mnemonic color. */
@Composable
fun ArticleText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val article = leadingArticle(text)
    val articleTint = articleColor(article)
    val annotated = buildAnnotatedString {
        if (article != null) {
            withStyle(SpanStyle(color = articleTint, fontWeight = FontWeight.Bold)) { append(article) }
            append(text.substring(article.length))
        } else {
            append(text)
        }
    }
    Text(annotated, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
}

// ---- Brand marks -----------------------------------------------------------------------

/** Small der/die/das tricolor bar — the brand mark, used under headings. */
@Composable
fun TricolorBar(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(articleColor("der"), articleColor("die"), articleColor("das")).forEach { color ->
            Box(
                Modifier
                    .width(22.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/** Letter-spaced wordmark above the tricolor bar. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            BRAND_WORDMARK,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        TricolorBar()
    }
}

/**
 * Signature element: the daily-goal ring is drawn as three arcs in the
 * der/die/das colors — progress fills blue, then red, then green.
 */
@Composable
fun DailyGoalRing(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(700),
        label = "goalRing",
    )
    val segments = listOf(articleColor("der"), articleColor("die"), articleColor("das"))
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            segments.forEachIndexed { i, color ->
                val segmentFill = (animated * 3f - i).coerceIn(0f, 1f)
                if (segmentFill > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f + i * 120f,
                        sweepAngle = 120f * segmentFill,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

/** Five dots showing how well a word is established (0 = not started, 5 = currently strong). */
@Composable
fun MasteryDots(level: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < level) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/** Small pill chip for stats and tags. */
@Composable
fun StatChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    leadingIcon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = contentColorFor(container),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- Headers and navigation ------------------------------------------------------------

/** Heading of a top-level tab: big Bricolage title with an optional quiet subtitle. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/**
 * Top bar of a detail or focus screen. The root already applies the status-bar inset,
 * so the bar draws no inset of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

// ---- Actions ---------------------------------------------------------------------------

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
fun TonalAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = MinTouchTarget)) {
        Text(text)
    }
}

/** Compact selectable pill, e.g. for the daily goal presets. */
@Composable
fun GoalPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) ink else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.surface else ink,
        border = BorderStroke(1.dp, if (selected) ink else MaterialTheme.colorScheme.outline),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 44.dp)
                .height(32.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = Bricolage,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
        }
    }
}

/** Speaker icon button; [contentDescription] is what screen readers announce. */
@Composable
fun SpeakerButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Listen", enabled: Boolean = true) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = contentDescription)
    }
}

/** Large centred speaker for listening prompts, where the audio is the whole question. */
@Composable
fun ListeningPrompt(label: String, onSpeak: () -> Unit) {
    PromptLabel(label)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        FilledTonalIconButton(onClick = onSpeak, modifier = Modifier.size(72.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Play recording",
                modifier = Modifier.size(36.dp),
            )
        }
    }
    Text(
        "Tap the speaker to listen again",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

// ---- Cards -----------------------------------------------------------------------------

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Card that frames the question of an exercise. */
@Composable
fun PromptCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** The small coloured instruction above a question. */
@Composable
fun PromptLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

/** Quiet muted note under a prompt, e.g. "(the article is optional)". */
@Composable
fun PromptNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

enum class OptionState { NEUTRAL, CORRECT, WRONG }

/** One answer option; der/die/das options take their mnemonic colour. */
@Composable
fun ChoiceOptionCard(text: String, state: OptionState, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val container = when (state) {
        OptionState.CORRECT -> correctColor().copy(alpha = 0.16f)
        OptionState.WRONG -> MaterialTheme.colorScheme.errorContainer
        OptionState.NEUTRAL -> MaterialTheme.colorScheme.surface
    }
    val border = when (state) {
        OptionState.CORRECT -> BorderStroke(2.dp, correctColor())
        OptionState.WRONG -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        OptionState.NEUTRAL -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    val prefix = when (state) {
        OptionState.CORRECT -> "✓ "
        OptionState.WRONG -> "✗ "
        OptionState.NEUTRAL -> ""
    }
    OutlinedCard(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
        border = border,
    ) {
        Text(
            prefix + text,
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = if (text in Articles) articleColor(text) else Color.Unspecified,
        )
    }
}

/** Selectable card with a title and detail, e.g. an onboarding choice or a goal preset. */
@Composable
fun OptionCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                trailing != null -> trailing()
                selected -> Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Raised card with an icon and a short title, for quick links on the home screen. */
@Composable
fun QuickLinkCard(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Result panel after an answer, outlined in the verdict colour. */
@Composable
fun FeedbackCard(title: String, color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Example sentence with its translation and a speaker. */
@Composable
fun ExampleRow(textDe: String, textEn: String, onPlay: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(textDe, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(textEn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onPlay != null) SpeakerButton(onClick = onPlay, contentDescription = "Play example")
    }
}

/** Labelled grammar facts laid out like a dictionary entry. */
@Composable
fun GrammarTable(lines: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { (label, value) ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(label, style = labelStyle, color = labelColor)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, detail: String? = null) {
    Card(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(12.dp).widthIn(min = 96.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Big celebratory header of a result screen: emoji, title, tricolor and the score. */
@Composable
fun ResultHeader(
    emoji: String,
    title: String,
    score: String,
    scoreLabel: String,
    scoreStyle: TextStyle = MaterialTheme.typography.displayLarge,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 64.sp)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        TricolorBar()
        Spacer(Modifier.height(10.dp))
        Text(score, style = scoreStyle, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        Text(scoreLabel, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Locked state is explained in text, not only with an icon (spec §12.1). */
@Composable
fun LockBadge(modifier: Modifier = Modifier) {
    StatChip("Premium", modifier, container = MaterialTheme.colorScheme.tertiaryContainer, leadingIcon = Icons.Filled.Lock)
}

/** Speaker button whose [label] doubles as the accessibility description. */
@Composable
fun AudioButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SpeakerButton(onClick = onClick, modifier = modifier, contentDescription = label, enabled = enabled)
}

@Composable
fun PlayIcon() = Icon(Icons.Filled.PlayArrow, contentDescription = null)

/** Umlaut/ß helper buttons; they insert text and never replace the system keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpecialCharacterBar(characters: List<String>, onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        characters.forEach { c ->
            AssistChip(
                onClick = { onInsert(c) },
                label = { Text(c, style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.heightIn(min = MinTouchTarget).semantics { contentDescription = "Insert $c" },
            )
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading") {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun ErrorMessage(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}
