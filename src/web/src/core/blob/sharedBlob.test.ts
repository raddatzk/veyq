// ADR 0013 § 5 - a blob that shares a stored object instead of being uploaded again: the sealed size a match compares
// first, the digest it compares next (read frame by frame, never whole, and only when a size matches), and the new row's
// `K_blob` wrapped again bound to its own owner and role.
import { describe, it, expect, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { CHUNK_SIZE, planBlob, readBlobMeta, unwrapBlobKey } from './transport'
import { contentDigest, findEqual, rememberDigest, sealedByteSize, shareBlob } from './sharedBlob'

const SCOPE = '11111111-1111-4111-8111-111111111111'
const NODE = '22222222-2222-4222-8222-222222222222'

describe('sealedByteSize', () => {
  it('is the byteSize a plan of the same plaintext declares - an empty source one frame', () => {
    for (const n of [0, 1, CHUNK_SIZE - 1, CHUNK_SIZE, CHUNK_SIZE + 1, 3 * CHUNK_SIZE + 7]) {
      const plan = planBlob(SCOPE, { ownerId: NODE, role: 'ORIGINAL' }, v1.randomBytes(32), { size: n, type: '', slice: () => new Blob() })
      expect(sealedByteSize(n), `${n}`).toBe(plan.byteSize)
    }
  })
})

describe('contentDigest', () => {
  it('is the SHA-256 of the whole plaintext in base64url, read one frame at a time', async () => {
    const bytes = new Uint8Array(2 * CHUNK_SIZE + 3).map((_, i) => i % 251)
    const blob = new Blob([bytes])
    const slices: number[] = []
    const source = { size: blob.size, type: '', slice: (a?: number, b?: number) => (slices.push((b ?? 0) - (a ?? 0)), blob.slice(a, b)) }
    expect(await contentDigest(source)).toBe(b64url(v1.sha256Digest(bytes)))
    expect(Math.max(...slices), 'never more than a frame').toBeLessThanOrEqual(CHUNK_SIZE)
    expect(await contentDigest(new Blob([]))).toBe(b64url(v1.sha256Digest(new Uint8Array())))
  })

  it('stops on a cancel', async () => {
    const ctl = new AbortController()
    ctl.abort()
    await expect(contentDigest(new Blob([new Uint8Array(10)]), ctl.signal)).rejects.toThrow('Upload cancelled')
  })
})

describe('rememberDigest: the digest core computed over a Blob it handed out (inbox #555)', () => {
  it('answers that very object without a read; a copy over the same bytes is read', async () => {
    const bytes = new Uint8Array([4, 5, 6])
    const blob = new Blob([bytes])
    const sha = v1.sha256Digest(bytes)
    rememberDigest(blob, sha)
    const slice = vi.spyOn(blob, 'slice')
    expect(await contentDigest(blob)).toBe(b64url(sha))
    expect(slice, 'remembered: no read').not.toHaveBeenCalled()
    // control: another object over the same bytes misses the memo and is hashed
    const copy = new Blob([blob])
    const copySlice = vi.spyOn(copy, 'slice')
    expect(await contentDigest(copy)).toBe(b64url(sha))
    expect(copySlice).toHaveBeenCalled()
  })

  it('an already-aborted signal rejects with an AbortError, remembered or not', async () => {
    const blob = new Blob([new Uint8Array([1])])
    rememberDigest(blob, new Uint8Array(32).fill(3))
    const ctl = new AbortController()
    ctl.abort()
    await expect(contentDigest(blob, ctl.signal)).rejects.toMatchObject({ name: 'AbortError' })
    // control: unaborted, the same call resolves the remembered value
    expect(await contentDigest(blob, new AbortController().signal)).toBe(b64url(new Uint8Array(32).fill(3)))
  })

  it('takes a SHA-256 only: 31 or 33 bytes throw', () => {
    const blob = new Blob([new Uint8Array([1])])
    expect(() => rememberDigest(blob, new Uint8Array(31))).toThrow('digest_not_sha256')
    expect(() => rememberDigest(blob, new Uint8Array(33))).toThrow('digest_not_sha256')
    expect(() => rememberDigest(blob, new Uint8Array(32))).not.toThrow()
  })
})

describe('findEqual', () => {
  const file = new Blob([new Uint8Array([1, 2, 3])])
  const size = sealedByteSize(3)

  it('compares the size first and reads the file only when one matches; then the digest decides', async () => {
    const digest = b64url(v1.sha256Digest(new Uint8Array([1, 2, 3])))
    const other = { byteSize: size + 1, sha256: vi.fn(() => digest) }
    const wrong = { byteSize: size, sha256: vi.fn(() => 'A'.repeat(43)) }
    const right = { byteSize: size, sha256: vi.fn(() => digest) }
    expect(await findEqual(file, [other, wrong, right])).toBe(right)
    expect(other.sha256, 'another size is never opened').not.toHaveBeenCalled()

    const slice = vi.spyOn(file, 'slice')
    expect(await findEqual(file, [other])).toBeNull()
    expect(slice, 'no size match: the file is not read').not.toHaveBeenCalled()
  })

  it('a candidate without a digest never matches, and the file is then not read', async () => {
    const slice = vi.spyOn(file, 'slice')
    expect(await findEqual(file, [{ byteSize: size, sha256: () => null }])).toBeNull()
    expect(slice).not.toHaveBeenCalled()
  })
})

describe('shareBlob', () => {
  it('wraps the source\'s K_blob again, bound to the new row\'s owner and role, with blob-meta of its own', () => {
    const key = v1.randomBytes(32)
    const source = planBlob(SCOPE, { ownerId: '33333333-3333-4333-8333-333333333333', role: 'PREVIEW' }, key, new Blob([new Uint8Array(40)]))
    const nodeKey = v1.randomBytes(32)
    const shared = shareBlob(SCOPE, { key, ref: source }, nodeKey, { ownerId: NODE, role: 'COVER' }, { type: 'image/jpeg' })
    expect(shared.request).toMatchObject({ sourceBlobId: source.blobId, blobId: shared.ref.blobId })
    expect(shared.ref.blobId).not.toBe(source.blobId)
    expect([shared.ref.chunkCount, shared.ref.byteSize]).toEqual([source.chunkCount, source.byteSize])
    expect(unwrapBlobKey(SCOPE, nodeKey, shared.ref)).toEqual(source.blobKey)
    expect(() => unwrapBlobKey(SCOPE, nodeKey, { ...shared.ref, role: 'BANNER' }), 'bound to its role').toThrow()
    expect(() => unwrapBlobKey(SCOPE, key, shared.ref), 'wrapped under the new owner\'s key').toThrow()
    expect(readBlobMeta(SCOPE, nodeKey, shared.ref)).toEqual({ v: 1, type: 'image/jpeg' })
    expect(shareBlob(SCOPE, { key, ref: source }, nodeKey, { ownerId: NODE, role: 'COVER' }).request.wrappedBlobMeta).toBeNull()
  })

  it('refuses a source whose wrap does not open where it is said to live', () => {
    const key = v1.randomBytes(32)
    const source = planBlob(SCOPE, { ownerId: '33333333-3333-4333-8333-333333333333', role: 'PREVIEW' }, key, new Blob([new Uint8Array(4)]))
    expect(() => shareBlob(SCOPE, { key, ref: { ...source, ownerId: '44444444-4444-4444-8444-444444444444' } }, key, { ownerId: NODE, role: 'COVER' })).toThrow()
  })
})
