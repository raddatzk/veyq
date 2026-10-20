// The review's reads on the wire (uc.moderation.review-a-case steps 4-5): each is a moderator's — the role is read off the
// session — so each carries the session's bearer AND the key proof, recorded at `fetch` from the real generated clients.
// Lane L1 found the gap this pins: a spec whose other operations are a guest's anonymous ones declared no bearer scheme,
// so its generated client sent `listCaseMedia` without the token, and the server answered 401.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { session } from '../../../app/session'
import { caseReviewApi } from '../../../api/client'
import { caseMediaApi } from '../../memories/api'
import { caseFilesApi } from '../../depot/api'

const CASE_ID = '7c000000-0000-4000-8000-000000000004'
const PROOF = { xVeyqModerationChallenge: 'challenge-1', xVeyqModerationSignature: 'signature-1' }

let sent: { url: string; headers: Headers }[] = []
beforeEach(() => {
  sent = []
  session.accessToken = 'moderator-token'
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    sent.push({ url: String(input instanceof Request ? input.url : input), headers: new Headers(init?.headers) })
    return new Response('{}', { status: 500 })
  }))
})
afterEach(() => {
  session.accessToken = null
  vi.unstubAllGlobals()
})

describe("the review's reads carry the moderator's bearer and the proof", () => {
  it.each([
    ['getCaseReview', () => caseReviewApi().getCaseReview({ caseId: CASE_ID, ...PROOF })],
    ['presignCaseBlobs', () => caseReviewApi().presignCaseBlobs({ caseId: CASE_ID, caseBlobsRequest: { blobIds: [] }, ...PROOF })],
    ['listCaseMedia', () => caseMediaApi().listCaseMedia({ caseId: CASE_ID, ...PROOF })],
    ['listCaseFiles', () => caseFilesApi().listCaseFiles({ caseId: CASE_ID, ...PROOF })],
  ])('%s', async (_name, call) => {
    await call().catch(() => undefined)
    expect(sent).toHaveLength(1)
    const [request] = sent
    expect(new URL(request!.url, 'https://veyq.test').pathname.startsWith(`/api/moderation/cases/${CASE_ID}/`)).toBe(true)
    expect(request!.headers.get('authorization')).toBe('Bearer moderator-token')
    expect(request!.headers.get('x-veyq-moderation-challenge')).toBe('challenge-1')
    expect(request!.headers.get('x-veyq-moderation-signature')).toBe('signature-1')
  })
})
