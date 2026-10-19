// The level view's live refresh (inbox #408): while a page of the tree is on screen — a folder level, or an opened node's
// page (inbox #503) — pick up what others changed — a share guest's upload into the folder, a peer's new Memory or folder,
// a moderator's block of the node on screen — without a reload. Core's, host-side: the HOST owns
// where the level's tree comes from (the member's `treeStore`, the guest's re-resolve), so it passes the one read;
// this composable owns the lifecycle and the cadence, and the level hands the resulting `ticks` on to its row sources.
//
// **One request per level, not per row source.** The read is the tree's (one conditional list, ADR 0081: an idle scope is a
// `304`, no work); a row source whose data lives beside the tree (Depot's blob listing, whose rows change when an upload
// COMPLETES, without the tree changing) watches `ticks` — the count of reads that found the scope MOVED, which an
// upload's completion does (it bumps the scope's tree revision) — and re-reads its own, cheap, part. A 304 ticks
// nothing. A row source with nothing beside the tree ignores it.
//
// **Cadence** (`LIVE_POLL`): ~5 s ± 20 % (a poll that nothing changed costs one `304`, ADR 0081), only while the tab is visible, at once on the return to visible or focus,
// doubling to 2 min on errors (`shared/state/livePoller.ts`). **Never over a write**: `paused` is the host's "a dialog
// is open / a write is in flight" — a paused tick asks the server nothing and the next interval tries again; and a read
// that began before a write's own re-read is dropped by the host's read (`treeStore.pollTree`'s generation).
import { onBeforeUnmount, readonly, ref, watch, type Ref } from 'vue'
import { createLivePoller, type LivePollerOptions } from '../../shared/state/livePoller'

export const LIVE_POLL: LivePollerOptions = { intervalMs: 5_000, maxBackoffMs: 120_000, jitter: 0.2, minGapMs: 2_000 }

export interface LevelLiveOptions {
  /** Whether a page of the tree is on screen now — a folder level, or an opened node's page of any kind (inbox #503: a
   *  block of the node or of a folder above it reaches the page only through this read) — and not a state or another
   *  page: polls only then. */
  active: () => boolean
  /** The host's "do not refresh over this": a dialog is open, a write is in flight. */
  paused: () => boolean
  /** The one read of the level's tree (a conditional GET, ADR 0081). Resolves whether the server's state MOVED — `false`
   *  for a `304`, a dropped read or nothing to do, which tells the row sources nothing; rejects on failure. It gets the
   *  poller's signal and must check it before applying anything. */
  read: (signal: AbortSignal) => Promise<boolean>
  /** Overrides {@link LIVE_POLL} (tests). */
  poll?: LivePollerOptions
}

/** Run the level's live refresh for the calling component's life; `ticks` counts the reads that found the state moved. */
export function useLevelLive(options: LevelLiveOptions): { ticks: Readonly<Ref<number>> } {
  const ticks = ref(0)
  const poller = createLivePoller(options.poll ?? LIVE_POLL)
  const run = () => {
    poller.start(async (signal) => {
      if (options.paused()) return 'skip'
      const moved = await options.read(signal)
      if (moved && !signal.aborted) ticks.value++
    })
  }
  watch(options.active, (on) => (on ? run() : poller.stop()), { immediate: true })
  onBeforeUnmount(() => poller.stop())
  return { ticks: readonly(ticks) }
}
