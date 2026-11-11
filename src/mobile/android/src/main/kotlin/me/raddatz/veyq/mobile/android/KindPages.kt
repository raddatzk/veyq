package me.raddatz.veyq.mobile.android

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.raddatz.veyq.mobile.expenses.PotSession
import me.raddatz.veyq.mobile.navigation.BarTab
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ObjectRef

/**
 * A kind's page as the app registers it (the web's `KindRegistration` of `app.kind`): what the kind's page contributes to
 * core's header ([header]: null while the page cannot tell yet) and the page's body under it ([body]; a folder's is core's
 * level). What the header offers is core's (`NodeHeaderModel`, `AppKinds`); core draws it for every kind
 * (`NodeHeaderActions`), never an application.
 */
internal class KindPage(
    val header: @Composable (NodeAt, AppModel) -> HeaderContribution?,
    val body: (@Composable (ObjectRef, BarTab?, AppModel) -> Unit)? = null,
)

/** The pages this build registers, by kind: core's folder and each application's kinds (the composition root). */
internal object KindPages {
    /** Core's folder: its ZIP and its copy with everything under it the build exports and copies (`NodeExport`, `SubtreeCopy`). */
    private val folder = KindPage(header = { node, model ->
        HeaderContribution(
            download = { out -> model.downloadFolder(node, out) },
        )
    })

    /** Expenses' Pot: its currency under its name, its cover and copy while Expenses is on, its ZIP. */
    private val pot = KindPage(header = { node, model -> potHeader(node, model) }, body = { ref, tab, model ->
        PotScreen(ref, tab, model)
    })

    /** Depot's file: its download under its own name, its replace (or first upload), its page (`FileScreen`). */
    private val file = KindPage(header = { node, model -> fileHeader(node, model) }, body = { ref, _, model ->
        FileScreen(ref, model)
    })

    /** Depot's drop box: its guests' folders (`DropBoxScreen`); never renamed; its cover; its ZIP and its copy (a plain folder). */
    private val dropBox = KindPage(header = { node, model ->
        HeaderContribution(
            download = { out -> model.downloadFolder(node, out) },
        )
    }, body = { ref, _, model -> DropBoxScreen(ref, model) })

    /** Memories' Memory and chapter: their gallery (`MemoryScreen`); their cover is core's. */
    private val memory = KindPage(header = { _, _ -> HeaderContribution() }, body = { ref, _, model -> MemoryScreen(ref, model) })

    fun of(kind: String): KindPage? = when (kind) {
        "MEMORY", "CHAPTER" -> memory
        me.raddatz.veyq.mobile.depot.DepotFiles.DROPBOX -> dropBox
        "FOLDER" -> folder
        PotSession.KIND -> pot
        me.raddatz.veyq.mobile.depot.DepotFiles.FILE -> file
        else -> null
    }
}

@Composable
private fun fileHeader(node: NodeAt, model: AppModel): HeaderContribution {
    val files by model.files.collectAsStateWithLifecycle()
    val f = files[node.nodeId]
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the replace: the system's picker hands over one document, which replaces the content over the one listed
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val p = uri?.let { model.picked(it) } ?: return@rememberLauncherForActivityResult
        model.replace(node.nodeId, model.source(p), f?.blobId)
    }
    val has = f?.hasContent == true
    return HeaderContribution(
        facts = me.raddatz.veyq.mobile.AppKinds.Facts(hasContent = has),
        download = { out -> model.downloadFile(node, out) },
        downloadName = me.raddatz.veyq.mobile.depot.DepotFiles.downloadName(f, node.name),
        downloadMime = "application/octet-stream",
        actions = listOf(
            OwnAction(
                "replace", say(me.raddatz.veyq.mobile.depot.DepotFiles.replaceWord(f)),
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
    val state = model.pot.collectAsStateWithLifecycle().value
    val view = state?.takeIf { it.ref.nodeId == node.nodeId }?.view ?: return null
    val ref = state.ref
    return HeaderContribution(
        subtitle = view.subtitle?.let { say(it) },
        // a cover and a copy write: core offers them where Expenses is on (the web's `canCopy`, `expensesRights`)
        facts = me.raddatz.veyq.mobile.AppKinds.Facts(canWrite = view.canWrite),
        download = { out -> if (model.downloadPot(ref, out)) emptyList() else null },
    )
}
