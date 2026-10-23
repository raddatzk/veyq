// The transient-feedback channel's door into the layer (ADR 0039, ADR 0107): `shared/state/useToast` says WHAT to
// show, this hands it to what draws the toasts (`VeyqToaster`, mounted once in App.vue). Nothing else adds a toast.
import type { ToastMessageOptions } from 'primevue/toast'
import { useToast as useLibraryToast } from 'primevue/usetoast'

/** The group whose templated surface in `VeyqToaster` renders the undo action. */
export const UNDO_GROUP = 'undo'

export interface ToastEntry {
  severity: 'success' | 'error' | 'info'
  summary: string
  /** Milliseconds until it goes by itself; absent, it stays until closed. */
  life?: number
  /** The undo toast: its action runs this. */
  onUndo?: () => void
}

export interface ToastChannel {
  add: (entry: ToastEntry) => void
  removeUndo: () => void
}

/** The channel, for the component being set up (it injects the toaster's service). */
export function useToastChannel(): ToastChannel {
  const toast = useLibraryToast()
  function add({ severity, summary, life, onUndo }: ToastEntry): void {
    if (!onUndo) {
      toast.add({ severity, summary, life })
      return
    }
    // `onUndo` rides along on the message so `VeyqToaster`'s template can wire the action to it; it is not part of
    // the library's options, and extra keys survive at runtime.
    const message: ToastMessageOptions & { onUndo: () => void } = { severity, summary, life, group: UNDO_GROUP, closable: false, onUndo }
    toast.add(message)
  }
  return { add, removeUndo: () => toast.removeGroup(UNDO_GROUP) }
}
