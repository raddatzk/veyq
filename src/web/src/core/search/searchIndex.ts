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
// **In slices** (cr-search-at-volume): opening a tree and making its documents run as steps, at most {@link SLICE_MS} at a
// time, so a space of thousands of nodes never holds the main thread whole - a keystroke or a paint gets in between; a
// reset between two slices abandons the build (its keys zeroed all the same).
//
// **The active space is the tree on screen.** While the member's space page holds the active space's tree (`treeStore`), its
// documents are derived from that tree whenever it changes - the tree poll the page already runs brings a rename here
// without a fetch of the search's own (uc A1) - under the key `activeSpace` owns (never zeroed here). Its build starts
// first, in slices like the others'; the other spaces follow.
//
// **An application's objects of every space** (ADR 0125 decision 8a): after a space's nodes, each application's
// `ApplicationSearch.scopeDocuments` is asked for the objects inside them (Memories: every photo and video) - for every space
// on every walk, the active one included, since an object's change moves no tree revision (a tree `304` would hide it). The
// hook reads its own listing by its own tag; only when that moved does it ask for the space's node keys (`keys()`), which a
// walk whose tree moved holds already and one whose tree did not opens then. Every node key it was handed is zeroed when it
// returns, the scope key with the walk's (never the active space's, which `activeSpace` owns). A failing hook is a space not
// searched, as a tree that does not open is.
//
// **Nothing persists.** No storage of any kind is written, and the query never leaves this module's callers. A build that a
// sign-out overtook is abandoned (`generation`, as `participantIndex` does; `aborter` stops an application's fetch);
// {@link resetSearchIndex} (called by `activeSpace.clearActiveSpace`) drops every document.
import { effectScope, reactive, watch, type EffectScope } from 'vue'
import { listMyScopes, listMyTenants, openListedScope, type OpenedScope } from '../../features/spaces/scopes'
import { zeroScopeKey, type ScopeKey } from '../../features/spaces/epochs'
import { ScopeKind, type ScopeSummary } from '../../api/generated/scopes'
import { activeEpochKeys, activeSpace } from '../../app/activeSpace'
import { registry } from '../applications/registry'
import { keyResolver, type KeyResolver } from '../node/keyResolver'
import { fetchTree, openTreeSteps, type TreeNode } from '../node/tree'
import { shownTreeTag, treeStore } from '../node/treeStore'
import { nodeDocumentSteps, scopeDocumentSteps, type NodeDocument } from './documents'
import type { ScopeDocument, SearchDocument } from './model'

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

/** One application's objects of one space: the tag its hook answered and what it found, as it found it and as documents. */
interface ObjectsEntry {
  tag: string | null
  objects: Map<string, ScopeDocument>
  documents: SearchDocument[]
}

const entries = new Map<string, Entry>()
/** By space, then by application. Kept apart from {@link entries}: the active space's entry is rebuilt from the tree on
 *  screen without asking any application. */
const objectEntries = new Map<string, Map<string, ObjectsEntry>>()
let aborter = new AbortController()
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

/** The active space's build in flight (its number, its space): a newer tree on screen, a reset or a space left supersedes it. */
let activeRun = 0
let activeRunScope: string | null = null

/**
 * The active space's entry from the tree on screen, built in slices like any other space's (cr-search-at-volume): the
 * documents of a big space on screen are made a slice at a time, and the space counts as loading once a build pauses (a
 * small tree, or one whose documents all stand, is built at once). The scope key is `activeSpace`'s: a build that meets
 * another space, another key or a reset after a pause is abandoned, and only the node keys it opened are zeroed.
 */
function followActive(): void {
  const scopeId = activeSpace.scopeId
  const epochs = activeEpochKeys()
  const kind = activeSpace.kind
  if (!scopeId || !epochs || !servedByTree(scopeId) || !kind) return
  const run = ++activeRun
  activeRunScope = scopeId
  const started = generation
  const nodes = treeStore.nodes
  const etag = shownTreeTag()
  const prev = entries.get(scopeId)
  const current = () => run === activeRun && generation === started && activeSpace.scopeId === scopeId && activeEpochKeys() === epochs
  const alive = () => {
    if (!current()) throw new Abandoned()
    if (!searchState.loading.includes(scopeId)) searchState.loading = [scopeId, ...searchState.loading]
  }
  // a newer build of the same space owns its loading mark; one of another space does not
  const done = () => {
    if (run === activeRun || activeRunScope !== scopeId) searchState.loading = searchState.loading.filter((id) => id !== scopeId)
  }
  void inSlices(nodeDocumentSteps(scopeId, epochs, nodes, prev?.documents), alive).then(
    (documents) => {
      done()
      if (!current()) return
      const before = entries.get(scopeId)
      const name = kind === ScopeKind.Group ? activeSpace.name : kind === ScopeKind.Tenant ? (before?.scope.name ?? tenantName) : null
      entries.set(scopeId, { scope: { id: scopeId, kind, name }, etag, nameSeal: before?.nameSeal ?? null, nodes, documents })
      searchState.failed = searchState.failed.filter((s) => s.id !== scopeId)
      bump()
    },
    (e: unknown) => {
      done()
      if (!(e instanceof Abandoned)) throw e
    },
  )
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
      for (const id of [...objectEntries.keys()]) if (!servedByTree(id)) objectEntries.delete(id)
      searchState.listFailed = true
      searchState.loading = []
      bump()
      return
    }
    searchState.listFailed = false
    const listed = new Set(scopes.map((s) => s.id))
    for (const id of [...entries.keys()]) if (!listed.has(id)) entries.delete(id)
    for (const id of [...objectEntries.keys()]) if (!listed.has(id)) objectEntries.delete(id)
    // The active space first (uc step 2), then the rest as listed.
    const ordered = [...scopes].sort((a, b) => Number(b.id === activeSpace.scopeId) - Number(a.id === activeSpace.scopeId))
    searchState.scopes = ordered.map((s) => scopeOf(s, entries.get(s.id)?.scope.name))
    // the active space is loading only while a build of its own pauses (followActive)
    searchState.loading = ordered.filter((s) => !servedByTree(s.id) || searchState.loading.includes(s.id)).map((s) => s.id)
    searchState.failed = []
    bump()
    for (const s of ordered) {
      if (servedByTree(s.id)) {
        followActive()
        if (!searchState.loading.includes(s.id)) searchState.loading = [s.id, ...searchState.loading]
        try {
          await readActiveObjects(s.id, alive)
        } catch (e) {
          if (e instanceof Abandoned) throw e
          objectEntries.delete(s.id)
          searchState.failed = [...searchState.failed, scopeOf(s, indexedScope(s.id)?.name)]
        }
        searchState.loading = searchState.loading.filter((id) => id !== s.id)
        bump()
        continue
      }
      try {
        await readSpace(s, alive)
      } catch (e) {
        if (e instanceof Abandoned) throw e
        const known = indexedScope(s.id)?.name
        entries.delete(s.id)
        objectEntries.delete(s.id)
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
  if (!read && !prev) throw new Error('tree_unchanged_without_entry')
  // The scope key, opened when the tree moved or an application asks for the node keys, and zeroed however this ends.
  const held: { scope: OpenedScope | null } = { scope: null }
  const open = async (): Promise<ScopeKey> => {
    held.scope ??= await openListedScope(s)
    if (!held.scope) throw new Error('scope_not_openable')
    return held.scope.epochs
  }
  try {
    let nodes = prev?.nodes ?? []
    if (read) {
      const epochs = await open()
      alive()
      nodes = await inSlices(openTreeSteps(s.id, epochs, read.rows), alive)
      const documents = await inSlices(nodeDocumentSteps(s.id, epochs, nodes, prev?.documents), alive)
      const name = held.scope?.name ?? null
      entries.set(s.id, { scope: scopeOf(s, name), etag: read.etag, nameSeal, nodes, documents })
      searchState.scopes = searchState.scopes.map((x) => (x.id === s.id ? scopeOf(s, name) : x))
    } // else 304: the tree is the one the entry was read at
    await readObjects(s.id, nodes, open, alive)
  } finally {
    if (held.scope) zeroScopeKey(held.scope.epochs)
  }
}

/** The active space's objects: its nodes are the tree on screen, its scope key `activeSpace`'s (never zeroed here). */
async function readActiveObjects(scopeId: string, alive: () => void): Promise<void> {
  const nodes = treeStore.nodes
  await readObjects(scopeId, nodes, async () => {
    const epochs = activeEpochKeys()
    if (!epochs || activeSpace.scopeId !== scopeId) throw new Abandoned()
    return epochs
  }, alive)
}

/**
 * Ask every application for its objects of one space (see the header): `nodes` is the space's opened tree, `open` the scope
 * key when an application asks for node keys. The node keys are opened by one resolver over the tree and zeroed - every one
 * of them - once the application's hook has returned, whatever it did.
 */
async function readObjects(scopeId: string, nodes: readonly TreeNode[], open: () => Promise<ScopeKey>, alive: () => void): Promise<void> {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  for (const { application, search } of registry.searches()) {
    const prev = objectEntries.get(scopeId)?.get(application)
    let resolver: KeyResolver | null = null
    try {
      const answer = await search.scopeDocuments({
        scopeId,
        tag: prev?.tag ?? null,
        previous: prev?.objects ?? new Map(),
        signal: aborter.signal,
        slices: (steps) => inSlices(steps, alive),
        keys: async () => {
          if (!resolver) {
            const epochs = await open()
            alive()
            resolver = keyResolver(scopeId, epochs, nodes)
          }
          const r = resolver
          return (nodeId) => {
            const node = byId.get(nodeId)
            return node ? r.keyOf(node) : null
          }
        },
      })
      alive()
      // Unchanged: the documents stand, their paths included - Memories' tag follows the tree revision, so a rename is a
      // new answer (ADR 0125 decision 8a).
      if (answer === null) {
        if (!prev) throw new Error('objects_unchanged_without_entry')
        continue
      }
      const documents = await inSlices(scopeDocumentSteps(scopeId, answer.documents, byId), alive)
      let byApp = objectEntries.get(scopeId)
      if (!byApp) objectEntries.set(scopeId, (byApp = new Map()))
      byApp.set(application, { tag: answer.tag, objects: new Map(answer.documents.map((d) => [d.id, d])), documents })
    } finally {
      ;(resolver as KeyResolver | null)?.zero()
    }
  }
}

/** How long one slice of a space's build may hold the main thread, in the page's own milliseconds - a throttled CPU
 *  measures slower milliseconds, so the slice stays this long on any device (cr-search-at-volume). */
export const SLICE_MS = 40

/** Hand the main thread back (a typed key, a paint) between two slices. */
const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

/**
 * Run `steps` to their result in slices of about {@link SLICE_MS}, pausing between two and asking `alive` after each pause
 * (a reset in the pause abandons the build). However it ends - its result, a throw, abandoned - the steps are closed, so
 * the keys a step holds are zeroed in its own `finally`.
 */
async function inSlices<T>(steps: Generator<void, T, void>, alive: () => void): Promise<T> {
  try {
    let started = performance.now()
    for (;;) {
      const step = steps.next()
      if (step.done) return step.value
      if (performance.now() - started >= SLICE_MS) {
        await pause()
        alive()
        started = performance.now()
      }
    }
  } finally {
    steps.return(undefined as never)
  }
}

/** Every document the index holds (reactive on `searchState.revision`). */
export function indexedDocuments(): readonly SearchDocument[] {
  const revision = searchState.revision
  if (flat?.revision !== revision) {
    const docs = [...entries.values()].flatMap((e) => [...e.documents.values()].map((d) => d.doc))
    for (const byApp of objectEntries.values()) for (const o of byApp.values()) for (const d of o.documents) docs.push(d)
    flat = { revision, docs }
  }
  return flat.docs
}

/** A space's opened tree (names only, no keys) as the index holds it, or null (reactive on `searchState.revision`: a field
 *  mounted before the index was built - a reload on a Memory's page - must see the tree once it is). */
export function indexedTree(scopeId: string): readonly TreeNode[] | null {
  void searchState.revision
  return entries.get(scopeId)?.nodes ?? null
}

/** The space the index knows by `scopeId`, or null. */
export function indexedScope(scopeId: string): SearchScope | null {
  void searchState.revision
  return entries.get(scopeId)?.scope ?? searchState.scopes.find((s) => s.id === scopeId) ?? null
}

/** Drop every document (sign-out, re-lock): a build in flight is abandoned, and nothing follows the tree any more. */
export function resetSearchIndex(): void {
  generation++
  aborter.abort()
  aborter = new AbortController()
  entries.clear()
  objectEntries.clear()
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
