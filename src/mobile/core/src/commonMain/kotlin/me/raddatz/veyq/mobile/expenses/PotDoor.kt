package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotErasureRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionWrite
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeRekey
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Person
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * What a [PotSession] reads and writes through (ADR 0150 decision 5; the web's `potSession.ts` one session over a member's
 * and a guest's reads): a member's door is its tenant's ([TenantPotDoor] — the member's API, the tenant's switch, the
 * space's members), a link guest's is its link's (`GuestPotDoor`: the share's Pot reads, a READ_WRITE guest's appends,
 * no meta, no members). The session's rules — the ledger, the writes' sealing, the epoch — are the same over either.
 */
interface PotDoor {
    val suite: V1

    /** Whether entries and people are written here (a member: Expenses on for the tenant; a guest: a READ_WRITE link). */
    val writesEntries: Boolean

    /** Whether the Pot's settings (base currency, own categories) and its people's standing are changed here: a member's. */
    val changesSettings: Boolean

    /**
     * Whether a member reads the Pot here: its Settings show the base currency and the categories, and its people's standing
     * (deactivate, delete) is managed. A link's guest — a READ_WRITE one adds and renames people alone (the web's
     * `rights.ts`: `addPeople` without `managePeople` or `settings`, ADR 0108 decision 6).
     */
    val member: Boolean get() = true

    /** The tenant a member id means a person in (a download's), "" for a guest. */
    val tenantId: String

    /** The tenant's members by user id (the history's writers); empty for a guest. */
    val directory: Map<String, String>

    /** One status and error code of a write. */
    class Sent(val status: Int, val code: String?)

    /** What a read since [PotSession]'s held state brought; [whole]: it is everything (held is replaced, not merged). */
    class Read(
        val rows: List<ServedRevision>, val tag: String?, val cursor: String?, val deleteRevision: Int, val keep: Set<String>?, val whole: Boolean,
    )

    /** The revisions since [cursor] (the first page conditional on [tag]); null for "unchanged". Throws where it failed. */
    suspend fun read(node: OpenNode, tag: String?, cursor: String?, deleteRevision: Int): Read?

    suspend fun append(node: OpenNode, revisions: List<PotRevisionWrite>): Sent
    suspend fun erase(node: OpenNode, request: PotErasureRequest): Sent

    /** Whether [node]'s key is below the scope's current epoch (the device floor); a guest's never is. */
    fun belowFloor(node: OpenNode): Boolean

    /** The node read again (its key and epoch may have moved); null when it is no longer there. */
    suspend fun reread(node: OpenNode): OpenNode?

    /** A re-key of [node] pulled forward where this device can (a member's). */
    suspend fun pull(node: OpenNode): Boolean

    /** The node's sealed meta written ([NodeMetaWrite]'s send). */
    suspend fun updateMeta(node: OpenNode, wrapped: String, revision: Long): NodeMetaWrite.Answer

    /** The space's current members, or null where none are read (a guest never reads them, ADR 0108 decision 2). */
    suspend fun members(scopeId: String): List<Person>?

    /** The exchange-rate service's URL template, or null for none. */
    fun ratesTemplate(): String?

    /** [day]'s rate table from [template], or null. */
    suspend fun dayTable(template: String, day: String): Map<String, ExchangeRates.Row>?

    /** The Pot's receipts, as this door reaches them. */
    val receipts: Receipts

    interface Receipts {
        suspend fun list(node: OpenNode, ifNoneMatch: String?): ApplicationBlobs.Listing?
        fun metaOf(node: OpenNode, b: ApplicationBlobs.Listed): BlobMeta.Opened?
        suspend fun download(node: OpenNode, b: ApplicationBlobs.Listed): ByteArray

        /** Receipt [id] of entry [entryId] uploaded and completed; throws `ApplicationBlobs.UploadRefused` with the code. */
        suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, bytes: ByteArray, type: String,
            name: String?, modifiedAt: Long?,
        )
    }
}

/** A member's Pot door: the tenant's API, its switch, its members and rates (the behaviour `PotSession` always had). */
internal class TenantPotDoor(private val tenant: TenantSession) : PotDoor {
    private val account = tenant.account
    private val api get() = ExpensesApi(tenant.base, account.http)
    override val suite: V1 get() = account.suite
    override val writesEntries: Boolean get() = "expenses" !in tenant.applicationsOff()
    override val changesSettings: Boolean get() = "expenses" !in tenant.applicationsOff()
    override val tenantId: String get() = tenant.tenant.tenantId
    override val directory: Map<String, String> get() = tenant.people.mapValues { it.value.displayName }

    override suspend fun read(node: OpenNode, tag: String?, cursor: String?, deleteRevision: Int): PotDoor.Read? {
        var since = cursor
        var first = true
        var firstTag = tag
        var firstDelete = deleteRevision
        val rows = mutableListOf<ServedRevision>()
        var keep: Set<String>? = null
        while (true) {
            val r = account.respond {
                api.listPotRevisions(
                    node.scopeId, node.id, ifNoneMatch = if (first) tag else null, since = since,
                    sinceDelete = if (first) deleteRevision else firstDelete, limit = PotSession.PAGE,
                )
            }
            if (r.status == 304) {
                if (!first) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "304 on a later page")
                return null
            }
            if (!r.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "pot revisions ${r.status}")
            val page = r.body()
            if (first) {
                firstTag = page.ledgerRevision.toString()
                firstDelete = page.deleteRevision
                keep = page.revisionIds?.map { "${it.entryId}:${it.revision}" }?.toSet()
            }
            rows += page.revisions.map {
                ServedRevision(
                    it.entryId, it.revision, SealedRevision(unb64(it.sealedBody), unb64(it.wrappedRevisionKey)), PotSession.epochMs(it.createdAt), it.writer,
                    it.purgeAfter?.let(PotSession::epochMs),
                )
            }
            if (!page.more) {
                return PotDoor.Read(rows, firstTag, page.cursor ?: since, firstDelete, keep, whole = false)
            }
            val next = page.cursor ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a page with more and no cursor")
            if (next == since) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a cursor that did not advance")
            since = next
            first = false
        }
    }

    override suspend fun append(node: OpenNode, revisions: List<PotRevisionWrite>): PotDoor.Sent {
        val r = account.respond { api.appendPotRevisions(node.scopeId, node.id, PotAppendRequest(node.epoch, revisions)) }
        return PotDoor.Sent(r.status, if (r.success) null else PotSession.codeOf(r))
    }

    override suspend fun erase(node: OpenNode, request: PotErasureRequest): PotDoor.Sent {
        val r = account.respond { api.erasePotRecord(node.scopeId, node.id, request) }
        return PotDoor.Sent(r.status, if (r.success) null else PotSession.codeOf(r))
    }

    override fun belowFloor(node: OpenNode): Boolean = node.scopeEpoch < (tenant.scopeEpoch(node.scopeId) ?: Int.MAX_VALUE)

    override suspend fun reread(node: OpenNode): OpenNode? {
        tenant.forgetTree()
        return tenant.trees()[node.scopeId]?.firstOrNull { it.id == node.id && it.kind == PotSession.KIND }
    }

    override suspend fun pull(node: OpenNode): Boolean = NodeRekey.pull(tenant, node.scopeId, listOf(node.id))

    override suspend fun updateMeta(node: OpenNode, wrapped: String, revision: Long): NodeMetaWrite.Answer {
        val r = account.respond {
            ContentApi(tenant.base, account.http).updateNode(
                node.scopeId, node.id, UpdateNodeRequest(wrappedMeta = wrapped, metaRevision = revision, nodeEpoch = node.epoch),
            )
        }
        if (r.success) return NodeMetaWrite.Answer.Taken
        val body = runCatching { r.response.bodyAsText() }.getOrDefault("")
        return NodeMetaWrite.staleOf(body)?.let { NodeMetaWrite.Answer.Stale(it) } ?: NodeMetaWrite.Answer.Refused(r.status, PotSession.codeIn(body))
    }

    override suspend fun members(scopeId: String): List<Person>? = tenant.scopeMembers(scopeId)

    override fun ratesTemplate(): String? = tenant.serviceUrl(ExchangeRates.CAPABILITY)

    override suspend fun dayTable(template: String, day: String): Map<String, ExchangeRates.Row>? =
        ExchangeRates.dayTable(template, day, account.deviceStore) { account.fetchOutside(it) }

    override val receipts: PotDoor.Receipts = object : PotDoor.Receipts {
        private val blobs = ApplicationBlobs(tenant)
        override suspend fun list(node: OpenNode, ifNoneMatch: String?) = blobs.list(node.scopeId, node.id, ifNoneMatch)
        override fun metaOf(node: OpenNode, b: ApplicationBlobs.Listed) = blobs.metaOf(node.scopeId, node, b)
        override suspend fun download(node: OpenNode, b: ApplicationBlobs.Listed) = blobs.download(node.scopeId, node, b)
        override suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
        ) {
            blobs.upload(
                node().scopeId, node, reopen, { belowFloor(node()) }, id, entryId, Receipts.ROLE, bytes, type, name, modifiedAt,
                pull = { pull(node()) },
            )
        }
    }
}
