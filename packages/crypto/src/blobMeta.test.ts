// A blob's sealed metadata (./blobMeta.ts). The vector `seal.sym['blob-meta']` pins the seal and one padded
// document (v1.conformance.test.ts); this pins what one vector cannot: the writer's normalizations and bounds, the
// bucket arithmetic at its edges, the reader's tolerance, and that the meta seal and a chunk frame under the same
// K_blob never open as each other.
import { describe, expect, it } from 'vitest'
import * as v1 from './v1'
import { b64url } from './base64'

const kBlob = new Uint8Array(32).fill(7)
const text = (b: Uint8Array) => new TextDecoder().decode(b)
/** The JSON document of a padded plaintext, the padding cut off. */
const docOf = (b: Uint8Array) => text(b).trimEnd()
/** Seal an arbitrary plaintext the way another writer could, to exercise the reader. */
const sealRaw = (plaintext: string) => v1.seal({ purpose: 'blob-meta' }, kBlob, new TextEncoder().encode(plaintext))
/** The SHA-256 of 'abc' in base64url, no padding: a digest as `sha256` carries it. */
const DIGEST = 'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0'

describe('blob meta', () => {
  it('round-trips through its seal, one 256-byte bucket for a short document', () => {
    const meta = { type: 'image/jpeg', name: 'Strand.jpg', modifiedAt: 1700000000000 }
    const sealed = v1.sealBlobMeta(kBlob, meta)
    expect(sealed.length).toBe(1 + 12 + 256 + 16)
    expect(v1.openBlobMeta(kBlob, sealed)).toEqual({ v: 1, ...meta })
  })

  it('writes compact JSON in the fixed key order, padded with spaces', () => {
    const b = v1.encodeBlobMeta({ modifiedAt: 0, name: 'a', type: 't' })
    expect(docOf(b)).toBe('{"v":1,"type":"t","name":"a","modifiedAt":0}')
    expect(b.length).toBe(256)
    expect([...b.slice(docOf(b).length)].every((x) => x === 0x20)).toBe(true)
  })

  it('leaves an absent name and modification time out, and keeps an empty type', () => {
    expect(docOf(v1.encodeBlobMeta({ type: '' }))).toBe('{"v":1,"type":""}')
    expect(v1.openBlobMeta(kBlob, v1.sealBlobMeta(kBlob, { type: '' }))).toEqual({ v: 1, type: '' })
  })

  describe('the bucket is the next multiple of 256 bytes', () => {
    // '{"v":1,"type":"","name":""}' is 27 bytes, so a name of n ASCII bytes makes a document of 27 + n.
    it.each([
      [229, 256],
      [230, 512],
    ])('a %d-byte name makes a %d-byte plaintext', (n, size) => {
      const b = v1.encodeBlobMeta({ type: '', name: 'x'.repeat(n) })
      expect(docOf(b).length).toBe(27 + n)
      expect(b.length).toBe(size)
    })
    it('the largest document a writer can produce stays inside maxBytes', () => {
      const b = v1.encodeBlobMeta({
        type: '"'.repeat(v1.BLOB_META.typeBytes),
        name: '\\'.repeat(400),
        modifiedAt: Number.MAX_SAFE_INTEGER,
        sha256: DIGEST,
      })
      expect(docOf(b).length).toBe(876)
      expect(b.length).toBe(v1.BLOB_META.maxBytes)
    })
  })

  describe('the writer normalizes the name', () => {
    const nameOf = (name: string) => v1.decodeBlobMeta(v1.encodeBlobMeta({ type: '', name })).name
    it('to NFC', () => {
      expect(nameOf('Müller.pdf')).toBe('Müller.pdf')
    })
    it('trimmed', () => {
      expect(nameOf('  a.txt \t')).toBe('a.txt')
    })
    it('without control characters', () => {
      expect(nameOf('a\u0000b\u001fc\u007fd\u0085e\n.txt')).toBe('abcde.txt')
    })
    it('and left out when nothing is left', () => {
      expect(nameOf('  \u0001 ')).toBeUndefined()
      expect(nameOf('')).toBeUndefined()
    })
    it('clamped to 255 UTF-8 bytes, exactly', () => {
      expect(nameOf('x'.repeat(255))).toBe('x'.repeat(255))
      expect(nameOf('x'.repeat(256))).toBe('x'.repeat(255))
    })
    it('clamped between code points, never inside one', () => {
      // 'ü' is two bytes: 127 of them are 254, a 128th would make 256.
      expect(nameOf('ü'.repeat(200))).toBe('ü'.repeat(127))
      // '😀' is four bytes: 63 of them are 252, a 64th would make 256.
      expect(nameOf('\u{1f600}'.repeat(100))).toBe('\u{1f600}'.repeat(63))
    })
    it('and trimmed again where the clamp ends on a space', () => {
      expect(nameOf('x'.repeat(254) + ' yz')).toBe('x'.repeat(254))
    })
  })

  describe('the writer bounds the type', () => {
    const typeOf = (type: string) => v1.decodeBlobMeta(v1.encodeBlobMeta({ type })).type
    it('keeps one of exactly 127 bytes', () => {
      expect(typeOf('t'.repeat(127))).toBe('t'.repeat(127))
    })
    it('writes one over 127 bytes as unknown, never truncated', () => {
      expect(typeOf('t'.repeat(128))).toBe('')
    })
    it('writes one with a control character as unknown', () => {
      expect(typeOf('text/plain\n')).toBe('')
      expect(typeOf('text/plain\u0085')).toBe('')
    })
  })

  describe('the writer keeps only an unsigned safe integer as modifiedAt', () => {
    // Read off the written document, not through the reader: the reader drops the same values, and would hide a
    // writer that let them through.
    const doc = (modifiedAt: number) => docOf(v1.encodeBlobMeta({ type: '', modifiedAt }))
    it.each([0, 1, Number.MAX_SAFE_INTEGER])('%d is kept', (n) => {
      expect(doc(n)).toBe(`{"v":1,"type":"","modifiedAt":${n}}`)
    })
    it.each([-1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1, Number.POSITIVE_INFINITY])('%d is left out', (n) => {
      expect(doc(n)).toBe('{"v":1,"type":""}')
    })
  })

  describe('the content digest (cr-cover-banner-upload-only-and-dedup)', () => {
    it('is written last, in the fixed key order, and read back', () => {
      const b = v1.encodeBlobMeta({ sha256: DIGEST, modifiedAt: 1, name: 'a', type: 't' })
      expect(docOf(b)).toBe(`{"v":1,"type":"t","name":"a","modifiedAt":1,"sha256":"${DIGEST}"}`)
      expect(v1.decodeBlobMeta(b).sha256).toBe(DIGEST)
    })
    it('is the digest of the plaintext, in base64url', () => {
      expect(b64url(v1.sha256Digest(new TextEncoder().encode('abc')))).toBe(DIGEST)
    })
    // Read off the written document: the reader drops the same values and would hide a writer that let them through.
    it.each([
      ['42 characters', DIGEST.slice(0, 42)],
      ['44 characters', `${DIGEST}A`],
      ['padding', `${DIGEST}=`],
      ['standard base64', DIGEST.replace('-', '+')],
      ['non-zero trailing bits', `${DIGEST.slice(0, 42)}b`],
      ['hex', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
    ])('a value with %s is left out', (_, sha256) => {
      expect(docOf(v1.encodeBlobMeta({ type: '', sha256 }))).toBe('{"v":1,"type":""}')
    })
    it('a digest that is no string is left out, even one that reads as a digest once stringified', () => {
      const meta = { type: '', sha256: [DIGEST] } as unknown as v1.BlobMeta
      expect(docOf(v1.encodeBlobMeta(meta))).toBe('{"v":1,"type":""}')
      expect(v1.openBlobMeta(kBlob, sealRaw(`{"v":1,"type":"","sha256":["${DIGEST}"]}`))).toEqual({ v: 1, type: '' })
    })
    it('the reader drops a malformed digest and keeps a well-formed one', () => {
      expect(v1.openBlobMeta(kBlob, sealRaw(`{"v":1,"type":"","sha256":"${DIGEST}"}`)).sha256).toBe(DIGEST)
      expect(v1.openBlobMeta(kBlob, sealRaw('{"v":1,"type":"","sha256":"abc"}'))).toEqual({ v: 1, type: '' })
      expect(v1.openBlobMeta(kBlob, sealRaw('{"v":1,"type":"","sha256":7}'))).toEqual({ v: 1, type: '' })
    })
  })

  describe('only this blob\'s meta seal opens', () => {
    it('a meta sealed under another K_blob is refused', () => {
      const other = new Uint8Array(32).fill(8)
      expect(() => v1.openBlobMeta(kBlob, v1.sealBlobMeta(other, { type: '' }))).toThrow(new v1.OpenRefused('blob-meta'))
    })
    // The frame AAD is u32be(index) || u32be(total), 8 bytes; the meta seal's is 0x02 || 'v1' || lp('blob-meta').
    it('a chunk frame under the same K_blob does not open as the meta, nor the meta as a frame', () => {
      const plaintext = v1.encodeBlobMeta({ type: 'text/plain' })
      const nonce = new Uint8Array(12).fill(1)
      const frame = v1.sealFrame(kBlob, 0, 1, plaintext, nonce)
      expect(() => v1.openBlobMeta(kBlob, v1.concat(new Uint8Array([0x02]), frame))).toThrow(
        new v1.OpenRefused('blob-meta'),
      )
      const sealed = v1.sealBlobMeta(kBlob, { type: 'text/plain' })
      expect(() => v1.openFrame(kBlob, 0, 1, sealed.slice(1))).toThrow()
      // positive controls on the same key: each opens as itself
      expect(v1.openFrame(kBlob, 0, 1, frame)).toEqual(plaintext)
      expect(v1.openBlobMeta(kBlob, sealed).type).toBe('text/plain')
    })
  })

  describe('the reader is tolerant of what it does not know', () => {
    it('keeps unknown fields and reads a higher version', () => {
      const opened = v1.openBlobMeta(kBlob, sealRaw('{"v":2,"type":"a/b","name":"n","modifiedAt":5,"hash":"x","n":[1]}'))
      expect(opened).toEqual({ v: 2, type: 'a/b', name: 'n', modifiedAt: 5, hash: 'x', n: [1] })
    })
    it('reads a document without padding, or with other whitespace', () => {
      expect(v1.openBlobMeta(kBlob, sealRaw('{"v":1,"type":""}'))).toEqual({ v: 1, type: '' })
      expect(v1.openBlobMeta(kBlob, sealRaw('{"v":1,"type":""}\n\t '))).toEqual({ v: 1, type: '' })
    })
    it.each([
      ['a type that is no string reads as unknown', '{"v":1,"type":3}', { v: 1, type: '' }],
      ['an absent type reads as unknown', '{"v":1}', { v: 1, type: '' }],
      ['a name that is no string is dropped', '{"v":1,"type":"","name":7}', { v: 1, type: '' }],
      ['an empty name is dropped', '{"v":1,"type":"","name":""}', { v: 1, type: '' }],
      ['a negative modifiedAt is dropped', '{"v":1,"type":"","modifiedAt":-1}', { v: 1, type: '' }],
      ['a fractional modifiedAt is dropped', '{"v":1,"type":"","modifiedAt":1.5}', { v: 1, type: '' }],
      ['a string modifiedAt is dropped', '{"v":1,"type":"","modifiedAt":"5"}', { v: 1, type: '' }],
      ['a modifiedAt of 0 is kept', '{"v":1,"type":"","modifiedAt":0}', { v: 1, type: '', modifiedAt: 0 }],
    ])('%s', (_name, doc, expected) => {
      expect(v1.openBlobMeta(kBlob, sealRaw(doc))).toEqual(expected)
    })
  })

  describe('the reader refuses what is no metadata document', () => {
    it.each([
      ['not JSON', '{"v":1,', /not a JSON document/],
      ['an array', '[1]', /not an object/],
      ['null', 'null', /not an object/],
      ['a number', '1', /not an object/],
      ['a string', '"v"', /not an object/],
      ['no version', '{"type":""}', /no version/],
      ['version 0', '{"v":0,"type":""}', /no version/],
      ['a fractional version', '{"v":1.5,"type":""}', /no version/],
      ['a string version', '{"v":"1","type":""}', /no version/],
    ])('%s', (_name, doc, message) => {
      expect(() => v1.openBlobMeta(kBlob, sealRaw(doc))).toThrow(v1.BlobMetaInvalid)
      expect(() => v1.openBlobMeta(kBlob, sealRaw(doc))).toThrow(message)
    })
    it('a plaintext that is not UTF-8', () => {
      const sealed = v1.seal({ purpose: 'blob-meta' }, kBlob, new Uint8Array([0x22, 0xff, 0x22]))
      expect(() => v1.openBlobMeta(kBlob, sealed)).toThrow(/not a JSON document/)
    })
    it('the refusal is its own class', () => {
      expect(new v1.BlobMetaInvalid('x').name).toBe('BlobMetaInvalid')
      expect(new v1.BlobMetaInvalid('x').message).toBe('blob_meta_invalid: x')
      expect(new v1.BlobMetaInvalid('x')).toBeInstanceOf(Error)
    })
  })
})
