// A Memory's `auto` map position (uc.memory.place): the medoid of its photos' GPS. The rule is Memories' Kotlin
// `MemoryLocation.medoid` (ADR 0155 decision 7.3, § Memories' move), drawn through `@veyq/core-js/memories` and held by
// `contracts/memories/v1-vectors.json`: a change to it is made there, never here. A file of its own, off `memoryLocation.ts`,
// which the level's rows and the search's facets read eagerly: only the place's composable imports this, so Memories' module
// file loads with a Memory's page, never for a visitor who opens none.
import * as core from '@veyq/core-js/memories'
import type { LatLng } from './memoryLocation'

/**
 * The **medoid** of a set of GPS points: the input point minimizing the summed **Haversine** distance to all the
 * others (a real photo location — robust to an outlier, never the sea, never the mean). Ties resolve to the
 * **lowest index**; a single point is its own medoid; an empty set ⇒ **null**. This is the `auto` map position.
 */
export function computeMedoid(points: LatLng[]): LatLng | null {
  const m = core.memoriesMedoid(points)
  return m ? { lat: m.lat, lng: m.lng } : null
}
