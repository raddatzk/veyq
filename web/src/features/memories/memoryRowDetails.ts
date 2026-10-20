// The DETAILS of a Memory's row or tile (the `details` of core's `LevelRow`, `core/node/levelRow.ts`): its date and its
// place, read from the node's own sealed meta - no media load, no network. ONE builder for every surface that draws a
// Memory the way the level list does (the level's rows, the profile page's "Memories with this person"), so the two cannot
// drift. A fact the Memory does not carry is LEFT OUT: no "no date yet", no "unknown place" - a row without them is just
// shorter. The same facts serve a share guest as a member (rights remove functions, not what the served meta holds).
import { openContentKey, type TreeNode } from '../../core/node/tree'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import type { LevelRowDetail } from '../../core/node/levelRow'
import { normalizeDate, type MemoryDate } from './memoryDate'
import { normalizeLocation } from './memoryLocation'
import { formatClosedRange, formatDay } from './memoryDateRange'

/** What a Memory's meta says of when and where. `placeName` is `''` without a (named) place. */
export interface MemoryRowFacts {
  date: MemoryDate | null
  placeName: string
}

export const NO_FACTS: MemoryRowFacts = { date: null, placeName: '' }

type Translate = (key: string, named?: Record<string, unknown>) => string

/** The facts of an already-unsealed node meta (`unsealNodeMeta`'s envelope). */
export function factsOfMeta(meta: Readonly<Record<string, unknown>>): MemoryRowFacts {
  return { date: normalizeDate(meta.date), placeName: normalizeLocation(meta.location)?.name ?? '' }
}

/**
 * A node's date and place: open its content key under `parentKey` (the scope's epoch key at the root, the folder's key inside
 * a folder, ADR 0080 Decision 1), then unseal its `wrappedMeta` ONCE. Best-effort like every display read: no key, a key that
 * does not open, or meta that does not open means no facts, never a throw.
 */
export function unsealRowFacts(scopeId: string, parentKey: Uint8Array | null, node: TreeNode): MemoryRowFacts {
  if (!parentKey) return NO_FACTS
  try {
    const key = openContentKey(scopeId, parentKey, node)
    return key ? factsOfMeta(unsealNodeMeta(key, { scopeId, nodeId: node.id }, node.wrappedMeta)) : NO_FACTS
  } catch {
    return NO_FACTS
  }
}

/** The date as the row says it: a day or a closed range, "since <day>" for an ongoing one; `''` without a usable date. */
export function memoryDateLabel(date: MemoryDate | null | undefined, t: Translate, locale: string): string {
  if (!date) return ''
  if (date.mode === 'ongoing') return date.from ? t('memory.date.since', { day: formatDay(date.from, locale) }) : ''
  return formatClosedRange(date.from, date.to, locale)
}

/** The details a Memory's row shows, in order: the date, then the place. Each only when there is one. */
export function memoryDetails(facts: MemoryRowFacts, t: Translate, locale: string): LevelRowDetail[] {
  const date = memoryDateLabel(facts.date, t, locale)
  const place = facts.placeName.trim()
  return [...(date ? [{ id: 'date', text: date }] : []), ...(place ? [{ id: 'place', text: place }] : [])]
}
