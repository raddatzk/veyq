package me.raddatz.veyq.mobile.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.account.KitStep
import me.raddatz.veyq.mobile.session.AccountErasure
import me.raddatz.veyq.mobile.session.AddressChange
import me.raddatz.veyq.mobile.session.PasswordChange
import me.raddatz.veyq.mobile.session.rotation.KeyRotation
import me.raddatz.veyq.mobile.words.Msg

/**
 * The account's flows (ADR 0148 decision 10, Android's row): each a full-screen dialog — Material 3's for a task of its
 * own — with a `TopAppBar`, drawn over the page it was opened from. The core's model decides every step; this draws its
 * state and hands the person's acts back through [AppModel].
 *
 * **Secrets on screen** (`src/mobile/CLAUDE.md`): a flow where the password or the Secret Key is typed is a window of its
 * own flagged secure (`SecureFlagPolicy.SecureOn`), so the Recents snapshot holds no frame of it — the password, the
 * address, the keys and the erasure; the profile is none. The fields are masked with no reveal, and their text lives in
 * `remember` only — never `rememberSaveable`, whose bundle the system keeps. The Emergency Kit is never shown: it is
 * written by the core straight into the document the system's save dialog made ([KitSave]).
 */
@Composable
internal fun AccountFlowDialog(flow: AppModel.AccountFlow, model: AppModel) {
    when (flow) {
        is AppModel.AccountFlow.Profile -> ProfileFlow(flow.model, model)
        is AppModel.AccountFlow.Password -> PasswordFlow(flow.model, model)
        is AppModel.AccountFlow.Address -> AddressFlow(flow.model, model)
        is AppModel.AccountFlow.Rotation -> RotationFlow(flow.model, model)
        is AppModel.AccountFlow.Erasure -> ErasureFlow(flow.model, model)
    }
}

/** A flow's window: full screen, edge to edge, secure where a secret is typed ([secure]), closed by its bar's ✕. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlowDialog(
    title: String, secure: Boolean, tag: String, onClose: () -> Unit,
    snackbar: SnackbarHostState? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        EdgeToEdgeDialogWindow()
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { snackbar?.let { SnackbarHost(it) } },
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose, modifier = Modifier.testTag("$tag-close")) { Icon(Icons.Filled.Close, stringResource(R.string.signin_cancel)) }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp).testTag(tag),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}


@Composable
internal fun Body(text: String, muted: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun Refusal(msg: Msg?, tag: String, vararg args: Any) {
    msg?.let { Text(say(it, *args), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag)) }
}

@Composable
internal fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

/** A row that is one checkbox, as Material's list item with a checkbox is: the label toggles too. */
@Composable
internal fun CheckRow(checked: Boolean, enabled: Boolean, label: String, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, enabled = enabled)
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The danger step's confirmation: Material's alert with an error-coloured confirm. */
@Composable
internal fun DangerConfirm(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("danger-confirm")) {
                Text(action)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signin_cancel)) } },
    )
}

// ---- one's profile (uc.spaces.edit-profile, uc.spaces.set-profile-picture) ----

@Composable
private fun ProfileFlow(m: me.raddatz.veyq.mobile.session.ProfileModel, model: AppModel) {
    val state by m.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.profile_saved)
    var seenSaves by remember { mutableStateOf(0) }
    LaunchedEffect(state.saves) {
        if (state.saves > seenSaves) {
            seenSaves = state.saves
            if (state.pictureRefusal == null && state.refusal == null) snackbar.showSnackbar(saved)
        }
    }
    var cropping by remember { mutableStateOf<Uri?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) cropping = uri }
    FlowDialog(stringResource(R.string.profile_title), secure = false, tag = "profile", onClose = model::closeAccountFlow, snackbar = snackbar) {
        if (!state.loaded) {
            Body(stringResource(R.string.profile_loading), muted = true)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@FlowDialog
        }
        Body(stringResource(R.string.profile_sub), muted = true)
        val enabled = !state.working
        // the fields are this view's, each change handed to the model at once (a field bound to a flow loses its cursor)
        var username by remember(state.loaded) { mutableStateOf(state.username) }
        var first by remember(state.loaded) { mutableStateOf(state.firstName) }
        var last by remember(state.loaded) { mutableStateOf(state.lastName) }
        Heading(stringResource(R.string.profile_picture_label))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProfilePicture(m.picture().takeIf { state.hasPicture }, "$first $last".trim().ifEmpty { username })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = enabled && state.hasKey, modifier = Modifier.testTag("profile-picture-choose"),
                ) { Text(stringResource(if (state.hasPicture) R.string.profile_picture_change else R.string.profile_picture_choose)) }
                if (state.hasPicture) TextButton(onClick = m::removePicture, enabled = enabled && state.hasKey, modifier = Modifier.testTag("profile-picture-remove")) {
                    Text(stringResource(R.string.profile_picture_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Body(
            stringResource(
                when {
                    !state.hasKey -> R.string.profile_picture_no_key
                    state.picture == me.raddatz.veyq.mobile.session.ProfileModel.PictureDraft.SET -> R.string.profile_picture_pending_set
                    state.picture == me.raddatz.veyq.mobile.session.ProfileModel.PictureDraft.CLEAR -> R.string.profile_picture_pending_clear
                    else -> R.string.profile_picture_hint
                },
            ),
            muted = true,
        )
        Refusal(state.pictureRefusal, "profile-picture-refusal")
        OutlinedTextField(
            username, { username = it; m.setUsername(it) }, label = { Text(stringResource(R.string.profile_username)) }, singleLine = true, enabled = enabled,
            isError = state.usernameRefusal != null,
            supportingText = { Text(state.usernameRefusal?.let { say(it) } ?: stringResource(R.string.profile_username_hint)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("profile-username"),
        )
        OutlinedTextField(
            first, { first = it; m.setFirstName(it) }, label = { Text(stringResource(R.string.profile_first_name)) }, singleLine = true,
            enabled = enabled && state.hasKey, modifier = Modifier.fillMaxWidth().testTag("profile-first-name"),
        )
        OutlinedTextField(
            last, { last = it; m.setLastName(it) }, label = { Text(stringResource(R.string.profile_last_name)) }, singleLine = true,
            enabled = enabled && state.hasKey, modifier = Modifier.fillMaxWidth().testTag("profile-last-name"),
        )
        if (!state.hasKey) Body(say(Msg.PROFILE_NO_NAME_YET), muted = true)
        // the whole row is the one switch (#944), as Settings' map switch
        Row(
            Modifier.fillMaxWidth().toggleable(state.requireGroupAddConsent, enabled = enabled, role = Role.Switch) { m.setRequireGroupAddConsent(it) }
                .padding(vertical = 4.dp).testTag("profile-consent"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.profile_consent_group_add), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.profile_consent_group_add_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(12.dp))
            Switch(state.requireGroupAddConsent, null, enabled = enabled)
        }
        if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        Refusal(state.refusal, "profile-refusal")
        Button(onClick = { model.saveProfile(m) }, enabled = enabled && username.isNotBlank(), modifier = Modifier.testTag("profile-save")) {
            Text(stringResource(R.string.profile_save))
        }
    }
    cropping?.let { uri -> AvatarCropSheet(uri, model, onUse = { rgba -> model.choosePicture(m, rgba) }, onClose = { cropping = null }) }
}

/** One's picture as the draft or the server holds it, else the letter (the web's `PersonAvatar`). */
@Composable
private fun ProfilePicture(bytes: ByteArray?, name: String) {
    val picture = remember(bytes) { bytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } }
    if (picture != null) {
        androidx.compose.foundation.Image(
            picture.asImageBitmap(), null,
            Modifier.size(72.dp).clip(CircleShape).testTag("profile-picture"), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    } else {
        PersonAvatar(name, null, 72.dp)
    }
}

/**
 * The picture framed square (ADR 0148 decision 2): the cover's cropper — core's `CoverFrame`, the web's math — with a
 * 1:1 frame; a drag pans, a pinch or the slider zooms (1–3). *Use this picture* renders the frame to 256 × 256 RGBA
 * ([AppModel.avatarPixels]); the core encodes, pads and seals it with the profile's save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvatarCropSheet(uri: Uri, model: AppModel, onUse: (ByteArray) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pose by remember { mutableStateOf<me.raddatz.veyq.mobile.session.CoverFrame.Pose?>(null) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var rendering by remember { mutableStateOf(false) }
    val zoomLabel = stringResource(R.string.cover_zoom)
    LaunchedEffect(uri) {
        image = model.coverPicture(uri)
        failed = image == null
    }
    val frame = remember(image, size) {
        val img = image
        if (img == null || size.width == 0) null
        else me.raddatz.veyq.mobile.session.CoverFrame(img.width.toDouble(), img.height.toDouble(), size.width.toDouble(), size.height.toDouble())
    }
    LaunchedEffect(frame) { if (frame != null) pose = frame.centred() }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = { if (!rendering) onClose() },
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("avatar-crop"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.profile_picture_label), style = MaterialTheme.typography.titleLarge)
            Body(stringResource(R.string.profile_picture_dialog_intro), muted = true)
            Box(
                Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).aspectRatio(1f)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged { size = it }
                    .pointerInput(frame) {
                        val f = frame ?: return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            pose = pose?.let { f.zoom(f.pan(it, pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble()) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val img = image
                val p = pose
                if (img != null && p != null && frame != null) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val scale = frame.baseScale * p.zoom
                        drawImage(
                            img,
                            dstOffset = androidx.compose.ui.unit.IntOffset(p.x.toInt(), p.y.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize((img.width * scale).toInt(), (img.height * scale).toInt()),
                        )
                    }
                } else if (!failed) CircularProgressIndicator()
            }
            val p = pose
            if (p != null && frame != null) {
                androidx.compose.material3.Slider(
                    value = p.zoom.toFloat(),
                    onValueChange = { z -> pose = frame.zoom(p, z / p.zoom) },
                    valueRange = me.raddatz.veyq.mobile.session.CoverFrame.MIN_ZOOM.toFloat()..me.raddatz.veyq.mobile.session.CoverFrame.MAX_ZOOM.toFloat(),
                    enabled = !rendering,
                    modifier = Modifier.semantics { contentDescription = zoomLabel },
                )
            }
            if (failed) Text(stringResource(R.string.profile_picture_not_image), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !rendering) { Text(stringResource(R.string.signin_cancel)) }
                Button(
                    enabled = !rendering && p != null && frame != null,
                    onClick = {
                        val f = frame ?: return@Button
                        val at = pose ?: return@Button
                        val img = image ?: return@Button
                        rendering = true
                        scope.launch {
                            val rgba = model.avatarPixels(img, f.crop(at))
                            rendering = false
                            if (rgba == null) failed = true
                            else {
                                onUse(rgba)
                                onClose()
                            }
                        }
                    },
                    modifier = Modifier.testTag("avatar-use"),
                ) { Text(stringResource(R.string.profile_picture_use)) }
            }
        }
    }
}
