package me.raddatz.veyq.mobile.search

import kotlinx.serialization.json.JsonObject
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.TenantSession
import me.raddatz.veyq.mobile.words.Msg

/**
 * What each application contributes to the search (ADR 0146 decision 4; the web's `KindDescriptor.search` and
 * `ApplicationRegistrar.search`): core names no application — `AppKinds.search` is the one place that wires an
 * application's part in, as the web's registry is (`scripts/validate_mobile_search_names_no_application.py`).
 */
interface SearchKinds {
    /** The search part of node kind [kind], or null for a kind this build does not search by a word of its own. */
    fun kind(kind: String): KindSearch?

    /** Every node kind with a search part, in the build's order. */
    val kinds: List<String>

    /** Each application's search over a whole space, with the application's id (`ApplicationId`'s, lower-cased). */
    val applications: List<Pair<String, ApplicationSearch>>
}

/**
 * A node kind's part (the web's `KindSearch`): its kind chip's [word], the icon kind its rows draw, the facets read from its
 * opened meta ([facets]: handed the plaintext meta core opened under the node's key, never the key — a tighter door than
 * the web's, which hands the key), and the kinds of documents its application's space search makes ([documents]).
 */
class KindSearch(
    val word: Msg,
    val icon: String,
    val facets: ((meta: JsonObject, nowMs: Long) -> SearchFacets)? = null,
    val documents: List<DocumentKind> = emptyList(),
)

/** A kind of document an application's space search makes (Memories' photo, video): its word and its icon kind. */
class DocumentKind(val kind: String, val word: Msg, val icon: String)

/**
 * An application's search over one whole space (the web's `ApplicationSearch.scopeDocuments`, ADR 0125 decision 8a): the
 * objects inside the space's nodes (Memories: every photo and video), read by the listing's own [tag] — null where it did
 * not move (a `304`: the previous documents stand). [nodes] is the shell's opened tree of the space, by id: the keys an
 * application opens its objects under are read in place there, never copied, never zeroed by it — the shell holds them
 * (ADR 0146 decision 5); what it unwraps itself it zeroes before it returns.
 */
fun interface ApplicationSearch {
    @Throws(Exception::class)
    suspend fun scopeDocuments(ctx: ScopeDocumentsContext): ScopeDocumentsAnswer?
}

class ScopeDocumentsContext(
    val tenant: TenantSession,
    val scopeId: String,
    /** The tag of the last answer, or null: read whole. */
    val tag: String?,
    /** The last answer's objects, by id: one at an unchanged revision is kept, not opened again. */
    val previous: Map<String, ScopeDocument>,
    val nodes: Map<String, OpenNode>,
    /** An object's name where it carries none (a photo's, a video's word). */
    val unnamed: (video: Boolean) -> String,
)

/** An application's answer for one space: the listing's [tag] and its objects. */
class ScopeDocumentsAnswer(val tag: String?, val documents: List<ScopeDocument>)
