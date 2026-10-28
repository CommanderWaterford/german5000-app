package com.vocab5000.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.AudioButton
import com.vocab5000.core.designsystem.EmptyState
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LockBadge
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ScreenTitle("Library", Modifier.padding(top = 16.dp))
        OutlinedTextField(
            value = filters.query,
            onValueChange = { viewModel.setFilters(filters.copy(query = it)) },
            label = { Text("Search German or English") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(StudyStatusFilter.entries) { status ->
                FilterChip(selected = filters.status == status, onClick = { viewModel.setFilters(filters.copy(status = status)) }, label = { Text(status.label) }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = filters.band == null, onClick = { viewModel.setFilters(filters.copy(band = null)) }, label = { Text("all ranks") }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
            items((1..bands.count).toList()) { band ->
                FilterChip(selected = filters.band == band, onClick = { viewModel.setFilters(filters.copy(band = band)) }, label = { Text(bands.label(band)) }, modifier = Modifier.heightIn(min = MinTouchTarget))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.entry.entryId }) { row ->
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .clickable(role = Role.Button) { onOpenEntry(row.entry.entryId) }
                        .padding(vertical = 8.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${row.entry.curriculumRank}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        // Locked rows show lemma, rank and part of speech only (spec §12.1).
                        Text(if (row.locked) row.entry.lemma else renderer.headline(row.entry), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (row.locked) renderer.partOfSpeechLabel(row.entry.pos) else "${row.entry.primarySense.glossEn} · ${row.status}" + if (row.favorite) " · ★ favourite" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (row.locked) LockBadge()
                }
                HorizontalDivider()
            }
        }
    }
}

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
    val d = detail ?: return
    val entry = d.entry
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (d.locked) entry.lemma else renderer.headline(entry), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Rank ${entry.curriculumRank} · ${renderer.partOfSpeechLabel(entry.pos)}", style = MaterialTheme.typography.bodyMedium)
        if (d.locked) {
            InfoCard {
                LockBadge()
                Text("Meanings, grammar, examples and audio for words ranked above the free range are part of Premium — a one-time purchase. Your free words and all your progress stay available.")
                PrimaryAction("About Premium", onClick = onOpenPremium)
            }
            SecondaryAction("Back", onClick = onBack)
            return@Column
        }
        Text("Status: ${d.status}", style = MaterialTheme.typography.bodyMedium)
        AudioButton("Play word", onClick = { onPlay(entry.wordAudioId, renderer.citationForm(entry)) })
        SectionTitle("Meaning")
        entry.senses.forEachIndexed { i, sense ->
            val label = buildList {
                sense.register?.let(::add)
                sense.region?.let { add("regional: $it") }
            }.joinToString(", ")
            Text("${i + 1}. ${sense.glossEn}" + if (label.isNotEmpty()) " ($label)" else "", style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        }
        entry.register?.let { Text("Register: $it", style = MaterialTheme.typography.bodyMedium) }
        entry.region?.let { Text("Region: $it", style = MaterialTheme.typography.bodyMedium) }
        val lines = renderer.grammarLines(entry)
        if (lines.isNotEmpty()) {
            SectionTitle("Grammar")
            lines.forEach { Text("${it.label}: ${it.value}") }
        }
        if (entry.examples.isNotEmpty()) {
            SectionTitle("Example")
            entry.primaryExample?.let { ex ->
                InfoCard {
                    Text(ex.textDe, style = MaterialTheme.typography.bodyLarge)
                    Text(ex.textEn, style = MaterialTheme.typography.bodyMedium)
                    AudioButton("Play example", onClick = { onPlay(ex.audioId, ex.textDe) })
                }
            }
        }
        entry.notes?.let {
            Text("Note: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SecondaryAction(if (d.favorite) "★ Remove from favourites" else "☆ Add to favourites", onClick = { viewModel.setFavorite(entry.entryId, !d.favorite) })
        d.productive?.let { card ->
            if (d.difficult) {
                InfoCard {
                    Text("This word has been difficult (${card.lapses} lapses). Try the example sentence and grammar practice, or pause it for now.")
                }
            }
            if (card.state == CardStateType.SUSPENDED) {
                SecondaryAction("Resume reviewing this word", onClick = { viewModel.unsuspend(card.cardId) })
            } else if (card.state != CardStateType.NEW) {
                SecondaryAction("Pause (suspend) this word", onClick = { viewModel.suspend(card.cardId) })
            }
        }
        Text("Browsing never changes your review schedule.", style = MaterialTheme.typography.bodySmall)
        SecondaryAction("Back", onClick = onBack)
    }
}
