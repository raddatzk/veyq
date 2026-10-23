// Transient-feedback channel (web) - fixes the app conventions so call sites pass only i18n strings, not toast
// plumbing (cr-0157, audit #74/P3d, ADR 0039). Two shapes: `success(summary)` for an auto-dismissing acknowledgement
// (retires the inline `notice` refs), and `showUndo(...)` for an undo-toast carrying a "Rückgängig" action (retires the
// two hand-rolled bars). The toasts are drawn by the layer's `VeyqToaster` in App.vue (`shared/ui/toast.ts` is the
// door); the timeout/restore live in `useUndo` at the call site - this only shows the toast and wires the action to
// `onUndo`.
import { useToastChannel } from '../ui/toast'

export { UNDO_GROUP } from '../ui/toast'

/** Default auto-dismiss for a success acknowledgement. */
const SUCCESS_LIFE_MS = 4000

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
  const channel = useToastChannel()

  /** An auto-dismissing success acknowledgement in the default toast region. */
  function success(summary: string): void {
    channel.add({ severity: 'success', summary, life: SUCCESS_LIFE_MS })
  }

  /** A failure the user must hear about when no open form can hold an inline message — stays until closed. */
  function error(summary: string): void {
    channel.add({ severity: 'error', summary })
  }

  /** An undo-toast: its action runs `onUndo`. */
  function showUndo({ summary, onUndo, life }: ShowUndoOptions): void {
    channel.add({ severity: 'info', summary, life, onUndo })
  }

  /** Programmatically clear the undo-toast — e.g. when the delete it offered to undo turned out to fail. */
  function dismissUndo(): void {
    channel.removeUndo()
  }

  return { success, error, showUndo, dismissUndo }
}
