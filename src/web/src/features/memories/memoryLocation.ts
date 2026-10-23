// en.memory.node-meta (web) — a Memory's curated **location**, the `location` sub-object of the sealed node-meta
// blob (uc.memory.place, cr-0069). One named place: a map position (the **medoid** of the Memory's own photo GPS,
// or a hand-dropped pin) plus a **mandatory name** (reverse-geocoded from the framed anchor, or typed). Sealed on
// the NODE under its `K_node` beside the `date` (operator-blind, ADR 0012) — the server only ever holds ciphertext.
//
// The seal is **read-modify-write** (shared `nodeMeta.ts`): `setMemoryLocation` unseals the current
// `{ v, date?, location? }`, swaps in (or drops) only the `location`, and re-seals — so it never clobbers the
// sibling `date`. `null` removes the place while preserving the date.
//
// A location is **never auto-created from GPS presence** — it is a deliberate per-Memory "set location" choice.
// This module is the pure model + seal; the map editor (Leaflet) and the medoid-over-media self-heal live in the
// UI layer (cr-0069 phase 2), reusing `computeMedoid` here.
import type { MediaItem } from './media'
import { openedMeta } from './openedListing'
import { editField, unsealNodeMeta } from '../../core/node/nodeMeta'
import { writeNodeMeta, type WriteNodeMetaOptions } from '../../core/node/nodeMetaWrite'
import type { NodeMetaState } from '../../core/node/nodeMeta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import type { GeoLevel } from './geocode'

/** A geographic point in decimal degrees. */
export type LatLng = { lat: number; lng: number }

/** The granularity levels of a reverse-geocoded name (finest→coarsest). */
const LEVEL_KEYS: readonly GeoLevel['key'][] = ['road', 'suburb', 'city', 'county', 'island', 'state', 'country']

/**
 * A Memory's one named place (uc.memory.place). `name` is **mandatory** — there is no location without a name.
 * `derived` is the map position: the **medoid** of the media GPS (`auto`) or the framed map centre (`fixed`). An
 * `auto` location re-medoids `derived` as media are added; a `fixed` one is frozen. A `geocoded` name may refresh
 * when the `auto` medoid moves; a `manual` (typed) name is frozen. `nameAnchor` records the coordinate a geocoded
 * name resolved at **and the chosen granularity `level`** (Straße…Land), so a refresh keeps the same grain.
 */
export type MemoryLocation = {
  name: string
  nameSource: 'geocoded' | 'manual'
  derived?: LatLng
  derivedMode: 'auto' | 'fixed'
  nameAnchor?: { lat: number; lng: number; level: GeoLevel['key'] }
}

const finiteNum = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

/**
 * A place name's bound, in code points: the field of the Memory's sealed meta that could still outgrow its 8192-character
 * cap (`banner/bannerModel.test.ts`, "the meta budget", seals the worst case with a name at this bound). The editor's
 * `maxlength`, every read and every write (`setMemoryLocation`) hold it; an import's longer name is cut to it.
 */
export const MAX_PLACE_NAME = 200
/** A name trimmed and cut to [MAX_PLACE_NAME] code points, so no character is split in half. */
export const boundPlaceName = (v: string): string => Array.from(v.trim()).slice(0, MAX_PLACE_NAME).join('')

const asLatLng = (raw: unknown): LatLng | undefined => {
  if (!raw || typeof raw !== 'object') return undefined
  const p = raw as { lat?: unknown; lng?: unknown }
  const lat = finiteNum(p.lat)
  const lng = finiteNum(p.lng)
  return lat !== undefined && lng !== undefined ? { lat, lng } : undefined
}

/**
 * Normalize a loosely-typed `location` payload to a valid [MemoryLocation], or **null** for the "no location"
 * state. A name is mandatory — **except** a still-**pending** `auto` place (cr-0069): an `auto` + `geocoded`
 * location whose name is deferred until the first geotagged media supplies the medoid position + a geocoded name.
 * Every other shape (a `fixed` place, a `manual` name, a materialized `geocoded` one) needs a non-empty name.
 * `nameSource`/`derivedMode` fall back to the safe values (`manual` / `auto`); `derived`/`nameAnchor` drop when
 * malformed.
 */
export function normalizeLocation(raw: unknown): MemoryLocation | null {
  if (!raw || typeof raw !== 'object') return null
  const l = raw as {
    name?: unknown
    nameSource?: unknown
    derived?: unknown
    derivedMode?: unknown
    nameAnchor?: unknown
  }
  const name = typeof l.name === 'string' ? boundPlaceName(l.name) : ''
  const nameSource = l.nameSource === 'geocoded' ? 'geocoded' : 'manual'
  const derivedMode = l.derivedMode === 'fixed' ? 'fixed' : 'auto'
  const pending = derivedMode === 'auto' && nameSource === 'geocoded' && !name
  if (!name && !pending) return null // a name is mandatory, unless this is a pending auto place
  const anchorPoint = asLatLng(l.nameAnchor)
  const rawLevel = (l.nameAnchor as { level?: unknown } | undefined)?.level
  const level = LEVEL_KEYS.find((k) => k === rawLevel)
  return {
    name,
    nameSource,
    derived: asLatLng(l.derived),
    derivedMode,
    nameAnchor: anchorPoint && level ? { ...anchorPoint, level } : undefined,
  }
}

/**
 * Whether a location is **armed but not yet materialized** — a pending `auto` place awaiting its first geotagged
 * media to supply the medoid position and (geocoded) name. Its chip shows a "pending" placeholder rather than a
 * name. Detected by the absent name (a materialized place always carries one).
 */
export function isLocationPending(loc: MemoryLocation | null | undefined): boolean {
  return Boolean(loc) && !loc!.name
}

/**
 * Unseal a Memory's location from a node's `wrappedMeta` under `nodeKey` (`K_node`). Best-effort (E3): null when
 * the node carries no meta/location, the wrap doesn't open, or the payload is malformed — the header then shows
 * the "set location" affordance rather than break.
 */
export function unsealMemoryLocation(
  scopeId: string, nodeKey: Uint8Array, node: { id: string; wrappedMeta: string | null },
): MemoryLocation | null {
  return normalizeLocation(unsealNodeMeta(nodeKey, { scopeId, nodeId: node.id }, node.wrappedMeta).location)
}

/**
 * The location EDITOR's save: **read-modify-write** the node-meta blob — set (or, for `null`, **remove**) only the
 * `location`, re-seal, PATCH with the `metaRevision` it was built from. **Preserves the sibling `date`/`cover`** in
 * every case (cr-0069), including ones a peer wrote meanwhile (ADR 0077); a peer's change to the PLACE itself
 * throws `MetaConflictError` so the editor re-opens on it. Returns the state the server confirmed.
 */
export async function setMemoryLocation(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  location: MemoryLocation | null,
  current: NodeMetaState,
  opts: WriteNodeMetaOptions = {},
): Promise<SealedMetaState> {
  const openedWith = unsealNodeMeta(nodeKey, { scopeId, nodeId }, current.wrappedMeta).location
  const bounded = location ? { ...location, name: boundPlaceName(location.name) } : null
  const { state } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, editField('location', bounded, openedWith), undefined, opts)
  return state
}

/** Whether two locations are equal (all fields) — the self-heal re-seals `derived` only when this is false. */
export function sameMemoryLocation(a: MemoryLocation | null, b: MemoryLocation | null): boolean {
  if (a === b) return true
  if (!a || !b) return false
  return (
    a.name === b.name &&
    a.nameSource === b.nameSource &&
    a.derivedMode === b.derivedMode &&
    a.derived?.lat === b.derived?.lat &&
    a.derived?.lng === b.derived?.lng &&
    a.nameAnchor?.lat === b.nameAnchor?.lat &&
    a.nameAnchor?.lng === b.nameAnchor?.lng &&
    a.nameAnchor?.level === b.nameAnchor?.level
  )
}

/** Great-circle distance between two points (Haversine). Unit is irrelevant for the medoid argmin — km here. */
function haversineKm(a: LatLng, b: LatLng): number {
  const R = 6371
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(b.lat - a.lat)
  const dLng = toRad(b.lng - a.lng)
  const s =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)))
}

/**
 * The **medoid** of a set of GPS points: the input point minimizing the summed **Haversine** distance to all the
 * others (a real photo location — robust to an outlier, never the sea, never the mean). Ties resolve to the
 * **lowest index**; a single point is its own medoid; an empty set ⇒ **null**. This is the `auto` map position.
 */
export function computeMedoid(points: LatLng[]): LatLng | null {
  if (points.length === 0) return null
  let best = 0
  let bestSum = Infinity
  for (let i = 0; i < points.length; i++) {
    let sum = 0
    for (let j = 0; j < points.length; j++) if (i !== j) sum += haversineKm(points[i], points[j])
    if (sum < bestSum) {
      bestSum = sum
      best = i // strict `<` keeps the lowest index on a tie
    }
  }
  return { lat: points[best].lat, lng: points[best].lng }
}

/**
 * Collect the GPS points from a Memory's **own** media (its listed `wrappedMeta` tier, cr-0068) under `nodeKey`
 * (`K_node`) — the raw input to [computeMedoid] for the `auto` position. Items without a GPS tier are skipped;
 * reads only the already-listed metadata, no per-item fetch.
 */
export function mediaGpsPoints(scopeId: string, items: MediaItem[], nodeKey: Uint8Array): LatLng[] {
  const points: LatLng[] = []
  for (const item of items) {
    const tier = openedMeta(scopeId, nodeKey, item)
    if (tier && tier.gpsLat !== undefined && tier.gpsLng !== undefined) {
      points.push({ lat: tier.gpsLat, lng: tier.gpsLng })
    }
  }
  return points
}
