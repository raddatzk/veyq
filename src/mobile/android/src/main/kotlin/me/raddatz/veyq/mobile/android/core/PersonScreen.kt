package me.raddatz.veyq.mobile.android.core

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
import me.raddatz.veyq.mobile.android.R
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
 * "Memories with {name}" (each registered `personSection`, Memories' `PersonMemoriesSection`). A screen over what was on screen, back returning there; no bar of its own.
 */
@Composable
internal fun PersonScreen(userId: String, model: AppModel) {
    val profile = remember(userId) { model.profile(userId) } ?: return
    val name = profileName(profile)
    val frame: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PersonAvatar(name, profile.userId.takeIf { !profile.former }, 112.dp)
            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            profile.handle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted)) }
            if (profile.former) Text(stringResource(R.string.people_former_member_hint), style = MaterialTheme.typography.bodyMedium, color = role(LocalPalette.current.muted))
        }
        // the safety number, a section below the name (ADR 0148 decision 10): one's own to show, another member's to compare
        if (!profile.former) SafetyNumberSection(profile.userId, name, model)
    }
    // an application's section draws the profile under core's frame (the registry's `personSection`)
    val section = profile.sections.firstNotNullOfOrNull { Registry.application(it)?.personSection }
    if (section != null) {
        section(profile, model, frame)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") { Column { frame() } }
    }
}
