// Live media updates for an open Memory gallery (uc.memory.live-updates, cr-0107, ADR 0033). While a Memory is
// open, poll `listNodeMedia` on an interval so another member's/contributor's adds + removes surface into the
// grid without a manual reload. The heavy lifting is server-side + conditional (a per-node media_revision → a
// 304 when nothing changed, a keyset delta + gated id-set on a change); this is only the transport loop, and it
// mirrors `grantDeliveryPolling` / `scopeRotationPolling`: fire on an interval, PAUSE while the tab is hidden,
// and fire once immediately the moment it becomes visible again (so a returning viewer catches up at once). A
// few seconds' latency is fine for a photo grid; a long-poll / push channel is the deferred zero-latency upgrade
// (ADR 0033). Single active gallery ⇒ a module-level singleton, restarted on each Memory open, stopped on close.
//
// The `tick` closure reads the caller's live cursor + revision at each call (not captured), so the poll always
// requests against the current state and no-ops cleanly once the Memory is gone.

const POLL_INTERVAL_MS = 20_000

let timer: ReturnType<typeof setInterval> | null = null
let onVisible: (() => void) | null = null

const documentVisible = () => typeof document === 'undefined' || document.visibilityState === 'visible'

/**
 * Start polling media revisions for the open Memory. [tick] is invoked on each interval (skipped while the tab
 * is hidden) and once immediately whenever the tab becomes visible again. No immediate tick on start — the
 * caller's initial full load already populated the grid. Restarts cleanly if called again for a new Memory.
 */
export function startMediaRevisionPolling(tick: () => void): void {
  stopMediaRevisionPolling()
  timer = setInterval(() => {
    if (documentVisible()) tick()
  }, POLL_INTERVAL_MS)
  onVisible = () => {
    if (documentVisible()) tick() // E3: catch up at once on return to the foreground
  }
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisible)
}

/** Stop polling + detach the visibility listener (Memory close / navigate / unmount / lock). Safe to call when not running. */
export function stopMediaRevisionPolling(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
  if (onVisible && typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
  onVisible = null
}
