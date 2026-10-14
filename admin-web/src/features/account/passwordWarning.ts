import { computed, ref, watch, type Ref, type WritableComputedRef } from 'vue'
import { pwnedCount } from '../../shared/pwned'
import { MIN_PASSWORD_LENGTH } from '../../shared/passwordPolicy'

/**
 * Where the check stands for the password currently in the field. `accepted` is a hit the user explicitly chose
 * to use anyway — for this exact password; any edit drops back to `idle`.
 */
export type BreachState =
  | { kind: 'idle' }
  | { kind: 'checking' }
  | { kind: 'clean' }
  | { kind: 'hit'; count: number }
  | { kind: 'unavailable' }
  | { kind: 'accepted'; count: number }

/**
 * The breached-password check of a password-choosing dialog (docs/crosscutting/security-model.md § the
 * breached-password check). Automatic, never while typing:
 *
 * - `checkNow()` is bound to the password field's blur — the moment a person treats the password as done. ONE
 *   query per distinct finished password: a blur on an unchanged password asks nothing again, and a password the
 *   dialog's own policy refuses (empty, under the floor) is never sent at all.
 * - `proceed()` is the submit guard, awaited before the dialog's own submit work. A password changed since the
 *   last check is checked once here. Then: clean, unavailable or accepted → go ahead; a hit → stop until the user
 *   ticks `useAnyway`. A check that could not run stops nothing — the dialog only says so.
 *
 * It never runs while typing. A query at every typing pause sent the prefix of each PARTIAL password, and a short
 * one — or a few keystrokes added to one already recovered — is all but identified by its 20-bit prefix.
 */
export function useBreachCheck(password: Ref<string>): {
  state: Readonly<Ref<BreachState>>
  useAnyway: WritableComputedRef<boolean>
  checkNow: () => Promise<void>
  proceed: () => Promise<boolean>
} {
  const state = ref<BreachState>({ kind: 'idle' })
  // The password `state` speaks about, and the query in flight for it. Plain variables: nothing renders them.
  let about: string | null = null
  let inFlight: Promise<void> | null = null

  // An edit makes the verdict — and any acceptance of it — about a different password. `sync`, so a verdict can
  // never outlive its password by a render tick and wave a different password through.
  watch(
    password,
    (pw) => {
      if (pw === about) return
      about = null
      inFlight = null
      state.value = { kind: 'idle' }
    },
    { flush: 'sync' },
  )

  function checkNow(): Promise<void> {
    const pw = password.value
    if (pw.length < MIN_PASSWORD_LENGTH) return Promise.resolve()
    if (pw === about) return inFlight ?? Promise.resolve()
    about = pw
    state.value = { kind: 'checking' }
    const query = pwnedCount(pw).then((count) => {
      // Edited while the query was in flight: the answer is about a password no longer in the field.
      if (inFlight !== query) return
      inFlight = null
      state.value = count === null ? { kind: 'unavailable' } : count === 0 ? { kind: 'clean' } : { kind: 'hit', count }
    })
    inFlight = query
    return query
  }

  const useAnyway = computed<boolean>({
    get: () => state.value.kind === 'accepted',
    set: (yes) => {
      const s = state.value
      if (yes && s.kind === 'hit') state.value = { kind: 'accepted', count: s.count }
      else if (!yes && s.kind === 'accepted') state.value = { kind: 'hit', count: s.count }
    },
  })

  async function proceed(): Promise<boolean> {
    const checked = password.value
    await checkNow()
    // Edited while the submit's own query was in flight: what was checked is not what would be submitted.
    if (password.value !== checked) return false
    const kind = state.value.kind
    return kind === 'clean' || kind === 'unavailable' || kind === 'accepted'
  }

  return { state, useAnyway, checkNow, proceed }
}
