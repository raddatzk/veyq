package me.raddatz.veyq.mobile.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Opening an object a space's search found outside the page on screen (ADR 0146 decision 8; the web's `openRequest.ts`): a
 * photo in another Memory. The search opens the object's node through the shell and leaves this one request; the node's
 * page takes it once it holds the object and opens it there (Memories: the lightbox on the medium). In memory only, for the
 * one opening the search starts: a request the page cannot answer once it has read its objects is dropped, and a newer
 * choice replaces an older one. One per tenant's shell.
 */
class OpenRequests {
    /** An object to open on its node's page. */
    data class Request(val scopeId: String, val nodeId: String, val itemId: String)

    private val _request = MutableStateFlow<Request?>(null)

    /** The one request in force (a page follows it). */
    val request: StateFlow<Request?> = _request

    /** The search: open [request]'s object on its node's page once it shows. */
    fun request(request: Request) {
        _request.value = request
    }

    /**
     * A node's page: the object the search asked it to open, if the request is this node's. [has] says whether the page holds
     * the object; [settled] that it has read all it will. The id to open (the request cleared), or null — a request for this
     * node that [settled] cannot answer is cleared too.
     */
    fun take(scopeId: String, nodeId: String, has: (String) -> Boolean, settled: Boolean): String? {
        val r = _request.value ?: return null
        if (r.scopeId != scopeId || r.nodeId != nodeId) return null
        if (has(r.itemId)) {
            _request.compareAndSet(r, null)
            return r.itemId
        }
        if (settled) _request.compareAndSet(r, null)
        return null
    }

    /** The tenant closed: nothing is asked any more. */
    fun clear() {
        _request.value = null
    }
}

/**
 * What the page of the node on screen adds to the search (ADR 0146 decision 8; the web's `contribution.ts`): documents only
 * it holds — Memories' photos and videos of the open Memory, from its already-opened listing, at no fetch of their own.
 * They win over the same medium from the space's listing and open in place. One page contributes at a time (the last
 * one wins); a page withdraws its contribution when it closes.
 */
class SearchContributions {
    /** A page's contribution: the node it is the page of, and the documents it holds. */
    data class Contribution(val scopeId: String, val nodeId: String, val documents: List<ContributedDocument>)

    private val _current = MutableStateFlow<Contribution?>(null)

    /** The contribution in force (the search re-reads it whenever it moves). */
    val current: StateFlow<Contribution?> = _current

    /** A page contributes [contribution] (replacing any other page's). */
    fun contribute(contribution: Contribution) {
        _current.value = contribution
    }

    /** The page of [nodeId] in [scopeId] went: its contribution is withdrawn (another page's stays). */
    fun withdraw(scopeId: String, nodeId: String) {
        val c = _current.value ?: return
        if (c.scopeId == scopeId && c.nodeId == nodeId) _current.compareAndSet(c, null)
    }

    fun clear() {
        _current.value = null
    }
}
