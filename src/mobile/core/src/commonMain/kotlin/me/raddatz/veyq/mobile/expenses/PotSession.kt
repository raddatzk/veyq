package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest
import me.raddatz.veyq.mobile.api.expenses.models.PotRevisionWrite
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * One Pot opened by a member, a port of the read and the add of `src/web/src/features/expenses/potSession.ts`: its
 * settings from the node's sealed meta, its revisions read page by page and merged by id, each opened at the address it
 * was read by, and the ledger over them. Expenses is an application: it uses core's session, never the reverse.
 */
class PotSession private constructor(private val tenant: TenantSession, private val node: OpenNode) {
    private val account = tenant.account
    private val codec = RevisionCodec(account.suite)
    private val api get() = ExpensesApi(tenant.base, account.http)

    /** The Pot's settings, or null when its meta carries none that reads (no balances, no money entries then). */
    val meta: PotMeta? = openMeta()

    val name: String get() = node.name

    private val held = LinkedHashMap<String, HeldRevision>()
    private var ledgerRevision: Int? = null
    private var deleteRevision = 0
    private var cursor: String? = null

    /** The ledger over what is held, or null without settings. */
    var ledger: Ledger.Pot? = null
        private set

    /** Whether this member may write here: Expenses is on for the tenant (a space pending deletion is not opened). */
    val canWrite: Boolean get() = meta != null && "expenses" !in tenant.applicationsOff()

    private fun openMeta(): PotMeta? {
        val wrapped = node.wrappedMeta ?: return null
        return runCatching {
            val plain = account.suite.openField(Context("node-meta", "scopeId" to node.scopeId, "nodeId" to node.id), node.key, unb64(wrapped))
            PotMeta.of(Json.parseToJsonElement(plain.decodeToString()) as JsonObject)
        }.getOrNull()
    }

    /**
     * Reads what changed since the last read (the first one reads everything): the first page conditional on the held
     * ledger revision, later pages by cursor; a page after an erasure names the revisions still held. True when anything
     * moved.
     */
    suspend fun refresh(): Boolean {
        var since = cursor
        var first = true
        var firstLedger = ledgerRevision
        var firstDelete = deleteRevision
        val rows = mutableListOf<ServedRevision>()
        var keep: Set<String>? = null
        while (true) {
            val r = account.respond {
                api.listPotRevisions(
                    node.scopeId, node.id, ifNoneMatch = if (first) ledgerRevision?.toString() else null, since = since,
                    sinceDelete = if (first) deleteRevision else firstDelete, limit = PAGE,
                )
            }
            if (r.status == 304) {
                if (!first) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "304 on a later page")
                return false
            }
            if (!r.success) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "pot revisions ${r.status}")
            val page = r.body()
            if (first) {
                firstLedger = page.ledgerRevision
                firstDelete = page.deleteRevision
                keep = page.revisionIds?.map { "${it.entryId}:${it.revision}" }?.toSet()
            }
            rows += page.revisions.map {
                ServedRevision(it.entryId, it.revision, SealedRevision(unb64(it.sealedBody), unb64(it.wrappedRevisionKey)), epochMs(it.createdAt), it.writer)
            }
            if (!page.more) {
                cursor = page.cursor ?: since
                break
            }
            val next = page.cursor ?: throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a page with more and no cursor")
            if (next == since) throw SignInFailure(SignInFailure.Reason.PROTOCOL, "a cursor that did not advance")
            since = next
            first = false
        }
        keep?.let { ids -> held.keys.retainAll(ids) }
        // A re-served id (a re-key's new wrap) replaces the held row.
        Revisions.open(codec, node.scopeId, node.id, node.key, rows).forEach { held[it.id] = it }
        ledgerRevision = firstLedger
        deleteRevision = firstDelete
        ledger = meta?.let { Ledger.build(held.values.toList(), it.base) }
        return true
    }

    /** What adding an entry came to. */
    enum class AddOutcome {
        ADDED,
        /** Its sums do not add up, or it names someone who is no participant: nothing was sent. */
        INVALID,
        /** Another device wrote first: the Pot was read again, the entry may be added again. */
        STALE,
        /** The server refused it (switched off, the Pot re-keyed meanwhile, blocked): nothing was stored. */
        REFUSED,
    }

    /**
     * Adds a plain expense in the Pot's base currency: [amount] minor units paid by [payer], split equally among
     * [beneficiaries], at [date] (`YYYY-MM-DD`, the person's local day). Its heads name the Pot's current heads; it is
     * sealed for the address it is posted to, revision 1 of a fresh record.
     */
    suspend fun addExpense(title: String, amount: Long, payer: String, beneficiaries: List<String>, date: String, category: String): AddOutcome {
        val base = meta?.base ?: return AddOutcome.INVALID
        val pot = ledger ?: return AddOutcome.INVALID
        val order = pot.participants.map { it.id }
        val split = Split.Equal(order.filter { it in beneficiaries })
        val content = Expense(
            title.trim(), category, date, base.currency, base.minorUnits, "1", amount, emptyList(),
            listOf(Contribution(payer, amount)), Allocation(emptyList(), split.takeIf { it.participants.isNotEmpty() }),
        )
        val known = order.toSet()
        if (Ledger.entryProblem(content) { it in known } != null) return AddOutcome.INVALID
        val entryId = uuid()
        val body = RevisionBody(Revisions.headsToName(held.values.toList()), content)
        val sealed = try {
            codec.seal(RevisionAddress(node.scopeId, node.id, entryId, 1), node.key, body)
        } catch (e: Exception) {
            return AddOutcome.INVALID
        }
        val write = PotRevisionWrite(entryId, 1, false, b64(sealed.sealedBody), b64(sealed.wrappedRevisionKey), null)
        val r = account.respond { api.appendPotRevisions(node.scopeId, node.id, PotAppendRequest(node.epoch, listOf(write))) }
        if (r.success) {
            refresh()
            return AddOutcome.ADDED
        }
        val code = runCatching { (Json.parseToJsonElement(r.response.bodyAsText()) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
        if (r.status == 409 && code == "stale_revision") {
            refresh()
            return AddOutcome.STALE
        }
        return AddOutcome.REFUSED
    }

    private fun uuid(): String {
        val b = account.primitives.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    companion object {
        private const val PAGE = 1000

        /** Opens the Pot [nodeId] of [scopeId] in [tenant] and reads it; null when the tenant holds no such open Pot. */
        suspend fun open(tenant: TenantSession, scopeId: String, nodeId: String): PotSession? {
            val node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId && it.kind == "EXPENSES_POT" } ?: return null
            return PotSession(tenant, node).also { it.refresh() }
        }

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun epochMs(iso: String): Long = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0)
    }
}
