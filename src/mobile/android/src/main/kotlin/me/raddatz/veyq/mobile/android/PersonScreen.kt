package me.raddatz.veyq.mobile.android

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.memories.people.PersonMemories
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.PersonProfile

/** The title a person's profile carries: their display name, else the former or the unknown member's words. */
@Composable
internal fun personTitle(model: AppModel, userId: String): String {
    val profile = remember(userId) { model.profile(userId) }
    return profileName(profile)
}

@Composable
private fun profileName(profile: PersonProfile?): String = profile?.name
    ?: stringResource(if (profile?.former == true) R.string.memory_participants_former else R.string.people_unknown_member)

/**
 * A person's profile (ADR 0144 decision 11; the web's `PersonProfile.vue`), core's frame — their picture at its largest,
 * their name, `~username` under a real name, a former member said so — and then each application's section, Memories'
 * "Memories with {name}" ([PersonMemoriesSection]). A screen over what was on screen, back returning there; no bar of its own.
 */
@Composable
internal fun PersonScreen(userId: String, model: AppModel) {
    val profile = remember(userId) { model.profile(userId) } ?: return
    val name = profileName(profile)
    val memories = if (ApplicationId.MEMORIES in profile.sections) remember(userId) { model.personMemories(userId) } else null
    val state = memories?.state?.collectAsStateWithLifecycle()?.value
    val head: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PersonAvatar(name, profile.userId.takeIf { !profile.former }, 112.dp)
            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            profile.handle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted)) }
            if (profile.former) Text(stringResource(R.string.people_former_member_hint), style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted))
        }
        // the safety number, a section below the name (ADR 0148 decision 10): one's own to show, another member's to compare
        if (!profile.former) SafetyNumberSection(profile.userId, name, model)
        if (memories != null && state != null) SectionHead(profile, memories, state)
    }
    // the map takes the screen below the frame; the list scrolls with it
    if (memories != null && state != null && state.view == PersonMemories.View.MAP) {
        Column(Modifier.fillMaxSize()) {
            head()
            MemoriesPinMap(state.map, model, Modifier.fillMaxWidth().weight(1f), memories::open)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") { Column { head() } }
        if (memories != null && state != null) personRows(memories, state, model)
    }
}

/** "Memories with {name}", the List | Map switch once something is listed (not stored: the section opens on the list). */
@Composable
private fun SectionHead(profile: PersonProfile, memories: PersonMemories, state: PersonMemories.State) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            profile.name?.let { stringResource(R.string.memory_people_with_name, it) } ?: stringResource(R.string.memory_people_with),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.offersViews) {
            val views = listOf(PersonMemories.View.LIST to R.string.node_view_list, PersonMemories.View.MAP to R.string.memory_view_map)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                views.forEachIndexed { i, (view, label) ->
                    SegmentedButton(
                        selected = state.view == view, onClick = { memories.view(view) },
                        shape = SegmentedButtonDefaults.itemShape(i, views.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        if (state.partial) Text(stringResource(R.string.memory_people_partial), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted), modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** The section's states and rows: loading, failed with *Try again*, empty, the person's Memories newest first (core's order). */
private fun androidx.compose.foundation.lazy.LazyListScope.personRows(memories: PersonMemories, state: PersonMemories.State, model: AppModel) {
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
        else -> items(state.rows, key = { it.ref.scopeId + "/" + it.ref.nodeId }) { row -> PersonMemoryRow(row) { memories.open(row) } }
    }
}

/** One of the person's Memories as the level draws a row: its cover, its name, its date, place and group. */
@Composable
private fun PersonMemoryRow(row: PersonMemories.Row, onOpen: () -> Unit) {
    val context = LocalContext.current
    val details = listOfNotNull(row.date?.let { context.memoryDateText(it) }, row.placeName?.takeIf { it.isNotEmpty() }, row.group?.let { spaceName(it) })
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
