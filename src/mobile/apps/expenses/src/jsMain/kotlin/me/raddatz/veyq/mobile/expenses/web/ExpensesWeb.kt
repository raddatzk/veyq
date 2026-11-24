@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.expenses.web

import me.raddatz.veyq.mobile.crypto.JsPrimitives
import me.raddatz.veyq.mobile.crypto.Pad
import me.raddatz.veyq.mobile.crypto.V1
import me.raddatz.veyq.mobile.expenses.Allocation
import me.raddatz.veyq.mobile.expenses.Amounts
import me.raddatz.veyq.mobile.expenses.Big
import me.raddatz.veyq.mobile.expenses.Contribution
import me.raddatz.veyq.mobile.expenses.Currencies
import me.raddatz.veyq.mobile.expenses.Deleted
import me.raddatz.veyq.mobile.expenses.Erasure
import me.raddatz.veyq.mobile.expenses.ExchangeRateTable
import me.raddatz.veyq.mobile.expenses.ExchangeRates
import me.raddatz.veyq.mobile.expenses.Expense
import me.raddatz.veyq.mobile.expenses.HeldRevision
import me.raddatz.veyq.mobile.expenses.Item
import me.raddatz.veyq.mobile.expenses.Ledger
import me.raddatz.veyq.mobile.expenses.LedgerCheck
import me.raddatz.veyq.mobile.expenses.Money
import me.raddatz.veyq.mobile.expenses.MoneyContent
import me.raddatz.veyq.mobile.expenses.ParticipantBody
import me.raddatz.veyq.mobile.expenses.Refund
import me.raddatz.veyq.mobile.expenses.RevisionAddress
import me.raddatz.veyq.mobile.expenses.RevisionBody
import me.raddatz.veyq.mobile.expenses.RevisionCodec
import me.raddatz.veyq.mobile.expenses.RevisionContent
import me.raddatz.veyq.mobile.expenses.RevisionFormat
import me.raddatz.veyq.mobile.expenses.RevisionFormatInvalid
import me.raddatz.veyq.mobile.expenses.RevisionUnreadable
import me.raddatz.veyq.mobile.expenses.Revisions
import me.raddatz.veyq.mobile.expenses.SealedRevision
import me.raddatz.veyq.mobile.expenses.Split
import me.raddatz.veyq.mobile.expenses.Transfer
import me.raddatz.veyq.mobile.json.JsJson
import me.raddatz.veyq.mobile.json.JsonValue
import me.raddatz.veyq.mobile.words.Shown
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.collections.JsReadonlyArray
import kotlin.js.collections.JsReadonlyMap

// Expenses' façade for the web (ADR 0155 decisions 4, 5 and 7; `@veyq/core-js/expenses`): the format's reader and writer,
// the revision codec, the ledger check, the ledger, money as text and as typed, and the day's rates — the core's, behind
// plain values (`Shapes.kt`), so the web computes every balance as the apps do and keeps only what a browser does: its
// screens, its session that holds the revisions, its fetch and cache of the rates. Bytes cross as views of the caller's
// buffer, never copies (a key the web zeroes is zeroed); a refusal crosses as a JavaScript `Error` named for it, carrying
// its `path` or `reason`. Nothing here reaches kotlinx.serialization, coroutines, Ktor or a `NotOnTheWeb` refusal (the
// package's slice check reads the shipped bundle's sources). What it exports is what the web's callers call, no more.

private val codec by lazy { RevisionCodec(V1(JsPrimitives)) }

// ---- the format ----

/** [parsed] held to the v1 schema: the body with only the fields the schema names, or a `RevisionFormatInvalid` error
 *  carrying the `path` of the first rule it breaks. */
@JsExport
fun readRevisionBody(parsed: JsonShape?): BodyShape = bodyShape(readBody(parsed))

/** Whether [content] is an expense, a refund or a transfer. */
@JsExport
fun isMoney(content: ContentShape): Boolean = content.kind == "expense" || content.kind == "refund" || content.kind == "transfer"

/** The format's numbers and pattern sources. */
@JsExport
fun formatRules(): FormatRulesShape = shape(
    "uuid" to RevisionFormat.UUID.pattern, "digest" to RevisionFormat.DIGEST.pattern, "currency" to RevisionFormat.CURRENCY.pattern,
    "date" to RevisionFormat.DATE.pattern, "rate" to Money.RATE.pattern, "maxHeads" to RevisionFormat.MAX_HEADS,
    "maxReceipts" to RevisionFormat.MAX_RECEIPTS, "percentWhole" to RevisionFormat.PERCENT_WHOLE.toInt(),
    "defaultCategories" to RevisionFormat.DEFAULT_CATEGORIES.toTypedArray(), "sizeClasses" to RevisionCodec.SIZE_CLASSES.toTypedArray(),
)

// ---- the codec ----

/** Seal one revision under a fresh `K_rev`, wrapped under the Pot's key; the body is held to the schema first, and one past
 *  16 KiB throws `pad_too_large`. */
@JsExport
fun sealRevision(address: AddressShape, potKey: Uint8Array, body: BodyShape): SealedShape {
    val read = readBody(body)
    val sealed = try {
        codec.seal(addressOf(address), potKey.bytes(), read)
    } catch (e: Pad.Invalid) {
        throw jsError("Error", e.message ?: "pad_too_large")
    }
    return shape("sealedBody" to sealed.sealedBody.u8(), "wrappedRevisionKey" to sealed.wrappedRevisionKey.u8())
}

/** Open one revision read at [address] with the Pot's key, or a `RevisionUnreadable` error with its `reason`. */
@JsExport
fun openRevision(address: AddressShape, potKey: Uint8Array, sealed: SealedShape): BodyShape = unreadable {
    bodyShape(codec.open(addressOf(address), potKey.bytes(), SealedRevision(sealed.sealedBody.bytes(), sealed.wrappedRevisionKey.bytes())))
}

/** Open one revision's body with its `K_rev` alone (a moderator's review), bound to [address] as every open is. */
@JsExport
fun openRevisionWithKey(address: AddressShape, kRev: Uint8Array, sealedBody: Uint8Array): BodyShape = unreadable {
    bodyShape(codec.openWithKey(addressOf(address), kRev.bytes(), sealedBody.bytes()))
}

/** The `K_rev` of one revision (a report hands it over), or a `RevisionUnreadable` error when its wrap does not open here. */
@JsExport
fun revisionKeyOf(address: AddressShape, potKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array = unreadable {
    codec.revisionKey(addressOf(address), potKey.bytes(), wrappedRevisionKey.bytes()).u8()
}

/** A re-key's part for one revision: its `K_rev` moved from the Pot's old key to the new one; the body is no input. */
@JsExport
fun rewrapRevisionKey(address: AddressShape, oldPotKey: Uint8Array, newPotKey: Uint8Array, wrappedRevisionKey: Uint8Array): Uint8Array = unreadable {
    codec.rewrapRevisionKey(addressOf(address), oldPotKey.bytes(), newPotKey.bytes(), wrappedRevisionKey.bytes()).u8()
}

/** The digest a later revision's heads name for this one: SHA-256 of its sealed body as served, base64url. */
@JsExport
fun revisionDigest(sealedBody: Uint8Array): String = codec.digest(sealedBody.bytes())

// ---- the held revisions and the ledger check ----

/** The one id of a revision in a Pot's list. */
@JsExport
fun revisionId(ref: RefShape): String = "${ref.entryId}:${ref.revision.toLong()}"

/** Open every served row of the Pot `nodeId` in `scopeId`: each row with its body's digest and what it opened to, or why
 *  it did not. Nothing throws for one row. */
@JsExport
fun openServedRevisions(scopeId: String, nodeId: String, potKey: Uint8Array, served: JsReadonlyArray<ServedShape>): JsReadonlyArray<HeldShape> {
    val key = potKey.bytes()
    return array(list(served).map { row ->
        val digest = codec.digest(row.sealedBody.bytes())
        val (body, reason) = try {
            bodyShape(codec.open(RevisionAddress(scopeId, nodeId, row.entryId, row.revision.toLong()), key, SealedRevision(row.sealedBody.bytes(), row.wrappedRevisionKey.bytes()))) to null
        } catch (e: RevisionUnreadable) {
            null to e.reason.name.lowercase()
        }
        val held: dynamic = js("Object").assign(js("({})"), row)
        held.digest = digest
        held.body = body
        held.unreadable = reason
        held.unsafeCast<HeldShape>()
    })
}

/** The held revisions grouped by record (the objects given), each record's ascending, in the order records first appear. */
@JsExport
fun byRecord(held: JsReadonlyArray<HeldShape>): JsReadonlyMap<String, JsReadonlyArray<HeldShape>> {
    val rows = list(held)
    val core = rows.map(::heldOf)
    val at = positions(core)
    return map(Revisions.byRecord(core)) { revisions -> array(revisions.map { rows[at.getValue(it)] }) }
}

/** Every held revision (the objects given) that no held revision names as a head. */
@JsExport
fun currentHeads(held: JsReadonlyArray<HeldShape>): JsReadonlyArray<HeldShape> {
    val rows = list(held)
    val core = rows.map(::heldOf)
    val at = positions(core)
    return array(Revisions.currentHeads(core).map { rows[at.getValue(it)] })
}

/** The heads a revision written now names. */
@JsExport
fun headsToName(held: JsReadonlyArray<HeldShape>): JsReadonlyArray<HeadShape> =
    array(Revisions.headsToName(list(held).map(::heldOf)).map { shape("entryId" to it.entryId, "revision" to it.revision.toDouble(), "digest" to it.digest) })

/** The ledger check over everything held for one Pot. */
@JsExport
fun checkLedger(held: JsReadonlyArray<HeldShape>): LedgerCheckShape {
    val check = LedgerCheck.of(list(held).map(::heldOf))
    fun refs(l: List<LedgerCheck.Ref>) = array(l.map { shape<RefShape>("entryId" to it.entryId, "revision" to it.revision.toDouble()) })
    return shape(
        "complete" to check.complete, "missing" to refs(check.missing), "altered" to refs(check.altered),
        "unreadable" to refs(check.unreadable), "entries" to check.entries.toTypedArray(),
    )
}

// ---- the ledger ----

/** The Pot's ledger from every revision held. */
@JsExport
fun buildLedger(held: JsReadonlyArray<HeldShape>, base: BaseShape): LedgerShape {
    val pot = Ledger.build(list(held).map(::heldOf), Ledger.BaseCurrency(base.currency, base.minorUnits))
    return shape(
        "participants" to array(pot.participants.map(::participantShape)),
        "entries" to array(pot.entries.map(::entryShape)),
        "balances" to bigMap(pot.balances),
        "settleUp" to array(pot.settleUp.map(::transferShape)),
        "overview" to shape<OverviewShape>(
            "byCategory" to bigMap(pot.overview.byCategory),
            "byPerson" to map(pot.overview.byPerson) { (paid, share) -> shape<PersonTotalShape>("paid" to big(paid), "share" to big(share)) },
            "byCategoryAndPerson" to map(pot.overview.byCategoryAndPerson) { bigMap(it) },
        ),
    )
}

/** Why a money entry may not count — or be saved — or null when its sums add up; [isParticipant] tells whether an id is a
 *  participant of the Pot (omit it to check the sums alone). The content is the editor's, unchecked by the reader. */
@JsExport
fun entryProblem(content: ContentShape, isParticipant: ((String) -> Boolean)? = null): String? =
    problemName(Ledger.entryProblem(moneyOf(content), isParticipant))

/** A valid money entry's effect in the base currency, people ranked by [rank] (their index in the join order). */
@JsExport
fun entryEffect(content: ContentShape, base: BaseShape, rank: JsReadonlyMap<String, Int>): EffectShape =
    effectShape(Ledger.entryEffect(moneyOf(content), Ledger.BaseCurrency(base.currency, base.minorUnits), readMap(rank) { it }))

/** Every person a money entry names. */
@JsExport
fun namedParticipants(content: ContentShape): JsReadonlyArray<String> = array(Ledger.namedParticipants(moneyOf(content)))

/** The people a split names, in its order. */
@JsExport
fun splitPeople(split: SplitShape): JsReadonlyArray<String> = array(Ledger.splitPeople(splitOf(split)))

/** Whether one split breaks [rule] (`named-twice`, `percent-unbalanced`, `amounts-unbalanced`); [part] what an amounts
 *  split must add up to (null: it splits nothing). */
@JsExport
fun splitBreaks(rule: String, split: SplitShape, part: bigint?): Boolean =
    Ledger.splitBreaks(Ledger.Problem.valueOf(rule.uppercase().replace('-', '_')), splitOf(split), part?.let(::bigOf))

/** `amount - sum(items)`: what the rest split gets (negative: items above the total). */
@JsExport
fun restOf(content: ContentShape): bigint = big(Ledger.restOf(content.amount!!.toLong(), allocationOf(content.allocation!!)))

/** Join order: `joined`, then the id. */
@JsExport
fun joinOrder(participants: JsReadonlyArray<JoinedShape>): JsReadonlyArray<String> =
    array(Ledger.joinOrder(list(participants).map { it.id to it.joined.toLong() }))

/** Each id's index in [order]. */
@JsExport
fun rankOf(order: JsReadonlyArray<String>): JsReadonlyMap<String, Int> = map(Ledger.rankOf(list(order))) { it }

/** A balance is settled when it is zero. */
@JsExport
fun isSettled(ledger: LedgerShape, participantId: String): Boolean =
    ledger.balances.asDynamic().get(participantId).unsafeCast<bigint?>()?.let { bigOf(it).signum == 0 } ?: true

/** The member a participant record is: its earliest revision that opened to a participant decides. */
@JsExport
fun participantMemberOf(revisions: JsReadonlyArray<HeldShape>): String? =
    list(revisions).firstNotNullOfOrNull { r -> r.body?.takeIf { it.kind == "participant" } }?.member?.userId

/** The settle-up of [balances], ties by join order ([order]). */
@JsExport
fun settleUp(balances: JsReadonlyMap<String, bigint>, order: JsReadonlyArray<String>): JsReadonlyArray<TransferShape> =
    array(Ledger.settleUp(readMap(balances, ::bigOf), list(order)).map(::transferShape))

// ---- money as text and as typed ----

/** [amount] minor units of [currency] for the reader's [locale] (`en`, `de`): "€12.50", "12,50 €". */
@JsExport
fun formatMoney(amount: bigint, currency: String, minorUnits: Int, locale: String): String = Shown.money(longOf(amount), currency, minorUnits, locale)

/** [amount] minor units as a plain decimal with a dot: `1250n, 2` → `"12.50"`. */
@JsExport
fun decimalOf(amount: bigint, minorUnits: Int): String = Amounts.text(longOf(amount), minorUnits)

/** An amount to be typed over: the decimal with the reader's separator. */
@JsExport
fun amountText(amount: bigint, minorUnits: Int, locale: String): String =
    Amounts.text(longOf(amount), minorUnits, if (locale.lowercase().startsWith("de")) ',' else '.')

/** What a person typed as an amount, in minor units, or null. */
@JsExport
fun parseAmount(text: String, minorUnits: Int): bigint? = Amounts.parse(text, minorUnits)?.let { big(Big.of(it)) }

/** A typed rate as the format seals it, or null. */
@JsExport
fun parseRate(text: String): String? = Amounts.parseRate(text)

/** The minor-unit digits of [currency] (the euro 2, the yen 0, the dinar 3); a code the table does not know has 2. */
@JsExport
fun minorUnitsOf(currency: String): Int = Currencies.minorUnits(currency)

/** The ISO 4217 codes a Pot or an entry may be kept in, sorted. */
@JsExport
fun knownCurrencies(): JsReadonlyArray<String> = array(Currencies.known)

// ---- the day's rates ----

/** A provider's answer as a table of the currencies [known] names (the core's list where none), or null for another shape. */
@JsExport
fun parseTable(answer: JsonShape?, known: JsReadonlyArray<String>? = null): JsonShape? {
    val tree = try { JsJson.fromJs(answer) } catch (e: IllegalArgumentException) { return null }
    val table = ExchangeRateTable.parseTable(tree, (known?.let(::list) ?: Currencies.known).toSet()) ?: return null
    return JsJson.toJs(JsonValue.Obj(table.mapValues { (_, r) -> JsonValue.Obj(linkedMapOf("rate" to JsonValue.Str(r.rate), "date" to JsonValue.Str(r.date))) }))
        .unsafeCast<JsonShape>()
}

/** One unit of [from] in [to] from [table], or null when either is missing. */
@JsExport
fun crossRate(table: JsonShape, from: String, to: String): RateShape? {
    val rows = (JsJson.fromJs(table) as? JsonValue.Obj)?.fields.orEmpty().mapNotNull { (code, row) ->
        val o = row as? JsonValue.Obj ?: return@mapNotNull null
        val rate = (o["rate"] as? JsonValue.Str)?.value ?: return@mapNotNull null
        val date = (o["date"] as? JsonValue.Str)?.value ?: return@mapNotNull null
        code to ExchangeRates.Row(rate, date)
    }.toMap()
    return ExchangeRateTable.crossRate(rows, from, to)?.let { shape("rate" to it.rate, "date" to it.date) }
}

// ---- conversions ----

private fun readBody(parsed: Any?): RevisionBody {
    val tree = try {
        JsJson.fromJs(parsed)
    } catch (e: IllegalArgumentException) {
        throw formatInvalid("")
    }
    return try {
        RevisionFormat.read(tree)
    } catch (e: RevisionFormatInvalid) {
        throw formatInvalid(e.path)
    }
}

private fun formatInvalid(path: String): Throwable = jsError("RevisionFormatInvalid", "revision_format_invalid: $path", "path" to path)

private inline fun <T> unreadable(f: () -> T): T = try {
    f()
} catch (e: RevisionUnreadable) {
    val reason = e.reason.name.lowercase()
    throw jsError("RevisionUnreadable", "revision_unreadable: $reason", "reason" to reason)
}

/** A JavaScript `Error` named [name] with [fields]: what the web's glue turns into its own error classes. */
private fun jsError(name: String, message: String, vararg fields: Pair<String, Any?>): Throwable {
    val e: dynamic = js("new Error(message)")
    e.name = name
    for ((k, v) in fields) e[k] = v
    return e.unsafeCast<Throwable>()
}

private fun bodyShape(body: RevisionBody): BodyShape = JsJson.toJs(RevisionFormat.toJson(body)).unsafeCast<BodyShape>()

private fun addressOf(a: AddressShape) = RevisionAddress(a.scopeId, a.nodeId, a.entryId, a.revision.toLong())

/** A held row as the core holds it; its body re-read from the format's JSON (the web's held bodies are the reader's own). */
private fun heldOf(h: HeldShape): HeldRevision = HeldRevision(
    h.entryId, h.revision.toLong(), h.createdAt.toLong(), h.digest,
    h.body?.let { RevisionFormat.read(JsJson.fromJs(it)) },
    h.unreadable?.let { RevisionUnreadable.Reason.valueOf(it.uppercase()) },
)

/** Each held revision's position among the web's rows (a `HeldRevision` is equal only to itself). */
private fun positions(core: List<HeldRevision>): Map<HeldRevision, Int> = core.withIndex().associate { (i, h) -> h to i }

private fun problemName(p: Ledger.Problem?): String? = p?.name?.lowercase()?.replace('_', '-')

private fun participantShape(p: Ledger.Participant): ParticipantShape = shape(
    "id" to p.id, "name" to p.name, "active" to p.active, "joined" to p.joined.toDouble(), "erased" to p.erased, "member" to p.member,
)

private fun entryShape(e: Ledger.Entry): EntryShape = shape(
    "id" to e.id, "revision" to e.revision.toDouble(), "status" to e.status.name.lowercase(),
    "content" to e.content?.let { JsJson.toJs(RevisionFormat.contentJson(it)) }, "problem" to problemName(e.problem),
    "effect" to e.effect?.let(::effectShape), "edited" to e.edited, "recordedAt" to e.recordedAt.toDouble(),
)

private fun effectShape(f: Ledger.Effect): EffectShape =
    shape("base" to big(f.base), "paid" to bigMap(f.paid), "received" to bigMap(f.received), "shares" to bigMap(f.shares))

private fun transferShape(t: Ledger.Settlement): TransferShape = shape("from" to t.from, "to" to t.to, "amount" to big(t.amount))

/** The web's content as the core's, unchecked: the editor asks the rules of what it has, before the reader would refuse it. */
private fun moneyOf(c: ContentShape): MoneyContent = when (c.kind) {
    "expense" -> Expense(
        c.title ?: "", c.category ?: "", c.date ?: "", c.currency ?: "", c.minorUnits ?: 0, c.rate ?: "", c.amount!!.toLong(),
        strings(c.receipts), contributionsOf(c.payers), allocationOf(c.allocation!!),
    )
    "refund" -> Refund(
        c.title ?: "", c.category ?: "", c.date ?: "", c.currency ?: "", c.minorUnits ?: 0, c.rate ?: "", c.amount!!.toLong(),
        strings(c.receipts), contributionsOf(c.receivers), allocationOf(c.allocation!!), c.refunds,
    )
    "transfer" -> Transfer(c.title, c.date ?: "", c.currency ?: "", c.minorUnits ?: 0, c.rate ?: "", c.amount!!.toLong(), strings(c.receipts), c.from!!, c.to!!)
    else -> throw IllegalArgumentException("not a money entry: ${c.kind}")
}

private fun strings(a: JsReadonlyArray<String>?): List<String> = a?.let(::list).orEmpty()

private fun contributionsOf(a: JsReadonlyArray<ContributionShape>?): List<Contribution> =
    a?.let(::list).orEmpty().map { Contribution(it.participant, it.amount.toLong()) }

private fun allocationOf(a: AllocationShape): Allocation =
    Allocation(list(a.items).map { Item(it.label, it.amount.toLong(), splitOf(it.split)) }, a.rest?.let(::splitOf))

private fun splitOf(s: SplitShape): Split = when (s.mode) {
    "equal" -> Split.Equal(strings(s.participants))
    "shares" -> Split.Shares(list(s.shares!!).map { it.participant to it.shares.toLong() })
    "percent" -> Split.Percent(list(s.percents!!).map { it.participant to it.percent.toLong() })
    "amounts" -> Split.Amounts(list(s.amounts!!).map { it.participant to it.amount.toLong() })
    else -> throw IllegalArgumentException("not a split: ${s.mode}")
}

// ---- JavaScript values ----

/** `Big` crosses as its decimal text to `BigInt`, so no digit is lost past 2^53. */
private fun big(b: Big): bigint {
    val text = b.toString()
    return js("BigInt(text)").unsafeCast<bigint>()
}

private fun bigOf(b: bigint): Big = Big.parse(b.asDynamic().toString().unsafeCast<String>())

private fun longOf(b: bigint): Long = b.asDynamic().toString().unsafeCast<String>().toLong()

private fun bigMap(m: Map<String, Big>): JsReadonlyMap<String, bigint> = map(m, ::big)

/** A JavaScript `Map` of [m], in its order, each value through [value]. */
private fun <V, W> map(m: Map<String, V>, value: (V) -> W): JsReadonlyMap<String, W> {
    val out: dynamic = js("new Map()")
    for ((k, v) in m) out.set(k, value(v))
    return out.unsafeCast<JsReadonlyMap<String, W>>()
}

private fun <V, W> readMap(m: JsReadonlyMap<String, V>, value: (V) -> W): Map<String, W> {
    val out = LinkedHashMap<String, W>()
    m.asDynamic().forEach { v: V, k: String -> out[k] = value(v) }
    return out
}

/** A JavaScript array read as a list, not copied. */
private fun <T> list(a: JsReadonlyArray<T>): List<T> = a.unsafeCast<Array<T>>().asList()

private fun <T> array(l: List<T>): JsReadonlyArray<T> = l.toTypedArray().unsafeCast<JsReadonlyArray<T>>()

/** A plain JavaScript object of [fields], typed as the shape it spells. */
private fun <T> shape(vararg fields: Pair<String, Any?>): T {
    val o: dynamic = js("({})")
    for ((k, v) in fields) o[k] = v
    return o.unsafeCast<T>()
}

/** The caller's bytes as a `ByteArray`: a view of the same buffer, so a key the caller zeroes is zeroed. */
private fun Uint8Array.bytes(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>()

private fun ByteArray.u8(): Uint8Array {
    val a = unsafeCast<Int8Array>()
    return Uint8Array(a.buffer, a.byteOffset, a.length)
}
