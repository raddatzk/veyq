package me.raddatz.veyq.mobile.android.core

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.session.WhosWho

/** What Settings' Who's who shows: still checking, or what core's `PersonTrust.whosWho` read. */
internal data class WhosWhoPage(val loading: Boolean, val read: WhosWho? = null)

/**
 * Settings' *Who's who* (core's `SettingsSection.WHOS_WHO` over `PersonTrust.whosWho`, the web's `PeopleOverviewPanel`;
 * inbox #967): every other member of the open space with the one state their key has here — the badge, *New* beside it,
 * who verified them ("you" first), a new identity, a replaced key —, in core's order, in a full-screen window over the
 * page as Terms is. A row, and its *Compare safety numbers* where this account has not verified them, opens their profile,
 * where the compare lives; core decides every word and whether the compare is offered.
 */
@Composable
internal fun WhosWhoDialog(page: WhosWhoPage, onRetry: () -> Unit, onOpen: (String) -> Unit, onClose: () -> Unit) {
    FlowDialog(title = stringResource(R.string.whos_who_title), secure = false, tag = "whos-who", onClose = onClose) {
        Text(stringResource(R.string.whos_who_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val read = page.read
        when {
            page.loading || read == null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.whos_who_loading))
            }
            read.said != null -> {
                Text(say(read.said!!), modifier = Modifier.testTag("whos-who-said"))
                if (read.retry) TextButton(onClick = onRetry, modifier = Modifier.testTag("whos-who-retry")) { Text(stringResource(R.string.people_retry)) }
            }
            else -> Column(Modifier.testTag("whos-who-list")) {
                read.people.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider()
                    WhosWhoRow(row, onOpen)
                }
            }
        }
    }
}

@Composable
private fun WhosWhoRow(row: WhosWho.Row, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.whos_who_open_profile, row.name)
    ListItem(
        leadingContent = { PersonAvatar(row.name, row.userId, 40.dp) },
        headlineContent = { Text(row.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.subtitle)
                val word = row.stateWord
                if (word != null || row.isNew) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    word?.let { StateChip(say(it), row.state, "whos-who-state") }
                    if (row.isNew) StateChip(stringResource(R.string.safety_state_new), null, "whos-who-new")
                }
                row.note?.let { Text(say(it), style = MaterialTheme.typography.bodySmall) }
                if (row.state == WhosWho.State.VERIFIED) {
                    val names = (if (row.verifiedByMe) listOf(context.getString(R.string.safety_verified_by_you)) else emptyList()) + row.verifiedBy
                    Text(stringResource(R.string.safety_verified_by, names.joinToString(", ")), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("whos-who-verified-by"))
                }
                row.rotatedAt?.let { at ->
                    Text(stringResource(R.string.trust_rotated, me.raddatz.veyq.mobile.words.Shown.dayOf(at, language())), style = MaterialTheme.typography.bodySmall)
                }
                if (row.replaced) Text(stringResource(R.string.safety_replaced), style = MaterialTheme.typography.bodySmall)
                if (row.compare) OutlinedButton(onClick = { onOpen(row.userId) }, modifier = Modifier.testTag("whos-who-compare")) {
                    Text(stringResource(R.string.whos_who_compare))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClickLabel = openLabel) { onOpen(row.userId) }
            .semantics { onClick(label = openLabel) { onOpen(row.userId); true } }
            .testTag("whos-who-${row.userId}"),
    )
}

/** A state's badge: an anomaly in the error colour with its warning, a verification with its seal, the rest plain. */
@Composable
private fun StateChip(label: String, state: WhosWho.State?, tag: String) {
    val (icon, color) = when (state) {
        WhosWho.State.CANT_CONFIRM -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        WhosWho.State.VERIFIED -> Icons.Outlined.VerifiedUser to MaterialTheme.colorScheme.primary
        else -> null to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        icon = icon?.let { { Icon(it, null, Modifier.size(AssistChipDefaults.IconSize)) } },
        colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(disabledLabelColor = color, disabledIconContentColor = color),
        modifier = Modifier.testTag(tag),
    )
}
