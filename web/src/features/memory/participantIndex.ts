// uc.memory.participants (web) — the profile page's list: which Memories a person took part in (ADR 0083 decision 6). The
// server holds no such relation (operator-blind), so it is COMPUTED HERE, by a client that holds the keys: a lazily built,
// in-memory index `Map<userId, Set<memoryRef>>` over the tenant scope and every group scope the viewer is in - never the
// personal scope (no participants there) - by listing each scope's tree, opening each Memory's key down the chain and its
// sealed meta, and reading the `participants` sub-object. Access is enforced for free: only what the viewer can list and
// open is ever read, so a Memory they cannot reach is simply absent.
//
// **Revalidated, not rebuilt (ADR 0081).** Each scope's result is kept with the tree revision (`ETag`) it was read at; the next
// open asks `fetchTree(scope, etag)` and a `304` reuses the entry with no rows, no scope grant opened and no decryption. A
// scope that moved is read again whole. The entries hold the Memories' content keys (the page's covers open under them), so
// the whole cache is dropped - keys zeroed - on every scope reset (`memoriesLifecycle.onScopeReset`: a scope switch, a lock,
// a sign-out) and a build that began before it is abandoned (`generation`).
//
// **Bounded.** At most {@link MAX_MEMORIES_PER_SCOPE} Memories of a scope are opened; the rest are not read and the result says
// `partial` - the page says so honestly rather than pretend the list is whole. A scope that cannot be read (offline, a grant
// that does not open) counts as `failedScopes` and also makes the result partial; every scope failing is an error.
import { fetchTree, openTree, childEntryKey, openContentKey, type TreeNode } from '../../core/node/tree'
import { keyAt, zeroEpochKeys, type EpochKeys } from '../spaces/epochs'
import { listMyScopes, openScopeById } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { normalizeParticipants } from './memoryParticipants'
import { normalizeDate, type MemoryDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { MEMORY_KIND } from './kinds'

/** Memories opened per scope before the walk stops and the result is partial. */
export const MAX_MEMORIES_PER_SCOPE = 500

/** A Memory the index found participants on. */
export interface ParticipatedMemory {
  /** `<scopeId>/<nodeId>` - what the per-user sets hold. */
  ref: string
  scopeId: string
  /** The leading fragment token of the Memory's scope (`''` the tenant's, `@<scopeId>` a group's). */
  scopeToken: string
  /** The group's name, '' for the tenant scope. */
  scopeLabel: string
  node: TreeNode
  /** The scope's whole opened tree (names only, no keys): the fragment of the Memory is built from it. */
  tree: TreeNode[]
  /** The Memory's content key (its cover opens under it). Dropped with the cache. */
  key: Uint8Array
  date: MemoryDate | null
  placeName: string
  userIds: string[]
}

export interface ParticipantIndex {
  /** userId -> the refs of the Memories they take part in. */
  byUser: ReadonlyMap<string, ReadonlySet<string>>
  memories: ReadonlyMap<string, ParticipatedMemory>
  /** Some Memories were not read (a scope over the cap, or a scope that failed). */
  partial: boolean
}

interface ScopeEntry {
  etag: string | null
  label: string
  memories: ParticipatedMemory[]
  capped: boolean
}

const cache = new Map<string, ScopeEntry>()
let generation = 0

/** Drop everything the index holds, zeroing the keys; any build in flight is abandoned. */
export function resetParticipantIndex(): void {
  generation++
  for (const entry of cache.values()) for (const m of entry.memories) m.key.fill(0)
  cache.clear()
}

class Abandoned extends Error {}

/** A memoizing resolver of node content keys over one opened tree (the chain is walked once per node, not once per Memory). */
function keyResolver(scopeId: string, epochs: EpochKeys, nodes: readonly TreeNode[]): (node: TreeNode) => Uint8Array | null {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const keys = new Map<string, Uint8Array | null>()
  const resolve = (node: TreeNode, depth: number): Uint8Array | null => {
    if (keys.has(node.id)) return keys.get(node.id)!
    let key: Uint8Array | null = null
    try {
      if (node.parentId === null) key = openContentKey(scopeId, keyAt(epochs, node.epoch), node)
      else if (depth <= nodes.length) {
        const parent = byId.get(node.parentId)
        const parentKey = parent ? resolve(parent, depth + 1) : null
        if (parent && parentKey) key = openContentKey(scopeId, childEntryKey(scopeId, parent, parentKey, node), node)
      }
    } catch {
      key = null // a chain that does not open: this node is not read
    }
    keys.set(node.id, key)
    return key
  }
  return (node) => resolve(node, 0)
}

function readScope(scopeId: string, scopeToken: string, label: string, epochs: EpochKeys, rows: Parameters<typeof openTree>[2], etag: string | null): ScopeEntry {
  const nodes = openTree(scopeId, epochs, rows)
  const keyOf = keyResolver(scopeId, epochs, nodes)
  const memories: ParticipatedMemory[] = []
  let opened = 0
  let capped = false
  for (const node of nodes) {
    if (node.kind !== MEMORY_KIND) continue
    if (opened >= MAX_MEMORIES_PER_SCOPE) {
      capped = true
      break
    }
    opened++
    const key = keyOf(node)
    if (!key) continue
    const meta = unsealNodeMeta(key, { scopeId, nodeId: node.id }, node.wrappedMeta)
    const userIds = normalizeParticipants(meta.participants)
    if (userIds.length === 0) continue
    // The key outlives the scope's epoch keys (zeroed by the caller): copy, the chain's arrays are not ours.
    memories.push({
      ref: `${scopeId}/${node.id}`,
      scopeId,
      scopeToken,
      scopeLabel: label,
      node,
      tree: nodes,
      key: key.slice(),
      date: normalizeDate(meta.date),
      placeName: normalizeLocation(meta.location)?.name ?? '',
      userIds,
    })
  }
  return { etag, label, memories, capped }
}

function indexOf(entries: ScopeEntry[], failed: number): ParticipantIndex {
  const byUser = new Map<string, Set<string>>()
  const memories = new Map<string, ParticipatedMemory>()
  for (const entry of entries) {
    for (const m of entry.memories) {
      memories.set(m.ref, m)
      for (const id of m.userIds) {
        const set = byUser.get(id) ?? new Set<string>()
        set.add(m.ref)
        byUser.set(id, set)
      }
    }
  }
  return { byUser, memories, partial: failed > 0 || entries.some((e) => e.capped) }
}

/**
 * Build (or revalidate) the index over the tenant and the viewer's group scopes. Rejects when no scope could be read at
 * all; otherwise resolves, `partial` when a scope failed or was capped. Rejects with `Abandoned` (never shown) if a scope
 * reset happened meanwhile - the caller discards the answer.
 */
export async function loadParticipantIndex(): Promise<ParticipantIndex> {
  const started = generation
  const alive = () => {
    if (generation !== started) throw new Abandoned()
  }
  const scopes = (await listMyScopes()).filter((s) => s.kind === ScopeKind.Tenant || s.kind === ScopeKind.Group)
  alive()
  const entries: ScopeEntry[] = []
  let failed = 0
  let lastError: unknown = null
  for (const scope of scopes) {
    try {
      const prev = cache.get(scope.id)
      const read = await fetchTree(scope.id, prev?.etag ?? null)
      alive()
      if (!read && prev) {
        entries.push(prev) // 304: the revision is the one this entry was read at
        continue
      }
      if (!read) throw new Error('tree_unchanged_without_entry')
      const opened = await openScopeById(scope.id)
      alive()
      if (!opened) throw new Error('scope_not_openable')
      try {
        const token = scope.kind === ScopeKind.Tenant ? '' : `@${scope.id}`
        const entry = readScope(scope.id, token, scope.kind === ScopeKind.Group ? (opened.name ?? '') : '', opened.epochs, read.rows, read.etag)
        alive()
        const old = cache.get(scope.id)
        if (old) for (const m of old.memories) m.key.fill(0)
        cache.set(scope.id, entry)
        entries.push(entry)
      } finally {
        zeroEpochKeys(opened.epochs)
      }
    } catch (e) {
      if (e instanceof Abandoned) throw e
      failed++
      lastError = e
    }
  }
  if (entries.length === 0 && failed > 0) throw lastError
  return indexOf(entries, failed)
}

/** True for the rejection of a build that a scope reset overtook: the caller drops it silently. */
export const isAbandoned = (e: unknown): boolean => e instanceof Abandoned
