// Live media updates for an open Memory gallery (uc.memory.live-updates, cr-0107, ADR 0033). While a Memory is
// open, poll `listNodeMedia` on an interval so another member's/contributor's adds + removes surface into the
// grid without a manual reload. The heavy lifting is server-side + conditional (a per-node media_revision → a
// 304 when nothing changed, a keyset delta + gated id-set on a change); this is only the transport loop, an
// instance of `createVisibilityPoller`: fire on an interval, PAUSE while the tab is hidden, and fire once
// immediately the moment it becomes visible again (so a returning viewer catches up at once — E3). A
// few seconds' latency is fine for a photo grid; a long-poll / push channel is the deferred zero-latency upgrade
// (ADR 0033). Single active gallery ⇒ a module-level singleton, restarted on each Memory open, stopped on close.
//
// The `tick` closure reads the caller's live cursor + revision at each call (not captured), so the poll always
// requests against the current state and no-ops cleanly once the Memory is gone.
import { createVisibilityPoller, type PollTick } from '../../shared/state/visibilityPoller'

const POLL_INTERVAL_MS = 20_000

const poller = createVisibilityPoller(POLL_INTERVAL_MS)

/**
 * Start polling media revisions for the open Memory. [tick] is invoked on each interval (skipped while the tab
 * is hidden) and once immediately whenever the tab becomes visible again. No immediate tick on start — the
 * caller's initial full load already populated the grid. A tick that returns its promise is not re-entered
 * while it is in flight, and must check the signal before it writes: a stop or a restart fences it. Restarts
 * cleanly if called again for a new Memory.
 */
export function startMediaRevisionPolling(tick: PollTick): void {
  poller.start(tick)
}

/** Stop polling + detach the visibility listener (Memory close / navigate / unmount / lock). Safe to call when not running. */
export function stopMediaRevisionPolling(): void {
  poller.stop()
}
