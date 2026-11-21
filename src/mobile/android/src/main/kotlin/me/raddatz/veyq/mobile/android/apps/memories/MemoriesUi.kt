package me.raddatz.veyq.mobile.android.apps.memories

import me.raddatz.veyq.mobile.memories.MemoriesGuest

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.Panorama
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.android.core.AppModel
import me.raddatz.veyq.mobile.android.core.ApplicationUi
import me.raddatz.veyq.mobile.android.core.ArrangedListScreen
import me.raddatz.veyq.mobile.android.core.CreateAction
import me.raddatz.veyq.mobile.android.core.CreateDialog
import me.raddatz.veyq.mobile.android.core.GuestWriteColumn
import me.raddatz.veyq.mobile.android.core.HeaderContribution
import me.raddatz.veyq.mobile.android.core.InlineNaming
import me.raddatz.veyq.mobile.android.core.KindPage
import me.raddatz.veyq.mobile.android.core.LocalListStates
import me.raddatz.veyq.mobile.android.core.LocalPalette
import me.raddatz.veyq.mobile.android.core.NameDialog
import me.raddatz.veyq.mobile.android.core.NotYou
import me.raddatz.veyq.mobile.android.core.OwnAction
import me.raddatz.veyq.mobile.android.core.PrimaryButton
import me.raddatz.veyq.mobile.android.core.RecordNotice
import me.raddatz.veyq.mobile.android.core.Registry
import me.raddatz.veyq.mobile.android.core.Screen
import me.raddatz.veyq.mobile.android.core.role
import me.raddatz.veyq.mobile.android.core.say
import me.raddatz.veyq.mobile.android.core.show
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestScreen

/**
 * A Memory's or a chapter's own header actions (core's `GalleryModel.headerActions`, drawn whole and in its order): adding
 * photos and videos where whoever looks uploads, its music played or stopped (ADR 0142 decision 6), the banner added or
 * changed (ADR 0144 decision 5). Core's header places each.
 */
@Composable
private fun memoryHeader(node: NodeAt, model: AppModel): HeaderContribution {
    val gallery = androidx.compose.runtime.remember(node) { model.galleryModel(node) } ?: return HeaderContribution()
    // read here, whatever is offered: an action appears once the gallery's first read finds it
    val state = gallery.state.collectAsStateWithLifecycle().value
    // the system's photo picker: photos and videos, several at once; full media access asked once before the first pick
    // (the picker hands a photo over with its location zeroed otherwise), whatever the answer the picker opens
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) model.uploadMedia(gallery, uris)
    }
    val pickMedia = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pickMedia() }
    val actions = gallery.headerActions.map { a ->
        val label = say(gallery.label(a)!!)
        when (a) {
            me.raddatz.veyq.mobile.navigation.HeaderAction.UPLOAD -> OwnAction(a, label, androidx.compose.material.icons.Icons.Outlined.AddPhotoAlternate) {
                if (model.mediaAccessAsked()) pickMedia() else askAccess.launch(mediaAccessPermissions())
            }
            me.raddatz.veyq.mobile.navigation.HeaderAction.MUSIC -> OwnAction(
                a, label, if (state.musicHere) androidx.compose.material.icons.Icons.Outlined.MusicOff else androidx.compose.material.icons.Icons.Outlined.MusicNote,
            ) { gallery.toggleMusic() }
            // the banner's dialog where whoever looks may set it, online (the web's header `banner`)
            me.raddatz.veyq.mobile.navigation.HeaderAction.BANNER -> OwnAction(a, label, androidx.compose.material.icons.Icons.Outlined.Panorama) { model.openBanner(gallery) }
            // core's own, or another page's: never a gallery's (`GalleryModel.headerActions` holds none of these)
            me.raddatz.veyq.mobile.navigation.HeaderAction.FAVORITE, me.raddatz.veyq.mobile.navigation.HeaderAction.SHARE,
            me.raddatz.veyq.mobile.navigation.HeaderAction.RENAME, me.raddatz.veyq.mobile.navigation.HeaderAction.MOVE,
            me.raddatz.veyq.mobile.navigation.HeaderAction.COPY, me.raddatz.veyq.mobile.navigation.HeaderAction.COVER,
            me.raddatz.veyq.mobile.navigation.HeaderAction.DOWNLOAD, me.raddatz.veyq.mobile.navigation.HeaderAction.OFFLINE,
            me.raddatz.veyq.mobile.navigation.HeaderAction.REPORT, me.raddatz.veyq.mobile.navigation.HeaderAction.DELETE,
            me.raddatz.veyq.mobile.navigation.HeaderAction.REPLACE, me.raddatz.veyq.mobile.navigation.HeaderAction.SEND,
            me.raddatz.veyq.mobile.navigation.HeaderAction.OPEN_OWN_PAGE, me.raddatz.veyq.mobile.navigation.HeaderAction.REPORT_SHARED,
            -> error("a gallery offers no own ${a.name}")
        }
    }
    return HeaderContribution(
        // a Memory's and a chapter's ZIP, the web's archive (ADR 0149 decision 3: core's `MemoryExport` behind `NodeExport`)
        download = { out -> model.downloadNode(node, out) },
        actions = actions,
    )
}

/**
 * New Memory (the web's `MemoryNodeDialog`): its name and, in a tenant or group space, **I was there** — ticked each time it
 * opens, never remembered (ADR 0144 decision 2); ticked, the Memory is born listing its maker among its participants.
 */
@Composable
private fun MemoryNameDialog(space: String, model: AppModel, onClose: () -> Unit, onCreate: suspend (String, Boolean, Boolean) -> String?) {
    val offers = remember(space) { model.offersWasThere(space) }
    // the place armed to come from the photos (uc.memory.place A1, the web's opt-in): offered where the map services are shown, unticked
    val offersPlace = remember(space) { model.offersAutoPlace() }
    var wasThere by remember { mutableStateOf(true) }
    var autoPlace by remember { mutableStateOf(false) }
    NameDialog(
        stringResource(R.string.memory_create_memory_title), onClose,
        extra = if (!offers && !offersPlace) null else { {
            Column {
                if (offersPlace) CheckRow(autoPlace, { autoPlace = it }, stringResource(R.string.memory_place_auto), stringResource(R.string.memory_place_auto_hint))
                if (offers) CheckRow(wasThere, { wasThere = it }, stringResource(R.string.memory_participants_was_there), stringResource(R.string.memory_participants_was_there_hint))
            }
        } },
    ) { name -> onCreate(name, offers && wasThere, offersPlace && autoPlace) }
}

/** One of New Memory's checkboxes: the whole row toggles it, its hint under its label. */
@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, hint: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
        }
    }
}

/**
 * Memories' own list (its Memories). An application's list offers no New of its own (core's `ShellModel.levelPlan`, ADR
 * 0154 decision 2): a Memory is made from the drawer's New or a level's.
 */
@Composable
private fun MemoriesList(s: Screen.Signed, model: AppModel, onOpen: (ObjectRef) -> Unit = model::open) {
    // the bar's Map tab: every located Memory of the same list on a map (ADR 0143 D6)
    if (s.shell.selectedTab?.key == "memories.map") {
        MemoriesMapTab(s, model)
        return
    }
    ArrangedListScreen(s.applicationList(ApplicationId.MEMORIES), onOpen = onOpen, state = LocalListStates.current.of("app:MEMORIES"))
}

/**
 * A Memory's or a chapter's WRITE_ONLY link (the web's `MemoryShareBody`): the guest names itself — the record's notice
 * before —, then adds photos and videos; it sees only what it sent while the link is open here, and removes its own.
 */
@Composable
private fun GuestUploaderPage(up: me.raddatz.veyq.mobile.memories.GuestUploader, access: GuestAccess?, model: AppModel, snackbar: SnackbarHostState) {
    val session by up.session.collectAsStateWithLifecycle()
    val sent by up.sent.collectAsStateWithLifecycle()
    val days = access?.guestWriteRecordDays
    val origin = access?.origin.orEmpty()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val sources = withContext(Dispatchers.IO) { uris.mapNotNull { model.picked(it) }.map { model.source(it) } }
            up.upload(sources)
        }
    }
    GuestWriteColumn {
        Text(stringResource(R.string.share_uploader_intro), color = role(LocalPalette.current.muted))
        val named = session
        if (named == null) {
            InlineNaming(stringResource(R.string.share_name_visibility), days, origin) { up.name(it) }
        } else if (up.offered) {
            NotYou(named, up::forget)
            RecordNotice(days, origin, named.identityId)
            PrimaryButton(stringResource(R.string.share_add_photos), Modifier.testTag("guest-uploader-pick")) {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            Text(stringResource(R.string.share_uploader_session), style = MaterialTheme.typography.bodySmall, color = role(LocalPalette.current.muted))
            for (s in sent) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(s.name, modifier = Modifier.weight(1f).testTag("guest-uploader-sent"))
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { up.remove(s.mediaId) }
                        if (!ok) snackbar.show(context, me.raddatz.veyq.mobile.words.Notice(me.raddatz.veyq.mobile.words.Msg.SHARE_DELETE_FAILED))
                    }
                }) { Text(stringResource(R.string.entry_delete)) }
            }
        }
    }
}

/** A link's Memory or chapter: its gallery with a guest's access, or — on a WRITE_ONLY link — the uploader. */
@Composable
private fun MemoryGuestPage(page: GuestPage, screen: GuestScreen, model: AppModel, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val view = screen.view
    when (page) {
        is MemoriesGuest.Gallery -> {
            val session = view.access?.session?.collectAsStateWithLifecycle()?.value
            DisposableEffect(page.model) { onDispose { page.model.close() } }
            MemoryGallery(page.model, model, guest = true, canAdd = session != null, onChapter = { c -> scope.launch(Dispatchers.IO) { view.open(c.nodeId) } })
        }
        is MemoriesGuest.Uploader -> GuestUploaderPage(page.model, view.access, model, snackbar)
        else -> {}
    }
}

/** New Memory at the level shown (core's `MemoryCreate`), the web's `createDialog` of a Memory. */
private val newMemory: CreateDialog = { _, model, space, folder, onClose ->
    if (space != null) MemoryNameDialog(space, model, onClose = onClose) { name, wasThere, autoPlace ->
        model.createMemory(space, folder, name, wasThere, autoPlace)
    }
}

/**
 * Memories' UI as it registers into the core UI (ADR 0154 decision 3; the web's `registerMemories`): the Memory's and the
 * chapter's page — the music and the banner it contributes to core's header, its gallery (`MemoryScreen`), a link's
 * gallery or uploader —, its own list with the bar's Map tab, New Memory, and its section of a person's profile.
 */
internal object MemoriesUi {
    private val memory = KindPage(
        header = { node, model -> memoryHeader(node, model) },
        body = { ref, _, model -> MemoryScreen(ref, model) },
        guest = { page, screen, model, snackbar -> MemoryGuestPage(page, screen, model, snackbar) },
    )

    fun register(registry: Registry) {
        registry.kind(memory, "MEMORY", "CHAPTER")
        registry.application(
            ApplicationUi(
                ApplicationId.MEMORIES,
                list = { s, model, onOpen -> MemoriesList(s, model, onOpen) },
                creates = mapOf(me.raddatz.veyq.mobile.AppKinds.Create.MEMORY to newMemory),
                personSection = { profile, model, frame -> PersonMemoriesSection(profile, model, frame) },
            ),
        )
    }
}
