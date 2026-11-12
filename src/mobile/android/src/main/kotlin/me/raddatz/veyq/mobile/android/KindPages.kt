package me.raddatz.veyq.mobile.android

import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.automirrored.outlined.Send
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
            download = { out -> model.downloadNode(node, out) },
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
            download = { out -> model.downloadNode(node, out) },
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
private fun fileHeader(node: NodeAt, model: AppModel): HeaderContribution? {
    // what the header offers on a file is core's (`FileModel`'s header: its facts, Replace, Send a copy, its download name)
    val file = androidx.compose.runtime.remember(node) { model.fileModel(node) } ?: return null
    val state by file.state.collectAsStateWithLifecycle()
    val h = state.header ?: return null
    // the replace: the system's picker hands over one document, which replaces the content shown
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.replaceFile(file, it) }
    }
    return HeaderContribution(
        facts = h.facts,
        download = { out -> model.downloadNode(node, out) },
        downloadName = h.downloadName,
        downloadMime = "application/octet-stream",
        actions = h.own.map { a ->
            when (a.id) {
                me.raddatz.veyq.mobile.depot.FileModel.REPLACE ->
                    OwnAction(a.id, say(h.label(a.id)!!), androidx.compose.material.icons.Icons.Outlined.Upload, a.slot) { pick.launch(arrayOf("*/*")) }
                // a decrypted copy to another app the person picks (the system's share sheet), after the shell's notice
                else -> OwnAction(a.id, say(h.label(a.id)!!), androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Send, a.slot) { model.askToSend(node) }
            }
        },
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
