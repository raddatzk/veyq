@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.expenses.web

import kotlin.js.Date
import kotlin.js.collections.JsReadonlyArray
import kotlin.js.collections.JsReadonlyMap
import org.khronos.webgl.Uint8Array

// The plain values Expenses' façade takes and returns (ADR 0155 decision 5): each an external interface, so its `.d.ts`
// names every field with its type (never `any`) and the web passes and reads plain objects — no Kotlin class crosses. They
// spell the web's own shapes (`features/expenses/domain/format.ts`, `ledger.ts`), which the web keeps for what it draws and
// holds to these by a compile-time check (`format.ts`). A body is the format's JSON object exactly; money is `bigint`; a
// map is a JavaScript `Map`; a time is the web's own `Date`; an enum is its string.

/**
 * JavaScript's `bigint`, as a type only: named so, the `.d.ts` writes the primitive. Kotlin's own (`JsBigInt`) is a `Long`,
 * which crosses only under the experimental `-Xes-long-as-bigint` (ADR 0155 decision 3); money crosses as the core's own
 * `Big`, a `BigInt` on JavaScript (`expenses/Big.js.kt`), never through a double.
 */
@Suppress("ClassName")
external class bigint

/** Any JSON value the web read (`JSON.parse`'s result, an object it built): a reader holds it to its rules. */
@JsExport
external interface JsonValueShape

/** One revision a Pot's heads name. */
@JsExport
external interface HeadShape {
    val entryId: String
    val revision: Double
    val digest: String
}

@JsExport
external interface ContributionShape {
    val participant: String
    val amount: Double
}

@JsExport
external interface ShareShape {
    val participant: String
    val shares: Double
}

@JsExport
external interface PercentShape {
    val participant: String
    val percent: Double
}

/** A split: `mode` and the list of that mode (the web's `Split` union, each member assignable to it). */
@JsExport
external interface SplitShape {
    val mode: String
    val participants: JsReadonlyArray<String>?
    val shares: JsReadonlyArray<ShareShape>?
    val percents: JsReadonlyArray<PercentShape>?
    val amounts: JsReadonlyArray<ContributionShape>?
}

@JsExport
external interface ItemShape {
    val label: String
    val amount: Double
    val split: SplitShape
}

@JsExport
external interface AllocationShape {
    val items: JsReadonlyArray<ItemShape>
    val rest: SplitShape?
}

@JsExport
external interface MemberShape {
    val userId: String
}

/** What a revision says: `kind` and that kind's fields (the web's `RevisionContent` union, each member assignable to it). */
@JsExport
external interface ContentShape {
    val kind: String
    val title: String?
    val category: String?
    val date: String?
    val currency: String?
    val minorUnits: Int?
    val rate: String?
    val amount: Double?
    val receipts: JsReadonlyArray<String>?
    val payers: JsReadonlyArray<ContributionShape>?
    val receivers: JsReadonlyArray<ContributionShape>?
    val allocation: AllocationShape?
    val refunds: String?
    val from: String?
    val to: String?
    val name: String?
    val active: Boolean?
    val joined: Double?
    val member: MemberShape?
}

/** One revision's whole plaintext: its content with the `v` and `heads` every body carries. */
@JsExport
external interface BodyShape : ContentShape {
    val v: Int
    val heads: JsReadonlyArray<HeadShape>
}

/** Where a revision lives: everything its two seals bind. */
@JsExport
external interface AddressShape {
    val scopeId: String
    val nodeId: String
    val entryId: String
    val revision: Double
}

/** One revision as the wire carries it, raw bytes. */
@JsExport
external interface SealedShape {
    val sealedBody: Uint8Array
    val wrappedRevisionKey: Uint8Array
}

/** One revision by its address inside a Pot. */
@JsExport
external interface RefShape {
    val entryId: String
    val revision: Double
}

/** One served row, decoded from the wire's base64 but not opened; `createdAt` and `purgeAfter` the web's own `Date`s. */
@JsExport
external interface ServedShape : SealedShape {
    val entryId: String
    val revision: Double
    val createdAt: Date
    val writer: String?
    val purgeAfter: Date?
}

/** One revision as held: the row, its body's digest, and what it opened to — or why it did not (`key`, `body`, `format`). */
@JsExport
external interface HeldShape : ServedShape {
    val digest: String
    val body: BodyShape?
    val unreadable: String?
}

/** What a read of the Pot found wrong with its ledger. */
@JsExport
external interface LedgerCheckShape {
    val complete: Boolean
    val missing: JsReadonlyArray<RefShape>
    val altered: JsReadonlyArray<RefShape>
    val unreadable: JsReadonlyArray<RefShape>
    val entries: JsReadonlyArray<String>
}

/** The Pot's base currency. */
@JsExport
external interface BaseShape {
    val currency: String
    val minorUnits: Int
}

/** A person and when they joined: what the join order reads. */
@JsExport
external interface JoinedShape {
    val id: String
    val joined: Double
}

@JsExport
external interface ParticipantShape : JoinedShape {
    val name: String?
    val active: Boolean
    val erased: Boolean
    val member: String?
}

/** One entry's effect in minor units of the base currency. */
@JsExport
external interface EffectShape {
    val base: bigint
    val paid: JsReadonlyMap<String, bigint>
    val received: JsReadonlyMap<String, bigint>
    val shares: JsReadonlyMap<String, bigint>
}

/** A money record as its latest revision leaves it; `status` is `live`, `deleted`, `invalid` or `unreadable`. */
@JsExport
external interface LedgerEntryShape {
    val id: String
    val revision: Double
    val status: String
    val content: ContentShape?
    val problem: String?
    val effect: EffectShape?
    val edited: Boolean
    val recordedAt: Date
}

/** One transfer of the settle-up. */
@JsExport
external interface TransferShape {
    val from: String
    val to: String
    val amount: bigint
}

@JsExport
external interface PersonTotalShape {
    val paid: bigint
    val share: bigint
}

@JsExport
external interface OverviewShape {
    val byCategory: JsReadonlyMap<String, bigint>
    val byPerson: JsReadonlyMap<String, PersonTotalShape>
    val byCategoryAndPerson: JsReadonlyMap<String, JsReadonlyMap<String, bigint>>
}

/** The Pot's ledger. */
@JsExport
external interface LedgerShape {
    val participants: JsReadonlyArray<ParticipantShape>
    val entries: JsReadonlyArray<LedgerEntryShape>
    val balances: JsReadonlyMap<String, bigint>
    val settleUp: JsReadonlyArray<TransferShape>
    val overview: OverviewShape
}

/** The format's numbers and the sources of its patterns (the schema's), for a caller that checks a field as it is typed. */
@JsExport
external interface FormatRulesShape {
    val uuid: String
    val digest: String
    val currency: String
    val date: String
    val rate: String
    val maxHeads: Int
    val maxReceipts: Int
    val percentWhole: Int
    val defaultCategories: JsReadonlyArray<String>
    val sizeClasses: JsReadonlyArray<Int>
}

/** One currency's row of a day's table, and a rate the editor fills: a decimal string and the day it is of. */
@JsExport
external interface RateShape {
    val rate: String
    val date: String
}
