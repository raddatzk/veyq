// uc.moderation.object-to-a-measure (web) — the member's side with real crypto: the statements are read only for an
// unlocked member, matched by the blocked id, the facts open with the key wrapped to the member's own identity at the
// case, statement and account the statement names, and the objection is sealed to the moderation key's current epoch, bound to its statement — read
// afresh once when the server says the epoch moved on.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { httpError } from '../../../test/httpError'
import type { ModerationStatement } from '../../../api/generated/moderation'

const keys = vi.hoisted(() => ({ identity: null as null | { kemSeed: Uint8Array; sign: Uint8Array } }))
vi.mock('../../../api/client', () => ({ statementsApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('../../../app/vault', () => ({
  identityPrivate: () => (keys.identity ? { kemSeed: keys.identity.kemSeed.slice(), sign: keys.identity.sign.slice() } : null),
}))
vi.mock('../../../core/report/report', () => ({ publishedKey: vi.fn() }))
vi.mock('./statementsRead', () => ({ readMyStatements: vi.fn() }))

import { statementsApi } from '../../../api/client'
import type { StatementsApi } from '../../../api/generated/moderation'
import { fakeApi } from '../../../test/apiMock'
import { account } from '../../../app/account'
import { publishedKey } from '../../../core/report/report'
import { guestStatementFor, loadStatements, objectTo, openFacts, statementFor, statements } from './statements'
import { closeGuestPage, guestPage, openGuestPage } from '../../../core/sharing/guestNotice'
import { readMyStatements } from './statementsRead'

const CASE = '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b'
const ME = '7e8f9a0b-1c2d-4e3f-8a5b-6c7d8e9f0a1b'
const STATEMENT = '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e'

const statement = (over: Partial<ModerationStatement> = {}): ModerationStatement => ({
  id: STATEMENT,
  caseId: CASE,
  measureKind: 'CONTENT',
  targetId: 'node-1',
  shareId: undefined,
  scopeId: undefined,
  ground: 'TERMS_BREACH',
  provision: null,
  basis: 'REPORT',
  sentAt: new Date(),
  roles: ['UPLOADER'],
  sealedFacts: undefined,
  inForce: true,
  liftedAt: undefined,
  endedWithTarget: false,
  contactAddress: undefined,
  objection: undefined,
  canObject: true,
  ...over,
})

describe('the statements of an affected member', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    statements.list = []
    account.userId = ''
    keys.identity = null
  })

  it('reads nothing without an unlocked member, and once with one', async () => {
    vi.mocked(readMyStatements).mockResolvedValue([statement()])
    await loadStatements(true)
    expect(readMyStatements).not.toHaveBeenCalled()
    account.userId = ME
    await loadStatements(true)
    await loadStatements()
    expect(readMyStatements).toHaveBeenCalledTimes(1)
    expect(statementFor({ targetId: 'node-1' })?.id).toBe(STATEMENT)
    expect(statementFor({ targetId: 'node-2' })).toBeUndefined()
    statements.list = [statement({ measureKind: 'SHARE', targetId: 'link-1', shareId: 'link-1' })]
    expect(statementFor({ shareId: 'link-1' })?.measureKind).toBe('SHARE')
    // Everything from one uploader: found on each item it covers, not on one it does not.
    statements.list = [statement({ measureKind: 'UPLOADER_CONTENT', targetId: 'uploader-1', covers: ['medium-1', 'file-1'] })]
    expect(statementFor({ targetId: 'medium-1' })?.measureKind).toBe('UPLOADER_CONTENT')
    expect(statementFor({ targetId: 'file-1' })?.measureKind).toBe('UPLOADER_CONTENT')
    expect(statementFor({ targetId: 'medium-2' })).toBeUndefined()
  })

  it('finds a guest uploader’s statement on the guest page by the same ids, with the contact, and none off that page (ADR 0093 § 7)', () => {
    const guestOne = {
      id: STATEMENT, measureKind: 'UPLOADER_CONTENT' as const, targetId: 'identity-1', covers: ['medium-1'],
      ground: 'TERMS_BREACH' as const, takenAt: new Date('2026-10-17T12:00:00Z'),
    }
    const link = { ...guestOne, id: 'other', measureKind: 'SHARE' as const, targetId: 'link-1', shareId: 'link-1', covers: [] }
    openGuestPage('link-1')
    guestPage.notice = { receivedAt: [], statements: [guestOne, link], contact: 'abuse@example.org' }
    expect(guestStatementFor({ targetId: 'medium-1' })).toEqual({ statement: guestOne, contact: 'abuse@example.org' })
    expect(guestStatementFor({ shareId: 'link-1' })?.statement.measureKind).toBe('SHARE')
    expect(guestStatementFor({ targetId: 'medium-2' })).toBeUndefined()
    // A member's lookup never reads the guest's answer, nor the guest's a member's.
    expect(statementFor({ targetId: 'medium-1' })).toBeUndefined()
    closeGuestPage()
    expect(guestStatementFor({ targetId: 'medium-1' })).toBeUndefined()
  })

  it('unwraps the facts’ key with this member’s identity key and opens the one sealed copy, at the statement it names', () => {
    const seed = v1.generateModerationKey()
    keys.identity = { kemSeed: v1.moderationKemSecret(seed), sign: new Uint8Array(32) }
    account.userId = ME
    const k = v1.newFactsKey()
    const sealedFacts = b64std(v1.sealStatementFacts(CASE, STATEMENT, k, 'the photo shows a threat'))
    const wrappedFactsKey = b64std(v1.wrapFactsKey(CASE, STATEMENT, ME, v1.moderationBundle(seed).kem, k))
    const facts = { sealedFacts, wrappedFactsKey }
    expect(openFacts(statement(facts))).toBe('the photo shows a threat')
    // Read under another case or another statement, or by another member: it does not open here.
    expect(openFacts(statement({ ...facts, caseId: STATEMENT }))).toBeNull()
    expect(openFacts(statement({ ...facts, id: CASE }))).toBeNull()
    // The key of one statement served beside another statement's facts opens nothing.
    const other = b64std(v1.sealStatementFacts(CASE, STATEMENT, v1.newFactsKey(), 'another statement'))
    expect(openFacts(statement({ ...facts, sealedFacts: other }))).toBeNull()
    account.userId = STATEMENT
    expect(openFacts(statement(facts))).toBeNull()
    account.userId = ME
    expect(openFacts(statement({ ...facts, sealedFacts: undefined }))).toBeNull()
    expect(openFacts(statement({ ...facts, wrappedFactsKey: undefined }))).toBeNull()
    keys.identity = null
    expect(openFacts(statement(facts))).toBeNull()
  })

  it('seals the objection to the current epoch bound to its statement, and reads the key afresh once when it moved on', async () => {
    const m = v1.generateModerationKey()
    const kem = v1.moderationBundle(m).kem
    vi.mocked(publishedKey).mockImplementation(async (fresh) => ({ epoch: fresh ? 3 : 2, kem }))
    const { objectToStatement } = fakeApi(statementsApi, {
      objectToStatement: vi
        .fn<StatementsApi['objectToStatement']>()
        .mockRejectedValueOnce(httpError(409, 'moderation_epoch_changed'))
        .mockResolvedValue(statement({ canObject: false })),
    })
    statements.list = [statement()]
    const answered = await objectTo(statement(), 'it is my own address')
    expect(objectToStatement).toHaveBeenCalledTimes(2)
    const sent = objectToStatement.mock.calls[1][0]
    expect(sent.statementId).toBe(STATEMENT)
    expect(sent.objectionSubmission.epoch).toBe(3)
    expect(sent.objectionSubmission.sealedObjection).not.toContain('address')
    const opened = v1.openObjection(3, STATEMENT, v1.moderationKemSecret(m), fromB64std(sent.objectionSubmission.sealedObjection))
    expect(opened).toBe('it is my own address')
    expect(answered.canObject).toBe(false)
    expect(statements.list[0].canObject).toBe(false)
  })

  it('another refusal is not retried', async () => {
    vi.mocked(publishedKey).mockResolvedValue({ epoch: 2, kem: v1.moderationBundle(v1.generateModerationKey()).kem })
    const { objectToStatement } = fakeApi(statementsApi, {
      objectToStatement: vi.fn<StatementsApi['objectToStatement']>().mockRejectedValue(httpError(409, 'objection_exists')),
    })
    await expect(objectTo(statement(), 'again')).rejects.toBeTruthy()
    expect(objectToStatement).toHaveBeenCalledTimes(1)
  })
})
