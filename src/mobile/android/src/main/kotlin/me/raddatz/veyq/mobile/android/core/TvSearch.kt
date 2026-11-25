package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.search.ResultShown

/**
 * Search on a TV (ADR 0159 decision 3, ADR 0146): core's [me.raddatz.veyq.mobile.search.SearchModel] — read-only, its
 * index in memory — under the platform's own input: the query is a field the system's input method fills (the TV's
 * keyboard and its voice; no keyboard of the app's own), the results Compose for TV's tiles. Every row is core's
 * `SearchShown` — the chips, *Filter by* and its suggestions, the results, *Show more*, what the field says —, every
 * action a call of the model; a result opens the object as the drawer would.
 */
@Composable
internal fun TvSearch(model: AppModel) {
    val search = model.searchModel.collectAsStateWithLifecycle().value ?: return
    val shown by search.state.collectAsStateWithLifecycle()
    var query by remember(search) { mutableStateOf(search.current.text) }
    LaunchedEffect(shown.chips) { if (query != shown.text) query = shown.text }
    LaunchedEffect(shown.openedCount) { query = shown.text }
    val focus = remember { FocusRequester() }
    LaunchedEffect(search) { runCatching { focus.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            query,
            {
                query = it
                search.text(it)
            },
            placeholder = { androidx.compose.material3.Text(stringResource(R.string.search_hint)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // the input method's search key only puts it away: a result is chosen by its tile
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(focus)
                // every focus is the model's: the first builds the index, every later one revalidates it
                .onFocusChanged { if (it.isFocused) search.focus() }
                .tvField()
                .testTag("tv-search-field"),
        )
        if (shown.chips.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("tv-search-chips")) {
                shown.chips.forEachIndexed { i, chip ->
                    val label = piece(chip.label)
                    // a chip set: pressed, it goes (core's `removeChip`), as the phone's input chip's close does
                    androidx.tv.material3.OutlinedButton(onClick = { search.removeChip(i) }, modifier = Modifier.testTag("tv-search-chip")) {
                        Icon(searchIcon(chip.icon), say(chip.word), Modifier.size(18.dp).padding(end = 4.dp))
                        Text(label)
                    }
                }
            }
        }
        if (!shown.asked) return@Column
        for (n in shown.status) Text(say(n), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("tv-search-status"))
        TvGrid {
            if (shown.suggestions.isNotEmpty()) {
                item(key = "filter-by", span = { GridItemSpan(maxLineSpan) }) { TvSection(stringResource(R.string.search_filter_by)) }
                itemsIndexed(shown.suggestions, key = { _, s -> s.id }) { i, s ->
                    val where = s.where.map { piece(it) }
                    Card(onClick = { search.choose(i) }, modifier = Modifier.testTag("tv-search-suggestion")) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(searchIcon(s.icon), null)
                            Column {
                                Text(piece(s.label), maxLines = 1)
                                Text(listOf(say(s.word), where.joined()).filter { it.isNotEmpty() }.joined(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (shown.results.isNotEmpty()) {
                item(key = "results", span = { GridItemSpan(maxLineSpan) }) { TvSection(stringResource(R.string.search_results)) }
                itemsIndexed(shown.results, key = { _, r -> r.id }) { i, r -> ResultTile(r) { search.open(i) } }
                if (shown.more > 0) {
                    item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth()) {
                            Button(onClick = search::more, modifier = Modifier.testTag("tv-search-more")) {
                                Text(stringResource(R.string.search_show_more, shown.more))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One result as a tile: its icon, its name, its second line and why it is listed (a match's value, *You are here*). */
@Composable
private fun ResultTile(r: ResultShown, onOpen: () -> Unit) {
    val name = r.name.joinToString("") { it.text }
    val line = r.line.map { piece(it) }.joined()
    val matched = r.matched.map { piece(it) }
    val also = listOfNotNull(line.takeIf { it.isNotEmpty() }, r.reason?.joinToString("") { it.text }, stringResource(R.string.search_here).takeIf { r.here }) + matched
    Card(onClick = onOpen, modifier = Modifier.testTag("tv-search-result")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(searchIcon(r.icon), null, Modifier.size(32.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (also.isNotEmpty()) Text(also.joined(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}
