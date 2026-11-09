package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.raddatz.veyq.mobile.export.NodeExport
import me.raddatz.veyq.mobile.session.VeyqAccount

/**
 * Expenses' side of a download, a port of the web's `features/expenses/exportImport.ts` `exportPot` (ADR 0082,
 * docs/apps/expenses.md § Download and import): a Pot's content is its settings, its people and the current revision of each
 * live entry, in the plaintext of the entry format (`pot` in `contracts/export/expenses/content.schema.json`), each entry with
 * the receipts that revision holds as payloads, and a CSV of the entries beside it for a spreadsheet. History, deleted entries
 * and dropped or blocked receipts are not exported. A participant whose name was erased exports as `deleted: true`, and only
 * while an exported entry still names it; a member's id travels with the tenant it means a person in (`tenantId`).
 */
object PotExport {
    const val TYPE = "expenses.pot"
    /** The content-schema version this build writes (the web's `EXPENSES_CONTENT_VERSION`). */
    const val SCHEMA = 1
    /** The longest receipt name the content carries (the schema's `receipt.name` maxLength, in code points). */
    private const val MAX_RECEIPT_NAME = 1024

    /**
     * The content of [session]'s Pot for a download, or null when the Pot has no settings to export in. Reads the receipt
     * listing where a live entry holds one; a receipt's bytes are read only when the archive is written. [deletedName] is
     * what the CSV calls an erased person (the web's "Deleted people").
     */
    suspend fun content(session: PotSession, deletedName: String): NodeExport.Content? {
        val meta = session.meta ?: return null
        val ledger = session.ledger ?: return null
        val live = ledger.entries.filter { it.status == Ledger.Status.LIVE && it.content != null }
        if (live.any { it.content!!.receipts.isNotEmpty() }) session.readReceipts()
        val listed = session.receipts.associateBy { it.receiptId }

        val payloads = mutableListOf<NodeExport.Payload>()
        class Carried(val id: String, val name: String?, val payload: Int)
        class Exported(val id: String, val entry: MoneyContent, val receipts: List<Carried>)
        val entries = live.map { e ->
            val c = e.content!!
            val carried = c.receipts.mapNotNull { id ->
                val r = listed[id]?.takeIf { it.entryId == e.id && !it.dropped && !it.blocked } ?: return@mapNotNull null
                payloads += NodeExport.Payload.whole(Receipts.downloadName(r.name, r.type), r.size, r.type.orEmpty(), r.modifiedAt) {
                    session.openReceipt(id) ?: throw IllegalStateException("receipt $id did not open")
                }
                val name = r.name?.takeIf { it.isNotEmpty() && RevisionFormat.codePoints(it) <= MAX_RECEIPT_NAME }
                Carried(id, name, payloads.size - 1)
            }
            Exported(e.id, withReceipts(c, carried.map { it.id }), carried)
        }
        val named = entries.flatMap { Ledger.namedParticipants(it.entry) }.toSet()
        val participants = ledger.participants.filter { !it.erased || it.id in named }
        val carriesMembers = participants.any { !it.erased && it.member != null }

        val base = ledger.entries.associate { it.id to (it.effect?.base ?: Big.ZERO) }
        val people = ledger.participants.associate { it.id to (it.name ?: deletedName) }
        val csv = entriesCsv(entries.map { it.entry to (base[it.id] ?: Big.ZERO) }, people, meta.base.minorUnits).encodeToByteArray()
        payloads += NodeExport.Payload.whole("${session.name}.csv", csv.size.toLong(), "text/csv") { csv }
        val table = payloads.size - 1

        return NodeExport.Content(TYPE, SCHEMA, payloads) { refs ->
            buildJsonObject {
                put("type", TYPE)
                put("schema", SCHEMA)
                put("baseCurrency", meta.base.currency)
                put("baseMinorUnits", meta.base.minorUnits)
                if (meta.categories.isNotEmpty()) {
                    put("categories", JsonArray(meta.categories.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
                }
                if (carriesMembers) put("tenantId", session.tenantId)
                put("participants", JsonArray(participants.map { p ->
                    buildJsonObject {
                        put("id", p.id); put("joined", p.joined)
                        if (p.erased) put("deleted", true)
                        else {
                            put("name", p.name); put("active", p.active)
                            p.member?.let { put("memberId", it) }
                        }
                    }
                }))
                put("entries", JsonArray(entries.map { e ->
                    buildJsonObject {
                        put("id", e.id)
                        put("entry", entryJson(e.entry))
                        if (e.receipts.isNotEmpty()) put("receipts", JsonArray(e.receipts.map { r ->
                            buildJsonObject { put("id", r.id); r.name?.let { put("name", it) }; put("file", refs[r.payload]) }
                        }))
                    }
                }))
                put("table", refs.getOrNull(table) ?: JsonNull)
            }
        }
    }

    /**
     * Downloads [session]'s Pot into [sink] as the web's ZIP (its content, then the archive), or null when the Pot has no
     * settings to export in. Each receipt is read when the archive reaches it; a failure fails the archive, and whatever
     * [sink] received is then no download (the caller discards it).
     */
    suspend fun download(session: PotSession, deletedName: String, nowMs: Long, sink: (ByteArray) -> Unit): NodeExport.Written? {
        val content = content(session, deletedName) ?: return null
        return NodeExport.write(session.name, PotSession.KIND, content, VeyqAccount.MAJOR.toString(), nowMs, sink)
    }

    /** An entry's revision without what an import writes anew (`v`, `heads`). */
    internal fun entryJson(c: MoneyContent): JsonObject =
        JsonObject(RevisionFormat.toJson(RevisionBody(emptyList(), c)).filterKeys { it != "v" && it != "heads" })

    private fun withReceipts(c: MoneyContent, ids: List<String>): MoneyContent = when (c) {
        is Expense -> c.copy(receipts = ids)
        is Refund -> c.copy(receipts = ids)
        is Transfer -> c.copy(receipts = ids)
    }

    /** [amount] minor units as a plain decimal ("-12.50"), the web's `decimalOf`. */
    internal fun decimalOf(amount: String, minorUnits: Int): String {
        val negative = amount.startsWith("-")
        val digits = amount.removePrefix("-").padStart(minorUnits + 1, '0')
        val whole = if (minorUnits == 0) digits else digits.dropLast(minorUnits)
        val frac = if (minorUnits == 0) "" else "." + digits.takeLast(minorUnits)
        return (if (negative) "-" else "") + whole + frac
    }

    private fun csvCell(s: String) = if (Regex("[\",\n\r]").containsMatchIn(s)) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** The entries as a spreadsheet reads them, the web's `entriesCsv` to the byte: one line per entry, CRLF. */
    internal fun entriesCsv(entries: List<Pair<MoneyContent, Big>>, people: Map<String, String>, baseMinorUnits: Int): String {
        val name = { id: String -> people[id].orEmpty() }
        val lines = mutableListOf(listOf("date", "kind", "title", "category", "amount", "currency", "rate", "base amount", "paid by", "from", "to").joinToString(","))
        for ((c, base) in entries) {
            val who = when (c) { is Expense -> c.payers; is Refund -> c.receivers; is Transfer -> emptyList() }
            lines += listOf(
                c.date,
                when (c) { is Expense -> "expense"; is Refund -> "refund"; is Transfer -> "transfer" },
                when (c) { is Expense -> c.title; is Refund -> c.title; is Transfer -> c.title.orEmpty() },
                when (c) { is Expense -> c.category; is Refund -> c.category; is Transfer -> "" },
                decimalOf(c.amount.toString(), c.minorUnits),
                c.currency,
                c.rate,
                decimalOf(base.toString(), baseMinorUnits),
                who.joinToString("; ") { name(it.participant) },
                if (c is Transfer) name(c.from) else "",
                if (c is Transfer) name(c.to) else "",
            ).joinToString(",", transform = ::csvCell)
        }
        return lines.joinToString("\r\n") + "\r\n"
    }
}
