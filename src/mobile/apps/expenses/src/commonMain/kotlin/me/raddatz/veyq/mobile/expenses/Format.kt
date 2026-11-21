package me.raddatz.veyq.mobile.expenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The plaintext of one Expenses revision, format v1, a port of `src/web/src/features/expenses/domain/format.ts`;
// `contracts/expenses/entry.v1.schema.json` is the source of truth (ADR 0108 decisions 3-5). Readers drop unknown fields
// and refuse any other `v`; the JSON's key order carries no meaning (a reader parses, never compares).

/** One revision the Pot's heads name: its address and the SHA-256 of its sealed body (base64url, no padding). */
data class Head(val entryId: String, val revision: Long, val digest: String)

data class Contribution(val participant: String, val amount: Long)

/** Who one part of an entry is for. */
sealed interface Split {
    data class Equal(val participants: List<String>) : Split
    data class Shares(val shares: List<Pair<String, Long>>) : Split
    /** Basis points per person, 10000 = 100.00 %. */
    data class Percent(val percents: List<Pair<String, Long>>) : Split
    /** A fixed amount each, in the entry's currency. */
    data class Amounts(val amounts: List<Pair<String, Long>>) : Split
}

data class Item(val label: String, val amount: Long, val split: Split)

/** The items, and the rest (`amount - sum(items)`) by a split of its own — present while the rest is above zero. */
data class Allocation(val items: List<Item>, val rest: Split? = null)

/** What a revision says, without the `v` and `heads` every body carries. */
sealed interface RevisionContent

/** An expense, a refund or a transfer. */
sealed interface MoneyContent : RevisionContent {
    val date: String
    val currency: String
    val minorUnits: Int
    val rate: String
    val amount: Long
    val receipts: List<String>
}

data class Expense(
    val title: String, val category: String, override val date: String, override val currency: String, override val minorUnits: Int,
    override val rate: String, override val amount: Long, override val receipts: List<String>,
    val payers: List<Contribution>, val allocation: Allocation,
) : MoneyContent

data class Refund(
    val title: String, val category: String, override val date: String, override val currency: String, override val minorUnits: Int,
    override val rate: String, override val amount: Long, override val receipts: List<String>,
    val receivers: List<Contribution>, val allocation: Allocation, val refunds: String? = null,
) : MoneyContent

data class Transfer(
    val title: String?, override val date: String, override val currency: String, override val minorUnits: Int,
    override val rate: String, override val amount: Long, override val receipts: List<String>,
    val from: String, val to: String,
) : MoneyContent

/** A person of the Pot: a name, or a member ([member]: their user id) with the name sealed at adding. */
data class ParticipantBody(val name: String, val active: Boolean, val joined: Long, val member: String? = null) : RevisionContent

data object Deleted : RevisionContent

data class Erasure(val joined: Long) : RevisionContent

/** One revision's whole plaintext. */
data class RevisionBody(val heads: List<Head>, val content: RevisionContent)

/** Why a body is not a v1 revision: the first rule it breaks, by the schema's path. */
class RevisionFormatInvalid(val path: String) : Exception("revision_format_invalid: $path")

object RevisionFormat {
    const val VERSION = 1L

    /** The default categories: stable ids the client translates. A Pot's own are uuids. */
    val DEFAULT_CATEGORIES = listOf("food", "groceries", "accommodation", "transport", "leisure", "household", "other")

    val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val DIGEST = Regex("^[A-Za-z0-9_-]{43}$")
    val CURRENCY = Regex("^[A-Z]{3}$")
    val DATE = Regex("^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$")
    const val MAX_HEADS = 64
    const val MAX_SAFE = 9007199254740991L
    private const val MAX_LIST = 200
    const val MAX_RECEIPTS = 100
    private const val MAX_SHARES = 1_000_000L
    const val PERCENT_WHOLE = 10_000L

    private val JSON = Json

    // ---- reading ----

    private fun fail(path: String): Nothing = throw RevisionFormatInvalid(path)

    private fun obj(v: JsonElement?, path: String): JsonObject = v as? JsonObject ?: fail(path)

    /** Length in code points, as `[...s].length` counts it. */
    fun codePoints(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }

    private fun str(v: JsonElement?, path: String, pattern: Regex? = null, min: Int = 0, max: Int = Int.MAX_VALUE): String {
        val p = v as? JsonPrimitive
        if (p == null || !p.isString) fail(path)
        val s = p.content
        val n = codePoints(s)
        if (n < min || n > max || (pattern != null && !pattern.matches(s))) fail(path)
        return s
    }

    /** An integer as JSON numbers carry one: `1`, also `1.0` or `1e2` (JS reads them as the same number). */
    private fun int(v: JsonElement?, path: String, min: Long, max: Long): Long {
        val p = v as? JsonPrimitive
        if (p == null || p.isString || p is JsonNull) fail(path)
        val n = p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && kotlin.math.abs(it) <= MAX_SAFE }?.toLong() ?: fail(path)
        if (n < min || n > max) fail(path)
        return n
    }

    private fun arr(v: JsonElement?, path: String, min: Int, max: Int): JsonArray {
        val a = v as? JsonArray ?: fail(path)
        if (a.size < min || a.size > max) fail(path)
        return a
    }

    private fun bool(v: JsonElement?, path: String): Boolean {
        val p = v as? JsonPrimitive
        if (p == null || p.isString) fail(path)
        return when (p.content) { "true" -> true; "false" -> false; else -> fail(path) }
    }

    private fun uniq(xs: List<String>, path: String): List<String> = if (xs.toSet().size == xs.size) xs else fail(path)
    private fun uuid(v: JsonElement?, path: String) = str(v, path, UUID)
    private fun minor(v: JsonElement?, path: String) = int(v, path, 1, MAX_SAFE)
    private fun uuids(v: JsonElement?, path: String) = uniq(arr(v, path, 1, MAX_LIST).mapIndexed { i, x -> uuid(x, "$path/$i") }, path)

    private fun contributions(v: JsonElement?, path: String) = arr(v, path, 1, MAX_LIST).mapIndexed { i, x ->
        val o = obj(x, "$path/$i")
        Contribution(uuid(o["participant"], "$path/$i/participant"), minor(o["amount"], "$path/$i/amount"))
    }

    private fun category(v: JsonElement?, path: String): String {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s != null && (s in DEFAULT_CATEGORIES || UUID.matches(s))) return s
        fail(path)
    }

    private fun weights(o: JsonObject, list: String, key: String, path: String, max: Long, min: Long = 1) =
        arr(o[list], "$path/$list", 1, MAX_LIST).mapIndexed { i, x ->
            val s = obj(x, "$path/$list/$i")
            uuid(s["participant"], "$path/$list/$i/participant") to int(s[key], "$path/$list/$i/$key", min, max)
        }

    private fun split(v: JsonElement?, path: String): Split {
        val o = obj(v, path)
        return when ((o["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "equal" -> Split.Equal(uuids(o["participants"], "$path/participants"))
            "shares" -> Split.Shares(weights(o, "shares", "shares", path, MAX_SHARES))
            "percent" -> Split.Percent(weights(o, "percents", "percent", path, PERCENT_WHOLE))
            "amounts" -> Split.Amounts(weights(o, "amounts", "amount", path, MAX_SAFE))
            else -> fail("$path/mode")
        }
    }

    private fun allocation(v: JsonElement?, path: String): Allocation {
        val o = obj(v, path)
        val items = arr(o["items"], "$path/items", 0, MAX_LIST).mapIndexed { i, x ->
            val it = obj(x, "$path/items/$i")
            Item(str(it["label"], "$path/items/$i/label", null, 1, 200), minor(it["amount"], "$path/items/$i/amount"), split(it["split"], "$path/items/$i/split"))
        }
        return Allocation(items, if (o.containsKey("rest")) split(o["rest"], "$path/rest") else null)
    }

    private class MoneyFields(val date: String, val currency: String, val minorUnits: Int, val rate: String, val amount: Long, val receipts: List<String>)

    private fun money(o: JsonObject) = MoneyFields(
        date = str(o["date"], "/date", DATE),
        currency = str(o["currency"], "/currency", CURRENCY),
        minorUnits = int(o["minorUnits"], "/minorUnits", 0, 4).toInt(),
        rate = str(o["rate"], "/rate", Money.RATE),
        amount = minor(o["amount"], "/amount"),
        receipts = uniq(arr(o["receipts"], "/receipts", 0, MAX_RECEIPTS).mapIndexed { i, x -> uuid(x, "/receipts/$i") }, "/receipts"),
    )

    private fun title(v: JsonElement?) = str(v, "/title", null, 1, 200)

    private fun content(o: JsonObject): RevisionContent = when ((o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "expense" -> {
            val t = title(o["title"]); val c = category(o["category"], "/category"); val m = money(o)
            Expense(t, c, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, contributions(o["payers"], "/payers"), allocation(o["allocation"], "/allocation"))
        }
        "refund" -> {
            val t = title(o["title"]); val c = category(o["category"], "/category"); val m = money(o)
            Refund(
                t, c, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, contributions(o["receivers"], "/receivers"),
                allocation(o["allocation"], "/allocation"), if (o.containsKey("refunds")) uuid(o["refunds"], "/refunds") else null,
            )
        }
        "transfer" -> {
            val m = money(o)
            Transfer(if (o.containsKey("title")) title(o["title"]) else null, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, uuid(o["from"], "/from"), uuid(o["to"], "/to"))
        }
        "participant" -> ParticipantBody(
            name = str(o["name"], "/name", null, 1, 100),
            active = bool(o["active"], "/active"),
            joined = int(o["joined"], "/joined", 0, MAX_SAFE),
            member = if (o.containsKey("member")) uuid(obj(o["member"], "/member")["userId"], "/member/userId") else null,
        )
        "deleted" -> Deleted
        "erasure" -> Erasure(int(o["joined"], "/joined", 0, MAX_SAFE))
        else -> fail("/kind")
    }

    /** A parsed plaintext held to the v1 schema, or [RevisionFormatInvalid] at the first rule it breaks. */
    fun read(parsed: JsonElement): RevisionBody {
        val o = obj(parsed, "")
        if ((o["v"] as? JsonPrimitive)?.let { !it.isString && it.content.toLongOrNull() == VERSION } != true) fail("/v")
        val heads = arr(o["heads"], "/heads", 0, MAX_HEADS).mapIndexed { i, h ->
            val ho = obj(h, "/heads/$i")
            Head(uuid(ho["entryId"], "/heads/$i/entryId"), int(ho["revision"], "/heads/$i/revision", 1, MAX_SAFE), str(ho["digest"], "/heads/$i/digest", DIGEST))
        }
        return RevisionBody(heads, content(o))
    }

    /** UTF-8 JSON plaintext back to a body; [RevisionFormatInvalid] for anything that is not one. */
    fun decode(bytes: ByteArray): RevisionBody {
        val parsed = try {
            JSON.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (e: Exception) {
            fail("")
        }
        return read(parsed)
    }

    // ---- writing ----

    private fun splitJson(s: Split): JsonObject = buildJsonObject {
        fun pairs(list: String, key: String, xs: List<Pair<String, Long>>) = put(list, buildJsonArray {
            xs.forEach { (p, w) -> add(buildJsonObject { put("participant", p); put(key, w) }) }
        })
        when (s) {
            is Split.Equal -> { put("mode", "equal"); put("participants", JsonArray(s.participants.map { JsonPrimitive(it) })) }
            is Split.Shares -> { put("mode", "shares"); pairs("shares", "shares", s.shares) }
            is Split.Percent -> { put("mode", "percent"); pairs("percents", "percent", s.percents) }
            is Split.Amounts -> { put("mode", "amounts"); pairs("amounts", "amount", s.amounts) }
        }
    }

    private fun contributionsJson(xs: List<Contribution>) = JsonArray(xs.map { buildJsonObject { put("participant", it.participant); put("amount", it.amount) } })

    private fun allocationJson(a: Allocation) = buildJsonObject {
        put("items", JsonArray(a.items.map { buildJsonObject { put("label", it.label); put("amount", it.amount); put("split", splitJson(it.split)) } }))
        a.rest?.let { put("rest", splitJson(it)) }
    }

    /** The body as JSON, before it is held to the schema. */
    fun toJson(body: RevisionBody): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("heads", JsonArray(body.heads.map { buildJsonObject { put("entryId", it.entryId); put("revision", it.revision); put("digest", it.digest) } }))
        fun money(m: MoneyContent) {
            put("date", m.date); put("currency", m.currency); put("minorUnits", m.minorUnits); put("rate", m.rate)
            put("amount", m.amount); put("receipts", JsonArray(m.receipts.map { JsonPrimitive(it) }))
        }
        when (val c = body.content) {
            is Expense -> {
                put("kind", "expense"); put("title", c.title); put("category", c.category); money(c)
                put("payers", contributionsJson(c.payers)); put("allocation", allocationJson(c.allocation))
            }
            is Refund -> {
                put("kind", "refund"); put("title", c.title); put("category", c.category); money(c)
                put("receivers", contributionsJson(c.receivers)); put("allocation", allocationJson(c.allocation)); c.refunds?.let { put("refunds", it) }
            }
            is Transfer -> {
                put("kind", "transfer"); c.title?.let { put("title", it) }; money(c); put("from", c.from); put("to", c.to)
            }
            is ParticipantBody -> {
                put("kind", "participant"); put("name", c.name); put("active", c.active); put("joined", c.joined)
                c.member?.let { put("member", buildJsonObject { put("userId", it) }) }
            }
            Deleted -> put("kind", "deleted")
            is Erasure -> { put("kind", "erasure"); put("joined", c.joined) }
        }
    }

    /** The bytes a writer seals: compact UTF-8 JSON, held to the schema first — a writer never seals a body its own reader
     *  would refuse. */
    fun encode(body: RevisionBody): ByteArray {
        val json = toJson(body)
        read(json)
        return json.toString().encodeToByteArray()
    }
}
