package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.text.Pattern
import me.raddatz.veyq.mobile.json.JsonText
import me.raddatz.veyq.mobile.json.JsonValue
import me.raddatz.veyq.mobile.json.jsonArray
import me.raddatz.veyq.mobile.json.jsonObject
import me.raddatz.veyq.mobile.json.jsonString

// The plaintext of one Expenses revision, format v1 — the web's and the apps' one reader and writer (ADR 0155: the web
// draws it through `@veyq/core-js/expenses`); `contracts/expenses/entry.v1.schema.json` is the source of truth (ADR 0108
// decisions 3-5) and `contracts/expenses/v1-vectors.json` pins it. Readers drop unknown fields and refuse any other `v`;
// the JSON's key order carries no meaning (a reader parses, never compares). It reads and writes the core's own JSON tree
// (`json/JsonTree.kt`), never a kotlinx type, so a façade reaches no serializer.

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

    val UUID = Pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val DIGEST = Pattern("^[A-Za-z0-9_-]{43}$")
    val CURRENCY = Pattern("^[A-Z]{3}$")
    val DATE = Pattern("^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])$")
    const val MAX_HEADS = 64
    const val MAX_SAFE = 9007199254740991L
    private const val MAX_LIST = 200
    const val MAX_RECEIPTS = 100
    private const val MAX_SHARES = 1_000_000L
    const val PERCENT_WHOLE = 10_000L

    // ---- reading ----

    private fun fail(path: String): Nothing = throw RevisionFormatInvalid(path)

    private fun obj(v: JsonValue?, path: String): JsonValue.Obj = v as? JsonValue.Obj ?: fail(path)

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

    private fun str(v: JsonValue?, path: String, pattern: Pattern? = null, min: Int = 0, max: Int = Int.MAX_VALUE): String {
        val s = (v as? JsonValue.Str)?.value ?: fail(path)
        val n = codePoints(s)
        if (n < min || n > max || (pattern != null && !pattern.matches(s))) fail(path)
        return s
    }

    /** An integer as JSON numbers carry one: a double with no fraction (`1`, `1.0`, `1e2` are one number). */
    private fun int(v: JsonValue?, path: String, min: Long, max: Long): Long {
        val d = (v as? JsonValue.Num)?.value ?: fail(path)
        if (d % 1.0 != 0.0 || d < min.toDouble() || d > max.toDouble()) fail(path)
        return d.toLong()
    }

    private fun arr(v: JsonValue?, path: String, min: Int, max: Int): List<JsonValue> {
        val a = (v as? JsonValue.Arr)?.items ?: fail(path)
        if (a.size < min || a.size > max) fail(path)
        return a
    }

    private fun bool(v: JsonValue?, path: String): Boolean = (v as? JsonValue.Bool)?.value ?: fail(path)

    private fun uniq(xs: List<String>, path: String): List<String> = if (xs.toSet().size == xs.size) xs else fail(path)
    private fun uuid(v: JsonValue?, path: String): String {
        val s = (v as? JsonValue.Str)?.value ?: fail(path)
        return if (isUuid(s)) s else fail(path)
    }

    /**
     * Whether [s] matches [UUID] — lowercase hex in 8-4-4-4-12 — by its characters, never a regex: a reader checks one per
     * person, entry and head, and a regex per check was a hot spot of a 10,000-entry Pot on JavaScript. `ExpensesRulesTest`
     * holds it to [UUID] over every one-character change of a valid id.
     */
    fun isUuid(s: String): Boolean {
        if (s.length != 36) return false
        for (i in 0 until 36) {
            val c = s[i]
            val ok = if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else (c in '0'..'9' || c in 'a'..'f')
            if (!ok) return false
        }
        return true
    }
    private fun minor(v: JsonValue?, path: String) = int(v, path, 1, MAX_SAFE)
    private fun uuids(v: JsonValue?, path: String) = uniq(arr(v, path, 1, MAX_LIST).mapIndexed { i, x -> uuid(x, "$path/$i") }, path)

    private fun contributions(v: JsonValue?, path: String) = arr(v, path, 1, MAX_LIST).mapIndexed { i, x ->
        val o = obj(x, "$path/$i")
        Contribution(uuid(o["participant"], "$path/$i/participant"), minor(o["amount"], "$path/$i/amount"))
    }

    private fun category(v: JsonValue?, path: String): String {
        val s = (v as? JsonValue.Str)?.value
        if (s != null && (s in DEFAULT_CATEGORIES || isUuid(s))) return s
        fail(path)
    }

    private fun weights(o: JsonValue.Obj, list: String, key: String, path: String, max: Long, min: Long = 1) =
        arr(o[list], "$path/$list", 1, MAX_LIST).mapIndexed { i, x ->
            val s = obj(x, "$path/$list/$i")
            uuid(s["participant"], "$path/$list/$i/participant") to int(s[key], "$path/$list/$i/$key", min, max)
        }

    private fun split(v: JsonValue?, path: String): Split {
        val o = obj(v, path)
        return when ((o["mode"] as? JsonValue.Str)?.value) {
            "equal" -> Split.Equal(uuids(o["participants"], "$path/participants"))
            "shares" -> Split.Shares(weights(o, "shares", "shares", path, MAX_SHARES))
            "percent" -> Split.Percent(weights(o, "percents", "percent", path, PERCENT_WHOLE))
            "amounts" -> Split.Amounts(weights(o, "amounts", "amount", path, MAX_SAFE))
            else -> fail("$path/mode")
        }
    }

    private fun allocation(v: JsonValue?, path: String): Allocation {
        val o = obj(v, path)
        val items = arr(o["items"], "$path/items", 0, MAX_LIST).mapIndexed { i, x ->
            val it = obj(x, "$path/items/$i")
            Item(str(it["label"], "$path/items/$i/label", null, 1, 200), minor(it["amount"], "$path/items/$i/amount"), split(it["split"], "$path/items/$i/split"))
        }
        return Allocation(items, if (o.has("rest")) split(o["rest"], "$path/rest") else null)
    }

    private class MoneyFields(val date: String, val currency: String, val minorUnits: Int, val rate: String, val amount: Long, val receipts: List<String>)

    private fun money(o: JsonValue.Obj) = MoneyFields(
        date = str(o["date"], "/date", DATE),
        currency = str(o["currency"], "/currency", CURRENCY),
        minorUnits = int(o["minorUnits"], "/minorUnits", 0, 4).toInt(),
        rate = str(o["rate"], "/rate", Money.RATE),
        amount = minor(o["amount"], "/amount"),
        receipts = uniq(arr(o["receipts"], "/receipts", 0, MAX_RECEIPTS).mapIndexed { i, x -> uuid(x, "/receipts/$i") }, "/receipts"),
    )

    private fun title(v: JsonValue?) = str(v, "/title", null, 1, 200)

    private fun content(o: JsonValue.Obj): RevisionContent = when ((o["kind"] as? JsonValue.Str)?.value) {
        "expense" -> {
            val t = title(o["title"]); val c = category(o["category"], "/category"); val m = money(o)
            Expense(t, c, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, contributions(o["payers"], "/payers"), allocation(o["allocation"], "/allocation"))
        }
        "refund" -> {
            val t = title(o["title"]); val c = category(o["category"], "/category"); val m = money(o)
            Refund(
                t, c, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, contributions(o["receivers"], "/receivers"),
                allocation(o["allocation"], "/allocation"), if (o.has("refunds")) uuid(o["refunds"], "/refunds") else null,
            )
        }
        "transfer" -> {
            val m = money(o)
            Transfer(if (o.has("title")) title(o["title"]) else null, m.date, m.currency, m.minorUnits, m.rate, m.amount, m.receipts, uuid(o["from"], "/from"), uuid(o["to"], "/to"))
        }
        "participant" -> ParticipantBody(
            name = str(o["name"], "/name", null, 1, 100),
            active = bool(o["active"], "/active"),
            joined = int(o["joined"], "/joined", 0, MAX_SAFE),
            member = if (o.has("member")) uuid(obj(o["member"], "/member")["userId"], "/member/userId") else null,
        )
        "deleted" -> Deleted
        "erasure" -> Erasure(int(o["joined"], "/joined", 0, MAX_SAFE))
        else -> fail("/kind")
    }

    /** A parsed plaintext held to the v1 schema, or [RevisionFormatInvalid] at the first rule it breaks. */
    fun read(parsed: JsonValue): RevisionBody {
        val o = obj(parsed, "")
        if ((o["v"] as? JsonValue.Num)?.value != VERSION.toDouble()) fail("/v")
        val heads = arr(o["heads"], "/heads", 0, MAX_HEADS).mapIndexed { i, h ->
            val ho = obj(h, "/heads/$i")
            Head(uuid(ho["entryId"], "/heads/$i/entryId"), int(ho["revision"], "/heads/$i/revision", 1, MAX_SAFE), str(ho["digest"], "/heads/$i/digest", DIGEST))
        }
        return RevisionBody(heads, content(o))
    }

    /** UTF-8 JSON plaintext back to a body; [RevisionFormatInvalid] for anything that is not one. A leading byte order mark
     *  is dropped, as a fatal UTF-8 `TextDecoder` drops it (the vectors' `decode`). */
    fun decode(bytes: ByteArray): RevisionBody {
        val parsed = try {
            JsonText.parse(bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿"))
        } catch (e: Exception) {
            fail("")
        }
        return read(parsed)
    }

    // ---- writing ----

    private fun num(n: Long) = JsonValue.Num(n)
    private fun strs(xs: List<String>) = jsonArray(xs.map(::jsonString))

    private fun splitJson(s: Split): JsonValue.Obj {
        fun pairs(key: String, xs: List<Pair<String, Long>>) = jsonArray(xs.map { (p, w) -> jsonObject("participant" to jsonString(p), key to num(w)) })
        return when (s) {
            is Split.Equal -> jsonObject("mode" to jsonString("equal"), "participants" to strs(s.participants))
            is Split.Shares -> jsonObject("mode" to jsonString("shares"), "shares" to pairs("shares", s.shares))
            is Split.Percent -> jsonObject("mode" to jsonString("percent"), "percents" to pairs("percent", s.percents))
            is Split.Amounts -> jsonObject("mode" to jsonString("amounts"), "amounts" to pairs("amount", s.amounts))
        }
    }

    private fun contributionsJson(xs: List<Contribution>) = jsonArray(xs.map { jsonObject("participant" to jsonString(it.participant), "amount" to num(it.amount)) })

    private fun allocationJson(a: Allocation) = jsonObject(
        "items" to jsonArray(a.items.map { jsonObject("label" to jsonString(it.label), "amount" to num(it.amount), "split" to splitJson(it.split)) }),
        "rest" to a.rest?.let(::splitJson),
    )

    private fun moneyJson(m: MoneyContent) = arrayOf(
        "date" to jsonString(m.date), "currency" to jsonString(m.currency), "minorUnits" to num(m.minorUnits.toLong()),
        "rate" to jsonString(m.rate), "amount" to num(m.amount), "receipts" to strs(m.receipts),
    )

    /** What a revision says as JSON, without the `v` and `heads` every body carries: the fields the schema names. */
    fun contentJson(c: RevisionContent): JsonValue.Obj = when (c) {
        is Expense -> jsonObject(
            "kind" to jsonString("expense"), "title" to jsonString(c.title), "category" to jsonString(c.category), *moneyJson(c),
            "payers" to contributionsJson(c.payers), "allocation" to allocationJson(c.allocation),
        )
        is Refund -> jsonObject(
            "kind" to jsonString("refund"), "title" to jsonString(c.title), "category" to jsonString(c.category), *moneyJson(c),
            "receivers" to contributionsJson(c.receivers), "allocation" to allocationJson(c.allocation), "refunds" to c.refunds?.let(::jsonString),
        )
        is Transfer -> jsonObject(
            // the title last, where the web's reader put it: the key order carries no meaning, but a rewrite of the vectors diffs
            "kind" to jsonString("transfer"), *moneyJson(c), "from" to jsonString(c.from), "to" to jsonString(c.to), "title" to c.title?.let(::jsonString),
        )
        is ParticipantBody -> jsonObject(
            "kind" to jsonString("participant"), "name" to jsonString(c.name), "active" to JsonValue.Bool(c.active), "joined" to num(c.joined),
            "member" to c.member?.let { jsonObject("userId" to jsonString(it)) },
        )
        Deleted -> jsonObject("kind" to jsonString("deleted"))
        is Erasure -> jsonObject("kind" to jsonString("erasure"), "joined" to num(c.joined))
    }

    /** The body as JSON, before it is held to the schema. */
    fun toJson(body: RevisionBody): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf<String, JsonValue>(
            "v" to num(VERSION),
            "heads" to jsonArray(body.heads.map { jsonObject("entryId" to jsonString(it.entryId), "revision" to num(it.revision), "digest" to jsonString(it.digest)) }),
        ) + contentJson(body.content).fields,
    )

    /** The bytes a writer seals: compact UTF-8 JSON, held to the schema first — a writer never seals a body its own reader
     *  would refuse. */
    fun encode(body: RevisionBody): ByteArray {
        val json = toJson(body)
        read(json)
        return JsonText.stringify(json).encodeToByteArray()
    }
}
