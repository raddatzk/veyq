// Background scope-rotation polling (cr-0092). The async half of forward secrecy: when a tenant owner removes a
// member co-sharing a group the owner can't rotate, the backend queues a SCOPE_ROTATION job and a remaining
// key-holder must complete it. Unlike grant-delivery this is NOT tied to an open scope — the key-holder may be
// sitting anywhere (their personal scope, another group) — so while the vault is unlocked we poll the worker
// (which claims across every held scope) on an interval, pausing when the tab is hidden and firing the moment it
// becomes visible again. Idempotent + first-online-wins, so multiple tabs/key-holders polling is harmless. This
// is the interim; an SSE push from the same backend is the eventual zero-latency answer (ADR 0021, deferred).
import { runScopeRotationWorker } from './scopeRotationWorker'
import { createVisibilityPoller } from '../../shared/state/visibilityPoller'

const POLL_INTERVAL_MS = 45_000

const poller = createVisibilityPoller(POLL_INTERVAL_MS)

/**
 * Start polling scope-rotation for the signed-in session. `unlocked` is read at each tick (not captured) so the
 * poll no-ops while the vault is locked — the rotation opens the caller's own grant on-device, so it needs an
 * unlocked identity. Runs once immediately, then every {@link POLL_INTERVAL_MS} (skipping ticks while the tab is
 * hidden), and once more the moment the tab becomes visible again — never while the previous drain is still in
 * flight, since a rotation lands on the server. Restarts cleanly if called again.
 */
export function startScopeRotationPolling(unlocked: () => boolean): void {
  const tick = () => (unlocked() ? runScopeRotationWorker().catch(() => {}) : undefined)
  poller.start(tick, { immediate: true }) // drain any queued rotations immediately on entry, then keep polling
}

/** Stop polling + detach the visibility listener (logout / re-lock). Safe to call when not running. */
export function stopScopeRotationPolling(): void {
  poller.stop()
}
