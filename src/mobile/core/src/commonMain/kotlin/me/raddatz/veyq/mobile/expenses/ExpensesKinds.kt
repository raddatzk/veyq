package me.raddatz.veyq.mobile.expenses

import kotlin.native.HiddenFromObjC
import me.raddatz.veyq.mobile.AppKinds
import me.raddatz.veyq.mobile.ApplicationKinds
import me.raddatz.veyq.mobile.PlatformApi
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.export.SubtreeCopy
import me.raddatz.veyq.mobile.export.SubtreeImport
import me.raddatz.veyq.mobile.media.Derivation
import me.raddatz.veyq.mobile.navigation.ApplicationId
import me.raddatz.veyq.mobile.navigation.HeaderAction as H
import me.raddatz.veyq.mobile.navigation.ShareAnswer
import me.raddatz.veyq.mobile.offline.PinKinds
import me.raddatz.veyq.mobile.offline.Queued
import me.raddatz.veyq.mobile.offline.QueuedSender
import me.raddatz.veyq.mobile.search.KindSearch
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.ShareWords
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.words.Msg

/**
 * Expenses' part of the platform's registry ([AppKinds], the web's `features/expenses/register.ts`): a Pot — its slug
 * rule (`PotCreate`), its revisions' keys on a re-key (`PotRekey`), its ledger and receipts in a download, a copy and an
 * import (`PotExport`, `PotCopy`, `PotImport`, its erased people named by the caller's word), its pins (`PotPins`), its
 * answer to shared files (`PotShare`) and its guest page (`PotGuest`).
 */
@HiddenFromObjC
object ExpensesKinds : ApplicationKinds {
    private const val POT = PotSession.KIND
    private const val FOLDER = "FOLDER"

    override val application = ApplicationId.EXPENSES
    override val kinds = listOf(POT)
    override val word = Msg.APP_EXPENSES

    override fun noun(kind: String): Msg? = if (kind == POT) Msg.POT_A else null

    override fun tabWord(key: String, objectKind: String?): Msg? = when (key) {
        "expenses.entries" -> Msg.TAB_EXPENSES_ENTRIES
        "expenses.overview" -> Msg.TAB_EXPENSES_OVERVIEW
        "expenses.settings" -> Msg.TAB_EXPENSES_SETTINGS
        else -> null
    }

    @PlatformApi

    @HiddenFromObjC
    override fun queuedSender(q: Queued): QueuedSender? = if (q == Queued.POT_ENTRY) QueuedExpenses.entrySender else null

    override fun tokenInput(kind: String, name: String): String? = if (kind == POT) PotCreate.tokenInput(name) else null

    override fun rekey(kind: String): NodeRekey.ContentRekey? = if (kind == POT) PotRekey.content else null

    override fun exports(deletedName: String): NodeExport.Kinds = NodeExport.Kinds(
        exports = { it == POT },
        content = { tenant, node -> if (node.kind == POT) PotSession.open(tenant, node.scopeId, node.id)?.let { PotExport.content(it, deletedName) } else null },
    )

    override fun copies(deletedName: String, uuid: () -> String): SubtreeCopy.Kinds = SubtreeCopy.Kinds(
        copies = { it == POT },
        duplicate = { tenant, node, scopeId, parentId, _ ->
            SubtreeCopy.Made(
                PotSession.open(tenant, scopeId, node.id)?.let { source ->
                    PotCopy.duplicate(source, tenant, scopeId, parentId, deletedName, uuid).takeIf { it.outcome == PotCopy.Outcome.COPIED }?.nodeId
                },
            )
        },
    )

    override fun imports(deletedName: String, uuid: () -> String, deriver: Derivation.Deriver?, now: () -> Long): SubtreeImport.Kinds {
        val pot = PotImport.hook(deletedName, uuid)
        return SubtreeImport.Kinds { kind -> if (kind == POT) pot else null }
    }

    override fun placement(kind: String, parent: String?): Boolean? = if (kind == POT) parent == null || parent == FOLDER else null

    override fun headerOrder(kind: String): List<H>? = if (kind == POT) listOf(H.FAVORITE, H.SHARE, H.DOWNLOAD, H.MOVE, H.COPY) else null

    override fun header(kind: String): AppKinds.Header? =
        if (kind == POT) AppKinds.Header(true, true, true, Msg.POT_DELETE, Msg.POT_DELETE_CONFIRM) else null

    // a Pot downloads, and takes a cover and a copy only while it takes writes (the web's `canCopy`)
    override fun doors(kind: String, facts: AppKinds.Facts): AppKinds.Doors? =
        if (kind == POT) AppKinds.Doors(coverOffered = facts.canWrite, copies = facts.canWrite, downloads = true) else null

    override fun lossesOfCopy(kind: String): List<Msg>? = if (kind == POT) listOf(Msg.COPY_LOSS_POT, Msg.COPY_LOSS_COVER) else null

    // by the Pot open on its page (`PotModel.duplicate`: its session read, its write right known)
    override fun copiesByOpenModel(kind: String): Boolean = kind == POT

    @PlatformApi

    @HiddenFromObjC
    override val kept = ExpensesKept

    override val pins: PinKinds = object : PinKinds {
        override fun pinnable(kind: String): Boolean = kind == POT
        override suspend fun blobs(tenant: TenantSession, node: OpenNode) = PotPins.pinned(tenant, node)
    }

    override fun search(kind: String): KindSearch? = if (kind == POT) KindSearch(Msg.SEARCH_KIND_POT, "wallet") else null

    override fun shareWords(kind: String): ShareWords? = if (kind == POT) ShareWords(
        Msg.SHARE_TITLE, Msg.SHARE_INTRO, Msg.SHARE_ROLE_CONTRIBUTOR,
        Msg.SHARE_ROLE_VIEWER_HINT, Msg.SHARE_ROLE_CONTRIBUTOR_HINT, Msg.SHARE_DELETE_CONFIRM,
    ) else null

    override fun share(kind: String): ShareAnswer? = if (kind == POT) PotShare.answer else null

    override fun guestDoor(kind: String, role: String): GuestDoor? =
        if (kind == POT && (role == GuestAccess.READ || role == GuestAccess.READ_WRITE)) PotGuest.pot else null
}
