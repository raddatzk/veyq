// The level view's live refresh (inbox #408): while a folder level is on screen, pick up what others changed — a share
// guest's upload into the folder, a peer's new Memory or folder — without a reload. Core's, host-side: the HOST owns
// where the level's tree comes from (the member's `treeStore`, the guest's re-resolve), so it passes the one read;
// this composable owns the lifecycle and the cadence, and the level hands the resulting `ticks` on to its sections.
//
// **One request per level, not per section.** The read is the tree's (one list); a section whose data lives beside the
// tree (Files' blob listing, whose rows change when an upload COMPLETES, without the tree changing) watches `ticks` —
// the count of completed ticks, changed or not — and re-reads its own, cheap, part. A section with nothing beside the
// tree ignores it.
//
// **Cadence** (`LIVE_POLL`): ~15 s ± 20 %, only while the tab is visible, at once on the return to visible or focus,
// doubling to 2 min on errors (`shared/state/livePoller.ts`). **Never over a write**: `paused` is the host's "a dialog
// is open / a write is in flight" — a paused tick asks the server nothing and the next interval tries again; and a read
// that began before a write's own re-read is dropped by the host's read (`treeStore.pollTree`'s generation).
import { onBeforeUnmount, readonly, ref, watch, type Ref } from 'vue'
import { createLivePoller, type LivePollerOptions } from '../../shared/state/livePoller'

export const LIVE_POLL: LivePollerOptions = { intervalMs: 15_000, maxBackoffMs: 120_000, jitter: 0.2, minGapMs: 2_000 }

export interface LevelLiveOptions {
  /** Whether a folder level is on screen now (the host shows a detail or a state instead otherwise): polls only then. */
  active: () => boolean
  /** The host's "do not refresh over this": a dialog is open, a write is in flight. */
  paused: () => boolean
  /** The one read of the level's tree. Resolves when it applied (or found nothing new); rejects on failure. It gets the
   *  poller's signal and must check it before applying anything. */
  read: (signal: AbortSignal) => Promise<unknown>
  /** Overrides {@link LIVE_POLL} (tests). */
  poll?: LivePollerOptions
}

/** Run the level's live refresh for the calling component's life; `ticks` counts the completed reads. */
export function useLevelLive(options: LevelLiveOptions): { ticks: Readonly<Ref<number>> } {
  const ticks = ref(0)
  const poller = createLivePoller(options.poll ?? LIVE_POLL)
  const run = () => {
    poller.start(async (signal) => {
      if (options.paused()) return 'skip'
      await options.read(signal)
      if (!signal.aborted) ticks.value++
    })
  }
  watch(options.active, (on) => (on ? run() : poller.stop()), { immediate: true })
  onBeforeUnmount(() => poller.stop())
  return { ticks: readonly(ticks) }
}
