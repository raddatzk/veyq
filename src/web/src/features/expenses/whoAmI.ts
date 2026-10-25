// "Who am I" in a Pot (uc.expenses.balances step 2, docs/apps/expenses.md § The Pot): each viewer picks their
// participant once, and their row is marked *you* in the balances and the overview. The choice is the viewer's own and
// stays in this browser — per Pot, and per audience as every client setting is (an account's, or a share guest's:
// `shared/state/scopedPreference.ts`) — and is never sent: no request carries it, no seal holds it.
import { computed, type ComputedRef } from 'vue'
import { createScopedPreference } from '../../shared/state/scopedPreference'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
type Preference = ReturnType<typeof createScopedPreference<string>>
const byPot = new Map<string, Preference>()

/** The storage base of Pot [potId]'s choice (the audience is appended to it). */
export const whoAmIBase = (potId: string) => `veyq.expenses.me.${potId}`

/** The participant the viewer chose to be in Pot [potId] (null: not chosen), and how to choose another (null: none). */
export function whoAmI(potId: string): { me: ComputedRef<string | null>; choose: (participantId: string | null) => void } {
  let pref = byPot.get(potId)
  if (!pref) {
    pref = createScopedPreference<string>(whoAmIBase(potId), (raw) => (raw && UUID.test(raw) ? raw : undefined), '')
    byPot.set(potId, pref)
  }
  const held = pref
  return { me: computed(() => held.value.value || null), choose: (id) => held.set(id ?? '') }
}
