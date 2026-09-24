// Background grant-delivery polling (cr-0079). The pull worker (cr-0076) delivers a scope key to pending
// members, but only ran on scope entry (cr-0077/0078) — so a member who registers while a key-holder is
// already sitting in the space waited until that holder's next entry. This closes the "awaiting access" window
// without a re-entry: while a shared scope is open, poll the worker on an interval, pausing when the tab is
// hidden and firing immediately when it becomes visible again. Idempotent + first-online-wins, so multiple
// tabs/members polling is harmless. This is the interim; an SSE push from the same backend is the eventual
// zero-latency answer (ADR 0021, deferred).
import { runGrantDeliveryWorker } from './grantDeliveryWorker'
import { createVisibilityPoller } from '../../shared/state/visibilityPoller'

const POLL_INTERVAL_MS = 45_000

const poller = createVisibilityPoller(POLL_INTERVAL_MS)

/**
 * Start polling grant-delivery for `scopeId` while it is the open scope. `currentKey` is read at each tick (not
 * captured) so the poll always wraps with the live `K_SC` and no-ops once the scope is gone. Runs once
 * immediately, then every {@link POLL_INTERVAL_MS} (skipping ticks while the tab is hidden), and once more the
 * moment the tab becomes visible again — never while the previous run is still in flight, since the worker's
 * effects land on the server. Restarts cleanly if called for a new scope.
 */
export function startGrantDeliveryPolling(scopeId: string, currentKey: () => Uint8Array | null): void {
  const tick = () => {
    const key = currentKey()
    return key ? runGrantDeliveryWorker({ scopeId, key }).catch(() => {}) : undefined
  }
  poller.start(tick, { immediate: true }) // deliver immediately on entry, then keep polling
}

/** Stop polling + detach the visibility listener (scope switch / logout). Safe to call when not running. */
export function stopGrantDeliveryPolling(): void {
  poller.stop()
}
