// A report on the wire (uc.moderation.report, ADR 0093 § 2), recorded at `fetch` through the real client: the request
// carries the category, the epoch, the items' kinds and ids and sealed blobs — and NO plaintext of the reason or the
// context anywhere; the address only in `receiptAddress` (owner decision Q6), and not at all when none was given. What
// was sealed opens with the epoch's moderation key, and each report share with the K_report inside it — the
// moderator's side of the same bytes.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'

vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.unmock('./moderationKey')

import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { session } from '../../app/session'
import type { Access, GuestAccess, MemberAccess } from '../access'
import { ShareRole } from '../../api/generated/sharing'
import { ReportCategory } from '../../api/generated/moderation'
import { resetReporting, submitReport, type ReportRequest, type ReportTarget } from './report'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f3'
const NODE = '5c09e000-0000-4000-8000-0000000000a0'
const SHARE_ID = 'Abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE'
const REASON = 'canary-reason-7f3a — this shows a threat'
const CONTACT = 'canary-reporter-7f3a@example.com'
const M = v1.generateModerationKey()
const M2 = v1.generateModerationKey()
const K_NODE = v1.randomBytes(32)
const K_SHARE = v1.randomBytes(32)

type Call = { url: string; method: string; body: string | null; headers: Headers }
const calls: Call[] = []
let epoch = 1
let refuseFirstPost = false

const keyJson = () => {
  const bundle = v1.moderationBundle(epoch === 1 ? M : M2)
  return { epoch, kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign) }
}

beforeEach(() => {
  calls.length = 0
  epoch = 1
  refuseFirstPost = false
  resetReporting()
  session.accessToken = 'member-token'
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const url = String(input)
    calls.push({ url, method, body: init?.body ? String(init.body) : null, headers: new Headers(init?.headers) })
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
    if (url.endsWith('/api/moderation/key')) return json(keyJson())
    if (refuseFirstPost) {
      refuseFirstPost = false
      epoch = 2
      return json({ code: 'moderation_epoch_changed' }, 409)
    }
    return json({ reportId: '0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f', receivedAt: '2026-10-17T12:00:00Z' }, 201)
  }))
})
afterEach(() => {
  vi.unstubAllGlobals()
  session.accessToken = null
})

const member = { mode: 'member', scopeId: SCOPE } as unknown as MemberAccess
const guest = (role: ShareRole): GuestAccess => ({
  mode: 'guest', shareId: SHARE_ID, role, pwToken: 'cHctdG9rZW4=', fragmentKey: b64url(K_SHARE),
  head: { scopeId: SCOPE, kShare: K_SHARE } as unknown as GuestAccess['head'],
  requestGuest: async () => null, reload: async () => {},
})
const nodeTarget: ReportTarget = { kind: 'NODE', id: NODE, keys: () => [{ role: 'NODE', ref: null, key: K_NODE }] }
const request = (access: Access, targets: ReportTarget[] = [nodeTarget]): ReportRequest => ({ access, scopeId: SCOPE, targets })
const posts = () => calls.filter((c) => c.method === 'POST')

describe('a report on the wire', () => {
  it('a member POSTs the clear fields and sealed blobs only: no reason, no context, the address only for the receipt', async () => {
    await submitReport(request(member), [nodeTarget], { category: ReportCategory.ThreatToLife, reason: REASON, contact: CONTACT })
    const [post] = posts()
    expect(post.url).toMatch(new RegExp(`/api/scopes/${SCOPE}/reports$`))
    expect(post.headers.get('authorization')).toBe('Bearer member-token')
    const body = JSON.parse(post.body!) as Record<string, unknown>
    expect(Object.keys(body).sort()).toEqual(['category', 'epoch', 'items', 'receiptAddress', 'sealedReport'])
    expect(body.category).toBe('THREAT_TO_LIFE')
    expect(body.epoch).toBe(1)
    expect(body.receiptAddress).toBe(CONTACT)
    // The reason appears nowhere; the address nowhere but its one field (owner decision Q6).
    expect(post.body).not.toContain('canary-reason')
    const { receiptAddress: _, ...rest } = body
    expect(JSON.stringify(rest)).not.toContain('canary-reporter')
    expect(body.sealedReport).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    const items = body.items as { kind: string; id?: string; sealedShare: string }[]
    expect(items.map(({ kind, id }) => ({ kind, id }))).toEqual([{ kind: 'NODE', id: NODE }])

    // The moderator's side: the epoch's key opens the sealed part, and its K_report opens the report share.
    const opened = v1.openReport(1, v1.moderationKemSecret(M), fromB64std(body.sealedReport as string))
    expect(opened).toMatchObject({ reason: REASON, contact: CONTACT, context: '' })
    expect(v1.openReportShare(opened.reportKey, { kind: 'NODE', id: NODE }, fromB64std(items[0].sealedShare))).toEqual([
      { role: 'NODE', ref: null, key: K_NODE },
    ])
    expect(() => v1.openReport(1, v1.moderationKemSecret(M2), fromB64std(body.sealedReport as string))).toThrow(v1.OpenRefused)
  })

  it('without an address no receiptAddress goes, and the sealed part carries an empty contact', async () => {
    await submitReport(request(member), [nodeTarget], { category: ReportCategory.TermsBreach, reason: REASON, contact: '  ' })
    const body = JSON.parse(posts()[0].body!) as Record<string, unknown>
    expect('receiptAddress' in body).toBe(false)
    expect(v1.openReport(1, v1.moderationKemSecret(M), fromB64std(body.sealedReport as string)).contact).toBe('')
  })

  it('a guest reports through the link: its write proof and password token, no Authorization header', async () => {
    const g = guest(ShareRole.WriteOnly)
    const link: ReportTarget = { kind: 'LINK', keys: () => [{ role: 'SHARE', ref: null, key: K_SHARE }] }
    session.accessToken = null
    await submitReport(request(g, [link]), [link], { category: ReportCategory.IllegalContent, reason: REASON, contact: '' })
    const [post] = posts()
    expect(post.url).toMatch(new RegExp(`/api/shares/${SHARE_ID}/reports$`))
    expect(post.headers.get('authorization')).toBeNull()
    expect(post.headers.get('x-share-write')).toBe(b64std(v1.shareWriteToken(K_SHARE, SHARE_ID)))
    expect(post.headers.get('x-share-password')).toBe('cHctdG9rZW4=')
    const body = JSON.parse(post.body!) as { items: { kind: string; id?: string; sealedShare: string }[]; sealedReport: string }
    expect(body.items).toHaveLength(1)
    expect(body.items[0].kind).toBe('LINK')
    expect('id' in body.items[0]).toBe(false)
    const opened = v1.openReport(1, v1.moderationKemSecret(M), fromB64std(body.sealedReport))
    expect(v1.openReportShare(opened.reportKey, { kind: 'LINK', shareId: SHARE_ID }, fromB64std(body.items[0].sealedShare))[0].key).toEqual(K_SHARE)
  })

  it('a key that changed in between is read again and the report sealed anew, once', async () => {
    refuseFirstPost = true
    await submitReport(request(member), [nodeTarget], { category: ReportCategory.TermsBreach, reason: REASON, contact: '' })
    const sent = posts()
    expect(sent).toHaveLength(2)
    expect(calls.filter((c) => c.url.endsWith('/api/moderation/key'))).toHaveLength(2)
    const second = JSON.parse(sent[1].body!) as { epoch: number; sealedReport: string }
    expect(second.epoch).toBe(2)
    expect(v1.openReport(2, v1.moderationKemSecret(M2), fromB64std(second.sealedReport)).reason).toBe(REASON)
  })
})
