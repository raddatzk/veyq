// The search's index (ADR 0125 decisions 2 and 3, uc.platform.search steps 2-3): the documents of every space the member
// belongs to in this tenant, held in memory, one entry per space - `{ etag, nodes, documents }`.
//
// **Built on the first focus, revalidated on every later one.** {@link refreshSearchIndex} lists the member's spaces once and
// walks them in a row: each tree is read conditionally with the tag its entry was read at (ADR 0081) - a `304` keeps the
// entry untouched, a tree that moved is opened with that space's key (`openListedScope`, the grant verified as entering the
// space would), turned into documents and its key zeroed in a `finally`. A space whose tree no longer fetches or opens (its
// key not on this device yet, the member removed, a rotation in flight) loses its documents and is named as not searched; a
// space no longer listed loses its documents too. **The active space does not change** - nothing here enters a space.
//
// **The active space is the tree on screen.** While the member's space page holds the active space's tree (`treeStore`), its
// documents are derived from that tree whenever it changes - the tree poll the page already runs brings a rename here
// without a fetch of the search's own (uc A1) - under the key `activeSpace` owns (never zeroed here). It is built first, at
// once; the other spaces follow.
//
// **Nothing persists.** No storage of any kind is written, no endpoint is added, the query never leaves this module's
// callers. A build that a sign-out overtook is abandoned (`generation`, as `participantIndex` does); {@link resetSearchIndex}
// (called by `activeSpace.clearActiveSpace`) drops every document.
import { effectScope, reactive, watch, type EffectScope } from 'vue'
import { listMyScopes, listMyTenants, openListedScope } from '../../features/spaces/scopes'
import { zeroScopeKey } from '../../features/spaces/epochs'
import { ScopeKind, type ScopeSummary } from '../../api/generated/scopes'
import { activeEpochKeys, activeSpace } from '../../app/activeSpace'
import { fetchTree, openTree, type TreeNode } from '../node/tree'
import { shownTreeTag, treeStore } from '../node/treeStore'
import { nodeDocuments, type NodeDocument } from './documents'
import type { SearchDocument } from './model'

/** A space as the search names it. */
export interface SearchScope {
  id: string
  kind: ScopeKind
  /** A group's name as it opened, the tenant's name; null for the personal space or a name that did not open. */
  name: string | null
}

interface Entry {
  scope: SearchScope
  /** The tree revision the documents were read at (null: unknown, the next read is a whole one). */
  etag: string | null
  /** The group's sealed name the entry was read with: a renamed group is opened again even when its tree did not move. */
  nameSeal: string | null
  /** The opened tree (names, no keys): where a result lives and how its fragment is built. */
  nodes: readonly TreeNode[]
  documents: Map<string, NodeDocument>
}

/** What the field shows about the index (reactive; the documents themselves are not). */
export const searchState = reactive({
  /** The index was asked for (the first focus): from then on the active space follows the tree on screen. */
  started: false,
  /** The spaces whose tree is still being read. */
  loading: [] as string[],
  /** The spaces that could not be searched (E1). */
  failed: [] as SearchScope[],
  /** The member's spaces could not be listed: only the active space is searched. */
  listFailed: false,
  /** Every space of the member in this tenant, as last listed. */
  scopes: [] as SearchScope[],
  /** Bumped whenever the documents change. */
  revision: 0,
})

const entries = new Map<string, Entry>()
let generation = 0
let refreshing: Promise<void> | null = null
let following: EffectScope | null = null
let tenantName: string | null = null
let flat: { revision: number; docs: SearchDocument[] } | null = null

class Abandoned extends Error {}

const bump = () => {
  searchState.revision++
}

/** Whether the active space's documents come from the tree on screen (no fetch of the search's own). */
function servedByTree(scopeId: string): boolean {
  return scopeId === activeSpace.scopeId && activeSpace.status === 'ready' && treeStore.loaded && activeEpochKeys() !== null
}

function scopeOf(s: ScopeSummary, groupName?: string | null): SearchScope {
  const name = s.kind === ScopeKind.Group ? (groupName ?? null) : s.kind === ScopeKind.Tenant ? tenantName : null
  return { id: s.id, kind: s.kind, name }
}

/** The active space's entry from the tree on screen. */
function followActive(): void {
  const scopeId = activeSpace.scopeId
  const epochs = activeEpochKeys()
  if (!scopeId || !epochs || !servedByTree(scopeId) || !activeSpace.kind) return
  const prev = entries.get(scopeId)
  const name = activeSpace.kind === ScopeKind.Group ? activeSpace.name : activeSpace.kind === ScopeKind.Tenant ? (prev?.scope.name ?? tenantName) : null
  entries.set(scopeId, {
    scope: { id: scopeId, kind: activeSpace.kind, name },
    etag: shownTreeTag(),
    nameSeal: prev?.nameSeal ?? null,
    nodes: treeStore.nodes,
    documents: nodeDocuments(scopeId, epochs, treeStore.nodes, prev?.documents),
  })
  searchState.failed = searchState.failed.filter((s) => s.id !== scopeId)
  bump()
}

/** Ask for the index: the first call starts it, every call revalidates every space (coalesced while one runs). */
export function refreshSearchIndex(): Promise<void> {
  if (!searchState.started) {
    searchState.started = true
    following = effectScope(true)
    // The tree on screen, a meta patched in place included (depth 2: the nodes' own fields).
    following.run(() =>
      watch([() => treeStore.nodes, () => treeStore.loaded, () => activeSpace.scopeId, () => activeSpace.status, () => activeSpace.epoch], followActive, { deep: 2, immediate: true }),
    )
  }
  if (refreshing) return refreshing
  const run: Promise<void> = walk().finally(() => {
    if (refreshing === run) refreshing = null
  })
  refreshing = run
  return run
}

async function walk(): Promise<void> {
  const started = generation
  const alive = () => {
    if (generation !== started) throw new Abandoned()
  }
  try {
    let scopes: ScopeSummary[]
    try {
      const [listed, tenants] = await Promise.all([listMyScopes(), listMyTenants().catch(() => [])])
      alive()
      scopes = listed
      tenantName = tenants.find((t) => t.tenantId === activeSpace.tenantId)?.name ?? null
    } catch (e) {
      if (e instanceof Abandoned) throw e
      // Nothing could be listed: no space but the one on screen is searched, and none is pretended to be.
      for (const id of [...entries.keys()]) if (!servedByTree(id)) entries.delete(id)
      searchState.listFailed = true
      searchState.loading = []
      bump()
      return
    }
    searchState.listFailed = false
    const listed = new Set(scopes.map((s) => s.id))
    for (const id of [...entries.keys()]) if (!listed.has(id)) entries.delete(id)
    // The active space first (uc step 2), then the rest as listed.
    const ordered = [...scopes].sort((a, b) => Number(b.id === activeSpace.scopeId) - Number(a.id === activeSpace.scopeId))
    searchState.scopes = ordered.map((s) => scopeOf(s, entries.get(s.id)?.scope.name))
    searchState.loading = ordered.filter((s) => !servedByTree(s.id)).map((s) => s.id)
    searchState.failed = []
    bump()
    for (const s of ordered) {
      if (servedByTree(s.id)) {
        followActive()
        continue
      }
      try {
        await readSpace(s, alive)
      } catch (e) {
        if (e instanceof Abandoned) throw e
        const known = indexedScope(s.id)?.name
        entries.delete(s.id)
        searchState.failed = [...searchState.failed, scopeOf(s, known)]
      }
      searchState.loading = searchState.loading.filter((id) => id !== s.id)
      bump()
    }
  } catch (e) {
    if (!(e instanceof Abandoned)) throw e
  }
}

async function readSpace(s: ScopeSummary, alive: () => void): Promise<void> {
  const prev = entries.get(s.id)
  const nameSeal = s.encryptedName ?? null
  const tag = prev && prev.nameSeal === nameSeal ? prev.etag : null
  const read = await fetchTree(s.id, tag)
  alive()
  if (!read) {
    if (!prev) throw new Error('tree_unchanged_without_entry')
    return // 304: the tree is the one the entry was read at
  }
  const opened = await openListedScope(s)
  if (!opened) throw new Error('scope_not_openable')
  try {
    alive()
    const nodes = openTree(s.id, opened.epochs, read.rows)
    const documents = nodeDocuments(s.id, opened.epochs, nodes, prev?.documents)
    entries.set(s.id, { scope: scopeOf(s, opened.name ?? null), etag: read.etag, nameSeal, nodes, documents })
    searchState.scopes = searchState.scopes.map((x) => (x.id === s.id ? scopeOf(s, opened.name ?? null) : x))
  } finally {
    zeroScopeKey(opened.epochs)
  }
}

/** Every document the index holds (reactive on `searchState.revision`). */
export function indexedDocuments(): readonly SearchDocument[] {
  const revision = searchState.revision
  if (flat?.revision !== revision) {
    flat = { revision, docs: [...entries.values()].flatMap((e) => [...e.documents.values()].map((d) => d.doc)) }
  }
  return flat.docs
}

/** A space's opened tree (names only, no keys) as the index holds it, or null. */
export function indexedTree(scopeId: string): readonly TreeNode[] | null {
  return entries.get(scopeId)?.nodes ?? null
}

/** The space the index knows by `scopeId`, or null. */
export function indexedScope(scopeId: string): SearchScope | null {
  return entries.get(scopeId)?.scope ?? searchState.scopes.find((s) => s.id === scopeId) ?? null
}

/** Drop every document (sign-out, re-lock): a build in flight is abandoned, and nothing follows the tree any more. */
export function resetSearchIndex(): void {
  generation++
  entries.clear()
  flat = null
  tenantName = null
  refreshing = null
  following?.stop()
  following = null
  searchState.started = false
  searchState.loading = []
  searchState.failed = []
  searchState.listFailed = false
  searchState.scopes = []
  bump()
}
