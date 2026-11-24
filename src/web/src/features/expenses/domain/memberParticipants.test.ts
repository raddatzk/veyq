// A participant that is a member of the space (ADR 0108 decision 2, inbox #783), through the real write path and the real
// reader (`potTestUtil.ts`): the record's first revision decides its kind for life — a reader ignores a later revision
// that adds, drops or changes the member, and this client's writer refuses to seal one — and a member is one participant
// of the Pot at most. Each rule beside the control that differs in it alone.
import { describe, it, expect } from 'vitest'
import { buildLedger, participantMemberOf } from './ledger'
import { byRecord } from './revisions'
import { PotWriteRefused, sealPotWrites, type PotWrite } from './potApi'
import { ANNA, BEN, EUR, PotServer, person } from '../potTestUtil'
import { sealRevision } from './codec'
import { headsToName } from './heads'
import type { RevisionBody } from './format'

const U_DANA = '0d000000-0000-4000-8000-000000000001'
const U_ERIK = '0d000000-0000-4000-8000-000000000002'
const DANA = '0e000000-0000-4000-8000-0000000000d1'

const member = (name: string, joined: number, userId: string, active = true): PotWrite['content'] => ({ kind: 'participant', name, active, joined, member: { userId } })

function pot() {
  const s = new PotServer()
  s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: DANA, content: member('Dana', 2, U_DANA) }])
  return s
}
/** A revision written past this client's writer (an older client, or a forged one): sealed straight with the codec. */
function writeAround(s: PotServer, entryId: string, content: PotWrite['content']) {
  const held = s.held()
  const revision = Math.max(...held.filter((r) => r.entryId === entryId).map((r) => r.revision)) + 1
  const sealed = sealRevision({ scopeId: s.scopeId, nodeId: s.nodeId, entryId, revision }, s.potKey, { v: 1, heads: headsToName(held), ...content } as RevisionBody)
  s.rows.push({ entryId, revision, createdAt: new Date(), writer: 'user:u-x', ...sealed })
}
const refusal = (f: () => unknown): string | null => {
  try {
    f()
    return null
  } catch (e) {
    if (e instanceof PotWriteRefused) return e.reason
    throw e
  }
}

describe('a member participant in the ledger', () => {
  it('reads as the member its first revision names; a name participant as none', () => {
    const ledger = buildLedger(pot().held(), EUR)
    expect(ledger.participants.map((p) => [p.name, p.member])).toEqual([['Anna', null], ['Dana', U_DANA]])
  })

  it('a later revision that drops the member (an older client) leaves it a member; one that names another leaves the first', () => {
    const s = pot()
    writeAround(s, DANA, person('Dana', 2, false))
    expect(buildLedger(s.held(), EUR).participants.find((p) => p.id === DANA)).toMatchObject({ member: U_DANA, active: false })
    writeAround(s, DANA, member('Dana', 2, U_ERIK))
    expect(buildLedger(s.held(), EUR).participants.find((p) => p.id === DANA)!.member).toBe(U_DANA)
  })

  it('a name never becomes a member by a later revision — nobody turns someone else\'s participant into their own account', () => {
    const s = pot()
    writeAround(s, ANNA, member('Anna', 1, U_ERIK))
    const anna = buildLedger(s.held(), EUR).participants.find((p) => p.id === ANNA)!
    expect(anna.member).toBeNull()
    // the same answer from the record's revisions alone, the forged one last among them
    expect(participantMemberOf(byRecord(s.held()).get(ANNA)!)).toBeNull()
  })

  it('an erased member participant names nobody any more', () => {
    const s = pot()
    s.erase(DANA)
    expect(buildLedger(s.held(), EUR).participants.find((p) => p.id === DANA)).toMatchObject({ erased: true, member: null, name: null })
  })
})

describe('the writer keeps a participant\'s kind', () => {
  const seal = (s: PotServer, writes: PotWrite[]) => () => sealPotWrites({ scopeId: s.scopeId, nodeId: s.nodeId }, s.potKey, s.held(), writes, EUR)

  it('refuses a revision that drops, changes or adds the member (control: the same member carried along seals)', () => {
    const s = pot()
    expect(refusal(seal(s, [{ entryId: DANA, content: person('Dana', 2, false) }]))).toBe('kind-changed')
    expect(refusal(seal(s, [{ entryId: DANA, content: member('Dana', 2, U_ERIK, false) }]))).toBe('kind-changed')
    expect(refusal(seal(s, [{ entryId: ANNA, content: member('Anna', 1, U_ERIK) }]))).toBe('kind-changed')
    expect(refusal(seal(s, [{ entryId: DANA, content: member('Dana', 2, U_DANA, false) }]))).toBeNull()
    expect(refusal(seal(s, [{ entryId: ANNA, content: person('Anna B.', 1) }]))).toBeNull()
  })

  it('refuses a member who already is a participant, or twice in one batch (control: another member, or after the erasure)', () => {
    const s = pot()
    expect(refusal(seal(s, [{ entryId: BEN, content: member('Dana again', 3, U_DANA) }]))).toBe('member-twice')
    const E1 = '0e000000-0000-4000-8000-0000000000e1'
    expect(refusal(seal(s, [{ entryId: BEN, content: member('Erik', 3, U_ERIK) }, { entryId: E1, content: member('Erik', 4, U_ERIK) }]))).toBe('member-twice')
    expect(refusal(seal(s, [{ entryId: BEN, content: member('Erik', 3, U_ERIK) }]))).toBeNull()
    s.erase(DANA)
    expect(refusal(seal(s, [{ entryId: BEN, content: member('Dana', 3, U_DANA) }]))).toBeNull()
  })
})
