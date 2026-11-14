// uc.memory.tracks (ADR 0130) — the tracks of the Memory on screen, read for its Map tab. A track is a medium whose
// original is its GPX file (decision 1); this reads each original whole through the session's loader (the member's or the
// guest's, so a share guest sees what a member sees), parses it on the device and keeps the result while the page stays on
// its node and key. Nothing is read before the tab is opened, and one track at a time: a Memory of many long tracks never
// holds two files' text at once. Everything decrypted stays in memory and goes with the node or the key.
import { computed, ref, watch, type Ref } from 'vue'
import type { MediaItem } from '../media'
import type { OpenedBlobMeta } from '../../../core/blob/meta'
import type { LatLng } from '../memoryLocation'
import { parseGpx, simplifyLine, trackColor, trackName, trackStats, type TrackStats } from './gpx'
import type { TrackActivity, TrackLabel } from './trackLabel'

/** One track as the Map tab lists and draws it. */
export type TrackEntry = {
  item: MediaItem
  mediaId: string
  /** The name a member gave it, else the file's own track name, else its file name without `.gpx`; null where none is
   *  known (the tab numbers it). */
  name: string | null
  /** What the name falls back to without the member's: the file's own track name, else its file name (an edit's hint). */
  fileTrackName: string | null
  /** The name a member gave it (ADR 0130 decision 9); null: named by its file. */
  ownName: string | null
  /** The activity a member gave it; null: none (drawn solid, no word). */
  activity: TrackActivity | null
  /** The file name it was picked under (the download's name). */
  fileName: string | undefined
  color: string
  status: 'loading' | 'ready' | 'failed'
  stats?: TrackStats
  /** Each segment as drawn: simplified ([simplifyLine]). */
  lines?: LatLng[][]
}

type Read = { status: 'loading' } | { status: 'failed' } | { status: 'ready'; name: string | null; stats: TrackStats; lines: LatLng[][] }

export interface MemoryTracksCtx {
  scopeId: () => string
  nodeId: () => string
  memoryKey: () => Uint8Array | null
  /** The node's tracks, as the gallery's listing separates them (`useMediaGalleryLive`'s `tracks`). */
  tracks: () => readonly MediaItem[]
  /** Whether the Map tab is on screen: nothing is read before it is. */
  active: () => boolean
  /** The whole original under the node's key (the session's loader). */
  original: (item: MediaItem, key: Uint8Array) => Promise<Uint8Array>
  /** The original's opened `blob-meta` (its file name). */
  blobMeta: (item: MediaItem, key: Uint8Array) => OpenedBlobMeta | null
  /** The listed capture time — the list's order, so a track keeps its colour as others are read. */
  capturedAt: (item: MediaItem, key: Uint8Array) => number | undefined
  /** The name and activity a member gave it, from its listed tier (ADR 0130 decision 9). */
  label: (item: MediaItem, key: Uint8Array) => TrackLabel
}

/** Read and parse one track's original; the decoder refuses bytes that are no UTF-8 text. */
export function readTrack(bytes: Uint8Array): { name: string | null; stats: TrackStats; lines: LatLng[][] } {
  const parsed = parseGpx(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  return {
    name: parsed.name,
    stats: trackStats(parsed.segments),
    lines: parsed.segments.map((s) => simplifyLine(s).map((p) => ({ lat: p.lat, lng: p.lng }))),
  }
}

export function useMemoryTracks(ctx: MemoryTracksCtx) {
  const reads: Ref<Record<string, Read>> = ref({})
  /** Which tracks the member hid on this view (nothing is written). */
  const hidden = ref<Set<string>>(new Set())
  /** The track a line or a row selected: drawn heavier and framed. */
  const selected = ref<string | null>(null)
  let generation = 0
  let running = false

  // A new node or key starts from nothing: what was read under another key is not this node's.
  watch(
    [ctx.nodeId, ctx.memoryKey] as const,
    () => {
      generation++
      reads.value = {}
      hidden.value = new Set()
      selected.value = null
    },
  )

  async function pump() {
    if (running) return
    running = true
    try {
      for (;;) {
        const key = ctx.memoryKey()
        if (!key || !ctx.active()) return
        const next = ctx.tracks().find((it) => !reads.value[it.mediaId])
        if (!next) return
        const at = generation
        reads.value = { ...reads.value, [next.mediaId]: { status: 'loading' } }
        let read: Read
        try {
          read = { status: 'ready', ...readTrack(await ctx.original(next, key)) }
        } catch {
          read = { status: 'failed' }
        }
        if (at !== generation) continue // the page moved on: this node's reads were dropped
        reads.value = { ...reads.value, [next.mediaId]: read }
      }
    } finally {
      running = false
    }
  }

  watch([() => ctx.tracks(), ctx.active, ctx.memoryKey] as const, () => void pump(), { immediate: true })

  const entries = computed<TrackEntry[]>(() => {
    const key = ctx.memoryKey()
    if (!key) return []
    const ordered = ctx.tracks().slice().sort((a, b) => {
      const ta = ctx.capturedAt(a, key) ?? 0
      const tb = ctx.capturedAt(b, key) ?? 0
      return ta - tb || (a.mediaId < b.mediaId ? -1 : a.mediaId > b.mediaId ? 1 : 0)
    })
    return ordered.map((item, index) => {
      const read = reads.value[item.mediaId]
      const fileName = typeof ctx.blobMeta(item, key)?.name === 'string' ? (ctx.blobMeta(item, key)?.name as string) : undefined
      const fileTrackName = trackName(read?.status === 'ready' ? read : null, fileName)
      const label = ctx.label(item, key)
      const entry: TrackEntry = {
        item,
        mediaId: item.mediaId,
        name: label.name ?? fileTrackName,
        fileTrackName,
        ownName: label.name,
        activity: label.activity,
        fileName,
        color: trackColor(index),
        status: read?.status ?? 'loading',
      }
      if (read?.status === 'ready') {
        entry.stats = read.stats
        entry.lines = read.lines
      }
      return entry
    })
  })

  function setShown(mediaId: string, shown: boolean) {
    const next = new Set(hidden.value)
    if (shown) next.delete(mediaId)
    else next.add(mediaId)
    hidden.value = next
    if (!shown && selected.value === mediaId) selected.value = null
  }

  function select(mediaId: string | null) {
    selected.value = mediaId
    if (mediaId) setShown(mediaId, true)
  }

  return { entries, hidden, selected, setShown, select }
}
