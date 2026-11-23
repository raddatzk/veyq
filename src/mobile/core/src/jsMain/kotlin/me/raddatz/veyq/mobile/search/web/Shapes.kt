@file:OptIn(ExperimentalJsExport::class, ExperimentalJsCollectionsApi::class)

package me.raddatz.veyq.mobile.search.web

import kotlin.js.collections.JsReadonlyArray

// The plain values the search's façade takes and returns (ADR 0155 decision 5): each an external interface, so its
// `.d.ts` names every field with its type (never `any`) and the web passes and reads plain objects — no Kotlin class
// crosses but the two opaque handles (`SearchWeb.kt`). They spell the web's own shapes (`core/search/model.ts`,
// `query.ts`), which the web keeps for what it draws.

/** Calendar years, both ends included. */
@JsExport
external interface YearSpanShape {
    val from: Int
    val to: Int
}

/** Calendar months as `YYYY-MM`, both ends included. */
@JsExport
external interface MonthSpanShape {
    val from: String
    val to: String
}

/** What a document carries beyond its name: each field a chip's predicate. */
@JsExport
external interface FacetsShape {
    val years: YearSpanShape?
    val months: MonthSpanShape?
    /** Epoch milliseconds; a value that is not finite is none. */
    val capturedAt: Double?
    val places: JsReadonlyArray<String>?
    val people: JsReadonlyArray<String>?
}

/** One searchable object as the web holds it, without its `open` and `icon`: [opensItself] stands for `open`. */
@JsExport
external interface DocumentShape {
    val scopeId: String
    val kind: String
    val id: String
    val revision: String?
    val name: String
    val path: JsReadonlyArray<String>
    val pathIds: JsReadonlyArray<String>?
    val facets: FacetsShape
    val nodeId: String
    val opensItself: Boolean?
    val aliases: JsReadonlyArray<String>?
    val keywords: JsReadonlyArray<String>?
    val subtitle: String?
}

/** A chip: `type` and the fields of that type (the web's `SearchChip` union, each member assignable to it). */
@JsExport
external interface ChipShape {
    val type: String
    val scopeId: String?
    val nodeId: String?
    val label: String?
    val year: Int?
    val month: Int?
    val place: String?
    val userId: String?
    val kinds: JsReadonlyArray<String>?
    val path: JsReadonlyArray<String>?
}

/** One text and the chips. */
@JsExport
external interface QueryShape {
    val chips: JsReadonlyArray<ChipShape>
    val text: String
}

/** Where the member stands: the space on screen and the node whose page is on screen, each null where none. */
@JsExport
external interface ContextShape {
    val activeScopeId: String?
    val openNodeId: String?
}

@JsExport
external interface SpaceShape {
    val scopeId: String
    val label: String
}

@JsExport
external interface PersonShape {
    val userId: String
    val name: String
}

/** A kind's word with the kinds it names. */
@JsExport
external interface KindWordShape {
    val label: String
    val kinds: JsReadonlyArray<String>
}

/** What a chip may be suggested from: the documents as their corpus handle. */
@JsExport
external interface ChipSourcesShape {
    val corpus: SearchCorpus
    val spaces: JsReadonlyArray<SpaceShape>
    val people: JsReadonlyArray<PersonShape>
    val kinds: JsReadonlyArray<KindWordShape>
    /** Each month's names, January first; none: no month is offered. */
    val months: JsReadonlyArray<JsReadonlyArray<String>>?
}

/** Where each character of a fold came from in the original. */
@JsExport
external interface FoldMapShape {
    val folded: String
    val start: JsReadonlyArray<Int>
    val end: JsReadonlyArray<Int>
}

/** A tail of the typed text that starts at a word, and what stays before it. */
@JsExport
external interface TailShape {
    val tail: String
    val rest: String
}

/** A part of a name as a result row draws it: [strong] where it matched the text. */
@JsExport
external interface NamePartShape {
    val text: String
    val strong: Boolean
}

/** A chip offered for the typed text: [text] is what stays once it is chosen. */
@JsExport
external interface SuggestionShape {
    val chip: ChipShape
    val text: String
}

/** The results as positions in the corpus the web passed, best first, and how many documents match. */
@JsExport
external interface HitsShape {
    val positions: JsReadonlyArray<Int>
    val total: Int
}

/** The ranks of a text match, best first. */
@JsExport
external interface RankShape {
    val exact: Int
    val prefix: Int
    val wordStart: Int
    val inside: Int
    val place: Int
}

/** A value of a chip in force a result carries (the core's `MatchedValue`): [text] shown as it is, or a month chip's
 *  [year] and [month] (1–12), which the web names in its language. */
@JsExport
external interface MatchedShape {
    val text: String?
    val year: Int?
    val month: Int?
}

/** Where the page on screen is (the core's `SearchContext` as `isHere` reads it). */
@JsExport
external interface HereShape {
    val activeScopeId: String?
    val hereNodeId: String?
}

/** The search's numbers a caller reading the same grammar needs (Memories' gallery filter). */
@JsExport
external interface SearchRulesShape {
    val minTail: Int
    val perType: Int
    val rank: RankShape
}
