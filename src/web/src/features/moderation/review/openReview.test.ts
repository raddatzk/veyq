// The review's data half (uc.moderation.review-a-case steps 4-5): a report opens with its epoch's key and its report
// share hands over the item's keys; the head the guest viewer renders takes its root key from that share (no `K_share`,
// no wrap); and every byte read of the page goes to `presignCaseBlobs` — batched, one fresh key proof over the case per
// call — never to the link's own presign (`shareChunkUrls` while a review is active).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', async (orig) => ({ ...(await orig<object>()), caseReviewApi: vi.fn(), publicSharesApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('../proof', async (orig) => ({
  ...(await orig<object>()),
  proofFor: vi.fn(),
}))

import { caseReviewApi, publicSharesApi } from '../../../api/client'
import { fakeApi, stubApi } from '../../../test/apiMock'
import { ShareRole, type CaseReview } from '../../../api/generated/sharing'
import { beginReview } from '../../../core/sharing/review'
import { shareChunkUrls } from '../../../core/sharing/guestBlob'
import { clearModerationKeys, holdModerationKeys } from '../moderationKeys'
import { proofFor } from '../proof'
import { keyOf, openReview, reviewHead, reviewSource, rootNameOf } from './openReview'

const CASE_ID = '7c000000-0000-4000-8000-000000000002'
const SCOPE_ID = '88888888-8888-4888-8888-888888888888'
const ROOT = '6e5a0000-0000-4000-8000-000000000800'
const EPOCH = 2

let moderationKey: Uint8Array
beforeEach(() => {
  vi.mocked(proofFor).mockReset()
  vi.mocked(proofFor).mockImplementation(async (operationId: string, caseId?: string | null) => ({
    xVeyqModerationChallenge: `c:${operationId}:${caseId}`, xVeyqModerationSignature: 's',
  }))
  moderationKey = v1.generateModerationKey()
  holdModerationKeys(new Map([[EPOCH, moderationKey.slice()]]), EPOCH)
})
afterEach(() => clearModerationKeys())

function report(reportId: string, epoch: number, keys: v1.ReportKey[], itemId = ROOT, kind: 'NODE' | 'NAME' = 'NODE') {
  const reportKey = v1.randomBytes(32)
  return {
    reportId, epoch, receivedAt: new Date('2026-10-01T10:00:00Z'),
    sealedReport: b64std(v1.sealReport(epoch, v1.moderationBundle(moderationKey).kem, { reportKey, reason: `reason ${reportId}`, context: '' })),
    sealedShare: b64std(v1.sealReportShare(reportKey, { kind, id: itemId }, keys)),
  }
}
function caseReview(reports: CaseReview['reports']): CaseReview {
  return {
    caseId: CASE_ID, itemKind: 'NODE', itemId: ROOT, reports, heldLinks: [],
    share: { role: ShareRole.Read, passwordRequired: false, scopeId: SCOPE_ID, rootKind: 'FOLDER', nodes: [] },
  }
}

describe('openReview', () => {
  it('opens each report and takes the keys of the first report share that opens', () => {
    const kRoot = v1.randomBytes(32)
    const opened = openReview(caseReview([report('r-1', EPOCH, [{ role: 'NODE', ref: null, key: kRoot }])]))
    expect(opened.reports.map((r) => r.body?.reason)).toEqual(['reason r-1'])
    expect(keyOf(opened, 'NODE')).toEqual(kRoot)

    const head = reviewHead(CASE_ID, opened, 'Unnamed')!
    expect(head.rootKey).toEqual(kRoot)
    expect(head.role).toBe(ShareRole.Read)
    expect(head.name).toBe('Unnamed')
  })

  it("a report of an epoch this account does not hold opens nothing, and the next report's share is used", () => {
    const kRoot = v1.randomBytes(32)
    const opened = openReview(caseReview([report('r-old', EPOCH + 5, [{ role: 'NODE', ref: null, key: v1.randomBytes(32) }]), report('r-2', EPOCH, [{ role: 'NODE', ref: null, key: kRoot }])]))
    expect(opened.reports.map((r) => r.body === null)).toEqual([true, false])
    expect(keyOf(opened, 'NODE')).toEqual(kRoot)
  })

  it('a report share addressed to another item hands over nothing — no head to render', () => {
    const opened = openReview(caseReview([report('r-1', EPOCH, [{ role: 'NODE', ref: null, key: v1.randomBytes(32) }], '6e5a0000-0000-4000-8000-000000000899')]))
    expect(opened.keys).toEqual([])
    expect(reviewHead(CASE_ID, opened, 'Unnamed')).toBeNull()
  })
})

describe('a name reported on its own (ADR 0093 § 9)', () => {
  const ctx = { purpose: 'node-name', scopeId: SCOPE_ID, nodeId: ROOT, epoch: 0, placementRevision: 3 } as const
  const parentKey = v1.randomBytes(32)
  const encryptedName = b64std(v1.sealField(ctx, parentKey, v1.utf8('Ein beleidigender Name')))
  const nameKey = v1.fieldKeyOf(ctx, parentKey, fromB64std(encryptedName))
  const review = (keys: v1.ReportKey[]): CaseReview => ({
    caseId: CASE_ID, itemKind: 'NAME', itemId: ROOT, reports: [report('r-1', EPOCH, keys, ROOT, 'NAME')], heldLinks: [],
    share: {
      role: ShareRole.Read, passwordRequired: false, scopeId: SCOPE_ID, rootKind: 'FOLDER',
      nodes: [{ id: ROOT, kind: 'FOLDER', epoch: 0, blockedByOperator: false, placementRevision: 3, encryptedName }],
    },
  })

  it('opens the held name with the report\'s NODE_NAME key, and renders no node: there is no node key', () => {
    const opened = openReview(review([{ role: 'NODE_NAME', ref: null, key: nameKey }]))
    expect(rootNameOf(opened)).toBe('Ein beleidigender Name')
    expect(keyOf(opened, 'NODE')).toBeNull()
    expect(reviewHead(CASE_ID, opened, 'Unnamed'), 'nothing below the name to render').toBeNull()
  })

  it('another revision\'s key opens nothing — the control on the same name', () => {
    const other = v1.fieldKeyOf(ctx, parentKey, v1.sealField(ctx, parentKey, v1.utf8('Ein beleidigender Name')))
    expect(rootNameOf(openReview(review([{ role: 'NODE_NAME', ref: null, key: other }])))).toBeNull()
  })
})

describe('reviewSource', () => {
  it('batches the byte reads into one presignCaseBlobs, chunks in order, a fresh proof over the case', async () => {
    const api = stubApi(caseReviewApi, {
      presignCaseBlobs: {
        blobs: [{ blobId: 'b-1', presignedGets: [{ index: 1, url: 'u1b' }, { index: 0, url: 'u1a' }] }, { blobId: 'b-2', presignedGets: [{ index: 0, url: 'u2' }] }],
      },
    })
    const source = reviewSource(CASE_ID, 1)
    const [one, two, again] = await Promise.all([source.chunkUrls('b-1'), source.chunkUrls('b-2'), source.chunkUrls('b-1')])
    expect(one).toEqual(['u1a', 'u1b'])
    expect(two).toEqual(['u2'])
    expect(again).toEqual(['u1a', 'u1b'])
    expect(api.presignCaseBlobs).toHaveBeenCalledTimes(1)
    expect(api.presignCaseBlobs).toHaveBeenCalledWith(
      expect.objectContaining({ caseId: CASE_ID, caseBlobsRequest: { blobIds: ['b-1', 'b-2'] }, xVeyqModerationChallenge: `c:presignCaseBlobs:${CASE_ID}` }),
    )
  })

  it('a blob the hold does not hold is refused; a refused call rejects every read of its batch', async () => {
    stubApi(caseReviewApi, { presignCaseBlobs: { blobs: [] } })
    await expect(reviewSource(CASE_ID, 1).chunkUrls('gone')).rejects.toThrow('not_held')
    fakeApi(caseReviewApi, { presignCaseBlobs: () => Promise.reject(new Error('moderation_proof_invalid')) })
    await expect(reviewSource(CASE_ID, 1).chunkUrls('b-1')).rejects.toThrow('moderation_proof_invalid')
  })

  it("while a review is active, the guest views' byte reads are the review's — never the link's presign", async () => {
    stubApi(caseReviewApi, { presignCaseBlobs: { blobs: [{ blobId: 'b-1', presignedGets: [{ index: 0, url: 'held' }] }] } })
    const end = beginReview(reviewSource(CASE_ID, 1))
    try {
      expect(await shareChunkUrls('any-share', 'b-1')).toEqual(['held'])
      expect(publicSharesApi).not.toHaveBeenCalled()
    } finally {
      end()
    }
  })
})
