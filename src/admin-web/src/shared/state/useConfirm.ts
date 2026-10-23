// Destructive-confirmation channel (admin-web) — the console's own dialog in place of `window.confirm`
// (cr-admin-web-own-confirm-dialog). A **copy** of `src/web/src/shared/state/useConfirm.ts`, not an import: ADR
// 0065 gives this package no shared UI, and `useToast.ts` beside it is the byte-identical precedent for
// exactly that. Shaped after that `useToast.ts`, the house precedent for "a thin wrapper that fixes the app
// conventions so call sites pass only i18n strings": the state lives here, its surface is mounted once in
// App.vue (`shared/ui/ConfirmDialog.vue`), and a call site keeps the guard clause it always had —
// `if (!(await ask({ … }))) return`.
//
// Three properties are load-bearing:
//   - **`ask()` resolves, it does not run.** The dialog never owns the mutation; the busy state stays on the row
//     button that already has `:loading`. Pulling the action in here would mean routing every call site's async
//     work through this module for no gain.
//   - **Single-flight.** A second `ask()` while one is open resolves `false` and leaves the open request alone.
//     Every caller is a user-driven click, so a concurrent ask is a bug: a queue would ask twice for one
//     double-click, and replacing the open request would silently re-point a "yes" at a different action.
//   - **Cancel, Escape and dismiss are one answer.** Anything that is not the accept button is `false`; there is
//     no third outcome a call site could forget to handle.
import { computed, ref } from 'vue'
import type { VeyqIconName } from '../ui/icons'

/** What a call site says; every string is already i18n-resolved by the caller, as with `useToast`. */
export interface ConfirmRequest {
  /** The dialog's header — short, the action in a few words. */
  header: string
  /** The consequence, in full: the `*Confirm` sentence the native dialog used to carry. */
  message: string
  /** The primary's label. Reuses the label of the button that opened it — a dialog whose primary reads
   *  differently from the control that summoned it is a new way to be wrong. */
  acceptLabel: string
  /** Optional icon for the primary, mirroring the opening button's icon. */
  acceptIcon?: VeyqIconName
}

const pending = ref<ConfirmRequest | null>(null)
/** Resolves the promise `ask()` handed out; `null` whenever no request is open. */
let settle: ((accepted: boolean) => void) | null = null

/** The open request, or `null`. Read by the single mounted `ConfirmDialog`; call sites use `ask()`. */
export const confirmRequest = computed<ConfirmRequest | null>(() => pending.value)

/**
 * Answer the open request and close it. The dialog calls this — accept with `true`, cancel/Escape/dismiss with
 * `false`. A stray call with nothing open is a no-op, so it can never arm the *next* request with an answer
 * nobody gave.
 */
export function settleConfirm(accepted: boolean): void {
  const resolve = settle
  settle = null
  pending.value = null
  resolve?.(accepted)
}

export function useConfirm(): { ask: (request: ConfirmRequest) => Promise<boolean> } {
  /** Ask, and resolve to what the user answered. `false` also means "something else was already being asked". */
  function ask(request: ConfirmRequest): Promise<boolean> {
    if (pending.value) return Promise.resolve(false)
    pending.value = request
    return new Promise<boolean>((resolve) => {
      settle = resolve
    })
  }

  return { ask }
}
