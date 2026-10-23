// Transient-feedback channel — the one door to the layer's toaster (`shared/ui/toast.ts`) that fixes the app
// conventions so call sites pass only i18n strings, not toast plumbing (cr-0157, audit #74/P3d, ADR 0039).
// One shape: `success(summary)` for an auto-dismissing acknowledgement (retires the inline `notice` refs).
import { addToast } from '../ui/toast'

/** Default auto-dismiss for a success acknowledgement. */
const SUCCESS_LIFE_MS = 4000

export function useToast(): {
  success: (summary: string) => void
} {
  /** An auto-dismissing success acknowledgement in the default toast region. */
  function success(summary: string): void {
    addToast({ severity: 'success', summary, life: SUCCESS_LIFE_MS })
  }

  return { success }
}
