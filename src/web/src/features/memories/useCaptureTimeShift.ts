// uc.memory.buckets § Correct a selection's capture time (web) — runs a shift `captureTimeShift.ts` planned. Client-only:
// each medium is the lightbox's own write, `MediaMetaDoor.set` (one `updateMediaMeta` with the revision it was read at,
// ADR 0077) — there is no bulk write and no offset the server sees; it sees N ordinary sealed meta writes, as it sees N
// corrections made one by one in the lightbox. A small pool runs them, so a long selection neither queues one at a time
// nor floods the server. A refusal does not stop the rest, and each refused medium is named with its reason, one line per
// reason, as a selection's trash names its own (`mediaRefusal.ts`, inbox #427).
//
// **Never against a time it no longer has:** every write starts from the medium as it was when the shift was planned — the
// planner's snapshot (`ShiftRead`), never the listed item, which a live poll patches in place (`useMediaGalleryLive`'s
// `upsertItems`, shared with the lightbox's list) between the plan and the write — so a peer's change of its capture time meanwhile is
// the door's `MetaConflictError` — refused and named, never re-shifted from the peer's value. A peer's unrelated write (a
// place name) merges as the single correction's does.
//
// The Undo lives here, in the detail, not in the lightbox: its bar outlives a closed lightbox. It restores each written
// medium's override as it was (or none) by the same write, from the state the shift wrote, so a later change by anyone is
// again a named refusal; a medium the shift refused is not touched. The Memory's automatic date reconcile is not run per
// medium: it runs at the gallery's list refreshes (`useMediaGalleryLive`'s `onSettle`), one at a time, never on a meta write.
import { onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { MetaConflictError, staleMetaOf, type SealedMetaState } from '../../core/node/sealedMetaWrite'
import { useToast } from '../../shared/state/useToast'
import { useUndo } from '../../shared/state/useUndo'
import type { ShiftPlan } from './captureTimeShift'
import type { MediaItem, MediaMeta, MediaMetaWrite } from './media'
import { refusalsByReason, sealedMediaName } from './mediaRefusal'
import type { MediaMetaDoor, NodeAt } from './source'

/** How many of a shift's writes run at once. */
export const SHIFT_CONCURRENCY = 4
const UNDO_MS = 8000

/** What the detail hands the shift: the door (absent: no shift), the node's key and epoch, the listed tier of a medium, and
 *  where a landed write (or a peer's state a conflict handed back) patches the grid. */
export interface CaptureTimeShiftContext {
  door: () => MediaMetaDoor | undefined
  scopeId: () => string
  memoryKey: () => Uint8Array | null
  node: () => NodeAt
  metaOf: (item: MediaItem) => MediaMeta | null
  onUpdated: (payload: { mediaId: string } & SealedMetaState) => void
}

/** A selected medium as the plan read it: a copy of the item taken with the plan (the base of its write), and its place in
 *  the list it was shown in, from 1 (the name of a medium with no sealed file name, as the organizer's grid numbers it). */
export interface ShiftRead {
  item: MediaItem
  position: number
}

/** What a lightbox opened from a selection is handed: the selection's media ids, and the run of a shift it planned. */
export interface CaptureTimeShift {
  selected: ReadonlySet<string>
  /** Each medium's number in the grid the selection was made in, where the lightbox lists only the selection. */
  numbers?: Readonly<Record<string, number>>
  run(plan: ShiftPlan, read: readonly ShiftRead[], onProgress?: (done: number, total: number) => void): Promise<void>
}

/** One medium a shift wrote: the state it wrote (the base of its Undo), the override it had before, and its name. */
type Written = { after: MediaItem; previous: number | null; name: string }

/** Run `tasks` at most `limit` at a time; `onSettled` hears each one as it settles. Settled results in the tasks' order. */
async function settleInPool<T>(
  tasks: readonly (() => Promise<T>)[],
  limit: number,
  onSettled: (index: number, result: PromiseSettledResult<T>) => void | Promise<void>,
): Promise<PromiseSettledResult<T>[]> {
  const results: PromiseSettledResult<T>[] = new Array(tasks.length)
  let next = 0
  async function worker() {
    while (next < tasks.length) {
      const i = next++
      try {
        results[i] = { status: 'fulfilled', value: await tasks[i]() }
      } catch (reason) {
        results[i] = { status: 'rejected', reason }
      }
      await onSettled(i, results[i])
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker))
  return results
}

export function useCaptureTimeShift(ctx: CaptureTimeShiftContext): { run: CaptureTimeShift['run'] } {
  const { t } = useI18n()
  const toast = useToast()
  // Registered before `useUndo`'s own teardown (which closes the window first): leaving the detail takes its Undo bar along.
  onBeforeUnmount(() => { if (undo.pending.value) toast.dismissUndo() })
  const undo = useUndo<{ written: Written[]; door: MediaMetaDoor; key: Uint8Array; node: NodeAt }>({ timeoutMs: UNDO_MS })

  /** Patch the grid with what a write settled to: the state written, or the newer one a refusal handed back. */
  async function patch(mediaId: string, result: PromiseSettledResult<MediaMetaWrite>) {
    if (result.status === 'fulfilled') {
      ctx.onUpdated({ mediaId, wrappedMeta: result.value.wrappedMeta, metaRevision: result.value.metaRevision })
      return
    }
    const current = result.reason instanceof MetaConflictError ? result.reason.current : await staleMetaOf(result.reason)
    if (current) ctx.onUpdated({ mediaId, ...current })
  }

  /** Write each target's override through the door, the pool's way; the grid patched as each lands. */
  function writeAll(door: MediaMetaDoor, key: Uint8Array, node: NodeAt, targets: { item: MediaItem; overrideMs: number | null }[], onProgress?: (done: number, total: number) => void) {
    let done = 0
    return settleInPool(
      targets.map(({ item, overrideMs }) => () => door.set(item, key, node, overrideMs)),
      SHIFT_CONCURRENCY,
      async (i, result) => {
        await patch(targets[i].item.mediaId, result)
        onProgress?.(++done, targets.length)
      },
    )
  }

  async function run(plan: ShiftPlan, read: readonly ShiftRead[], onProgress?: (done: number, total: number) => void) {
    const door = ctx.door()
    const key = ctx.memoryKey()
    if (!door || !key) return
    const node = { ...ctx.node() }
    const at = new Map(read.map((r) => [r.item.mediaId, r]))
    const steps = plan.steps.filter((s) => s.action !== 'skip-undated' && at.has(s.mediaId))
    const targets = steps.map((s) => ({ item: at.get(s.mediaId)!.item, overrideMs: s.action === 'set' ? s.overrideMs : null }))
    const names = steps.map((s) => {
      const { item, position } = at.get(s.mediaId)!
      return sealedMediaName(ctx.scopeId(), key, item) || t('memory.buckets.unnamedMedia', { n: position })
    })
    const previous = targets.map(({ item }) => ctx.metaOf(item)?.capturedAtOverride ?? null)
    const results = await writeAll(door, key, node, targets, onProgress)

    const written: Written[] = []
    results.forEach((r, i) => {
      if (r.status !== 'fulfilled') return
      const after = { ...targets[i].item, wrappedMeta: r.value.wrappedMeta ?? undefined, metaRevision: r.value.metaRevision }
      written.push({ after, previous: previous[i], name: names[i] })
    })
    const refused = await refusalsByReason(results, names)
    if (written.length) {
      undo.arm({ written, door, key, node })
      toast.showUndo({ summary: t('memory.buckets.shifted', { count: written.length }, written.length), onUndo: undoShift, life: UNDO_MS })
    }
    for (const [why, refusedNames] of refused) {
      toast.error(t('memory.buckets.shiftRefused', { names: refusedNames.join(', '), reason: t(`memory.buckets.deleteRefusedReason.${why}`) }))
    }
  }

  /** Undo: each written medium back to the override it had (or none), from the state the shift wrote; a refusal named. */
  async function undoShift() {
    const u = undo.cancel()
    if (!u) return
    const results = await writeAll(u.door, u.key, u.node, u.written.map((w) => ({ item: w.after, overrideMs: w.previous })))
    const refused = await refusalsByReason(results, u.written.map((w) => w.name))
    for (const [why, refusedNames] of refused) {
      toast.error(t('memory.buckets.restoreRefused', { names: refusedNames.join(', '), reason: t(`memory.buckets.deleteRefusedReason.${why}`) }))
    }
  }

  return { run }
}
