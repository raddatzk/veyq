// The transient-feedback channel's door into the layer (ADR 0039, ADR 0107): `shared/state/useToast` says WHAT to
// show, this queues it for what draws the toasts (`VeyqToaster`, mounted once in App.vue). Nothing else adds a toast.
import { reactive } from 'vue'

export interface ToastEntry {
  severity: 'success' | 'error' | 'info'
  summary: string
  /** Milliseconds until it goes by itself; absent, it stays until closed. */
  life?: number
}

export interface QueuedToast extends ToastEntry {
  id: number
}

/** What `VeyqToaster` draws, in the order they arrived. */
export const toastQueue = reactive<QueuedToast[]>([])
let nextId = 0

/** A toast is gone: closed or timed out. */
export function dropToast(id: number): void {
  const at = toastQueue.findIndex((t) => t.id === id)
  if (at >= 0) toastQueue.splice(at, 1)
}

/** Queue a toast for `VeyqToaster`. */
export function addToast(entry: ToastEntry): void {
  toastQueue.push({ ...entry, id: ++nextId })
}
