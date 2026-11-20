package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.apis.ShareApplicationBlobsApi
import me.raddatz.veyq.mobile.api.expenses.apis.ShareExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotErasureRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionWrite
import me.raddatz.veyq.mobile.api.expenses.models.SharePotAppendRequest
import me.raddatz.veyq.mobile.api.models.ShareApplicationBlob
import me.raddatz.veyq.mobile.api.models.ShareApplicationBlobCompleteRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.ObjectRef
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.sharing.GuestAccess
import me.raddatz.veyq.mobile.sharing.GuestDoor
import me.raddatz.veyq.mobile.sharing.GuestPage
import me.raddatz.veyq.mobile.sharing.GuestView

/**
 * Expenses' answer to a link's guest (ADR 0150 decision 5; the web's `potSession.ts` over `readGuestPot`): a Pot of a READ
 * or READ_WRITE link opened as the member's Pot page over a guest's [PotDoor] — the link's tagged read of the revisions,
 * replaced whole when its tag moved; a READ_WRITE guest's appends of entries and people as its named identity (the web's
 * `expensesRights` guest rows: entries, adding and renaming people); no settings, no members, no member's names.
 */
object PotGuest {
    /**
     * Whether a guest of [access] is offered *Add entry* (ADR 0154 decision 1, the web's rule): the link is READ_WRITE — a
     * guest not named yet is asked its name at the write ([GuestAccess.naming]). No platform reads a role or a name.
     */
    fun offersEntries(access: GuestAccess): Boolean = access.role == GuestAccess.READ_WRITE

    val pot = GuestDoor { access, node, _: GuestView ->
        val opened = access.tree?.node(node.nodeId)?.takeIf { it.kind == PotSession.KIND } ?: return@GuestDoor GuestPage.NotShown(node)
        val door = GuestPotDoor(access)
        GuestPage.Pot(node, PotModel(null, access.now, { ref: ObjectRef -> if (ref.nodeId == opened.id) PotModel.SessionOpened(PotSession.open(door, opened)) else null }))
    }
}

/** A guest's Pot door: the link's Pot reads and a READ_WRITE guest's appends (see [PotGuest]). */
internal class GuestPotDoor(private val access: GuestAccess) : PotDoor {
    private val api get() = ShareExpensesApi(access.origin, access.client.http)
    override val suite: V1 get() = access.suite

    /** A READ_WRITE guest writes entries and people once it named itself (the web's `requestGuest` first). */
    override val writesEntries: Boolean get() = access.role == GuestAccess.READ_WRITE && access.session.value != null
    override val offersEntries: Boolean get() = PotGuest.offersEntries(access)
    override suspend fun named(): Boolean = writesEntries || access.naming.request() != null
    override val changesSettings: Boolean get() = false
    override val member: Boolean get() = false
    override val tenantId: String get() = ""
    override val directory: Map<String, String> get() = emptyMap()

    private var rateService: String? = null

    override suspend fun read(node: OpenNode, tag: String?, cursor: String?, deleteRevision: Int): PotDoor.Read? {
        val rows = mutableListOf<ServedRevision>()
        var after: String? = null
        var first = true
        var firstTag: String? = null
        while (true) {
            val r = api.listSharePotRevisions(access.shareId, node.id, access.pwToken, after, PotSession.PAGE, if (first) tag else null)
            if (first && r.status == 304) return null
            if (!r.success) throw IllegalStateException("share pot revisions ${r.status}")
            if (first) firstTag = r.response.headers[io.ktor.http.HttpHeaders.ETag]
            val page = r.body()
            if (first) rateService = page.exchangeRateService
            rows += page.revisions.map {
                ServedRevision(
                    it.entryId, it.revision, SealedRevision(unb64(it.sealedBody), unb64(it.wrappedRevisionKey)), PotSession.epochMs(it.createdAt), null,
                    it.purgeAfter?.let(PotSession::epochMs),
                )
            }
            if (!page.more) break
            val next = page.cursor ?: throw IllegalStateException("a page with more and no cursor")
            if (next == after) throw IllegalStateException("a cursor that did not advance")
            after = next
            first = false
        }
        return PotDoor.Read(rows, firstTag, null, 0, null, whole = true)
    }

    override suspend fun append(node: OpenNode, revisions: List<PotRevisionWrite>): PotDoor.Sent {
        val token = access.session.value?.contributorToken ?: return PotDoor.Sent(401, "guest_unnamed")
        val r = api.appendSharePotRevisions(access.shareId, node.id, SharePotAppendRequest(node.epoch, token, revisions), access.pwToken)
        return PotDoor.Sent(r.status, if (r.success) null else PotSession.codeOf(r))
    }

    /** A guest erases no one (a member's). */
    override suspend fun erase(node: OpenNode, request: PotErasureRequest) = PotDoor.Sent(403, "share_not_contributable")

    override fun belowFloor(node: OpenNode): Boolean = false

    /** The link resolved again: the Pot's node as the link serves it now. */
    override suspend fun reread(node: OpenNode): OpenNode? = access.tree?.node(node.id)

    override suspend fun pull(node: OpenNode): Boolean = false

    override suspend fun updateMeta(node: OpenNode, wrapped: String, revision: Long): NodeMetaWrite.Answer = NodeMetaWrite.Answer.Refused(403, "share_not_contributable")

    /** A guest never reads the space's members (ADR 0108 decision 2). */
    override suspend fun members(scopeId: String): List<Person>? = null

    override fun ratesTemplate(): String? = rateService

    override suspend fun dayTable(template: String, day: String): Map<String, ExchangeRates.Row>? = null

    override val receipts: PotDoor.Receipts = object : PotDoor.Receipts {
        private val blobsApi get() = ShareApplicationBlobsApi(access.origin, access.client.http)

        override suspend fun list(node: OpenNode, ifNoneMatch: String?): ApplicationBlobs.Listing? {
            val out = ArrayList<ApplicationBlobs.Listed>()
            var after: String? = null
            var etag: String? = null
            var first = true
            while (true) {
                val r = blobsApi.listShareApplicationBlobs(access.shareId, node.id, access.pwToken, after, PotSession.PAGE, if (first) ifNoneMatch else null)
                if (first && r.status == 304) return null
                if (!r.success) throw ApplicationBlobs.UploadRefused("list ${r.status}")
                if (first) etag = r.response.headers[io.ktor.http.HttpHeaders.ETag]
                val page = r.body()
                out += page.blobs.map(::listed)
                if (!page.more) break
                val next = page.cursor ?: throw ApplicationBlobs.UploadRefused("a page with more and no cursor")
                if (next == after) throw ApplicationBlobs.UploadRefused("a cursor that did not advance")
                after = next
                first = false
            }
            return ApplicationBlobs.Listing(out, etag)
        }

        private fun listed(b: ShareApplicationBlob) = ApplicationBlobs.Listed(
            b.id, b.ownerId, b.role, b.blobId, b.chunkCount, b.byteSize, b.wrappedBlobKey, b.wrappedBlobMeta, null,
            PotSession.epochMs(b.createdAt), b.keptAt?.let(PotSession::epochMs), b.releasedAt?.let(PotSession::epochMs),
            b.purgeAfter?.let(PotSession::epochMs), b.blockedByOperator,
        )

        private fun keyOf(node: OpenNode, b: ApplicationBlobs.Listed): ByteArray? =
            access.blobs.openKey(node.scopeId, node.id, b.blobId, b.role, node.key, b.wrappedBlobKey)

        override fun metaOf(node: OpenNode, b: ApplicationBlobs.Listed): BlobMeta.Opened? {
            val sealed = b.wrappedBlobMeta ?: return null
            val key = keyOf(node, b) ?: return null
            return BlobMeta.open(suite, key, unb64(sealed))
        }

        // a link's guest keeps nothing on the device (ADR 0150 decision 3): [keep] is a member's
        override suspend fun download(node: OpenNode, b: ApplicationBlobs.Listed, keep: Boolean): ByteArray {
            val key = keyOf(node, b) ?: throw ApplicationBlobs.UploadRefused("key_unreadable")
            return access.blobs.whole(b.blobId, b.chunkCount, key)
        }

        override suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
        ) {
            val token = access.session.value?.contributorToken ?: throw ApplicationBlobs.UploadRefused("guest_unnamed")
            val n = node()
            val (uploadId, _) = access.blobs.reserveAndPumpAll(
                n.scopeId, n.id, n.key, token,
                listOf(ApplicationBlobs.Outgoing(Receipts.ROLE, bytes.size.toLong(), type, name, modifiedAt, null) { from, length -> bytes.copyOfRange(from.toInt(), (from + length).toInt()) }),
                nodeId = n.id,
            ).single()
            val r = blobsApi.completeShareApplicationBlob(
                access.shareId, n.id, ShareApplicationBlobCompleteRequest(n.epoch, token, id, entryId, Receipts.ROLE, uploadId), access.pwToken,
            )
            if (!r.success) throw ApplicationBlobs.UploadRefused(PotSession.codeIn(runCatching { r.response.bodyAsText() }.getOrDefault("")) ?: "complete ${r.status}")
        }
    }
}
