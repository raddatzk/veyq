// uc.moderation.review-a-case, uc.moderation.take-a-measure (web) — every case call carries a FRESH key proof over its
// OWN operation id and its case (the server rebuilds the statement from the operation it matched, so a proof made for
// another operation would be refused), and the texts the server must never read are sealed and opened in the browser
// with a real moderation key: a decision's note, an extension's reason, a report.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../api/client', () => ({ moderationApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('./proof', async (orig) => ({ ...(await orig<object>()), proofFor: vi.fn() }))

import { moderationApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import type { ModerationCase } from '../../api/generated/moderation'
import {
  barReporter,
  dismissCase,
  extendHold,
  getCase,
  liftMeasure,
  listCases,
  openNote,
  openReportOf,
  proposeStep,
  reviewUrl,
  sealNote,
  takeMeasure,
} from './cases'
import { ModerationKeyMissing, proofFor } from './proof'
import { clearModerationKeys, holdModerationKeys } from './moderationKeys'

const CASE = '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b'
const ITEM = '4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b'
const MEASURE = '9a8b1c2d-3e4f-4a6b-8f2c-9a1e7b3d4e5f'
const REPORT = '4e5f9a8b-1c2d-4e4f-8a6b-4f2c9a1e7b3d'
const ACCOUNT = '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a'
const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'
const UNTIL = new Date('2027-04-01T00:00:00Z')
const EPOCH = 2

const aCase: ModerationCase = {
  id: CASE,
  itemKind: 'ITEM',
  itemId: ITEM,
  scopeId: '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c',
  tenantId: null,
  tenantDomain: null,
  category: 'TERMS_BREACH',
  state: 'OPEN',
  decidedAt: null,
  closedAt: null,
  hold: null,
  reports: [],
  measures: [],
  proposals: [],
  targets: { links: [], uploaders: [] },
  events: [],
}

const stub = () =>
  stubApi(moderationApi, {
    listModerationCases: { cases: [] },
    getModerationCase: aCase,
    dismissModerationCase: aCase,
    takeModerationMeasure: aCase,
    liftModerationMeasure: aCase,
    proposeModerationStep: aCase,
    extendModerationHold: aCase,
    barModerationReporter: aCase,
  })

let api: ReturnType<typeof stub>
let key: Uint8Array
let proofs = 0

beforeEach(() => {
  vi.clearAllMocks()
  proofs = 0
  // Each proof is distinct, so a call that reused an earlier one would carry the wrong headers.
  vi.mocked(proofFor).mockImplementation(async () => {
    proofs += 1
    return { xVeyqModerationChallenge: `challenge-${proofs}`, xVeyqModerationSignature: `signature-${proofs}` }
  })
  api = stub()
  key = v1.generateModerationKey()
  holdModerationKeys(new Map([[EPOCH, key.slice()]]), EPOCH)
})

afterEach(clearModerationKeys)

type Decision = [operationId: keyof typeof api, call: () => Promise<unknown>, request: Record<string, unknown>]

const decisions: Decision[] = [
  ['dismissModerationCase', () => dismissCase(CASE, ''), { moderationDecisionNote: { note: null } }],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'SHARE', { shareId: SHARE }, ''),
    { moderationMeasureRequest: { kind: 'SHARE', shareId: SHARE, targetId: null, note: null } },
  ],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, ''),
    { moderationMeasureRequest: { kind: 'CONTENT', shareId: null, targetId: ITEM, note: null } },
  ],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'GUEST_IDENTITY', { targetId: ACCOUNT }, ''),
    { moderationMeasureRequest: { kind: 'GUEST_IDENTITY', shareId: null, targetId: ACCOUNT, note: null } },
  ],
  ['liftModerationMeasure', () => liftMeasure(CASE, MEASURE, ''), { measureId: MEASURE, moderationDecisionNote: { note: null } }],
  [
    'proposeModerationStep',
    () => proposeStep(CASE, 'MEMBER_SIGN_IN', ACCOUNT, ''),
    { moderationProposalRequest: { step: 'MEMBER_SIGN_IN', targetId: ACCOUNT, note: null } },
  ],
  [
    'proposeModerationStep',
    () => proposeStep(CASE, 'TENANT_OFFLINE', null, ''),
    { moderationProposalRequest: { step: 'TENANT_OFFLINE', targetId: null, note: null } },
  ],
  ['extendModerationHold', () => extendHold(CASE, UNTIL, 'an authority asked'), { moderationHoldExtension: expect.objectContaining({ until: UNTIL }) }],
  ['barModerationReporter', () => barReporter(CASE, REPORT, ''), { reportId: REPORT, moderationDecisionNote: { note: null } }],
]

describe('a decision carries a fresh proof over its own operation and its case', () => {
  it.each(decisions)('%s', async (operationId, call, request) => {
    await call()
    expect(proofFor).toHaveBeenCalledTimes(1)
    expect(proofFor).toHaveBeenCalledWith(operationId, CASE)
    expect(api[operationId]).toHaveBeenCalledWith({
      caseId: CASE,
      xVeyqModerationChallenge: 'challenge-1',
      xVeyqModerationSignature: 'signature-1',
      ...request,
    })
    // No other operation was called with it.
    for (const [other, f] of Object.entries(api)) if (other !== operationId) expect(f).not.toHaveBeenCalled()

    // A second call takes a second proof: a challenge proves one call.
    await call()
    expect(proofFor).toHaveBeenCalledTimes(2)
    expect(api[operationId]).toHaveBeenLastCalledWith(
      expect.objectContaining({ xVeyqModerationChallenge: 'challenge-2', xVeyqModerationSignature: 'signature-2' }),
    )
  })

  it('the queue is proved with no case, one case over that case', async () => {
    await listCases('CLOSED')
    expect(proofFor).toHaveBeenLastCalledWith('listModerationCases')
    expect(api.listModerationCases).toHaveBeenCalledWith({ state: 'CLOSED', xVeyqModerationChallenge: 'challenge-1', xVeyqModerationSignature: 'signature-1' })
    await getCase(CASE)
    expect(proofFor).toHaveBeenLastCalledWith('getModerationCase', CASE)
    expect(api.getModerationCase).toHaveBeenCalledWith({ caseId: CASE, xVeyqModerationChallenge: 'challenge-2', xVeyqModerationSignature: 'signature-2' })
  })

  it('an extension without a reason is refused before any proof is made', async () => {
    await expect(extendHold(CASE, UNTIL, '  ')).rejects.toThrow('extension_reason_required')
    expect(proofFor).not.toHaveBeenCalled()
    expect(api.extendModerationHold).not.toHaveBeenCalled()
  })
})

describe('the sealed texts', () => {
  it('a decision note is sealed to the current epoch, bound to its case, and opens with that epoch', async () => {
    await dismissCase(CASE, 'unfounded: a holiday photo')
    const note = api.dismissModerationCase.mock.calls[0][0].moderationDecisionNote.note!
    expect(note.epoch).toBe(EPOCH)
    expect(note.sealedNote).not.toContain('holiday')
    expect(openNote(EPOCH, CASE, note.sealedNote)).toBe('unfounded: a holiday photo')
    // Bound to its case: under another case's id it does not open.
    expect(openNote(EPOCH, ITEM, note.sealedNote)).toBeNull()
  })

  it('an extension reason is sealed the same way', async () => {
    await extendHold(CASE, UNTIL, 'an authority asked')
    const { reason } = api.extendModerationHold.mock.calls[0][0].moderationHoldExtension
    expect(openNote(reason.epoch, CASE, reason.sealedNote)).toBe('an authority asked')
  })

  it('sealNote and openNote round-trip; no text seals to nothing; an epoch not held opens nothing', () => {
    const sealed = sealNote(CASE, 'a lawyer’s letter')!
    expect(sealed.epoch).toBe(EPOCH)
    expect(openNote(EPOCH, CASE, sealed.sealedNote)).toBe('a lawyer’s letter')
    expect(openNote(EPOCH + 1, CASE, sealed.sealedNote)).toBeNull()
    expect(sealNote(CASE, '   ')).toBeNull()
  })

  it('without the key nothing is sealed or opened', () => {
    const sealed = sealNote(CASE, 'kept')!
    clearModerationKeys()
    expect(() => sealNote(CASE, 'a note')).toThrow(ModerationKeyMissing)
    expect(openNote(EPOCH, CASE, sealed.sealedNote)).toBeNull()
  })

  it('a report opens with its epoch’s key', () => {
    const body: v1.ReportBody = { reportKey: new Uint8Array(32).fill(7), reason: 'it is a threat', contact: 'r@example.org', context: 'a guest' }
    const sealed = b64std(v1.sealReport(EPOCH, v1.moderationBundle(key).kem, body))
    const opened = openReportOf(EPOCH, sealed)!
    expect(opened.reason).toBe('it is a threat')
    expect(opened.contact).toBe('r@example.org')
    expect(opened.context).toBe('a guest')
    expect(openReportOf(EPOCH + 1, sealed)).toBeNull()
  })
})

describe('reviewUrl', () => {
  const here = { href: 'https://mod.example.org:8443/moderation?tab=queue#x' } as Location

  it('opens on the tenant’s domain, keeping protocol and port, with the moderation fragment', () => {
    expect(reviewUrl(CASE, 'club.example.org', here)).toBe(`https://club.example.org:8443/s/${CASE}#moderation`)
  })

  it('stays on this host when the server names no domain', () => {
    expect(reviewUrl(CASE, null, here)).toBe(`https://mod.example.org:8443/s/${CASE}#moderation`)
  })
})
