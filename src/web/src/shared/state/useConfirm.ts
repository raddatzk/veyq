// Destructive-confirmation channel (web) — the app's own dialog in place of `window.confirm`
// (cr-web-own-confirm-dialog). Shaped after `useToast.ts`, the house precedent for "a thin wrapper that fixes
// the app conventions so call sites pass only i18n strings": the state lives here, its surface is mounted once
// in App.vue (`shared/ui/ConfirmDialog.vue`), and a call site keeps the guard clause it always had —
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
//
// A request may carry one **opt-in** (`option`, asked through `askWithOption`): a checkbox beside the consequence,
// unchecked every time the dialog opens, whose state rides on the accept — a decline answers `null` whatever was
// ticked, so a call site cannot act on an option the user then cancelled. ADR 0075's "also turn off every link" on
// a tenant removal is the first; the group removal asks the same thing through its inline confirm, which is a
// different mechanism (ui-web-styleguide § Confirming a destructive action), not a copy of this one.
import { computed, ref } from 'vue'

/** What a call site says; every string is already i18n-resolved by the caller, as with `useToast`. */
export interface ConfirmRequest {
  /** The dialog's header — short, the action in a few words. */
  header: string
  /** The consequence, in full: the `*Confirm` sentence the native dialog used to carry. */
  message: string
  /** The primary's label. Reuses the label of the button that opened it — a dialog whose primary reads
   *  differently from the control that summoned it is a new way to be wrong. */
  acceptLabel: string
  /** Optional PrimeVue icon class for the primary, mirroring the opening button's icon. */
  acceptIcon?: string
  /** An opt-in offered beside the consequence — only through `askWithOption`, which reads it back. */
  option?: ConfirmOption
}

/** A checkbox the dialog offers with the question; every string already i18n-resolved by the caller. */
export interface ConfirmOption {
  /** What ticking it adds to the action, in a few words. */
  label: string
  /** Its consequence, in a sentence under the label. */
  hint?: string
}

const pending = ref<ConfirmRequest | null>(null)
/** The open request's opt-in, as ticked so far — unchecked whenever a request opens. */
const optionChecked = ref(false)
/** Resolves the promise `ask()` handed out; `null` whenever no request is open. */
let settle: ((accepted: boolean, option: boolean) => void) | null = null

/** The open request, or `null`. Read by the single mounted `ConfirmDialog`; call sites use `ask()`. */
export const confirmRequest = computed<ConfirmRequest | null>(() => pending.value)

/** The open request's opt-in state — the dialog's checkbox binds it; it is read back only on accept. */
export const confirmOption = computed<boolean>({
  get: () => optionChecked.value,
  set: (checked) => {
    optionChecked.value = checked
  },
})

/**
 * Answer the open request and close it. The dialog calls this — accept with `true`, cancel/Escape/dismiss with
 * `false`. A stray call with nothing open is a no-op, so it can never arm the *next* request with an answer
 * nobody gave.
 */
export function settleConfirm(accepted: boolean): void {
  const resolve = settle
  const option = optionChecked.value
  settle = null
  pending.value = null
  optionChecked.value = false
  resolve?.(accepted, option)
}

export function useConfirm(): {
  ask: (request: ConfirmRequest) => Promise<boolean>
  askWithOption: (request: ConfirmRequest & { option: ConfirmOption }) => Promise<{ option: boolean } | null>
} {
  /** Ask, and resolve to what the user answered. `false` also means "something else was already being asked". */
  function ask(request: ConfirmRequest): Promise<boolean> {
    if (pending.value) return Promise.resolve(false)
    pending.value = request
    optionChecked.value = false
    return new Promise<boolean>((resolve) => {
      settle = (accepted) => resolve(accepted)
    })
  }

  /** Ask with an opt-in: `null` when declined (or another request is open), else whether the option was ticked. */
  function askWithOption(request: ConfirmRequest & { option: ConfirmOption }): Promise<{ option: boolean } | null> {
    if (pending.value) return Promise.resolve(null)
    pending.value = request
    optionChecked.value = false
    return new Promise((resolve) => {
      settle = (accepted, option) => resolve(accepted ? { option } : null)
    })
  }

  return { ask, askWithOption }
}
