package me.raddatz.veyq.mobile.android

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.expenses.PotCopy
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A kind's page as the app registers it (the web's `KindRegistration` of `app.kind`): what core's header reads of the kind
 * (it brings a share sheet, it takes a cover), what the kind's page contributes to that header ([header]: null while the
 * page cannot tell yet), and the page's body under it ([body]; a folder's is core's level). Core draws the header for
 * every kind (`NodeHeaderActions`), never an application.
 */
internal class KindPage(
    val shareable: Boolean,
    val takesCover: Boolean,
    val header: @Composable (NodeAt, AppModel) -> HeaderContribution?,
    val body: (@Composable (ObjectRef, BarTab?, AppModel) -> Unit)? = null,
)

/** The pages this build registers, by kind: core's folder and each application's kinds (the composition root). */
internal object KindPages {
    /** Core's folder: its ZIP and its copy with everything under it the build exports and copies (`NodeExport`, `SubtreeCopy`). */
    private val folder = KindPage(shareable = true, takesCover = true, header = { node, model ->
        val context = LocalContext.current
        HeaderContribution(
            deleteTitle = stringResource(R.string.folder_delete),
            deleteQuestion = stringResource(R.string.folder_delete_confirm, node.name),
            copy = { to -> model.copyFolder(node, to) },
            copyNotes = {
                val (kinds, left) = model.copyPreview(node)
                CopyNotes(kinds.flatMap { copyLosses(context, it) }.distinct(), left)
            },
            download = { out -> model.downloadFolder(node, out) },
        )
    })

    /** Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP. */
    private val pot = KindPage(shareable = true, takesCover = true, header = { node, model -> potHeader(node, model) }, body = { ref, tab, model ->
        PotScreen(ref, tab, model)
    })

    /** Depot's file: its download under its own name, its replace (or first upload), its page (`FileScreen`). */
    private val file = KindPage(shareable = true, takesCover = false, header = { node, model -> fileHeader(node, model) }, body = { ref, _, model ->
        FileScreen(ref, model)
    })

    /** Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder). */
    private val dropBox = KindPage(shareable = true, takesCover = true, header = { node, model ->
        val context = LocalContext.current
        HeaderContribution(
            rename = false,
            deleteTitle = stringResource(R.string.dropbox_delete),
            deleteQuestion = stringResource(R.string.dropbox_delete_confirm, node.name),
            copy = { to -> model.copyFolder(node, to) },
            copyNotes = {
                val (kinds, left) = model.copyPreview(node)
                CopyNotes(kinds.flatMap { copyLosses(context, it) }.distinct(), left)
            },
            download = { out -> model.downloadFolder(node, out) },
        )
    }, body = { ref, _, model -> DropBoxScreen(ref, model) })

    /** Memories' Memory and chapter: their gallery (`MemoryScreen`); their cover is core's. */
    private val memory = KindPage(shareable = false, takesCover = true, header = { node, _ ->
        HeaderContribution(
            deleteTitle = stringResource(if (node.kind == "CHAPTER") R.string.memory_delete_chapter else R.string.memory_delete_memory),
            deleteQuestion = stringResource(R.string.memory_delete_confirm, node.name),
        )
    }, body = { ref, _, model -> MemoryScreen(ref, model) })

    fun of(kind: String): KindPage? = when (kind) {
        "MEMORY", "CHAPTER" -> memory
        me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX -> dropBox
        "FOLDER" -> folder
        PotSession.KIND -> pot
        me.raddatz.veyq.mobile.depot.DepotFiles.FILE -> file
        else -> null
    }
}

/** What a copy of a node of [kind] leaves behind, in its kind's words (the web descriptor's `copyLosses`). */
private fun copyLosses(context: android.content.Context, kind: String): List<String> = when (kind) {
    "FOLDER" -> listOf(context.getString(R.string.copy_loss_folder_cover))
    PotSession.KIND -> listOf(context.getString(R.string.copy_loss_pot), context.getString(R.string.copy_loss_cover))
    me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX -> listOf(context.getString(R.string.copy_loss_dropbox))
    else -> emptyList()
}

@Composable
private fun fileHeader(node: NodeAt, model: AppModel): HeaderContribution {
    val files by model.files.collectAsStateWithLifecycle()
    val f = files[node.nodeId]
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the replace: the system's picker hands over one document, which replaces the content over the one listed
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val p = uri?.let { model.picked(it) } ?: return@rememberLauncherForActivityResult
        scope.launch { model.replaceWithNotice(node.nodeId, p, f?.blobId) }
    }
    val has = f?.hasContent == true
    return HeaderContribution(
        deleteTitle = stringResource(R.string.file_delete),
        deleteQuestion = stringResource(R.string.file_delete_confirm, node.name),
        download = if (has) { out -> model.downloadFile(node, out) } else null,
        // a copy is read here and uploaded again under a fresh key (the web's `importFile` for a copy)
        copy = if (has) { to -> model.copyFolder(node, to) } else null,
        copyNotes = { CopyNotes(emptyList()) },
        downloadName = me.raddatz.veyq.mobile.depot.DepotFiles.downloadName(f, node.name),
        downloadMime = "application/octet-stream",
        actions = listOf(
            OwnAction(
                "replace", stringResource(if (has) R.string.depot_replace else R.string.depot_upload_into),
                androidx.compose.material.icons.Icons.Outlined.Upload,
            ) { pick.launch(arrayOf("*/*")) },
            // a decrypted copy to another app the person picks (the system's share sheet), after the shell's notice
            OwnAction(
                "send", stringResource(R.string.file_send), androidx.compose.material.icons.Icons.Outlined.Send,
                me.raddatz.veyq.mobile.navigation.NodeHeader.Slot.MENU,
            ) { model.askToSend(node) },
        ).filter { it.id != "send" || has },
    )
}

@Composable
private fun potHeader(node: NodeAt, model: AppModel): HeaderContribution? {
    val context = LocalContext.current
    val state = model.pot.collectAsStateWithLifecycle().value
    val view = state?.takeIf { it.ref.nodeId == node.nodeId }?.view ?: return null
    // a cover and a copy write: offered where Expenses is on (the web's `canCopy`, `expensesRights`)
    val writable = view.canWrite
    val ref = state.ref
    return HeaderContribution(
        subtitle = view.currency?.let { stringResource(R.string.pot_in_currency, it) },
        cover = writable,
        deleteTitle = stringResource(R.string.pot_delete),
        deleteQuestion = stringResource(R.string.pot_delete_confirm, node.name),
        copyNotes = { CopyNotes(copyLosses(context, PotSession.KIND)) },
        copy = if (writable) { to ->
            val r = model.copyPot(ref, to)
            when (r.outcome) {
                PotCopy.Outcome.COPIED -> null
                PotCopy.Outcome.NAME_TAKEN -> context.getString(R.string.copy_name_taken)
                PotCopy.Outcome.NOTHING_TO_COPY -> context.getString(R.string.copy_nothing)
                PotCopy.Outcome.BUSY -> context.getString(R.string.move_refused_rekey_pending)
                PotCopy.Outcome.BLOCKED, PotCopy.Outcome.REFUSED -> context.getString(R.string.move_refused_illegal_structure)
                PotCopy.Outcome.FAILED -> context.getString(R.string.copy_failed)
            }
        } else null,
        download = { out -> if (model.downloadPot(ref, out)) emptyList() else null },
    )
}
