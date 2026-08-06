// Undo timer-state (web) — the timer/ref/auto-dismiss/cleanup skeleton the two hand-rolled undo bars
// (gallery delete cr-0110, buckets multi-delete cr-0122) each duplicated (cr-0157, audit #74/P3d, ADR 0039).
// Holds a single pending payload `T` behind a `timeoutMs` window: `arm(payload)` starts the window,
// `cancel()` (the user hit Undo) stops it and hands the payload back so the caller can restore, and the
// window elapsing (or an unmount) commits via `onExpire`. The item-specific restore/commit stays at the
// call site as the `cancel()` follow-up / `onExpire` callback — this owns only the timeout, not the business.
import { onBeforeUnmount, ref, type Ref } from 'vue'

export interface UseUndoOptions<T> {
  /** How long the undo window stays open before `onExpire` commits. */
  timeoutMs: number
  /** Commit hook: runs when the window elapses (or on unmount) with the still-pending payload. */
  onExpire?: (payload: T) => void
}

export function useUndo<T>({ timeoutMs, onExpire }: UseUndoOptions<T>): {
  /** The payload awaiting commit, or `null` when no undo window is open. */
  pending: Ref<T | null>
  /** Open a fresh undo window for `payload`. A payload already pending is committed first (no silent loss). */
  arm: (payload: T) => void
  /** The user hit Undo: close the window without committing and return the payload to restore (or `null`). */
  cancel: () => T | null
  /** Commit now: run `onExpire` with the pending payload and close the window. No-op when nothing is pending. */
  expire: () => void
} {
  const pending = ref<T | null>(null) as Ref<T | null>
  let timer: ReturnType<typeof setTimeout> | null = null

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function expire(): void {
    if (pending.value === null) return
    const payload = pending.value
    clearTimer()
    pending.value = null
    onExpire?.(payload)
  }

  function arm(payload: T): void {
    // A new window supersedes a still-open one — commit the previous payload rather than drop it.
    expire()
    pending.value = payload
    timer = setTimeout(expire, timeoutMs)
  }

  function cancel(): T | null {
    const payload = pending.value
    clearTimer()
    pending.value = null
    return payload
  }

  // A teardown mid-window commits the pending payload (matches the bars' auto-dismiss-on-leave).
  onBeforeUnmount(expire)

  return { pending, arm, cancel, expire }
}
