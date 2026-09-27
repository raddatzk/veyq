// en.memory.node-meta (web) — the space map's pure model (uc.memory.place, cr-0071). Collects the pins for the
// read-only "Karte" view: every content node (MEMORY or CHAPTER) in a folder's subtree whose sealed `location`
// carries a `derived` position, unsealed **on device** under its own `K_mem`. Operator-blind by construction —
// there is no locations API; the pins come from the `wrappedMeta` that `listNodes` already returns (the loaded
// `treeStore`), so no coordinate ever crosses the blind server.
//
// Best-effort: a node whose key won't resolve or whose meta won't open is silently skipped (never a broken map).
// A **pending `auto`** location (armed at creation, no geotagged media yet) has no `derived` → no pin until its
// first upload materializes it. Folders carry no location and are never pinned.
import { NodeKind } from '../../api/generated/content'
import { nodeContentKey, type TreeNode } from './tree'
import type { EpochKeys } from '../spaces/epochs'
import { unsealMemoryLocation, type MemoryLocation } from './memoryLocation'
import { unsealMemoryDate, type MemoryDate } from './memoryDate'

/**
 * A located node ready to drop as a map pin: the tree node, its unsealed `location` (with a `derived` position),
 * and its unsealed `date` (for the hover card + ordering a cluster's fan) — both read from the same `wrappedMeta`.
 */
export type LocatedPin = {
  node: TreeNode
  location: MemoryLocation & { derived: NonNullable<MemoryLocation['derived']> }
  date: MemoryDate | null
}

/** A screen-space point (Leaflet layer pixels) — the unit the map clusters in. */
export type Projected = { x: number; y: number }

/** A cluster of pins that fall within the grouping radius of each other on screen; `lat`/`lng` is their centroid. */
export type PinCluster = { pins: LocatedPin[]; lat: number; lng: number }

/**
 * Greedily group pins whose **projected** (screen-pixel) positions fall within `distancePx` of an already-seen
 * cluster's anchor — the same fixed-anchor scheme the legacy media map used, so grouping stays stable as members
 * join (comparisons are against each cluster's first member, the display centre is the members' centroid). `project`
 * maps a lat/lng to layer pixels (e.g. Leaflet's `latLngToLayerPoint`), so the caller re-clusters on every zoom.
 * A lone pin yields a one-member cluster. Pure + Leaflet-free so the math is unit-tested in isolation.
 */
export function clusterPins(
  pins: LocatedPin[],
  project: (lat: number, lng: number) => Projected,
  distancePx: number,
): PinCluster[] {
  const groups: { pins: LocatedPin[]; anchor: Projected }[] = []
  for (const pin of pins) {
    const p = project(pin.location.derived.lat, pin.location.derived.lng)
    const hit = groups.find((g) => Math.hypot(p.x - g.anchor.x, p.y - g.anchor.y) <= distancePx)
    if (hit) hit.pins.push(pin)
    else groups.push({ pins: [pin], anchor: p })
  }
  return groups.map((g) => ({
    pins: g.pins,
    lat: g.pins.reduce((s, p) => s + p.location.derived.lat, 0) / g.pins.length,
    lng: g.pins.reduce((s, p) => s + p.location.derived.lng, 0) / g.pins.length,
  }))
}

/**
 * Whether `nodeId` sits in the subtree rooted at `rootFolderId` — walk its `parentId` chain up; a match anywhere
 * (including the node itself) means it's inside. `rootFolderId === null` is the scope root ⇒ every node qualifies.
 */
function inSubtree(byId: Map<string, TreeNode>, nodeId: string, rootFolderId: string | null): boolean {
  if (rootFolderId === null) return true
  for (let cur = byId.get(nodeId); cur; cur = cur.parentId ? byId.get(cur.parentId) : undefined) {
    if (cur.id === rootFolderId) return true
  }
  return false
}

/**
 * Collect the located pins for the subtree of `rootFolderId` (or the whole tree at the scope root, `null`): every
 * MEMORY/CHAPTER in that subtree whose `location` unseals to a `derived` position, each opened under its own
 * `nodeContentKey(scopeId, epochs, tree, id)` (which resolves the top-level Memory under its epoch's `K_SC`, ADR 0024).
 * Best-effort — a node with no meta, an unresolvable key, or a pending (`derived`-less) location is skipped. The
 * result feeds `SpaceMap.vue` (a marker per pin, click → open the node).
 */
export function collectLocatedPins(
  scopeId: string,
  epochs: EpochKeys,
  tree: TreeNode[],
  rootFolderId: string | null,
): LocatedPin[] {
  const byId = new Map(tree.map((n) => [n.id, n]))
  const pins: LocatedPin[] = []
  for (const node of tree) {
    if (node.kind !== NodeKind.Memory && node.kind !== NodeKind.Chapter) continue
    if (!node.wrappedMeta) continue
    if (!inSubtree(byId, node.id, rootFolderId)) continue
    // Best-effort: a wrong/absent key throws on the AEAD tag — treat any failure as "can't open" and skip.
    let location: MemoryLocation | null
    let date: MemoryDate | null = null
    try {
      const nodeKey = nodeContentKey(scopeId, epochs, tree, node.id)
      if (!nodeKey) continue // unchainable key — skip, never break the map
      location = unsealMemoryLocation(scopeId, nodeKey, node)
      date = unsealMemoryDate(scopeId, nodeKey, node) // same K_mem, same blob — free alongside the location
    } catch {
      continue // locked node — silently skipped
    }
    if (!location?.derived) continue // no location, or a pending auto place with no position yet
    pins.push({ node, location: { ...location, derived: location.derived }, date })
  }
  return pins
}

/**
 * Order pins by **start day, newest first, undated last** — the same convention as the card grid
 * (`sortNodesByDate`), so a spiderfied cluster's fan reads consistently. Stable within equal/undated groups.
 * Pure (no Leaflet) so it's unit-tested; returns a new array.
 */
export function sortPinsByStartDate(pins: LocatedPin[]): LocatedPin[] {
  return pins
    .map((pin, i) => ({ pin, i }))
    .sort((a, b) => {
      const da = a.pin.date?.from
      const db = b.pin.date?.from
      if (!da && !db) return a.i - b.i // both undated → stable order, grouped last
      if (!da) return 1
      if (!db) return -1
      return da < db ? 1 : da > db ? -1 : a.i - b.i // larger ISO day (later) first
    })
    .map(({ pin }) => pin)
}
