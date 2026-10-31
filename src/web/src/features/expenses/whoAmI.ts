// "Who am I" in a Pot (uc.expenses.balances step 2, docs/apps/expenses.md § The Pot): each viewer picks their
// participant once, and their row is marked *you* in the balances and the overview. The choice is the viewer's own and
// stays in this browser — per Pot, and per audience as every client setting is (an account's, or a share guest's:
// `shared/state/scopedPreference.ts`) — and is never sent: no request carries it, no seal holds it.
//
// Until the viewer has chosen, a signed-in member who is a member participant of the Pot is that participant
// ({@link ownParticipant}): a default read, never written, so a choice the viewer makes — "nobody" among them, stored as
// its own token — always wins and stays theirs. A link guest has no member identity and gets no default.
import { computed, type ComputedRef } from 'vue'
import { createScopedPreference } from '../../shared/state/scopedPreference'
import type { PotLedger } from './domain/ledger'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
/** What a stored "nobody" reads as: chosen, unlike the empty value of a viewer who never chose. */
const NOBODY = 'nobody'
type Preference = ReturnType<typeof createScopedPreference<string>>
const byPot = new Map<string, Preference>()

/** The storage base of Pot [potId]'s choice (the audience is appended to it). */
export const whoAmIBase = (potId: string) => `veyq.expenses.me.${potId}`

/** The participant of [ledger] that is the member [selfId] (the signed-in account's user id; null for a link guest), or
 *  null when they are none — an erased participant is nobody's to be. */
export function ownParticipant(ledger: PotLedger, selfId: string | null): string | null {
  if (!selfId) return null
  return ledger.participants.find((p) => !p.erased && p.member === selfId)?.id ?? null
}

/** The participant the viewer is in Pot [potId] — the one they chose, else [ownDefault] while they have not chosen (null:
 *  nobody) — and how to choose another (null: nobody). */
export function whoAmI(
  potId: string, ownDefault: string | null = null,
): { me: ComputedRef<string | null>; choose: (participantId: string | null) => void } {
  let pref = byPot.get(potId)
  if (!pref) {
    pref = createScopedPreference<string>(whoAmIBase(potId), (raw) => (raw && (UUID.test(raw) || raw === NOBODY) ? raw : undefined), '')
    byPot.set(potId, pref)
  }
  const held = pref
  return {
    me: computed(() => {
      const stored = held.value.value
      if (stored === NOBODY) return null
      return stored || ownDefault
    }),
    choose: (id) => held.set(id ?? NOBODY),
  }
}
