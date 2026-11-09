// Opening an object a space's search found outside the page on screen (ADR 0125 decision 8a): a photo in another Memory.
// The search navigates to the object's node as it does to a node (its fragment, ADR 0015) and leaves this one request
// behind; the node's page takes it once it holds the object and opens it there (Memories: the lightbox on the medium).
//
// **Not a deep link.** The request lives in memory only, for the one navigation the search starts: a reload, a copied
// address or the back button carries no object, and the fragment grammar is unchanged - an object's own address is an open
// question (the CR hands it back). A request the page cannot answer (the object gone meanwhile) is dropped when the page
// says it has read its objects, and a newer choice replaces an older one, so none outlives the navigation it was made for.
import { shallowRef } from 'vue'

/** An object to open on its node's page. */
export interface SearchOpenRequest {
  scopeId: string
  nodeId: string
  /** The application's object id (a medium's id). */
  itemId: string
}

/** The one request in force (reactive: a page watches it). */
export const searchOpenRequest = shallowRef<SearchOpenRequest | null>(null)

/** The search: open `itemId` on its node's page once it shows. */
export function requestOpenItem(request: SearchOpenRequest): void {
  searchOpenRequest.value = request
}

/**
 * A node's page: the object the search asked it to open, if the request is this node's. `has` says whether the page holds
 * the object; `settled` that it has read all it will (so an object it lacks is not coming). Returns the id to open and
 * clears the request, or null - clearing a request for this node that `settled` cannot answer.
 */
export function takeOpenRequest(scopeId: string, nodeId: string, has: (itemId: string) => boolean, settled: boolean): string | null {
  const r = searchOpenRequest.value
  if (!r || r.scopeId !== scopeId || r.nodeId !== nodeId) return null
  if (has(r.itemId)) {
    searchOpenRequest.value = null
    return r.itemId
  }
  if (settled) searchOpenRequest.value = null
  return null
}
