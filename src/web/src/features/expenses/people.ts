// How a Pot's participants read on screen (docs/apps/expenses.md § The Pot): a live or deactivated one by name, every
// erased one as one "deleted people" — in the data each keeps its own id, which entries and the rounding order use;
// only the screen merges them. And who an entry may name: the active ones, plus whoever the entry being edited already
// names (a deactivated or deleted person stays on an old entry, never offered for a new one).
import type { Participant, PotLedger } from './domain/ledger'

type Translate = (key: string) => string

/** The label of participant [id] in [ledger]: the name, or "deleted people" for an erased one. */
export function personLabel(ledger: PotLedger, id: string, t: Translate): string {
  const p = ledger.participants.find((x) => x.id === id)
  if (!p || p.erased || p.name === null) return t('expenses.people.deletedPeople')
  return p.name
}

/** The people an entry may name: every active one, then those [named] already (in join order). */
export function offeredPeople(ledger: PotLedger, named: readonly string[] = []): Participant[] {
  const keep = new Set(named)
  return ledger.participants.filter((p) => (p.active && !p.erased) || keep.has(p.id))
}

/** A balance per person as the screen shows it: each live or deactivated person by name, and every erased one summed
 *  into ONE line (`id: null`). Deactivated or erased people whose balance is settled are left out — they show again
 *  once an edit unsettles them, so the group sees what is open. */
export function shownBalances(ledger: PotLedger): { id: string | null; participant: Participant | null; balance: bigint }[] {
  const rows: { id: string | null; participant: Participant | null; balance: bigint }[] = []
  let erased = 0n
  let anyErasedOpen = false
  for (const p of ledger.participants) {
    const balance = ledger.balances.get(p.id) ?? 0n
    if (p.erased) {
      erased += balance
      if (balance !== 0n) anyErasedOpen = true
      continue
    }
    if (!p.active && balance === 0n) continue
    rows.push({ id: p.id, participant: p, balance })
  }
  if (anyErasedOpen) rows.push({ id: null, participant: null, balance: erased })
  return rows
}
