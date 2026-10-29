package com.vocab5000.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.ArticleText
import com.vocab5000.core.designsystem.AudioButton
import com.vocab5000.core.designsystem.BackTopBar
import com.vocab5000.core.designsystem.EmptyState
import com.vocab5000.core.designsystem.GrammarTable
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.LockBadge
import com.vocab5000.core.designsystem.MasteryDots
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.PromptNote
import com.vocab5000.core.designsystem.ScreenHeader
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.StatChip
import com.vocab5000.core.learning.RankBands
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.GrammarRenderer
import com.vocab5000.core.model.PartOfSpeech

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    renderer: GrammarRenderer,
    bands: RankBands,
    onOpenEntry: (String) -> Unit,
) {
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Library", subtitle = "Every word with its grammar, example and recording")
            OutlinedTextField(
                value = filters.query,
                onValueChange = { viewModel.setFilters(filters.copy(query = it)) },
                label = { Text("Search German or English") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilterRow {
            items(StudyStatusFilter.entries) { status ->
                FilterChip(selected = filters.status == status, onClick = { viewModel.setFilters(filters.copy(status = status)) }, label = { Text(status.label) }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
        }
        FilterRow {
            item {
                FilterChip(selected = filters.band == null, onClick = { viewModel.setFilters(filters.copy(band = null)) }, label = { Text("all ranks") }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
            items((1..bands.count).toList()) { band ->
                FilterChip(selected = filters.band == band, onClick = { viewModel.setFilters(filters.copy(band = band)) }, label = { Text(bands.label(band)) }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
        }
        FilterRow {
            item {
                FilterChip(selected = filters.pos == null, onClick = { viewModel.setFilters(filters.copy(pos = null)) }, label = { Text("all types") }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
            items(PartOfSpeech.entries) { pos ->
                FilterChip(selected = filters.pos == pos, onClick = { viewModel.setFilters(filters.copy(pos = pos)) }, label = { Text(renderer.partOfSpeechLabel(pos)) }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
        }
        if (rows.isEmpty()) {
            EmptyState("No matches", "Try another spelling, an English meaning, or clear the filters.")
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            items(rows, key = { it.entry.entryId }) { row ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onOpenEntry(row.entry.entryId) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Text(
                            "${row.entry.curriculumRank}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = {
                        // Locked rows show lemma, rank and part of speech only (spec §12.1).
                        ArticleText(if (row.locked) row.entry.lemma else renderer.headline(row.entry), style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(
                            if (row.locked) renderer.partOfSpeechLabel(row.entry.pos) else "${row.entry.primarySense.glossEn} · ${row.status}" + if (row.favorite) " · ★ favourite" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        if (row.locked) LockBadge() else MasteryDots(row.level)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
private fun FilterRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryDetailScreen(
    viewModel: LibraryViewModel,
    entryId: String,
    renderer: GrammarRenderer,
    onPlay: (String?, String?) -> Unit,
    onStopAudio: () -> Unit,
    onOpenPremium: () -> Unit,
    onBack: () -> Unit,
) {
    val flow = remember(entryId) { viewModel.detail(entryId) }
    val detail by flow.collectAsStateWithLifecycle(initialValue = null)
    DisposableEffect(Unit) { onDispose { onStopAudio() } }
    Scaffold(
        topBar = { BackTopBar("Word", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
    ) { padding ->
        val d = detail
        if (d == null) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val entry = d.entry
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (d.locked) {
                Text(entry.lemma, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Rank ${entry.curriculumRank} · ${renderer.partOfSpeechLabel(entry.pos)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                InfoCard {
                    LockBadge()
                    Text("Meanings, grammar, examples and audio for words ranked above the free range are part of Premium — a one-time purchase. Your free words and all your progress stay available.")
                    PrimaryAction("About Premium", onClick = onOpenPremium)
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArticleText(renderer.headline(entry), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                AudioButton("Play word", onClick = { onPlay(entry.wordAudioId, renderer.citationForm(entry)) })
            }
            Text(entry.primarySense.glossEn, style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip(renderer.partOfSpeechLabel(entry.pos))
                StatChip("Rank ${entry.curriculumRank}")
                entry.cefrTag?.let { StatChip(it) }
                StatChip(d.status, container = MaterialTheme.colorScheme.primaryContainer)
                if (d.favorite) StatChip("★ favourite", container = MaterialTheme.colorScheme.tertiaryContainer)
            }
            val others = entry.senses.filter { it.senseId != entry.primarySenseId }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Also", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                others.forEach { sense ->
                    val label = buildList {
                        sense.register?.let(::add)
                        sense.region?.let { add("regional: $it") }
                    }.joinToString(", ")
                    Text(sense.glossEn + if (label.isNotEmpty()) " ($label)" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
            entry.register?.let { Text("Register: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            entry.region?.let { Text("Region: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val lines = renderer.grammarLines(entry)
            if (lines.isNotEmpty()) GrammarTable(lines.map { it.label to it.value }, Modifier.padding(top = 8.dp))
            entry.primaryExample?.let { ex ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ex.textDe, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(ex.textEn, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AudioButton("Play example", onClick = { onPlay(ex.audioId, ex.textDe) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MasteryDots(d.level)
                Text(
                    "Mastery: ${d.level}/5 · ${d.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.notes?.let { PromptNote("Note: $it") }
            Spacer(Modifier.height(8.dp))
            SecondaryAction(if (d.favorite) "★ Remove from favourites" else "☆ Add to favourites", onClick = { viewModel.setFavorite(entry.entryId, !d.favorite) })
            d.productive?.let { card ->
                if (d.difficult) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "This word has been difficult (${card.lapses} lapses). Try the example sentence and grammar practice, or pause it for now.",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (card.state == CardStateType.SUSPENDED) {
                    SecondaryAction("Resume reviewing this word", onClick = { viewModel.unsuspend(card.cardId) })
                } else if (card.state != CardStateType.NEW) {
                    SecondaryAction("Pause (suspend) this word", onClick = { viewModel.suspend(card.cardId) })
                }
            }
            PromptNote("Browsing never changes your review schedule.")
        }
    }
}
