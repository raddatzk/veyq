package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.search.PartShown
import me.raddatz.veyq.mobile.search.ResultShown
import me.raddatz.veyq.mobile.search.SearchDocument
import me.raddatz.veyq.mobile.search.SearchModel

/**
 * Search, the bar's last place (ADR 0146 decisions 7–9 and 12): core's [SearchModel] drawn in Material 3's full-screen
 * `SearchBar` — the chips set as `InputChip`s in a row under the field, *Filter by* and its suggestions, a divider,
 * *Results* with the bold part of each name and its second line, *Show more*, what the field says. The page decides
 * nothing: every row is core's `SearchShown`, every action a call of the model. A calm focus builds the index and shows no
 * list; the list opens with the first character or a chip. What a screen reader hears is a polite live region (TalkBack).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(model: AppModel, onClose: () -> Unit) {
    val search = model.searchModel.collectAsStateWithLifecycle().value ?: return
    val shown by search.state.collectAsStateWithLifecycle()
    // the field's own text, so typing never waits on the model; the model is told every change
    var query by remember(search) { mutableStateOf(search.current.text) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(expanded) { if (!expanded) onClose() }
    // a chip chosen keeps only the rest of the text (core's): the field follows the model there, never while typing
    LaunchedEffect(shown.chips) { if (query != shown.text) query = shown.text }
    // a settings result: the Settings page, at its section
    LaunchedEffect(shown.openSettingsCount) { model.openSettingsFromSearch(shown.openSettings, shown.openSettingsCount) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(search) { runCatching { focus.requestFocus() } }
    val keyboard = LocalSoftwareKeyboardController.current

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = {
                    query = it
                    search.text(it)
                },
                // the keyboard's search key only puts the keyboard away: a result is chosen by its row
                onSearch = { keyboard?.hide() },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_close)) } },
                modifier = Modifier
                    .focusRequester(focus)
                    // every focus is the model's: the first builds the index, every later one revalidates it
                    .onFocusChanged { if (it.isFocused) search.focus() }
                    // a hardware keyboard's Backspace in an empty field removes the last chip, as on the web
                    .onPreviewKeyEvent {
                        if (it.key == Key.Backspace && it.type == KeyEventType.KeyDown && query.isEmpty() && shown.chips.isNotEmpty()) {
                            search.removeLastChip()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("search-field"),
            )
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // what a screen reader hears (the web's role=status, aria-live=polite): one polite live region, never drawn —
            // first, so a long list never pushes it out of the window, where TalkBack would not read it
            val said = shown.announcement.map { say(it) }.joinToString(" ")
            Box(
                Modifier.size(1.dp).testTag("search-announcement").semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = said
                },
            )
            if (shown.chips.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp).testTag("search-chips"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.chips.forEachIndexed { i, chip ->
                        val label = piece(chip.label)
                        InputChip(
                            selected = true,
                            onClick = { search.removeChip(i) },
                            label = { Text(label) },
                            leadingIcon = { Icon(searchIcon(chip.icon), say(chip.word), Modifier.size(InputChipDefaults.IconSize)) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, stringResource(R.string.search_remove_chip, label), Modifier.size(InputChipDefaults.IconSize))
                            },
                        )
                    }
                }
            }
            if (shown.asked) {
                LazyColumn(Modifier.fillMaxWidth().testTag("search-list")) {
                    // what the field says (a space loading, not searched, offline's photos, the empty state): above the lists
                    if (shown.status.isNotEmpty()) {
                        item(key = "status") {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("search-status")) {
                                for (n in shown.status) Text(say(n), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (shown.suggestions.isNotEmpty()) {
                        item(key = "filter-by") { Head(stringResource(R.string.search_filter_by)) }
                        itemsIndexed(shown.suggestions, key = { _, s -> s.id }) { i, s ->
                            val where = s.where.map { piece(it) }
                            ListItem(
                                headlineContent = { Text(piece(s.label)) },
                                supportingContent = where.takeIf { it.isNotEmpty() }?.let { { Text(it.joined()) } },
                                leadingContent = { Icon(searchIcon(s.icon), null) },
                                trailingContent = { Text(say(s.word), style = MaterialTheme.typography.labelMedium) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.clickableRow { search.choose(i) },
                            )
                        }
                    }
                    if (shown.suggestions.isNotEmpty() && shown.results.isNotEmpty()) item(key = "divider") { HorizontalDivider() }
                    if (shown.results.isNotEmpty()) {
                        item(key = "results") { Head(stringResource(R.string.search_results)) }
                        itemsIndexed(shown.results, key = { _, r -> r.id }) { i, r -> ResultRow(r) { search.open(i) } }
                        if (shown.more > 0) {
                            item(key = "more") {
                                TextButton(onClick = search::more, modifier = Modifier.padding(horizontal = 8.dp).testTag("search-more")) {
                                    Text(stringResource(R.string.search_show_more, shown.more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A list's heading: *Filter by*, *Results*. */
@Composable
private fun Head(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One result: its icon (a member's avatar), its name with the matched part bold, its second line and why it is listed. */
@Composable
private fun ResultRow(r: ResultShown, onOpen: () -> Unit) {
    val line = r.line.map { piece(it) }.joined()
    val person = r.id.removePrefix(":${SearchDocument.PERSON}:").takeIf { r.id.startsWith(":${SearchDocument.PERSON}:") }
    val meta = buildAnnotatedString {
        append(line)
        r.reason?.let { reason ->
            if (line.isNotEmpty()) append(me.raddatz.veyq.mobile.words.Shown.SEPARATOR)
            append(bold(reason))
        }
    }
    ListItem(
        headlineContent = { Text(bold(r.name)) },
        supportingContent = meta.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        leadingContent = {
            if (person != null) PersonAvatar(r.name.joinToString("") { it.text }, person) else Icon(searchIcon(r.icon), null)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.clickableRow(onClick = onOpen),
    )
}

/** A name in parts as one string, the matched parts bold. */
private fun bold(parts: List<PartShown>): AnnotatedString = buildAnnotatedString {
    for (p in parts) if (p.strong) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.text) } else append(p.text)
}

/** The icon kinds core names (the web's icon names) in Material Symbols, as [kindIcon] draws the kinds. */
private fun searchIcon(kind: String): ImageVector = when (kind) {
    "folder" -> Icons.Outlined.Folder
    "folder-open" -> Icons.Outlined.FolderOpen
    "bookmark" -> Icons.Outlined.Link
    "file" -> Icons.AutoMirrored.Outlined.InsertDriveFile
    "inbox" -> Icons.Outlined.Inbox
    "wallet" -> Icons.Outlined.AccountBalanceWallet
    "images" -> Icons.Outlined.PhotoLibrary
    "image" -> Icons.Outlined.Image
    "play" -> Icons.Outlined.PlayCircle
    "calendar" -> Icons.Outlined.CalendarMonth
    "map-marker" -> Icons.Outlined.Place
    "user" -> Icons.Outlined.Person
    "users" -> Icons.Outlined.Groups
    "tag" -> Icons.Outlined.Sell
    "settings" -> Icons.Outlined.Settings
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}
