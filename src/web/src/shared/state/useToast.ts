// Transient-feedback channel (web) — a thin wrapper over PrimeVue's `useToast()` that fixes the app
// conventions so call sites pass only i18n strings, not toast plumbing (cr-0157, audit #74/P3d, ADR 0039).
// Two shapes: `success(summary)` for an auto-dismissing acknowledgement (retires the inline `notice` refs),
// and `showUndo(...)` for an undo-toast carrying a "Rückgängig" action (retires the two hand-rolled bars).
// The undo-toast renders through the `undo`-group `<Toast>` in App.vue; the timeout/restore live in `useUndo`
// at the call site — this only shows the toast and wires the action button to `onUndo`.
import type { ToastMessageOptions } from 'primevue/toast'
import { useToast as usePrimeToast } from 'primevue/usetoast'

/** Default auto-dismiss for a success acknowledgement. */
const SUCCESS_LIFE_MS = 4000
/** The Toast group whose templated surface in App.vue renders the "Rückgängig" action. */
export const UNDO_GROUP = 'undo'

export interface ShowUndoOptions {
  /** The undo-toast text, e.g. "Foto gelöscht." (i18n resolved by the caller). */
  summary: string
  /** Runs when the user hits "Rückgängig" — cancel the pending commit + restore (typically `useUndo.cancel()`). */
  onUndo: () => void
  /** How long the toast stays visible; keep in sync with the `useUndo` timeout that owns the commit. */
  life: number
}

export function useToast(): {
  success: (summary: string) => void
  error: (summary: string) => void
  showUndo: (options: ShowUndoOptions) => void
  dismissUndo: () => void
} {
  const toast = usePrimeToast()

  /** An auto-dismissing success acknowledgement in the default toast region. */
  function success(summary: string): void {
    toast.add({ severity: 'success', summary, life: SUCCESS_LIFE_MS })
  }

  /** A failure the user must hear about when no open form can hold an inline message — stays until closed. */
  function error(summary: string): void {
    toast.add({ severity: 'error', summary })
  }

  /** An undo-toast: shown in the `undo` group, its `#message` slot renders the action wired to `onUndo`. */
  function showUndo({ summary, onUndo, life }: ShowUndoOptions): void {
    // `onUndo` rides along on the message so App.vue's `#message` slot can wire the action button to it;
    // it is not part of PrimeVue's `ToastMessageOptions`, hence the cast (extra keys survive at runtime).
    const message: ToastMessageOptions & { onUndo: () => void } = {
      severity: 'info',
      summary,
      group: UNDO_GROUP,
      life,
      closable: false,
      onUndo,
    }
    toast.add(message)
  }

  /** Programmatically clear the undo-toast — e.g. when the delete it offered to undo turned out to fail. */
  function dismissUndo(): void {
    toast.removeGroup(UNDO_GROUP)
  }

  return { success, error, showUndo, dismissUndo }
}
