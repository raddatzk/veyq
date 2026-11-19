package me.raddatz.veyq.mobile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.memories.GalleryFilter
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.search.SearchChip
import me.raddatz.veyq.mobile.words.Msg

/**
 * A medium's marks on its tile (ADR 0149 decision 4): a badge, not a button — a phone's tile is one target, the mark is
 * toggled in the lightbox. The heart filled where the caller marked it, the count where one is kept (none in a personal
 * space) and above zero; a screen reader hears who marked it.
 */
@Composable
internal fun MarkBadge(mark: GalleryModel.Mark, modifier: Modifier = Modifier) {
    val said = markWords(mark)
    Row(
        modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp).semantics { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (mark.mine) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, Modifier.size(14.dp),
            tint = if (mark.mine) role(LocalPalette.current.favorite) else Color.White,
        )
        mark.count?.takeIf { it > 0 }?.let { Text("$it", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp)) }
    }
}

/** What a mark's badge says: that the caller marked it, and how often it was marked where a count is kept. */
@Composable
private fun markWords(mark: GalleryModel.Mark): String {
    val count = mark.count ?: 0
    return listOfNotNull(
        if (mark.mine) stringResource(R.string.media_marks_yours) else null,
        if (count > 0) pluralStringResource(R.plurals.media_marks_count, count, count) else null,
    ).joinToString(", ")
}

/** The lightbox's heart (ADR 0149 decision 4): the caller's mark turned, the count beside it where one is kept. */
@Composable
internal fun MarkButton(mark: GalleryModel.Mark?, enabled: Boolean, onToggle: () -> Unit) {
    val mine = mark?.mine == true
    val count = mark?.count ?: 0
    // The count is said in words inside the button's one reading; the badge's bare digit read as "1" after it (inbox #993's
    // pass, heard with TalkBack 2026-11-19)
    val said = listOfNotNull(
        stringResource(if (mine) R.string.media_marks_unmark else R.string.media_marks_mark),
        if (count > 0) pluralStringResource(R.plurals.media_marks_count, count, count) else null,
    ).joinToString(", ")
    IconButton(onClick = onToggle, enabled = enabled) {
        BadgedBox(badge = { if (count > 0) Badge { Text("$count", Modifier.clearAndSetSemantics {}) } }) {
            Icon(
                if (mine) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                said,
                tint = if (mine) role(LocalPalette.current.favorite) else Color.White,
            )
        }
    }
}

/** A filter chip's words: a day's label, a year, a place's or a kind's name. */
internal fun chipLabel(chip: GalleryFilter.Chip): String = when (chip) {
    is GalleryFilter.Chip.Day -> chip.label
    is GalleryFilter.Chip.Search -> when (val k = chip.chip) {
        is SearchChip.Year -> k.year.toString()
        is SearchChip.Place -> k.label
        is SearchChip.Kind -> k.label
        else -> me.raddatz.veyq.mobile.search.Query.chipKey(k)
    }
}

/** What a gallery says when its filter keeps nothing: every filter in force named (E5), or the marks' own empty line. */
@Composable
internal fun filterEmptyText(filter: GalleryFilter.State): String {
    if (filter.chips.isEmpty() && filter.text.isBlank()) when (filter.mode) {
        GalleryFilter.Mode.MINE -> return stringResource(R.string.media_marks_empty_mine)
        GalleryFilter.Mode.EVERYONE -> return stringResource(R.string.media_marks_empty_everyone)
        GalleryFilter.Mode.ALL -> {}
    }
    val named = buildList {
        when (filter.mode) {
            GalleryFilter.Mode.MINE -> add(stringResource(R.string.media_filter_marks_mine))
            GalleryFilter.Mode.EVERYONE -> add(stringResource(R.string.media_filter_marks_everyone))
            GalleryFilter.Mode.ALL -> {}
        }
        filter.chips.forEach { add(chipLabel(it)) }
        if (filter.text.isNotBlank()) add(stringResource(R.string.media_filter_text, filter.text.trim()))
    }
    return stringResource(R.string.media_filter_empty, named.joinToString(", "))
}

/**
 * A gallery's filter (`uc.memory.gallery` step 8, ADR 0149 decision 5), the Photos view's own toolbar under the switch:
 * the search field over the day, place, kind and name — the core's chips suggested under it as the text names one, the
 * chosen ones as input chips — and the marks' mode beside it as a filter chip's menu (all, everyone's, only mine; a
 * member's, never in a personal space). Stored nowhere: the page's, gone with it.
 */
@Composable
internal fun GalleryFilterBar(gallery: GalleryModel, state: GalleryModel.State) {
    val filter = state.filter
    // the field is drawn from its own state, set at once on a key (src/mobile/CLAUDE.md § Traps: a field reads synchronously)
    var text by remember(gallery) { mutableStateOf(filter.text) }
    LaunchedEffect(filter.text) { if (filter.text != text) text = filter.text }
    val photo = say(Msg.SEARCH_KIND_PHOTO)
    val video = say(Msg.SEARCH_KIND_VIDEO)
    val suggestions = if (text.isBlank()) emptyList() else remember(filter, state.rows) { gallery.suggestions(photo, video) }
    val label = stringResource(R.string.media_filter_label)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text,
                {
                    text = it
                    gallery.filter(gallery.current.filter.copy(text = it))
                },
                Modifier.weight(1f).semantics { contentDescription = label },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.media_filter_placeholder)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                // the whole filter cleared at once (its text, its chips, the marks' mode)
                trailingIcon = if (!filter.narrows) null else { {
                    IconButton(onClick = { text = ""; gallery.filter(GalleryFilter.State()) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.media_filter_clear))
                    }
                } },
                shape = RoundedCornerShape(28.dp),
            )
            if (gallery.marksModeOffered) MarksModeMenu(filter.mode) { mode -> gallery.filter(gallery.current.filter.copy(mode = mode)) }
        }
        if (filter.chips.isNotEmpty() || suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (chip in filter.chips) {
                    val words = chipLabel(chip)
                    InputChip(
                        selected = true,
                        onClick = { gallery.filter(gallery.current.filter.let { f -> f.copy(chips = f.chips.filter { GalleryFilter.key(it) != GalleryFilter.key(chip) }) }) },
                        label = { Text(words) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.search_remove_chip, words), Modifier.size(InputChipDefaults.IconSize)) },
                    )
                }
                for ((chip, rest) in suggestions) {
                    SuggestionChip(
                        onClick = {
                            text = rest
                            gallery.filter(gallery.current.filter.let { it.copy(chips = it.chips + chip, text = rest) })
                        },
                        label = { Text(chipLabel(chip)) },
                    )
                }
            }
        }
    }
}

/** The marks' mode as the platform's filter menu: a filter chip naming the mode, its menu the three. */
@Composable
private fun MarksModeMenu(mode: GalleryFilter.Mode, onMode: (GalleryFilter.Mode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.media_marks_filter_label)
    val modeWords: @Composable (GalleryFilter.Mode) -> String = { m ->
        stringResource(
            when (m) {
                GalleryFilter.Mode.ALL -> R.string.media_marks_filter_all
                GalleryFilter.Mode.EVERYONE -> R.string.media_marks_filter_everyone
                GalleryFilter.Mode.MINE -> R.string.media_marks_filter_mine
            },
        )
    }
    val shown = modeWords(mode)
    Box(Modifier.padding(start = 8.dp)) {
        FilterChip(
            selected = mode != GalleryFilter.Mode.ALL,
            onClick = { open = true },
            label = { Text(shown, Modifier.clearAndSetSemantics {}) },
            leadingIcon = { Icon(if (mode == GalleryFilter.Mode.ALL) Icons.Outlined.FavoriteBorder else Icons.Filled.Favorite, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
            // The label and the mode as one reading: the chip's own text, merged in, read the mode a second time to
            // TalkBack (inbox #993, heard 2026-11-19). It opens a menu, so it is no checkbox. Not `clearAndSetSemantics`
            // on the chip: that drops its focused state too, and TalkBack no longer follows a keyboard's Tab onto it.
            modifier = Modifier.semantics {
                contentDescription = "$label: $shown"
                role = Role.DropdownList
            },
        )
        DropdownMenu(open, { open = false }) {
            for (m in GalleryFilter.Mode.entries) {
                DropdownMenuItem(
                    text = { Text(modeWords(m)) },
                    leadingIcon = { if (m == mode) Icon(Icons.Filled.Check, null) else Box(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onMode(m)
                    },
                )
            }
        }
    }
}
