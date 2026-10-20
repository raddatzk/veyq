// A blob's sealed metadata on the read side (`meta.ts`, inbox #397) and the one download-name rule: real crypto, no
// mock. What it pins: a meta sealed by `planBlob` opens back under the plan's K_blob; absent, foreign (another blob's
// key), malformed or non-base64 → null, never a throw; `readBlobMeta` unwraps through the ref's binding, so a ref moved
// to another owner or role reads no meta; the chunk provider's `meta()` opens under the key it already holds; and
// `downloadName`'s precedence and extension rule.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { downloadName, MIME_TO_EXT, openBlobMetaOf, type OpenedBlobMeta } from './meta'
import { makeChunkProvider, planBlob, readBlobMeta } from './transport'
import type { BlobRef } from './types'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const OWNER = { ownerId: '33333333-3333-4333-8333-333333333333', role: 'FILE_CONTENT' }
const META = { type: 'application/pdf', name: 'Bericht 2026.pdf', modifiedAt: 1_700_000_000_000 }

/** A planned blob and the ref a listing would serve for it. */
function planned(meta: typeof META | null = META) {
  const key = v1.randomBytes(32)
  const plan = planBlob(SCOPE, OWNER, key, new Blob([new Uint8Array(10)]), meta ?? undefined)
  const ref: BlobRef = {
    blobId: plan.blobId, chunkCount: plan.chunkCount, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey,
    ownerId: plan.ownerId, role: plan.role, wrappedBlobMeta: plan.wrappedBlobMeta,
  }
  return { key, plan, ref }
}

describe('the meta planBlob seals', () => {
  it('opens back under the plan\'s own K_blob, the uploaded facts intact', () => {
    const { plan, ref } = planned()
    expect(openBlobMetaOf(ref, plan.blobKey)).toEqual({ v: 1, ...META })
  })

  it('is absent from a plan given none — and no randomness is drawn for it', () => {
    const { plan } = planned(null)
    expect('wrappedBlobMeta' in plan).toBe(false)
  })

  it('is padded to its bucket: a short and a long name seal to the same length', () => {
    const short = planned({ ...META, name: 'a' }).plan.wrappedBlobMeta!
    const long = planned({ ...META, name: 'a'.repeat(150) }).plan.wrappedBlobMeta!
    expect(short.length).toBe(long.length)
  })
})

describe('openBlobMetaOf (display: null, never a throw)', () => {
  it('is null for a ref without meta', () => {
    const { plan, ref } = planned()
    expect(openBlobMetaOf({ ...ref, wrappedBlobMeta: undefined }, plan.blobKey)).toBeNull()
    expect(openBlobMetaOf({ ...ref, wrappedBlobMeta: null }, plan.blobKey)).toBeNull()
    expect(openBlobMetaOf({ ...ref, wrappedBlobMeta: '' }, plan.blobKey)).toBeNull()
  })

  it('is null for another blob\'s meta served in this ref\'s place (OpenRefused under this K_blob)', () => {
    const mine = planned()
    const theirs = planned({ ...META, name: 'not mine.pdf' })
    expect(() => v1.openBlobMeta(mine.plan.blobKey, fromB64std(theirs.ref.wrappedBlobMeta!)))
      .toThrow(v1.OpenRefused) // the premise: it is the seal that refuses
    expect(openBlobMetaOf({ ...mine.ref, wrappedBlobMeta: theirs.ref.wrappedBlobMeta }, mine.plan.blobKey)).toBeNull()
  })

  it('is null for an authentic plaintext that is no metadata document (BlobMetaInvalid), and for non-base64', () => {
    const { plan, ref } = planned()
    const notADoc = b64std(v1.seal({ purpose: 'blob-meta' }, plan.blobKey, v1.utf8('[1,2]')))
    expect(() => v1.openBlobMeta(plan.blobKey, fromB64std(notADoc))).toThrow(v1.BlobMetaInvalid)
    expect(openBlobMetaOf({ ...ref, wrappedBlobMeta: notADoc }, plan.blobKey)).toBeNull()
    expect(openBlobMetaOf({ ...ref, wrappedBlobMeta: '%%%not base64' }, plan.blobKey)).toBeNull()
  })
})

describe('readBlobMeta (a listed ref: unwrap through its binding, then open)', () => {
  it('reads the meta of a ref listed for its own owner and role', () => {
    const { key, ref } = planned()
    expect(readBlobMeta(SCOPE, key, ref)).toEqual({ v: 1, ...META })
  })

  it('reads nothing of a ref moved to another owner, another role, another scope or under the wrong key', () => {
    const { key, ref } = planned()
    expect(readBlobMeta(SCOPE, key, { ...ref, ownerId: '44444444-4444-4444-8444-444444444444' })).toBeNull()
    expect(readBlobMeta(SCOPE, key, { ...ref, role: 'ORIGINAL' })).toBeNull()
    expect(readBlobMeta('22222222-2222-4222-8222-222222222222', key, ref)).toBeNull()
    expect(readBlobMeta(SCOPE, v1.randomBytes(32), ref)).toBeNull()
  })

  it('reads nothing of a ref without meta (a blob uploaded before the field)', () => {
    const { key, ref } = planned(null)
    expect(readBlobMeta(SCOPE, key, ref)).toBeNull()
  })
})

describe('the chunk provider\'s meta()', () => {
  it('opens the meta under the K_blob the provider unwrapped, without a request', () => {
    const { key, ref } = planned()
    const presign = async () => { throw new Error('no request for the meta') }
    expect(makeChunkProvider(SCOPE, key, ref, presign).meta()).toEqual({ v: 1, ...META })
    expect(makeChunkProvider(SCOPE, key, { ...ref, wrappedBlobMeta: null }, presign).meta()).toBeNull()
  })

  it('a ref moved to another owner refuses at the unwrap, before any meta is read', () => {
    const { key, ref } = planned()
    expect(() => makeChunkProvider(SCOPE, key, { ...ref, ownerId: '44444444-4444-4444-8444-444444444444' }, async () => []))
      .toThrow(v1.OpenRefused)
  })
})

describe('downloadName', () => {
  const meta = (m: Partial<OpenedBlobMeta>): OpenedBlobMeta => ({ v: 1, type: '', ...m })
  const fallback = () => 'Rom 2026 3'

  it('the object\'s own name wins over the sealed one (a rename is the object\'s), then the sealed name, then the fallback', () => {
    expect(downloadName('Renamed.pdf', meta({ name: 'Original.pdf' }), fallback)).toBe('Renamed.pdf')
    expect(downloadName('', meta({ name: 'Original.pdf' }), fallback)).toBe('Original.pdf')
    expect(downloadName('   ', meta({ name: 'Original.pdf' }), fallback)).toBe('Original.pdf')
    expect(downloadName(null, meta({ name: '  Original.pdf ' }), fallback)).toBe('Original.pdf')
    expect(downloadName(undefined, meta({}), fallback)).toBe('Rom 2026 3')
    expect(downloadName(undefined, null, fallback)).toBe('Rom 2026 3')
  })

  it('calls the fallback only when nothing else names the blob', () => {
    let called = 0
    const counting = () => { called += 1; return 'x' }
    downloadName('Own.txt', null, counting)
    downloadName('', meta({ name: 'Sealed.txt' }), counting)
    expect(called).toBe(0)
    downloadName('', null, counting)
    expect(called).toBe(1)
  })

  it('appends the type\'s extension to a name without one, and never replaces one it has', () => {
    expect(downloadName('Bericht', meta({ type: 'application/pdf' }), fallback)).toBe('Bericht.pdf')
    expect(downloadName('Bericht.PDF', meta({ type: 'application/pdf' }), fallback)).toBe('Bericht.PDF')
    expect(downloadName('Notes.txt', meta({ type: 'image/png' }), fallback)).toBe('Notes.txt')
    expect(downloadName(undefined, meta({ type: 'image/heic' }), fallback)).toBe('Rom 2026 3.heic')
    expect(downloadName('v1.2 notes', meta({ type: 'image/jpeg' }), fallback)).toBe('v1.2 notes.jpg')
    expect(downloadName('.profile', meta({ type: 'image/jpeg' }), fallback)).toBe('.profile.jpg')
  })

  it('appends nothing for an unknown, empty or prototype-key type', () => {
    expect(downloadName('Bericht', meta({ type: 'text/x-unknown' }), fallback)).toBe('Bericht')
    expect(downloadName('Bericht', meta({ type: '' }), fallback)).toBe('Bericht')
    expect(downloadName('Bericht', meta({ type: 'toString' }), fallback)).toBe('Bericht')
    expect(downloadName('Bericht', null, fallback)).toBe('Bericht')
  })

  it('names every type the table holds by its row', () => {
    for (const [type, ext] of Object.entries(MIME_TO_EXT)) expect(downloadName('f', meta({ type }), fallback)).toBe(`f.${ext}`)
  })
})
