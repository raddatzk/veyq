// How a Pot's participants read on screen (docs/apps/expenses.md § The Pot): a live or deactivated one by name, every
// erased one as one "deleted people" — in the data each keeps its own id, which entries and the rounding order use;
// only the screen merges them. And who an entry may name: the active ones, plus whoever the entry being edited already
// names (a deactivated or deleted person stays on an old entry, never offered for a new one).
//
// A participant that is a member (ADR 0108 decision 2) reads, for a member, by the CURRENT display name the space's
// directory gives (`core/people/directory.ts`), and as a former member once the directory no longer lists them (an erased
// or departed account) — by the name sealed at adding, so the balances still say whom one owes. A link guest reads no
// directory: it is handed none and shows the sealed name.
import type { Person } from '../../core/people/directory'
import type { Participant, PotLedger } from './domain/ledger'
import type { ParticipantBody } from './domain/format'

type Translate = (key: string) => string

/** The space's current members as this viewer knows them, by user id: a member's read of the Pot's scope, or null — a link
 *  guest, who is never handed one, or a member whose read has not answered (yet). */
export type MemberDirectory = ReadonlyMap<string, Person> | null

/** How a participant stands to the space's members: a name (`name`), a member the directory lists (`member`), one it no
 *  longer lists (`former`), or a member this viewer cannot look up (`sealed`: a guest, or the directory not read yet). */
export type ParticipantStanding = 'name' | 'member' | 'former' | 'sealed'

export function standingOf(p: Participant, directory: MemberDirectory): ParticipantStanding {
  if (p.member === null) return 'name'
  if (!directory) return 'sealed'
  return directory.has(p.member) ? 'member' : 'former'
}

/** The name participant [p] reads by: a listed member's current display name, else the record's own (the sealed one). */
export function shownName(p: Participant, directory: MemberDirectory): string | null {
  if (p.erased) return null
  const current = p.member !== null ? directory?.get(p.member)?.displayName : undefined
  return current ?? p.name
}

/** The label of participant [id] in [ledger]: its shown name ({@link shownName}), or "deleted people" for an erased one. */
export function personLabel(ledger: PotLedger, id: string, t: Translate, directory: MemberDirectory = null): string {
  const p = ledger.participants.find((x) => x.id === id)
  const name = p ? shownName(p, directory) : null
  return name ?? t('expenses.people.deletedPeople')
}

/** The members a writer may add as participants: the space's current members [members] that are no participant of the
 *  Pot yet (an erased participant names nobody any more), in the order given. [pot] is the Pot's ledger — or, in the
 *  create dialog, the people chosen so far, before any record exists. */
export function addableMembers(pot: { readonly participants: readonly Pick<Participant, 'erased' | 'member'>[] }, members: readonly Person[]): Person[] {
  const taken = new Set(pot.participants.filter((p) => !p.erased && p.member !== null).map((p) => p.member!))
  return members.filter((m) => !taken.has(m.userId))
}

/** Case and accents aside: "Zoë" and "zoe" are the same letters. */
const folded = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase()

/** Whether [a] and [b] are the same name, case and accents aside. */
export const sameName = (a: string, b: string): boolean => folded(a.trim()) === folded(b.trim())

/** The members of [members] whose display name holds [query], case and accents aside — the create dialog's suggestions
 *  while a name is typed; every one of them for a blank query. In the order given. */
export function membersMatching(members: readonly Person[], query: string): Person[] {
  const q = folded(query.trim())
  return q ? members.filter((m) => folded(m.displayName).includes(q)) : [...members]
}

/** A participant a writer adds: a name typed, or a member of the space chosen ({@link addableMembers}). */
export type NewParticipant = { readonly name: string } | { readonly member: Person }

/** The first revision of the participant [p], joining at [joined]. A member's record holds their user id and, sealed
 *  with it, their display name as it is now — what a reader without the directory (a link guest) shows — cut to the
 *  format's 100 characters. */
export function participantRecord(p: NewParticipant, joined: number): ParticipantBody {
  if ('name' in p) return { kind: 'participant', name: p.name, active: true, joined }
  return { kind: 'participant', name: [...p.member.displayName].slice(0, 100).join(''), active: true, joined, member: { userId: p.member.userId } }
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
