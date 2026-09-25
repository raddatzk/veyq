import { ref, watch, type Ref } from 'vue'
import { pwnedCount } from '../../shared/pwned'

/** Why the first submit was stopped: the breach count, or `null` when the check could not run. */
export interface BreachStop {
  count: number | null
}

/**
 * The opt-in breached-password check of a password-choosing dialog (docs/crosscutting/security-model.md § the
 * breached-password check). `enabled` is the dialog's box — unticked by default and never persisted, because a
 * password is chosen rarely and a remembered preference would consent to requests the user no longer sees coming.
 *
 * `proceed()` is the submit guard, awaited before the dialog's own submit work:
 * - box unticked → proceed, no request;
 * - the password and the box unchanged since the last stop → proceed, no new request (the second press: the
 *   check advises, it never gates);
 * - otherwise ONE query over the final password: clean → proceed; a hit, or a check that could not run → stop.
 *
 * It never runs while typing. A query at every typing pause sent the prefix of each PARTIAL password, and a short
 * one — or a few keystrokes added to one already recovered — is all but identified by its 20-bit prefix.
 */
export function useBreachCheck(password: Ref<string>): {
  enabled: Ref<boolean>
  stop: Ref<BreachStop | null>
  proceed: () => Promise<boolean>
} {
  const enabled = ref(false)
  const stop = ref<BreachStop | null>(null)
  // A changed password or an unticked box is no longer the thing the stop was about. `sync`, so a stop can never
  // outlive its password by a render tick and wave a different password through as the "second press".
  watch(
    [password, enabled],
    () => {
      stop.value = null
    },
    { flush: 'sync' },
  )

  async function proceed(): Promise<boolean> {
    if (!enabled.value) return true
    // Kept, not cleared: if the dialog's own work then fails, pressing again still goes ahead without a query.
    if (stop.value) return true
    const checked = password.value
    const count = await pwnedCount(checked)
    // Edited (or unticked) while the query was in flight: what was checked is not what would be submitted.
    if (password.value !== checked || !enabled.value) return false
    if (count === 0) return true
    stop.value = { count }
    return false
  }

  return { enabled, stop, proceed }
}
