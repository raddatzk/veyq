// The guest resolve's conditional GET on the wire (ADR 0081): recorded at `fetch` — the headers the real generated client
// sends and the status and headers it is answered with, no mock of the API client. The first poll holds no tag and sends
// none; a later one echoes the tag the last response named, byte for byte; a 304 is `null` (nothing to open); and the
// failures keep their meaning: a 404 is "this link is gone" (the page flips), a 403 the password, while a network error,
// a 429 or a 5xx say nothing about the link and are rethrown as they are.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import {
  pollPublicShare,
  SHARE_MEMORY_NAME,
  ShareUnavailableError,
  SharePasswordIncorrectError,
  SharePasswordRequiredError,
} from './resolvePublicShare'

const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const K_SHARE = v1.randomBytes(32)
const FRAGMENT = b64url(K_SHARE)

type Sent = { url: string; ifNoneMatch: string | null; password: string | null }

function recordFetch(...answers: (Response | Error)[]) {
  const sent: Sent[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers = new Headers(init?.headers)
    sent.push({ url: String(input), ifNoneMatch: headers.get('if-none-match'), password: headers.get('x-share-password') })
    const answer = answers.shift()
    if (!answer) throw new Error('unexpected request')
    if (answer instanceof Error) throw answer
    return answer
  }))
  return sent
}
const unlocked = (etag: string | null) =>
  new Response(
    JSON.stringify({
      role: 'READ', passwordRequired: false, scopeId: '22222222-2222-4222-8222-222222222222', rootKind: 'FOLDER',
      sealedName: b64std(v1.seal(SHARE_MEMORY_NAME, K_SHARE, v1.utf8('Fotos'))), nodes: [],
    }),
    { status: 200, headers: { 'content-type': 'application/json', ...(etag ? { ETag: etag } : {}) } },
  )
const status = (code: number) => new Response(JSON.stringify({ code: 'x' }), { status: code, headers: { 'content-type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('pollPublicShare', () => {
  it('sends no If-None-Match while it holds no tag, and returns the head with the tag the response named', async () => {
    const sent = recordFetch(unlocked('"abc"'))
    const polled = await pollPublicShare(SHARE, FRAGMENT, undefined, null)
    expect(sent).toEqual([{ url: `/api/shares/${SHARE}`, ifNoneMatch: null, password: null }])
    expect(polled?.etag).toBe('"abc"')
    expect(polled?.head).toMatchObject({ shareId: SHARE, name: 'Fotos', rootKind: 'FOLDER' })
  })

  it('echoes the tag exactly, with the password token, and a 304 is null', async () => {
    const sent = recordFetch(new Response(null, { status: 304, headers: { ETag: '"abc"' } }))
    expect(await pollPublicShare(SHARE, FRAGMENT, 'pw-token', '"abc"')).toBeNull()
    expect(sent[0]).toMatchObject({ ifNoneMatch: '"abc"', password: 'pw-token' })
  })

  it('a changed share is a 200 with a new tag', async () => {
    recordFetch(unlocked('"def"'))
    expect((await pollPublicShare(SHARE, FRAGMENT, undefined, '"abc"'))?.etag).toBe('"def"')
  })

  it('a 404 — revoked, expired, deleted, its space going — is ShareUnavailableError', async () => {
    recordFetch(status(404))
    await expect(pollPublicShare(SHARE, FRAGMENT, undefined, '"abc"')).rejects.toBeInstanceOf(ShareUnavailableError)
  })

  it('a 403 is the wrong password, and the locked shell asks for it', async () => {
    recordFetch(status(403))
    await expect(pollPublicShare(SHARE, FRAGMENT, 'old', '"abc"')).rejects.toBeInstanceOf(SharePasswordIncorrectError)
    recordFetch(new Response(JSON.stringify({ role: 'READ', passwordRequired: true, pwSalt: 'c2FsdA==', nodes: [] }), { status: 200, headers: { 'content-type': 'application/json' } }))
    await expect(pollPublicShare(SHARE, FRAGMENT, undefined, '"abc"')).rejects.toBeInstanceOf(SharePasswordRequiredError)
  })

  it('a network error, a 429 and a 5xx are rethrown as they are: they say nothing about the link', async () => {
    for (const failure of [new TypeError('failed to fetch'), status(429), status(500), status(503)]) {
      recordFetch(failure)
      const error = await pollPublicShare(SHARE, FRAGMENT, undefined, '"abc"').catch((e: unknown) => e)
      expect(error).not.toBeInstanceOf(ShareUnavailableError)
      expect(error).toBeInstanceOf(Error)
    }
  })

  it('a missing or malformed fragment key is unavailable before anything is asked', async () => {
    const sent = recordFetch()
    await expect(pollPublicShare(SHARE, '', undefined, null)).rejects.toBeInstanceOf(ShareUnavailableError)
    expect(sent).toEqual([])
  })
})
