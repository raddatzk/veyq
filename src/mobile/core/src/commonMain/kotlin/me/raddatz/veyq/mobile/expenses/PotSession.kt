package me.raddatz.veyq.mobile.expenses

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.expenses.apis.ExpensesApi
import me.raddatz.veyq.mobile.api.expenses.models.PotAppendRequest
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.hex
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.api.infrastructure.HttpResponse
import me.raddatz.veyq.mobile.session.NodeEpochWrite
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.SignInFailure
import me.raddatz.veyq.mobile.session.TenantSession

/**
 * One Pot opened by a member, a port of `src/web/src/features/expenses/potSession.ts`: its settings from the node's
 * sealed meta, its revisions read page by page and merged by id, each opened at the address it was read by, the ledger
 * and the ledger check over them, and every write. Expenses is an application: it uses core's session, never the reverse.
 */
class PotSession private constructor(private val tenant: TenantSession, private var node: OpenNode) {
    private val account = tenant.account
    private val codec = RevisionCodec(account.suite)
    private val api get() = ExpensesApi(tenant.base, account.http)

    /** The Pot's settings, or null when its meta carries none that reads (no balances, no money entries then). */
    var meta: PotMeta? = openMeta()
        private set

    val name: String get() = node.name

    private val held = LinkedHashMap<String, HeldRevision>()
    private var ledgerRevision: Int? = null
    private var deleteRevision = 0
    private var cursor: String? = null

    /** The ledger over what is held, or null without settings. */
    var ledger: Ledger.Pot? = null
        private set

    /** What the last read found wrong with the ledger (ADR 0108 decision 5); complete for an empty Pot. */
    var check: LedgerCheck = LedgerCheck.of(emptyList())
        private set

    /** Whether this member may write here: Expenses is on for the tenant (a space pending deletion is not opened). */
    val canWrite: Boolean get() = meta != null && "expenses" !in tenant.applicationsOff()

    /** The waits before each retry while the Pot is not re-keyed since a rotation (`REKEY_RETRY_DELAYS_MS`). */
    internal var rekeyWaitsMs: List<Long> = NodeEpochWrite.REKEY_WAITS_MS

    private fun openMeta(): PotMeta? {
        val wrapped = node.wrappedMeta ?: return null
        return runCatching {
            val plain = account.suite.openField(Context("node-meta", "scopeId" to node.scopeId, "nodeId" to node.id), node.key, unb64(wrapped))
            PotMeta.of(Json.parseToJsonElement(plain.decodeToString()) as JsonObject)
        }.getOrNull()
    }

    /** What is held, for a pure rule over it. */
    internal fun heldRevisions(): List<HeldRevision> = held.values.toList()

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
        val all = held.values.toList()
        ledger = meta?.let { Ledger.build(all, it.base) }
        check = LedgerCheck.of(all)
        return true
    }

    /** What a write came to. */
    enum class WriteOutcome {
        WRITTEN,
        /** Its sums do not add up, it names someone who is no participant, or it breaks another rule: nothing was sent. */
        INVALID,
        /** A person with a balance cannot be deactivated or deleted: nothing was sent. */
        NOT_SETTLED,
        /** Another device wrote first: the Pot was read again, the write may be made again on what it shows now. */
        STALE,
        /** The Pot is not re-keyed since its space's key changed, and still was not after waiting: try again later. */
        BUSY,
        /** The operator blocked the Pot. */
        BLOCKED,
        /** The server refused it otherwise (switched off, removed, an id taken): nothing was stored. */
        REFUSED,
    }

    /**
     * Appends [writes] in one batch, all or none ([PotWrites.seal] on what is held), then reads again. A `409
     * stale_revision` re-reads and answers [WriteOutcome.STALE]; the rest is [atNodeEpoch]'s.
     */
    suspend fun write(writes: List<PotWrite>): WriteOutcome {
        val base = meta?.base ?: return WriteOutcome.INVALID
        return atNodeEpoch { n ->
            val revisions = PotWrites.seal(codec, n.scopeId, n.id, n.key, held.values.toList(), writes, base)
            suspend { api.appendPotRevisions(n.scopeId, n.id, PotAppendRequest(n.epoch, revisions)) }
        }
    }

    /** Deletes or removes participant [participantId] by its sealed erasure (members only; settled people only). */
    suspend fun erase(participantId: String): WriteOutcome {
        val base = meta?.base ?: return WriteOutcome.INVALID
        return atNodeEpoch { n ->
            val request = PotWrites.erasure(codec, n.scopeId, n.id, n.key, n.epoch, held.values.toList(), participantId, base)
            suspend { api.erasePotRecord(n.scopeId, n.id, request) }
        }
    }

    /**
     * A write at the Pot's epoch through core's [NodeEpochWrite] (the floor, `node_rekey_pending` and `stale_epoch`
     * handled there); [prepare] seals on the node it is handed, so an attempt after a re-read seals under its key now.
     */
    private suspend fun atNodeEpoch(prepare: (OpenNode) -> (suspend () -> HttpResponse<Unit>)): WriteOutcome {
        val result = try {
            NodeEpochWrite.run(
                waitsMs = rekeyWaitsMs,
                belowFloor = { node.scopeEpoch < (tenant.scopeEpoch(node.scopeId) ?: Int.MAX_VALUE) },
                reread = ::reopen,
                attempt = {
                    val send = prepare(node)
                    val r = account.respond { send() }
                    val code = if (r.success) null else codeOf(r)
                    val answer = when {
                        r.success -> NodeEpochWrite.Answer.DONE
                        r.status == 409 && code == NODE_REKEY_PENDING -> NodeEpochWrite.Answer.REKEY_PENDING
                        r.status == 409 && code == STALE_EPOCH -> NodeEpochWrite.Answer.STALE_EPOCH
                        else -> NodeEpochWrite.Answer.OTHER
                    }
                    answer to (r.status to code)
                },
            )
        } catch (e: PotWriteRefused) {
            return if (e.reason == PotWriteRefused.Reason.NOT_SETTLED) WriteOutcome.NOT_SETTLED else WriteOutcome.INVALID
        } catch (e: RevisionFormatInvalid) {
            return WriteOutcome.INVALID
        } catch (e: IllegalArgumentException) {
            return WriteOutcome.INVALID
        }
        return when (result) {
            NodeEpochWrite.Result.Busy -> WriteOutcome.BUSY
            NodeEpochWrite.Result.Gone, NodeEpochWrite.Result.StaleAgain -> WriteOutcome.REFUSED
            is NodeEpochWrite.Result.Answered -> {
                val (status, code) = result.value
                when {
                    status in 200..299 -> {
                        refresh()
                        WriteOutcome.WRITTEN
                    }
                    status == 409 && code == STALE_REVISION -> {
                        runCatching { refresh() }
                        WriteOutcome.STALE
                    }
                    status == 451 -> WriteOutcome.BLOCKED
                    else -> WriteOutcome.REFUSED
                }
            }
        }
    }

    /**
     * Reads the tree again and takes the Pot's node as it is now (its key and epoch may have moved), its settings with it,
     * and everything held read anew under that key. False when the Pot is no longer there to write to.
     */
    private suspend fun reopen(): Boolean {
        tenant.forgetTree()
        node = tenant.trees()[node.scopeId]?.firstOrNull { it.id == node.id && it.kind == KIND } ?: return false
        meta = openMeta()
        held.clear()
        ledgerRevision = null
        deleteRevision = 0
        cursor = null
        refresh()
        return true
    }

    /**
     * Adds a plain expense in the Pot's base currency: [amount] minor units paid by [payer], split equally among
     * [beneficiaries], at [date] (`YYYY-MM-DD`, the person's local day), as revision 1 of a fresh record.
     */
    suspend fun addExpense(title: String, amount: Long, payer: String, beneficiaries: List<String>, date: String, category: String): WriteOutcome {
        val base = meta?.base ?: return WriteOutcome.INVALID
        val order = ledger?.participants?.map { it.id } ?: return WriteOutcome.INVALID
        val split = Split.Equal(order.filter { it in beneficiaries })
        if (split.participants.isEmpty()) return WriteOutcome.INVALID
        val content = Expense(
            title.trim(), category, date, base.currency, base.minorUnits, "1", amount, emptyList(),
            listOf(Contribution(payer, amount)), Allocation(emptyList(), split),
        )
        return write(listOf(PotWrite(newId(), content)))
    }

    /** A fresh record id (a UUID v4), for an entry or a person this device adds. */
    fun newId(): String {
        val b = account.primitives.randomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    companion object {
        private const val PAGE = 1000
        const val KIND = "EXPENSES_POT"
        private const val NODE_REKEY_PENDING = "node_rekey_pending"
        private const val STALE_EPOCH = "stale_epoch"
        private const val STALE_REVISION = "stale_revision"

        /** Opens the Pot [nodeId] of [scopeId] in [tenant] and reads it; null when the tenant holds no such open Pot. */
        suspend fun open(tenant: TenantSession, scopeId: String, nodeId: String): PotSession? {
            val node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId && it.kind == KIND } ?: return null
            return PotSession(tenant, node).also { it.refresh() }
        }

        private suspend fun codeOf(r: HttpResponse<*>): String? = runCatching {
            (Json.parseToJsonElement(r.response.bodyAsText()) as JsonObject)["code"]?.jsonPrimitive?.content
        }.getOrNull()

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun epochMs(iso: String): Long = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0)
    }
}
