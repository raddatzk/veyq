package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.raddatz.veyq.mobile.export.ImportRefused
import me.raddatz.veyq.mobile.export.Manifest
import me.raddatz.veyq.mobile.export.NodeImport
import me.raddatz.veyq.mobile.session.NodeTrash

/**
 * Expenses' part in an import (ADR 0152 decision 3), a port of the web's `features/expenses/exportImport.ts`
 * `validatePotContent` and `importPot`: the content is held to its contract before anything is written; then a new Pot
 * with the archive's settings and own categories, its people and the current revision of each entry under ids minted anew
 * with every reference mapped ([PotCopy.remap]), each receipt uploaded under a new id BEFORE the batch whose revision lists
 * it. A member stays one only in the tenant its id belongs to (`tenantId`); anywhere else the participant is the name sealed
 * with it. An erased person comes back as a deactivated one under [deletedName]. A Pot that fails part way is trashed.
 */
object PotImport {
    private const val MAX_PARTICIPANTS = 1000
    private const val MAX_ENTRIES = 100_000
    private const val MAX_RECEIPT_NAME = 1024
    private const val MAX_PERSON_NAME = 100

    private fun bad(message: String) = NodeImport.Problem(ImportRefused.Refusal.INVALID_MANIFEST, message)
    private fun tooLarge(message: String) = NodeImport.Problem(ImportRefused.Refusal.TOO_LARGE, message)
    private fun str(v: kotlinx.serialization.json.JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun bool(v: kotlinx.serialization.json.JsonElement?): Boolean? =
        (v as? JsonPrimitive)?.takeIf { !it.isString && (it.content == "true" || it.content == "false") }?.content?.toBooleanStrict()
    private fun int(v: kotlinx.serialization.json.JsonElement?): Long? =
        Manifest.number(v)?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) <= RevisionFormat.MAX_SAFE }?.toLong()
    private fun baseName(path: String) = path.substringAfterLast('/')

    /** An entry's revision body as the import reads it: the archive's entry with what an import writes anew (`v`, `heads`). */
    private fun body(entry: kotlinx.serialization.json.JsonElement?): RevisionBody =
        RevisionFormat.read(JsonObject((entry as? JsonObject).orEmpty() + mapOf("v" to JsonPrimitive(1), "heads" to JsonArray(emptyList()))))

    /** An `expenses.pot` content from a download held to its contract (decision 10: the archive is untrusted input). */
    fun validate(content: JsonObject): NodeImport.Problem? {
        val type = str(content["type"])
        if (type != PotExport.TYPE) return bad("not an Expenses Pot: $type")
        if (str(content["baseCurrency"])?.let(RevisionFormat.CURRENCY::matches) != true) return bad("a Pot without a base currency")
        val mu = int(content["baseMinorUnits"])
        if (mu == null || mu !in 0..4) return bad("a Pot without its minor units")
        content["categories"]?.let { c ->
            val list = c as? JsonArray ?: return bad("categories that are no list")
            if (list.size > PotMeta.MAX_OWN) return tooLarge("too many categories")
            for (x in list) {
                val o = x as? JsonObject ?: return bad("a category without an id")
                if (str(o["id"])?.let(RevisionFormat.UUID::matches) != true) return bad("a category without an id")
                val name = str(o["name"])
                if (name == null || name.isEmpty()) return bad("a category without a name")
                if (RevisionFormat.codePoints(name) > PotMeta.MAX_NAME) return tooLarge("a category name too long")
            }
        }
        content["tenantId"]?.let { if (str(it)?.let(RevisionFormat.UUID::matches) != true) return bad("a tenant that is no id") }
        val people = content["participants"] as? JsonArray ?: return bad("a Pot without its people")
        if (people.size > MAX_PARTICIPANTS) return tooLarge("too many people")
        val ids = HashSet<String>()
        val members = HashSet<String>()
        for (x in people) {
            val p = x as? JsonObject ?: return bad("a person without an id of its own")
            val id = str(p["id"])?.takeIf(RevisionFormat.UUID::matches)
            if (id == null || !ids.add(id)) return bad("a person without an id of its own")
            val joined = int(p["joined"])
            if (joined == null || joined < 0) return bad("a person without a join time")
            val deleted = p["deleted"]
            if (deleted != null && bool(deleted) != true) return bad("a person deleted in another way")
            val name = p["name"]
            if (deleted == null && (str(name) == null || str(name)!!.isEmpty())) return bad("a person without a name")
            if (str(name)?.let { RevisionFormat.codePoints(it) > MAX_PERSON_NAME } == true) return tooLarge("a name too long")
            if (p["active"] != null && bool(p["active"]) == null) return bad("a person whose state is no switch")
            p["memberId"]?.let { m ->
                val member = str(m)?.takeIf(RevisionFormat.UUID::matches) ?: return bad("a member that is no id")
                if (deleted != null) return bad("a deleted person who is a member")
                if (!members.add(member)) return bad("a member who is two people")
            }
        }
        if (members.isNotEmpty() && content["tenantId"] == null) return bad("members without their tenant")
        val entries = content["entries"] as? JsonArray ?: return bad("a Pot without its entries")
        if (entries.size > MAX_ENTRIES) return tooLarge("too many entries")
        val receiptIds = HashSet<String>()
        for (x in entries) {
            val e = x as? JsonObject ?: return bad("an entry without an id")
            if (str(e["id"])?.let(RevisionFormat.UUID::matches) != true) return bad("an entry without an id")
            val b = try {
                body(e["entry"])
            } catch (f: RevisionFormatInvalid) {
                return bad("an entry not in the format: ${f.path}")
            }
            val c = b.content as? MoneyContent ?: return bad("an entry that is no money entry")
            if (Ledger.namedParticipants(c).any { it !in ids }) return bad("an entry naming someone the Pot does not have")
            receiptsProblem(e["receipts"], c.receipts, receiptIds)?.let { return it }
        }
        return null
    }

    /** What is wrong with an entry's carried receipts against the ids its revision lists, or null (the web's `receiptsProblem`). */
    private fun receiptsProblem(carried: kotlinx.serialization.json.JsonElement?, listed: List<String>, seen: MutableSet<String>): NodeImport.Problem? {
        if (carried != null && carried !is JsonArray) return bad("receipts that are no list")
        val items = (carried as? JsonArray).orEmpty()
        if (items.size > RevisionFormat.MAX_RECEIPTS) return tooLarge("too many receipts on one entry")
        val own = HashSet<String>()
        for (x in items) {
            val r = x as? JsonObject ?: return bad("a receipt without an id")
            val id = str(r["id"])?.takeIf(RevisionFormat.UUID::matches) ?: return bad("a receipt without an id")
            if (!seen.add(id)) return bad("a receipt carried twice")
            own += id
            val f = r["file"] as? JsonObject
            val size = f?.let { int(it["size"]) }
            if (f == null || str(f["path"]) == null || str(f["mediaType"]) == null || size == null || size < 0) return bad("a receipt without its file")
            val name = r["name"]
            if (name != null && (str(name) == null || str(name)!!.isEmpty())) return bad("a receipt whose name is no name")
            if (str(name)?.let { RevisionFormat.codePoints(it) > MAX_RECEIPT_NAME } == true) return tooLarge("a receipt name too long")
            if (size > Receipts.MAX_BYTES) return tooLarge("a receipt above 10 MB")
            if (!Receipts.claims(str(f["mediaType"]), str(name) ?: baseName(str(f["path"])!!))) return bad("a receipt that is no image or PDF")
        }
        if (own.size != listed.size || listed.any { it !in own }) return bad("an entry whose receipts are not the ones it lists")
        return null
    }

    /** One receipt to upload into the new Pot: the new id, its payload reference and its sealed name. */
    private class Carried(val newId: String, val file: Manifest.Payload, val name: String?)

    /** Expenses' hook: [deletedName] is what an erased person is called; [uuid] mints the new ids. */
    fun hook(deletedName: String, uuid: () -> String) = NodeImport.Hook(PotExport.TYPE.substringBefore('.'), PotExport.SCHEMA, ::validate) { ctx ->
        val content = ctx.node.content ?: throw NodeImport.Failed("import_invalid_content")
        validate(content)?.let { throw NodeImport.Failed("import_invalid_content: ${it.message}") }
        val tenant = ctx.session()
        val ids = HashMap<String, String>()
        val id = { old: String -> ids.getOrPut(old, uuid) }
        // a receipt's ids in a map of their own: an archive's receipt id equal to one of its record ids still gets one of its own
        val receiptIds = HashMap<String, String>()
        val receiptId = { old: String -> receiptIds.getOrPut(old, uuid) }
        val categories = (content["categories"] as? JsonArray).orEmpty().map { it as JsonObject }.map { id(str(it["id"])!!) to str(it["name"])!! }
        val sameTenant = str(content["tenantId"])?.let { it == tenant.tenant.tenantId } == true
        val people = (content["participants"] as JsonArray).map { it as JsonObject }.map { p ->
            val deleted = p["deleted"] != null
            PotWrite(
                id(str(p["id"])!!),
                if (deleted) ParticipantBody(deletedName, false, int(p["joined"])!!)
                else ParticipantBody(str(p["name"])!!, bool(p["active"]) != false, int(p["joined"])!!, str(p["memberId"])?.takeIf { sameTenant }),
            )
        }
        val entries = (content["entries"] as JsonArray).map { it as JsonObject }
        // entry ids are minted first, so a refund naming an expense maps to that expense's new id
        for (e in entries) id(str(e["id"])!!)
        val uploads = HashMap<String, List<Carried>>()
        val money = entries.map { e ->
            val c = body(e["entry"]).content as MoneyContent
            val newEntry = id(str(e["id"])!!)
            val carried = (e["receipts"] as? JsonArray).orEmpty().map { it as JsonObject }.map { r ->
                Carried(receiptId(str(r["id"])!!), Manifest.payloadOf(r["file"] as JsonObject), str(r["name"]))
            }
            if (carried.isNotEmpty()) uploads[newEntry] = carried
            PotWrite(newEntry, PotCopy.remap(c, id, receiptId, c.receipts))
        }
        val base = Ledger.BaseCurrency(str(content["baseCurrency"])!!, int(content["baseMinorUnits"])!!.toInt())
        val total = uploads.values.flatten().sumOf { it.file.size }
        val made = PotCreate.create(tenant, ctx.scopeId, ctx.parentId, ctx.name, base, emptyList(), categories)
        when (made.outcome) {
            PotCreate.Outcome.CREATED -> {}
            PotCreate.Outcome.NAME_TAKEN -> return@Hook NodeImport.Made(null, nameTaken = true)
            else -> return@Hook NodeImport.Made(null, failure = made.outcome.name.lowercase())
        }
        val nodeId = made.nodeId!!
        try {
            tenant.forgetTree()
            val pot = PotSession.open(tenant, ctx.scopeId, nodeId) ?: throw NodeImport.Failed("pot_unreadable")
            var done = 0L
            for (batch in (people + money).chunked(PotCreate.BATCH)) {
                for (w in batch) for (r in uploads[w.entryId].orEmpty()) {
                    val checked = ctx.payload(r.file)
                    val bytes = checked.read(0, r.file.size.toInt())
                    if (bytes.size.toLong() != r.file.size) throw NodeImport.Failed("receipt_unreadable")
                    pot.uploadReceipt(w.entryId, r.newId, bytes, r.file.mediaType, r.name ?: baseName(r.file.path), r.file.modifiedAt)
                    done += r.file.size
                    if (total > 0) ctx.progress(done.toDouble() / total)
                }
                if (batch.any { uploads.containsKey(it.entryId) }) pot.readReceipts()
                val written = pot.write(batch)
                if (written != PotSession.WriteOutcome.WRITTEN) throw NodeImport.Failed("pot_${written.name.lowercase()}")
            }
        } catch (e: Throwable) {
            // the Pot holds only part of what it imports: none is left behind (best effort; core trashes what holds it)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { NodeTrash.delete(tenant, ctx.scopeId, nodeId) } }
            throw e
        }
        NodeImport.Made(nodeId)
    }
}
