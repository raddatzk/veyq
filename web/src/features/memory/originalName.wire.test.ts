// The original's file name on the wire (inbox #395 F10): every upload now seals the picked file's name into the media's
// EXIF tier — for a video, a screenshot and a PDF too — because the lightbox downloads the original under it. The
// server must still see no filename (uc.memory.gallery "no pixel, no filename"). Recorded at `fetch`, past the
// generated clients' `…ToJSON`, so this is what actually leaves the page: every JSON string, every base64 field
// DECODED (an unsealed tier would hide the name behind base64 alone), every path and header, and every binary PUT body
// are searched for the name's UTF-8 bytes. A member's upload and a guest's upload, which share the sealing but not
// the transport.
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'

vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))

import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { addMedia, type MediaDeriver } from './media'
import { addSharePhoto } from './share'
import { session } from '../../app/session'

const SCOPE_ID = '33333333-3333-4333-8333-333333333333'
const NODE_ID = '6e5a0000-0000-4000-8000-0000000000b1'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
/** Distinctive and non-ASCII, so a hit cannot be a coincidence and a Latin-1 slip would not hide it. */
const NAME = 'Strandkörbe_Ærø_2026.mp4'

type Sent = { url: string; headers: string; body: Uint8Array; json: unknown }

/** A server for both control planes and the store; every request is recorded as it left `fetch`. */
function recordFetch(): Sent[] {
  const sent: Sent[] = []
  const blobIds = new Map<string, string>()
  let seq = 0
  const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  const puts = (uploadId: string) => [{ index: 0, url: `https://store.test/${uploadId}/0` }]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const raw = init?.body
    const body: Uint8Array = raw instanceof Uint8Array ? new Uint8Array(raw) : new TextEncoder().encode(typeof raw === 'string' ? raw : '')
    const parsed = typeof raw === 'string' ? (JSON.parse(raw) as unknown) : null
    sent.push({ url, headers: JSON.stringify(init?.headers ?? {}), body, json: parsed })
    if (url.startsWith('https://store.test/')) return new Response(null, { status: 200 })
    const b = parsed as { items?: { blobId?: string; mediaId?: string; originalUploadId?: string }[] }
    if (url.endsWith('/blob-uploads-batch') || url.endsWith('/blob-uploads')) {
      return json({
        results: b.items!.map((it) => {
          const uploadId = `up-${seq++}`
          blobIds.set(uploadId, it.blobId!)
          return { uploadId, presignedPuts: puts(uploadId), error: null }
        }),
      })
    }
    if (url.endsWith('/blob-upload-windows')) return json({ results: [] })
    if (url.endsWith('/media-batch') || url.endsWith('/media')) {
      return json({ results: b.items!.map((m) => ({ mediaId: m.mediaId, blobId: blobIds.get(m.originalUploadId!), error: null })) })
    }
    throw new Error(`unexpected request ${init?.method ?? 'GET'} ${url}`)
  }))
  return sent
}

/** Whether `needle` occurs in `hay` as a byte sequence. */
function containsBytes(hay: Uint8Array, needle: Uint8Array): boolean {
  outer: for (let i = 0; i + needle.length <= hay.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer
    return true
  }
  return false
}

/** Every string inside a JSON value. */
function strings(value: unknown): string[] {
  if (typeof value === 'string') return [value]
  if (Array.isArray(value)) return value.flatMap(strings)
  if (value && typeof value === 'object') return Object.values(value).flatMap(strings)
  return []
}

/** Every place the name could ride in a request: the URL, the headers, the raw body, and each base64 string decoded. */
function leaks(s: Sent, name: string): string[] {
  const needle = new TextEncoder().encode(name)
  const where: string[] = []
  if (s.url.includes(name) || decodeURIComponent(s.url).includes(name)) where.push('url')
  if (s.headers.includes(name)) where.push('headers')
  if (containsBytes(s.body, needle)) where.push('body')
  for (const str of strings(s.json)) {
    if (str.length >= 8 && /^[A-Za-z0-9+/]*={0,2}$/.test(str) && str.length % 4 === 0 && containsBytes(fromB64std(str), needle)) {
      where.push(`base64 field ${str.slice(0, 12)}…`)
    }
  }
  return where
}

const noTiers = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn(async () => { throw new Error('no poster') }) } as unknown as MediaDeriver
const video = () => ({ blob: new Blob([new Uint8Array(64).fill(7)]), mediaType: 'video/mp4', name: NAME })

beforeEach(() => {
  session.apiBase = ''
  session.accessToken = 'access-token-1'
})
afterEach(() => {
  vi.unstubAllGlobals()
  session.accessToken = null
})

describe('the original file name never leaves the device in the clear', () => {
  it('a member upload: the name is sealed into the EXIF tier sent, and appears nowhere on the wire', async () => {
    const sent = recordFetch()
    const memoryKey = v1.randomBytes(32)
    const { mediaId } = await addMedia(SCOPE_ID, NODE_ID, memoryKey, video(), noTiers)

    // control: the name IS in what was sent — sealed. Without this, an upload that dropped it would pass the scan.
    const complete = sent.find((s) => s.url.endsWith('/media-batch'))!.json as { items: { wrappedExif: string }[] }
    const opened = v1.open({ purpose: 'media-exif', scopeId: SCOPE_ID, mediaId }, memoryKey, fromB64std(complete.items[0].wrappedExif))
    expect(JSON.parse(new TextDecoder().decode(opened))).toEqual({ v: 1, name: NAME })

    expect(sent.length).toBeGreaterThan(2)
    for (const s of sent) expect(leaks(s, NAME), s.url).toEqual([])
  })

  it("a guest's upload through a share: the same — sealed under the guest's key, nowhere in the clear", async () => {
    session.accessToken = null // the anonymous /s/ page has no session
    const sent = recordFetch()
    const sealKey = v1.randomBytes(32)
    await addSharePhoto({ identityId: 'identity-1', contributorToken: 'tok-1', shareId: SHARE_ID, scopeId: SCOPE_ID, sealKey }, video(), noTiers)

    const complete = sent.find((s) => s.url.endsWith(`/shares/${SHARE_ID}/media`))!.json as { items: { wrappedExif: string | null }[] }
    expect(complete.items[0].wrappedExif).toBeTruthy()
    for (const s of sent) expect(leaks(s, NAME), s.url).toEqual([])
  })
})
