package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import me.raddatz.veyq.mobile.api.apis.ContentApi
import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotErasureRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotRevision
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionPage
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionWrite
import me.raddatz.veyq.mobile.api.models.UpdateNodeRequest
import me.raddatz.veyq.mobile.blob.ApplicationBlobs
import me.raddatz.veyq.mobile.crypto.BlobMeta
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.offline.KeptRead
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

    /**
     * Whether *Add entry* is offered here (ADR 0154 decision 1, the web's rule): where entries are written — and to a
     * READ_WRITE link's guest not named yet, asked its name at the write ([named]). A member's is [writesEntries].
     */
    val offersEntries: Boolean get() = writesEntries

    /** Before a write: whether it may go on — a guest not named yet is asked its name first (the web's `requestGuest`). */
    suspend fun named(): Boolean = writesEntries

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

    /**
     * What a read since [PotSession]'s held state brought; [whole]: it is everything (held is replaced, not merged);
     * [offline]: it is the ledger the offline store kept, read while the server is unreachable, as of when (ADR 0153
     * decision 6) — null where the server answered.
     */
    class Read(
        val rows: List<ServedRevision>, val tag: String?, val cursor: String?, val deleteRevision: Int, val keep: Set<String>?, val whole: Boolean,
        val offline: me.raddatz.veyq.mobile.session.OfflineAsOf? = null,
    )

    /**
     * The revisions since [cursor] (the first page conditional on [tag]); null for "unchanged" — the server answered
     * `304`. Throws where it failed.
     */
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

    /** The account whose outbox a write made offline here queues in (ADR 0153); null where none does (a link's guest). */
    val queueAccount: me.raddatz.veyq.mobile.session.VeyqAccount? get() = null

    interface Receipts {
        suspend fun list(node: OpenNode, ifNoneMatch: String?): ApplicationBlobs.Listing?
        fun metaOf(node: OpenNode, b: ApplicationBlobs.Listed): BlobMeta.Opened?
        /** A receipt's bytes; [keep]: its frames are kept in the offline store (a receipt shown, ADR 0153 decision 6). */
        suspend fun download(node: OpenNode, b: ApplicationBlobs.Listed, keep: Boolean): ByteArray

        /** Receipt [id] of entry [entryId] uploaded and completed; throws `ApplicationBlobs.UploadRefused` with the code. */
        suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, bytes: ByteArray, type: String,
            name: String?, modifiedAt: Long?,
        )

        /** A receipt [sealed] ahead (queued offline, ADR 0153): its frames sent as they are. A member's door only. */
        suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, type: String, name: String?,
            modifiedAt: Long?, sealed: ApplicationBlobs.Sealed,
        ): Unit = throw ApplicationBlobs.UploadRefused("not_queued_here")
    }
}

/** A member's Pot door: the tenant's API, its switch, its members and rates (the behaviour `PotSession` always had). */
internal class TenantPotDoor(private val tenant: TenantSession) : PotDoor {
    private val account = tenant.account
    override val queueAccount get() = account
    private val api get() = ExpensesApi(tenant.base, account.http)
    override val suite: V1 get() = account.suite
    override val writesEntries: Boolean get() = "expenses" !in tenant.applicationsOff()
    override val changesSettings: Boolean get() = "expenses" !in tenant.applicationsOff()
    override val tenantId: String get() = tenant.tenant.tenantId
    override val directory: Map<String, String> get() = tenant.people.mapValues { it.value.displayName }

    override suspend fun read(node: OpenNode, tag: String?, cursor: String?, deleteRevision: Int): PotDoor.Read? {
        if (cursor == null) return whole(node)
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
            rows += page.revisions.map(::served)
            if (!page.more) {
                return PotDoor.Read(rows, firstTag, page.cursor ?: since, firstDelete, keep, whole = false)
            }
            val next = page.cursor ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a page with more and no cursor")
            if (next == since) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a cursor that did not advance")
            since = next
            first = false
        }
    }

    /**
     * The whole ledger (no `since`), a kept read (ADR 0153 decision 6, as `MemoryMedia.list` keeps `listNodeMedia`): the
     * kept `ledgerRevision` sent as `If-None-Match`, a newer answer replacing the kept one — never merged, so an entry gone
     * on the server is gone from it —, a `403`/`404` dropping it, and the kept ledger while the server is unreachable.
     * Every page as the server sent it; the live poll's deltas after it are [read]'s, not kept.
     */
    internal suspend fun whole(node: OpenNode): PotDoor.Read {
        val read = try {
            account.kept(ExpensesKept.POT_REVISIONS, tenant.tenant.tenantId, node.scopeId, node.id, conditional = true) { tag -> pages(node, tag) }
        } catch (e: me.raddatz.veyq.mobile.session.VeyqAccount.Refused) {
            throw SignInFailure(SignInFailure.Reason.PROTOCOL, "pot revisions ${e.status}")
        }
        val rows = mutableListOf<ServedRevision>()
        var tag: String? = null
        var cursor: String? = null
        var deletes = 0
        read.pages.forEachIndexed { i, raw ->
            val page = me.raddatz.veyq.mobile.session.VeyqAccount.KEPT_JSON.decodeFromString(PotRevisionPage.serializer(), raw.decodeToString())
            if (i == 0) {
                tag = page.ledgerRevision.toString()
                deletes = page.deleteRevision
            }
            rows += page.revisions.map(::served)
            page.cursor?.let { cursor = it }
        }
        return PotDoor.Read(rows, tag, cursor, deletes, null, whole = true, offline = read.offline)
    }

    /** Every page of the whole ledger as the server sent it, the first conditional on [tag] (the kept `ledgerRevision`). */
    private suspend fun pages(node: OpenNode, tag: String?): me.raddatz.veyq.mobile.session.VeyqAccount.Fetched {
        val out = ArrayList<ByteArray>()
        var since: String? = null
        var revision: String? = null
        while (true) {
            val first = out.isEmpty()
            val r = account.respond {
                api.listPotRevisions(node.scopeId, node.id, ifNoneMatch = if (first) tag else null, since = since, limit = PotSession.PAGE)
            }
            if (first && r.status == 304) return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.NotModified
            if (!r.success) return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.Refused(r.status, account.errorCode(r))
            val raw = r.response.readRawBytes()
            out += raw
            val page = me.raddatz.veyq.mobile.session.VeyqAccount.KEPT_JSON.decodeFromString(PotRevisionPage.serializer(), raw.decodeToString())
            // the live poll and the store continue from the FIRST page's revision
            if (first) revision = page.ledgerRevision.toString()
            if (!page.more) break
            val next = page.cursor ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a page with more and no cursor")
            if (next == since) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a cursor that did not advance")
            since = next
        }
        return me.raddatz.veyq.mobile.session.VeyqAccount.Fetched.Pages(out, revision)
    }

    private fun served(it: PotRevision) = ServedRevision(
        it.entryId, it.revision, SealedRevision(unb64(it.sealedBody), unb64(it.wrappedRevisionKey)), PotSession.epochMs(it.createdAt), it.writer,
        it.purgeAfter?.let(PotSession::epochMs),
    )

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
        override suspend fun download(node: OpenNode, b: ApplicationBlobs.Listed, keep: Boolean) = blobs.download(node.scopeId, node, b, keep)
        override suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, bytes: ByteArray, type: String, name: String?, modifiedAt: Long?,
        ) {
            blobs.upload(
                node().scopeId, node, reopen, { belowFloor(node()) }, id, entryId, Receipts.ROLE, bytes, type, name, modifiedAt,
                pull = { pull(node()) },
            )
        }

        override suspend fun upload(
            node: () -> OpenNode, reopen: suspend () -> Boolean, id: String, entryId: String, type: String, name: String?,
            modifiedAt: Long?, sealed: ApplicationBlobs.Sealed,
        ) {
            blobs.upload(
                node().scopeId, node, reopen, { belowFloor(node()) }, id, entryId, Receipts.ROLE, ByteArray(0), type, name, modifiedAt,
                pull = { pull(node()) }, sealed = sealed,
            )
        }
    }
}
