package me.raddatz.veyq.mobile.android.apps.memories

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.LevelTile
import me.raddatz.veyq.mobile.android.core.LocalCovers
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.clickableRow
import me.raddatz.veyq.mobile.android.core.kindIcon
import me.raddatz.veyq.mobile.android.core.memoryDateText
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.spaceName
import me.raddatz.veyq.mobile.android.core.tileColumns
import me.raddatz.veyq.mobile.android.core.tileRows
import me.raddatz.veyq.mobile.memories.people.PersonMemories
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.PersonProfile

/**
 * "Memories with {name}", the List | Tiles | Map switch once something is listed — the account's one view choice on this
 * device, every level's too (ADR 0149 decision 8).
 */
@Composable
private fun SectionHead(profile: PersonProfile, memories: PersonMemories, state: PersonMemories.State, model: AppModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            profile.name?.let { stringResource(R.string.memory_people_with_name, it) } ?: stringResource(R.string.memory_people_with),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.offersViews) {
            val views = listOf(
                PersonMemories.View.LIST to R.string.node_view_list, PersonMemories.View.TILES to R.string.node_view_tiles,
                PersonMemories.View.MAP to R.string.memory_view_map,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                views.forEachIndexed { i, (view, label) ->
                    SegmentedButton(
                        selected = state.view == view, onClick = { model.choosePersonView(memories, view) },
                        shape = SegmentedButtonDefaults.itemShape(i, views.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        if (state.partial) Text(stringResource(R.string.memory_people_partial), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted), modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** The section's states and rows: loading, failed with *Try again*, empty, the person's Memories newest first (core's order). */
private fun androidx.compose.foundation.lazy.LazyListScope.personRows(memories: PersonMemories, state: PersonMemories.State, model: AppModel, columns: Int) {
    when {
        state.loading && state.rows.isEmpty() -> item(key = "loading") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.memory_people_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        state.failed -> item(key = "failed") {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.memory_people_error), color = role(LocalPalette.current.danger))
                TextButton(onClick = { model.retryPerson(memories) }) { Text(stringResource(R.string.memory_people_retry)) }
            }
        }
        state.empty -> item(key = "empty") {
            Text(stringResource(R.string.memory_people_empty), color = role(LocalPalette.current.muted), modifier = Modifier.padding(16.dp))
        }
        // as tiles (ADR 0149 decision 8): each Memory's cover square, its name, its date, place and group below
        state.view == PersonMemories.View.TILES -> tileRows(state.rows, columns, key = { it.ref.scopeId + "/" + it.ref.nodeId }) { row, modifier ->
            LevelTile(row.ref.name, personDetails(row), LocalCovers.current[row.ref.nodeId]?.second, kindIcon(row.ref.kind), folder = false, modifier = modifier) { memories.open(row) }
        }
        else -> items(state.rows, key = { it.ref.scopeId + "/" + it.ref.nodeId }) { row -> PersonMemoryRow(row) { memories.open(row) } }
    }
}

/** A person's Memory's second line: its date, place and group. */
@Composable
private fun personDetails(row: PersonMemories.Row): String? {
    val context = LocalContext.current
    return listOfNotNull(row.date?.let { context.memoryDateText(it) }, row.placeName?.takeIf { it.isNotEmpty() }, row.group?.let { spaceName(it) })
        .takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** One of the person's Memories as the level draws a row: its cover, its name, its date, place and group. */
@Composable
private fun PersonMemoryRow(row: PersonMemories.Row, onOpen: () -> Unit) {
    val details = personDetails(row)?.let { listOf(it) }.orEmpty()
    ListItem(
        headlineContent = { Text(row.ref.name) },
        supportingContent = if (details.isEmpty()) null else { { Text(details.joinToString(" · ")) } },
        leadingContent = {
            val cover = LocalCovers.current[row.ref.nodeId]?.second
            if (cover != null) Image(cover, null, Modifier.size(40.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Crop)
            else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(kindIcon(row.ref.kind), null, tint = role(LocalPalette.current.accent)) }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.clickableRow(onOpen),
    )
}

/**
 * Memories' section of a person's profile (ADR 0144 decision 11): under core's [frame], "Memories with {name}" and the
 * person's Memories newest first as rows, tiles or — taking the screen below the frame — a map.
 */
@Composable
internal fun PersonMemoriesSection(profile: PersonProfile, model: AppModel, frame: @Composable ColumnScope.() -> Unit) {
    val userId = profile.userId
    val memories = if (ApplicationId.MEMORIES in profile.sections) remember(userId) { model.personMemories(userId) } else null
    val state = memories?.state?.collectAsStateWithLifecycle()?.value
    val head: @Composable ColumnScope.() -> Unit = {
        frame()
        if (memories != null && state != null) SectionHead(profile, memories, state, model)
    }
    // the map takes the screen below the frame; the list scrolls with it
    if (memories != null && state != null && state.view == PersonMemories.View.MAP) {
        Column(Modifier.fillMaxSize()) {
            head()
            MemoriesPinMap(state.map, model, Modifier.fillMaxWidth().weight(1f), memories::open)
        }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = tileColumns(maxWidth)
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "head") { Column { head() } }
            if (memories != null && state != null) personRows(memories, state, model, columns)
        }
    }
}
