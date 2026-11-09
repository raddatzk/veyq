package me.raddatz.veyq.mobile.android

import androidx.compose.runtime.Composable
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

    fun of(kind: String): KindPage? = when (kind) {
        "FOLDER" -> folder
        PotSession.KIND -> pot
        else -> null
    }
}

/** What a copy of a node of [kind] leaves behind, in its kind's words (the web descriptor's `copyLosses`). */
private fun copyLosses(context: android.content.Context, kind: String): List<String> = when (kind) {
    "FOLDER" -> listOf(context.getString(R.string.copy_loss_folder_cover))
    PotSession.KIND -> listOf(context.getString(R.string.copy_loss_pot), context.getString(R.string.copy_loss_cover))
    else -> emptyList()
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
