// The transient-feedback channel's door into the layer (ADR 0039, ADR 0107): `shared/state/useToast` says WHAT to
// show, this queues it for what draws the toasts (`VeyqToaster`, mounted once in App.vue). Nothing else adds a toast.
import { reactive } from 'vue'

/** The group whose surface in `VeyqToaster` renders the undo action. */
export const UNDO_GROUP = 'undo'

export interface ToastEntry {
  severity: 'success' | 'error' | 'info'
  summary: string
  /** Milliseconds until it goes by itself; absent, it stays until closed. */
  life?: number
  /** The undo toast: its action runs this. */
  onUndo?: () => void
}

export interface QueuedToast extends ToastEntry {
  id: number
}

export interface ToastChannel {
  add: (entry: ToastEntry) => void
  removeUndo: () => void
}

/** What `VeyqToaster` draws, in the order they arrived. */
export const toastQueue = reactive<QueuedToast[]>([])
let nextId = 0

/** A toast is gone: closed, timed out or replaced. */
export function dropToast(id: number): void {
  const at = toastQueue.findIndex((t) => t.id === id)
  if (at >= 0) toastQueue.splice(at, 1)
}

/** The channel. A function, as the library's was, so a caller sets it up once and keeps the handle. */
export function useToastChannel(): ToastChannel {
  return {
    add: (entry) => void toastQueue.push({ ...entry, id: ++nextId }),
    removeUndo: () => {
      for (const t of toastQueue.filter((q) => q.onUndo)) dropToast(t.id)
    },
  }
}
