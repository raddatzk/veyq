package me.raddatz.veyq.mobile.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.raddatz.veyq.mobile.memories.CaptureTimeEditor
import me.raddatz.veyq.mobile.memories.GalleryModel
import me.raddatz.veyq.mobile.memories.Participants
import me.raddatz.veyq.mobile.memories.ParticipantsPicker
import me.raddatz.veyq.mobile.memories.banner.Banner
import me.raddatz.veyq.mobile.memories.banner.BannerEditor
import me.raddatz.veyq.mobile.memories.banner.BannerFrame
import me.raddatz.veyq.mobile.memories.banner.BannerWrite
import me.raddatz.veyq.mobile.words.Msg
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// A Memory's curation on Android (ADR 0144): its people line and their list, the participants picker, the banner above
// every view with its dialog and the question before its picture is trashed, a medium's capture-time editor. Every rule
// is the core's (`Participants`, `ParticipantsPicker`, `Banner`, `BannerFrame`, `BannerEditor`, `CaptureTimeEditor`);
// this file picks, decodes, plays and draws.

// ---- the people line (decision 1) ----

/** A participant's name as the line writes it: theirs, a former member's words, nothing while the members read. */
@Composable
private fun entryName(e: Participants.Entry): String = e.name ?: if (e.former) stringResource(R.string.memory_participants_former) else ""

/** One avatar in the line, ringed in the page's background so overlapping ones stay apart; a former member's greyed. */
@Composable
private fun RingedAvatar(e: Participants.Entry, size: Dp) {
    Box(
        Modifier.size(size + 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).alpha(if (e.former) 0.5f else 1f),
        contentAlignment = Alignment.Center,
    ) { PersonAvatar(entryName(e), e.userId, size) }
}

/**
 * The header's people line (ADR 0144 decision 1; the web's `MemoryParticipants.vue`), under the facts line: one person as
 * avatar and name, several as up to three overlapping avatars with "+N" in the third slot; a tap opens everyone's list
 * ([ParticipantsListSheet]). Nobody listed: the *Add participants* chip for who may edit, nothing for anyone else. Shown only
 * where the core's line is (a member's, on a Memory, not in a personal space, online).
 */
@Composable
internal fun MemoryPeopleLine(gallery: GalleryModel, model: AppModel) {
    val state by gallery.state.collectAsStateWithLifecycle()
    val line = state.people ?: return
    var listing by remember { mutableStateOf(false) }
    if (line.add) {
        AssistChip(
            onClick = { model.openParticipants(gallery) },
            label = { Text(stringResource(R.string.memory_participants_add)) },
            leadingIcon = { Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    if (line.people.isEmpty()) return
    val title = stringResource(R.string.memory_participants_title)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClickLabel = title) { listing = true }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatar = 28.dp
            val step = 20.dp
            val slots = line.avatars.size + if (line.more > 0) 1 else 0
            Box(Modifier.width(avatar + 4.dp + step * (slots - 1)).height(avatar + 4.dp)) {
                line.avatars.forEachIndexed { i, e -> Box(Modifier.offset(x = step * i)) { RingedAvatar(e, avatar) } }
                if (line.more > 0) {
                    Box(
                        Modifier.offset(x = step * line.avatars.size).size(avatar + 4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background).padding(2.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Text("+" + line.more, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
            }
            val text = if (line.people.size == 1) entryName(line.people.first()) else title
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        if (line.edits) {
            IconButton(onClick = { model.openParticipants(gallery) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.memory_participants_edit)) }
        }
    }
    if (listing) ParticipantsListSheet(line, onOpen = { id -> listing = false; model.openPerson(id) }, onEdit = { listing = false; model.openParticipants(gallery) }) { listing = false }
}

/** Everyone listed (avatar, name; a former member greyed), a current member's row opening their profile (decision 11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsListSheet(line: Participants.Line, onOpen: (String) -> Unit, onEdit: () -> Unit, onClose: () -> Unit) {
    val palette = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onClose) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.memory_participants_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (line.edits) TextButton(onClick = onEdit) { Text(stringResource(R.string.memory_participants_edit)) }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(line.people, key = { it.userId }) { e ->
                val name = entryName(e)
                ListItem(
                    headlineContent = { Text(name, color = if (e.former) role(palette.muted) else Color.Unspecified) },
                    leadingContent = { Box(Modifier.alpha(if (e.former) 0.5f else 1f)) { PersonAvatar(name, e.userId, 40.dp) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = if (e.opensProfile) Modifier.clickable(onClickLabel = stringResource(R.string.people_open, name)) { onOpen(e.userId) } else Modifier,
                )
            }
        }
    }
}

/**
 * The participants picker (the web's `MemoryParticipantsDialog.vue`, core's `ParticipantsPicker`): the space's current
 * members with a search field and ticks, a former member still listed above them until unticked; the cap said and held;
 * Save writes the pick through the core's merge. Held by [AppModel.participants], so a rotation keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParticipantsPickerSheet(picker: ParticipantsPicker, model: AppModel) {
    val s by picker.state.collectAsStateWithLifecycle()
    val palette = LocalPalette.current
    ModalBottomSheet(onDismissRequest = { if (!s.saving) model.closeParticipants(picker) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.memory_participants_dialog_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.memory_participants_dialog_hint), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (s.members != null && (s.members!!.isNotEmpty() || s.former.isNotEmpty())) {
                OutlinedTextField(
                    s.query, picker::search, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.memory_participants_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                s.failed -> item(key = "failed") {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.memory_participants_load_error), color = role(palette.danger))
                        TextButton(onClick = model::reloadParticipants) { Text(stringResource(R.string.memory_people_retry)) }
                    }
                }
                s.members == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                else -> {
                    items(s.former, key = { "former:$it" }) { id ->
                        val name = stringResource(R.string.memory_participants_former)
                        PickRow(name, id, ticked = s.ticked(id), enabled = s.togglable(id), former = true) { picker.toggle(id) }
                    }
                    items(s.visible, key = { "member:" + it.userId }) { p ->
                        PickRow(p.displayName, p.userId, ticked = s.ticked(p.userId), enabled = s.togglable(p.userId), former = false) { picker.toggle(p.userId) }
                    }
                    if (s.nobody) item(key = "nobody") { Text(stringResource(R.string.memory_participants_no_members), color = role(palette.muted), modifier = Modifier.padding(16.dp)) }
                    if (s.noMatches) item(key = "nomatch") { Text(stringResource(R.string.memory_participants_no_matches), color = role(palette.muted), modifier = Modifier.padding(16.dp)) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (s.full) Text(stringResource(R.string.memory_participants_full, Participants.MAX), style = MaterialTheme.typography.bodySmall, color = role(palette.muted), modifier = Modifier.padding(top = 8.dp))
            if (s.said != null) Text(stringResource(R.string.memory_participants_save_failed), style = MaterialTheme.typography.bodySmall, color = role(palette.danger), modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (s.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TextButton(onClick = { model.closeParticipants(picker) }, enabled = !s.saving) { Text(stringResource(R.string.trash_cancel)) }
                TextButton(onClick = model::saveParticipants, enabled = s.canSave) { Text(stringResource(R.string.entry_save)) }
            }
        }
    }
}

/** One person in the picker: the whole row ticks and unticks (Material: a checkbox row is `toggleable`). */
@Composable
private fun PickRow(name: String, userId: String, ticked: Boolean, enabled: Boolean, former: Boolean, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    ListItem(
        headlineContent = { Text(name, color = if (former) role(palette.muted) else Color.Unspecified) },
        leadingContent = { Box(Modifier.alpha(if (former) 0.5f else 1f)) { PersonAvatar(name, if (former) null else userId, 36.dp) } },
        trailingContent = { Checkbox(checked = ticked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(ticked, enabled = enabled, role = Role.Checkbox) { onToggle() },
    )
}

// ---- the banner (decisions 4 and 5) ----

/** A banner's picture decoded once, with its surround: the same picture at most 32 px on its long edge (core's `BannerFrame.surround`). */
private class BannerPicture(val picture: ImageBitmap, val surround: ImageBitmap) {
    val aspect: Float get() = picture.width.toFloat() / picture.height.coerceAtLeast(1)

    companion object {
        fun of(bytes: ByteArray?): BannerPicture? = runCatching {
            val b = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@runCatching null
            val (sw, sh) = BannerFrame.surround(b.width, b.height).let { it[0] to it[1] }
            // scaled down with filtering: stretched back over the frame below, it is the blur (the web's 32 px trick, no blur API)
            BannerPicture(b.asImageBitmap(), android.graphics.Bitmap.createScaledBitmap(b, sw, sh, true).asImageBitmap())
        }.getOrNull()
    }
}

/**
 * A banner drawn as the web draws it (ADR 0144 decision 4): the picture whole, contained in the frame, and behind it its
 * surround stretched back with smooth filtering; [content] over it (a video's play button or its player).
 */
@Composable
private fun BannerDrawn(p: BannerPicture, modifier: Modifier, description: String?, content: @Composable () -> Unit = {}) {
    Box(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Image(p.surround, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        Image(p.picture, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        content()
    }
}

/**
 * The banner above the Memory's header, on every view (decision 4, owner 2026-11-17), scrolling away with it: 16:9 on a
 * column wider than 720 dp, 1:1 at or below, never taller than 60 % of the screen (core's `BannerFrame`). A video shows its
 * poster with a play button; a tap plays it through the platform's player over the core's reader, with controls — a
 * rotation brings back the poster, the player being the view's. Nothing at all where the banner does not read or decode.
 */
@Composable
internal fun MemoryBannerFrame(gallery: GalleryModel, model: AppModel) {
    val state by gallery.state.collectAsStateWithLifecycle()
    val shown = state.banner ?: return
    val read by produceState<BannerPicture?>(null, shown.key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BannerPicture.of(gallery.bannerPicture()) }
    }
    val p = read ?: return
    val video = shown.medium == Banner.Medium.VIDEO
    var playing by remember(shown.key) { mutableStateOf(false) }
    val viewport = LocalConfiguration.current.screenHeightDp.toDouble()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val frame = BannerFrame.of(maxWidth.value.toDouble(), viewport)
        BannerDrawn(p, Modifier.fillMaxWidth().height(frame.height.dp), if (video) stringResource(R.string.memory_banner_video) else null) {
            if (video && !playing) {
                FilledIconButton(onClick = { playing = true }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.memory_banner_play), Modifier.size(36.dp))
                }
            }
            if (video && playing) BannerVideo(gallery, model, shown.key, p.aspect)
        }
    }
}

/** The banner's video played as it is read; the Memory's music is interrupted while it plays (as a lightbox video does). */
@Composable
private fun BannerVideo(gallery: GalleryModel, model: AppModel, key: String, aspect: Float) {
    val reader by produceState<me.raddatz.veyq.mobile.blob.RangeReader?>(null, key) { value = gallery.bannerReader() }
    val music by model.music.collectAsStateWithLifecycle()
    var interruption by remember { mutableStateOf<me.raddatz.veyq.mobile.audio.MusicPlayer.Interruption?>(null) }
    val r = reader
    DisposableEffect(r) { onDispose { r?.close() } }
    DisposableEffect(Unit) { onDispose { interruption?.end() } }
    if (r == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ReaderPlayer(r, "banner:$key", aspect, onPlaying = { on ->
            if (on) { if (interruption == null) interruption = music?.interrupt() } else { interruption?.end(); interruption = null }
        }) {
            Text(stringResource(R.string.memory_banner_play_failed), color = Color.White, modifier = Modifier.padding(24.dp))
        }
    }
}

/**
 * The banner's dialog (the web's `BannerDialog.vue`, core's `BannerEditor`) as Material's full-screen dialog: what a banner
 * is, *Choose an image or a video* through the system's photo picker (one file, images and videos — no permission), the two
 * previews (wide 16:9, phone 1:1) drawn as the banner is, **Save banner** once a file is picked, its progress with Cancel,
 * and **Remove banner**. The save runs in the app's model: a rotation keeps it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BannerDialog(editor: BannerEditor, gallery: GalleryModel, model: AppModel) {
    val s by editor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = LocalPalette.current
    LaunchedEffect(s.done) { if (s.done) model.closeBanner() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::pickBanner) }
    // the previews show the picked file, else the banner as it is
    val now by produceState<BannerPicture?>(null) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BannerPicture.of(gallery.bannerPicture()) } }
    val picked = s.picked
    val pickedPicture = remember(picked) { picked?.let { BannerPicture.of(it.picture) } }
    val preview = pickedPicture ?: now
    Dialog(onDismissRequest = { if (!s.saving) model.closeBanner() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        EdgeToEdgeDialogWindow(darkIcons = !androidx.compose.foundation.isSystemInDarkTheme())
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (s.hasBanner) R.string.memory_banner_change else R.string.memory_banner_add)) },
                    navigationIcon = { IconButton(onClick = model::closeBanner, enabled = !s.saving) { Icon(Icons.Filled.Close, stringResource(R.string.trash_cancel)) } },
                    actions = { TextButton(onClick = { model.saveBanner() }, enabled = s.canSave) { Text(stringResource(R.string.memory_banner_save)) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.memory_banner_hint), style = MaterialTheme.typography.bodyMedium, color = role(palette.muted))
                OutlinedButton(
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    enabled = !s.saving && !s.picking, modifier = Modifier.padding(top = 12.dp),
                ) { Text(stringResource(R.string.memory_banner_choose)) }
                if (s.picking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(stringResource(R.string.memory_banner_preparing), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                s.said?.let { said ->
                    val text = when (said) {
                        BannerEditor.Said.TOO_LARGE -> stringResource(R.string.memory_banner_too_large, android.text.format.Formatter.formatShortFileSize(context, BannerWrite.MAX_IMAGE_BYTES / (1024 * 1024) * 1_000_000))
                        BannerEditor.Said.UNREADABLE, BannerEditor.Said.NOT_MEDIA -> stringResource(R.string.memory_banner_unreadable)
                        BannerEditor.Said.VIDEO_UNREADABLE -> stringResource(R.string.memory_banner_video_unreadable)
                        BannerEditor.Said.STALE -> stringResource(R.string.memory_banner_stale)
                        BannerEditor.Said.BUSY, BannerEditor.Said.FAILED -> stringResource(R.string.memory_banner_failed)
                    }
                    Text(text, color = role(palette.danger), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
                if (preview != null) {
                    val videoMark: @Composable () -> Unit = {
                        if ((picked?.medium ?: gallery.current.banner?.medium) == Banner.Medium.VIDEO) Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                    Text(stringResource(R.string.memory_banner_preview_wide), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    BannerDrawn(preview, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium), null, videoMark)
                    Text(stringResource(R.string.memory_banner_preview_phone), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    BannerDrawn(preview, Modifier.width(240.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium), null, videoMark)
                }
                if (s.saving) {
                    val progress = s.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                        Text(stringResource(R.string.memory_banner_uploading, (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                    TextButton(onClick = model::cancelBanner) { Text(stringResource(R.string.trash_cancel)) }
                }
                if (s.hasBanner) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { model.saveBanner(remove = true) }, enabled = s.canRemove) {
                        Text(stringResource(R.string.memory_banner_remove), color = if (s.canRemove) role(palette.danger) else Color.Unspecified)
                    }
                }
            }
        }
    }
}

/**
 * Trashing the medium the banner shows, asked first (A2; the web's `onDeleteBannerPicture`): the banner stays, *Remove the
 * banner too* offered; declined, nothing is deleted.
 */
@Composable
internal fun BannerPictureQuestion(gallery: GalleryModel, model: AppModel) {
    val context = LocalContext.current
    var removeToo by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { gallery.cancelAsk() },
        title = { Text(stringResource(R.string.memory_banner_picture_title)) },
        text = {
            Column {
                Text(stringResource(R.string.memory_banner_picture_notice))
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).toggleable(removeToo, role = Role.Checkbox) { removeToo = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = removeToo, onCheckedChange = null)
                    Text(stringResource(R.string.memory_banner_picture_remove), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val remove = removeToo
                model.galleryAct {
                    val said = gallery.trashBannerPicture(remove)
                    // the trash's own notice is its Undo (the gallery's state); a banner that did not go is said
                    if (remove && said?.msg == Msg.MEMORY_REFUSED_FAILED) model.notify(AppModel.Notice(context.getString(R.string.memory_banner_failed)))
                }
            }) { Text(stringResource(R.string.memory_trash), color = role(LocalPalette.current.danger)) }
        },
        dismissButton = { TextButton(onClick = { gallery.cancelAsk() }) { Text(stringResource(R.string.trash_cancel)) } },
    )
}

// ---- a medium's capture time (decision 3) ----

/**
 * The capture-time editor (the web's lightbox editor, core's `CaptureTimeEditor`): the effective time's day and minute in
 * this device's time zone, each picked with Material's own picker; the platform turns the picked local time into epoch ms
 * (`java.time`) and the core keeps the minute. *Reset to original* where a correction is set. A peer's correction meanwhile
 * reopens the fields on theirs and says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureTimeDialog(editor: CaptureTimeEditor, onClose: () -> Unit) {
    val s by editor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    fun local(ms: Long?) = Instant.ofEpochMilli(ms ?: System.currentTimeMillis()).atZone(zone)
    var day by remember { mutableStateOf(local(s.atMs).toLocalDate()) }
    var time by remember { mutableStateOf(local(s.atMs).toLocalTime().withSecond(0).withNano(0)) }
    // the editor reopened on a peer's time (or a save's): the fields follow
    LaunchedEffect(s.atMs) {
        day = local(s.atMs).toLocalDate()
        time = local(s.atMs).toLocalTime().withSecond(0).withNano(0)
    }
    LaunchedEffect(s.saved) { if (s.saved) onClose() }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = { if (!s.saving) onClose() },
        title = { Text(stringResource(R.string.media_edit_captured_at)) },
        text = {
            Column {
                val dayText = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date.from(day.atStartOfDay(zone).toInstant()))
                val timeText = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date.from(day.atTime(time).atZone(zone).toInstant()))
                ListItem(
                    headlineContent = { Text(dayText) },
                    leadingContent = { Icon(Icons.Outlined.CalendarMonth, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !s.saving) { pickingDay = true },
                )
                ListItem(
                    headlineContent = { Text(timeText) },
                    leadingContent = { Icon(Icons.Outlined.Schedule, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !s.saving) { pickingTime = true },
                )
                val said = when (s.said) {
                    CaptureTimeEditor.Said.CONFLICT -> R.string.media_captured_at_conflict
                    CaptureTimeEditor.Said.FAILED -> R.string.media_captured_at_save_failed
                    CaptureTimeEditor.Said.UNREADABLE -> R.string.media_meta_unreadable
                    null -> null
                }
                said?.let { Text(stringResource(it), color = role(palette.danger), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (s.corrected) {
                    TextButton(onClick = { scope.launch { editor.reset() } }, enabled = !s.saving, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.media_reset_captured_at))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !s.saving, onClick = {
                val ms = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                scope.launch { editor.set(ms) }
            }) { Text(stringResource(R.string.entry_save)) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !s.saving) { Text(stringResource(R.string.trash_cancel)) } },
    )
    if (pickingDay) {
        // Material's date picker speaks UTC midnights: the local day goes in and comes out as one
        val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms -> day = LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L)) }
                    pickingDay = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text(stringResource(R.string.trash_cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    pickingTime = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.trash_cancel)) } },
        )
    }
}
