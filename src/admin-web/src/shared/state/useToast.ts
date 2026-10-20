// Transient-feedback channel —a thin wrapper over PrimeVue's `useToast()` that fixes the app conventions so
// call sites pass only i18n strings, not toast plumbing (cr-0157, audit #74/P3d, ADR 0039).
// One shape: `success(summary)` for an auto-dismissing acknowledgement (retires the inline `notice` refs).
import { useToast as usePrimeToast } from 'primevue/usetoast'

/** Default auto-dismiss for a success acknowledgement. */
const SUCCESS_LIFE_MS = 4000

export function useToast(): {
  success: (summary: string) => void
} {
  const toast = usePrimeToast()

  /** An auto-dismissing success acknowledgement in the default toast region. */
  function success(summary: string): void {
    toast.add({ severity: 'success', summary, life: SUCCESS_LIFE_MS })
  }

  return { success }
}
