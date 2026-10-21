// uc.moderation.review-a-case, uc.moderation.take-a-measure (web) — every case call carries a FRESH key proof over its
// OWN operation id and its case (the server rebuilds the statement from the operation it matched, so a proof made for
// another operation would be refused), and the texts the server must never read are sealed and opened in the browser
// with a real moderation key: a decision's note, an extension's reason, a report.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

vi.mock('../../api/client', () => ({ moderationApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('./proof', async (orig) => ({ ...(await orig<object>()), proofFor: vi.fn() }))

import { moderationApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import type { ModerationCase } from '../../api/generated/moderation'
import {
  barReporter,
  decideObjection,
  dismissCase,
  extendHold,
  getCase,
  getExport,
  liftMeasure,
  listCases,
  openNote,
  openObjectionOf,
  openReportOf,
  proposeStep,
  recordHandover,
  renewDeferral,
  reporterNoticesOf,
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
  tenantId: undefined,
  tenantDomain: undefined,
  category: 'TERMS_BREACH',
  state: 'OPEN',
  decidedAt: undefined,
  closedAt: undefined,
  hold: undefined,
  reports: [],
  measures: [],
  proposals: [],
  targets: { links: [], uploaders: [] },
  events: [],
  origin: 'REPORT',
  orders: [],
  objections: [],
}

const NO_FACTS = { ground: 'TERMS_BREACH' as const, facts: '' }
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const STATEMENT_NONE = {
  statementId: expect.stringMatching(UUID_V4),
  ground: 'TERMS_BREACH',
  provision: null,
  facts: null,
  sealedFacts: null,
  factsKeyFor: [],
  deferral: null,
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
    decideModerationObjection: aCase,
    getModerationStatementRecipients: { accounts: [], guests: 0 },
    deferModerationStatement: aCase,
    recordModerationHandover: aCase,
    getModerationCaseExport: { caseId: CASE, category: 'TERMS_BREACH', origin: 'REPORT', timeZone: 'UTC', reportedAt: [], measures: [], uploaders: [], guestWrites: [], held: false },
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
  ['dismissModerationCase', () => dismissCase(CASE, ''), { moderationDismissal: { note: null, reporterNotices: [] } }],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'SHARE', { shareId: SHARE }, '', NO_FACTS),
    { moderationMeasureRequest: { kind: 'SHARE', shareId: SHARE, targetId: null, note: null, statement: STATEMENT_NONE, reporterNotices: [] } },
  ],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', NO_FACTS),
    { moderationMeasureRequest: { kind: 'CONTENT', shareId: null, targetId: ITEM, note: null, statement: STATEMENT_NONE, reporterNotices: [] } },
  ],
  [
    'takeModerationMeasure',
    () => takeMeasure(CASE, 'GUEST_IDENTITY', { targetId: ACCOUNT }, '', NO_FACTS),
    { moderationMeasureRequest: { kind: 'GUEST_IDENTITY', shareId: null, targetId: ACCOUNT, note: null, statement: STATEMENT_NONE, reporterNotices: [] } },
  ],
  [
    'decideModerationObjection',
    () => decideObjection(CASE, REPORT, 'UPHOLD', ''),
    { objectionId: REPORT, moderationObjectionDecision: { outcome: 'UPHOLD', note: null } },
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
  [
    'deferModerationStatement',
    () => renewDeferral(CASE, MEASURE, UNTIL, 'the authority renewed its request'),
    { statementId: MEASURE, moderationStatementDeferral: expect.objectContaining({ until: UNTIL }) },
  ],
  ['recordModerationHandover', () => recordHandover(CASE, 'REPORTED', 'BKA 17'), { moderationHandover: expect.objectContaining({ kind: 'REPORTED' }) }],
  ['getModerationCaseExport', () => getExport(CASE), {}],
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
    const note = api.dismissModerationCase.mock.calls[0][0].moderationDismissal.note!
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

  it('a report opens with its epoch’s key, its contact sealed apart beside it', () => {
    const kem = v1.moderationBundle(key).kem
    const body: v1.ReportBody = { reportKey: new Uint8Array(32).fill(7), reason: 'it is a threat', context: 'a guest' }
    const sealed = b64std(v1.sealReport(EPOCH, kem, body))
    const contact = b64std(v1.sealReportContact(EPOCH, kem, 'r@example.org'))
    const opened = openReportOf(EPOCH, sealed, contact)!
    expect(opened.reason).toBe('it is a threat')
    expect(opened.contact).toBe('r@example.org')
    expect(opened.context).toBe('a guest')
    expect(openReportOf(EPOCH + 1, sealed, contact)).toBeNull()
    // Gone after six months (ADR 0093 § 11), or never left: the report still opens, with no contact.
    expect(openReportOf(EPOCH, sealed, null)!.contact).toBe('')
    // A contact that does not open (sealed to another epoch) reads as none, the report still opens.
    expect(openReportOf(EPOCH, sealed, b64std(v1.sealReportContact(EPOCH + 1, kem, 'x@y.z')))!.contact).toBe('')
  })
})

describe('the statement of reasons', () => {
  const recipient = () => {
    const k = v1.generateModerationKey()
    return { secret: v1.moderationKemSecret(k), kem: v1.moderationBundle(k).kem }
  }

  it('seals the facts to the moderation key, and once under a key wrapped to every member the server lists', async () => {
    const a = recipient()
    const b = recipient()
    const identity = (kem: Uint8Array) => b64std(v1.serializeIdentityPublic({ kem, sign: new Uint8Array(32) }))
    api.getModerationStatementRecipients.mockResolvedValueOnce({
      accounts: [
        { accountId: ACCOUNT, identityPublicKey: identity(a.kem) },
        { accountId: REPORT, identityPublicKey: identity(b.kem) },
      ],
      guests: 1,
    })
    await takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', { ground: 'ILLEGAL_CONTENT', provision: ' § 241 StGB ', facts: 'the photo shows a threat' })
    expect(proofFor).toHaveBeenNthCalledWith(1, 'getModerationStatementRecipients', CASE)
    expect(proofFor).toHaveBeenNthCalledWith(2, 'takeModerationMeasure', CASE)
    expect(api.getModerationStatementRecipients).toHaveBeenCalledWith(expect.objectContaining({ caseId: CASE, kind: 'CONTENT', targetId: ITEM }))
    const { statement } = api.takeModerationMeasure.mock.calls[0][0].moderationMeasureRequest
    expect(statement.ground).toBe('ILLEGAL_CONTENT')
    // The provision travels in the clear beside the ground (DSA Art. 17(3)(d)), trimmed; the facts stay sealed.
    expect(statement.provision).toBe('§ 241 StGB')
    expect(openNote(statement.facts!.epoch, CASE, statement.facts!.sealedNote)).toBe('the photo shows a threat')
    // The statement's id is minted here, and the facts are sealed once, bound to it and to the case.
    expect(statement.statementId).toMatch(UUID_V4)
    const bytes = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
    expect(statement.sealedFacts).not.toContain('threat')
    // Each member gets only the key, wrapped to their identity at their account; it opens the one sealed copy.
    expect(statement.factsKeyFor!.map((w) => w.accountId)).toEqual([ACCOUNT, REPORT])
    const [forA, forB] = statement.factsKeyFor!.map((w) => bytes(w.wrappedFactsKey))
    const kA = v1.unwrapFactsKey(CASE, statement.statementId, ACCOUNT, a.secret, forA)
    expect(v1.unwrapFactsKey(CASE, statement.statementId, REPORT, b.secret, forB)).toEqual(kA)
    expect(v1.openStatementFacts(CASE, statement.statementId, kA, bytes(statement.sealedFacts!))).toBe('the photo shows a threat')
    // A wrap opens for its own member only.
    expect(() => v1.unwrapFactsKey(CASE, statement.statementId, REPORT, a.secret, forA)).toThrow(v1.OpenRefused)
    expect(() => v1.unwrapFactsKey(CASE, statement.statementId, ACCOUNT, b.secret, forA)).toThrow(v1.OpenRefused)
  })

  it('mints a fresh statement id per step', async () => {
    await takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', NO_FACTS)
    await takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', NO_FACTS)
    const [first, second] = api.takeModerationMeasure.mock.calls.map((c) => c[0].moderationMeasureRequest.statement.statementId)
    expect(first).not.toBe(second)
  })

  it('without facts reads no recipients and seals nothing', async () => {
    await takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', NO_FACTS)
    expect(api.getModerationStatementRecipients).not.toHaveBeenCalled()
    expect(api.takeModerationMeasure.mock.calls[0][0].moderationMeasureRequest.statement).toEqual(STATEMENT_NONE)
  })

  it('tells only the reporters whose contact is an address, and a report it cannot open tells nobody', () => {
    const kem = v1.moderationBundle(key).kem
    const sealedReport = b64std(v1.sealReport(EPOCH, kem, { reportKey: new Uint8Array(32), reason: 'r', context: '' }))
    const sealedContact = (contact: string) => b64std(v1.sealReportContact(EPOCH, kem, contact))
    const report = (id: string, contact: string | null, epoch = EPOCH) =>
      ({ id, epoch, receivedAt: UNTIL, category: 'TERMS_BREACH', sealedReport, sealedContact: contact, reporter: 'LINK', barred: false }) as const
    const notices = reporterNoticesOf({
      ...aCase,
      reports: [
        report(REPORT, sealedContact(' reporter@example.org ')),
        report(ITEM, sealedContact('call me on Tuesdays')),
        report(MEASURE, sealedContact('x@y.z'), EPOCH + 1),
        report(ACCOUNT, null),
      ],
    })
    expect(notices).toEqual([{ reportId: REPORT, address: 'reporter@example.org' }])
  })

  it('an objection opens with its epoch’s key at its statement, and nowhere else', () => {
    const sealed = b64std(v1.sealObjection(EPOCH, MEASURE, v1.moderationBundle(key).kem, 'it is my own address'))
    expect(openObjectionOf(EPOCH, MEASURE, sealed)).toBe('it is my own address')
    expect(openObjectionOf(EPOCH, REPORT, sealed)).toBeNull()
    expect(openObjectionOf(EPOCH + 1, MEASURE, sealed)).toBeNull()
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

describe('an authority’s deferral (uc.moderation.authority-order `the-authority-defers-the-statement`)', () => {
  it('goes with the measure, the authority’s request sealed to the case and opened with the epoch key', async () => {
    await takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', { ...NO_FACTS, deferral: { until: UNTIL, reason: 'BKA asked: not before 1 April' } })
    const deferral = api.takeModerationMeasure.mock.calls[0][0].moderationMeasureRequest.statement.deferral!
    expect(deferral.until).toEqual(UNTIL)
    expect(deferral.reason.epoch).toBe(EPOCH)
    expect(v1.openModerationNote(EPOCH, CASE, v1.moderationKemSecret(key), fromB64std(deferral.reason.sealedNote))).toBe('BKA asked: not before 1 April')
  })

  it('is refused without the authority’s request, and nothing is sent', async () => {
    await expect(takeMeasure(CASE, 'CONTENT', { targetId: ITEM }, '', { ...NO_FACTS, deferral: { until: UNTIL, reason: '  ' } }))
      .rejects.toThrow('deferral_reason_required')
    await expect(renewDeferral(CASE, MEASURE, UNTIL, '')).rejects.toThrow('deferral_reason_required')
    await expect(recordHandover(CASE, 'REPORTED', '')).rejects.toThrow('handover_reference_required')
    expect(api.takeModerationMeasure).not.toHaveBeenCalled()
    expect(api.deferModerationStatement).not.toHaveBeenCalled()
    expect(api.recordModerationHandover).not.toHaveBeenCalled()
  })
})
