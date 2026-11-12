// The lightbox's decrypted previews, kept while it is open so that paging back shows an item at once and its neighbours
// are read before they are asked for. Bytes only, never an object URL: a URL is minted for the item on screen alone, by the
// lightbox, and revoked when it moves on.
//
// What may stay is bounded three ways. By size: at most `maxBytes` of settled previews, the least recently asked dropped
// first. By what the gallery still lists: `retain` drops every entry whose item is gone (trashed, purged) or blocked by a
// moderator (uc.moderation.take-a-measure), so a block removes the plaintext at once and a lift reads it again. By the
// lightbox's life: it is cleared when the lightbox closes and when the key it reads under changes. It is memory only -
// never Cache Storage, IndexedDB or any other storage on the device: no plaintext on disk (ADR 0047), and a store that
// outlives the session would, on our reading of § 25 TDDDG, need the member's own choice first (owner 2026-11-12).
import { lightboxRef, type MediaItem } from './media'

/** Settled previews held at most, in bytes. A preview is at most 2048 px on its long edge (`TIER_SPECS.PREVIEW`). */
export const PREVIEW_CACHE_BYTES = 24 * 1024 * 1024

/** The entry's identity: the medium and the blob its preview is read from, so a replaced preview is never served. */
export const previewKeyOf = (item: MediaItem): string => `${item.mediaId}:${lightboxRef(item).blobId}`

type Entry = {
  /** The read: in flight, or settled with `bytes`. */
  pending: Promise<Uint8Array>
  bytes: Uint8Array | null
  /** Started ahead, nobody waits for it yet: a failure is retried once by the viewer that asks for it. */
  ahead: boolean
}

export interface PreviewCache {
  /** The item's preview: held, joined while in flight, or read now. A failed read is not kept. */
  get(item: MediaItem): Promise<Uint8Array>
  /** The item's preview when it has settled - for showing it in the same tick, with no loading state. */
  peek(item: MediaItem): Uint8Array | null
  /** Read the item's preview ahead; a failure is dropped silently. */
  prefetch(item: MediaItem): void
  /** Keep only the entries whose key is in `keys`; a read in flight for a dropped one is not kept when it lands. */
  retain(keys: ReadonlySet<string>): void
  clear(): void
  /** The settled bytes held - for the tests of the bound. */
  heldBytes(): number
}

export function createPreviewCache(load: (item: MediaItem) => Promise<Uint8Array>, maxBytes = PREVIEW_CACHE_BYTES): PreviewCache {
  // A Map iterates in insertion order: re-inserting on every ask makes the first entry the least recently asked.
  const entries = new Map<string, Entry>()

  function touch(key: string, entry: Entry) {
    entries.delete(key)
    entries.set(key, entry)
  }

  /** Drop the least recently asked settled entries until the held bytes fit; `keep` (the one that just landed) stays. */
  function trim(keep: string) {
    let held = heldBytes()
    for (const [key, entry] of entries) {
      if (held <= maxBytes) return
      if (key === keep || !entry.bytes) continue
      entries.delete(key)
      held -= entry.bytes.byteLength
    }
  }

  function heldBytes(): number {
    let sum = 0
    for (const entry of entries.values()) sum += entry.bytes?.byteLength ?? 0
    return sum
  }

  function start(item: MediaItem, ahead: boolean): Entry {
    const key = previewKeyOf(item)
    const entry: Entry = { pending: load(item), bytes: null, ahead }
    entries.set(key, entry)
    entry.pending.then(
      (bytes) => {
        if (entries.get(key) !== entry) return // dropped while in flight (a block, a removal, a clear)
        entry.bytes = bytes
        trim(key)
      },
      () => {
        if (entries.get(key) === entry) entries.delete(key) // a failure is never served again
      },
    )
    return entry
  }

  function get(item: MediaItem): Promise<Uint8Array> {
    const key = previewKeyOf(item)
    const held = entries.get(key)
    if (!held) return start(item, false).pending
    touch(key, held)
    if (held.ahead && !held.bytes) {
      held.ahead = false // the viewer waits for it now: one retry of its own, then the failure is the viewer's
      return held.pending.catch(() => get(item))
    }
    return held.pending
  }

  return {
    get,
    peek(item) {
      const key = previewKeyOf(item)
      const held = entries.get(key)
      if (!held?.bytes) return null
      touch(key, held)
      return held.bytes
    },
    prefetch(item) {
      if (entries.has(previewKeyOf(item))) return
      start(item, true).pending.catch(() => {})
    },
    retain(keys) {
      for (const key of [...entries.keys()]) if (!keys.has(key)) entries.delete(key)
    },
    clear: () => entries.clear(),
    heldBytes,
  }
}
