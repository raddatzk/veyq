// Live media updates for an open Memory gallery (uc.memory.live-updates, cr-0107, ADR 0033). While a Memory is
// open, poll `listNodeMedia` on an interval so another member's/contributor's adds + removes surface into the
// grid without a manual reload. The heavy lifting is server-side + conditional (core's per-node item revision → a
// 304 when nothing changed, a keyset delta + gated id-set on a change) and the conditional read itself is core's
// (`core/items/itemsLive.ts`, inbox #410); this is only the Memories binding of the transport loop: ONE gallery is
// open at a time, so a module-level singleton over core's `createLivePoller` at the shared item-list cadence
// (`ITEMS_POLL`: 20 s ± 20 %, backing off on errors, paused while the tab is hidden, an immediate catch-up on the
// return to visible or focus). Restarted on each Memory open, stopped on close.
//
// The `tick` closure reads the caller's live cursor + revision at each call (not captured), so the poll always
// requests against the current state and no-ops cleanly once the Memory is gone.
import { ITEMS_POLL } from '../../core/items/itemsLive'
import { createLivePoller, type LiveTick } from '../../shared/state/livePoller'

// `random` reads Math.random at each draw (not once at creation), so a test can pin the jitter.
const poller = createLivePoller({ ...ITEMS_POLL, random: () => Math.random() })

/**
 * Start polling media revisions for the open Memory. [tick] is invoked on each interval (skipped while the tab
 * is hidden) and once immediately whenever the tab becomes visible again. No immediate tick on start — the
 * caller's initial full load already populated the grid. A tick that returns its promise is not re-entered
 * while it is in flight, and must check the signal before it writes: a stop or a restart fences it. A tick that
 * REJECTS backs the loop off (a server that is down is asked less often). Restarts cleanly if called again for a
 * new Memory.
 */
export function startMediaRevisionPolling(tick: LiveTick): void {
  poller.start(tick)
}

/** Stop polling + detach the visibility listener (Memory close / navigate / unmount / lock). Safe to call when not running. */
export function stopMediaRevisionPolling(): void {
  poller.stop()
}
