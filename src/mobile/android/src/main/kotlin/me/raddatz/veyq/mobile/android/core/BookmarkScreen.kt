package me.raddatz.veyq.mobile.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.sharing.BookmarkPage

/** The header's own action that opens a kept link on its own page (`AppKinds.headerOrder`'s `open-own-page`). */
internal const val OPEN_OWN_PAGE = "open-own-page"

/** The header's own action that reports a kept link's content to the share's own operator (ADR 0150 decision 6). */
internal const val REPORT_SHARED = "report-shared"

/**
 * A kept link's header contribution (ADR 0150 decision 6; the web's `BookmarkDetail`): *Open on its own page* first, and
 * *Report shared content* beside core's report — which, on a bookmark, reports its name only to this instance (core's
 * `ShellModel.reportSheet`). Its rename, move, favourite and delete are core's.
 */
@Composable
internal fun bookmarkHeader(node: NodeAt, model: AppModel): HeaderContribution {
    val page = remember(node.scopeId, node.nodeId) { model.bookmarkPage(node) } ?: return HeaderContribution()
    val state by page.state.collectAsStateWithLifecycle()
    if (state is BookmarkPage.State.Loading || state is BookmarkPage.State.Unreadable) return HeaderContribution()
    return HeaderContribution(
        actions = listOf(
            OwnAction(OPEN_OWN_PAGE, stringResource(R.string.bookmark_open_own_page), Icons.AutoMirrored.Outlined.OpenInNew) { model.bookmarkOwnPage(page) },
            OwnAction(REPORT_SHARED, stringResource(R.string.bookmark_report_share), Icons.Outlined.Flag, me.raddatz.veyq.mobile.navigation.NodeHeader.Slot.LAST) {
                model.bookmarkReportShared(page)
            },
        ),
    )
}

/**
 * A kept link's page (ADR 0150 decision 6, ADR 0121 decision 1): while the tenant embeds, the share in place under the
 * header — the link's guest view, its region named by the link's host —; otherwise the row opens the link on its own page
 * at once, the person back at the list once it is left, and the page says where the link points. A meta that does not
 * read says so; its delete is the header's.
 */
@Composable
internal fun BookmarkScreen(node: NodeAt, model: AppModel) {
    val page = remember(node.scopeId, node.nodeId) { model.bookmarkPage(node) }
    if (page == null) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    DisposableEffect(page) { onDispose { model.closeBookmark(page) } }
    val state by page.state.collectAsStateWithLifecycle()
    val muted = role(LocalPalette.current.muted)
    when (val s = state) {
        BookmarkPage.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        BookmarkPage.State.Unreadable -> Text(stringResource(R.string.bookmark_unreadable), color = muted, modifier = Modifier.padding(16.dp).testTag("bookmark-unreadable"))
        is BookmarkPage.State.InPlace -> {
            val view = s.screen.view
            val stack by view.stack.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            // *Back* inside the link steps back in it; at its root the shell's own back leaves the page
            BackHandler(enabled = stack.size > 1) { scope.launch(Dispatchers.IO) { view.back() } }
            val snackbar = LocalShellNotices.current?.snackbar ?: remember { SnackbarHostState() }
            Column(Modifier.fillMaxSize()) {
                Text(
                    stringResource(R.string.bookmark_embed_region, s.host), style = MaterialTheme.typography.labelMedium, color = muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("bookmark-in-place"),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { GuestContent(s.screen, model, snackbar) }
            }
        }
        is BookmarkPage.State.OnItsOwn -> {
            // embedding off: the row opens the link directly (the web's), the shell back at the list behind it
            LaunchedEffect(page) {
                model.bookmarkOwnPage(page)
                model.goBack()
            }
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.host, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { model.bookmarkOwnPage(page) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.bookmark_open_own_page))
                }
            }
        }
    }
}
