import { describe, it, expect } from 'vitest'
import {
  FRAME_OVERHEAD,
  PLAINTEXT_CHUNK_SIZE,
  plaintextTotal,
  parseRange,
  coveringChunks,
  clampChunkToRange,
  contentRange,
  unsatisfiedRange,
} from './range'
import { CHUNK_SIZE } from '../features/memory/media'

describe('range.ts — pure range↔chunk arithmetic (Slice 2)', () => {
  it('PLAINTEXT_CHUNK_SIZE is pinned equal to media.ts CHUNK_SIZE (the SW must not import the data layer)', () => {
    // The SW bundle cannot import media.ts (it would drag in exifr + the API client); this test is the anti-drift
    // guard that keeps the two constants in lockstep instead.
    expect(PLAINTEXT_CHUNK_SIZE).toBe(CHUNK_SIZE)
    expect(FRAME_OVERHEAD).toBe(28) // nonce(12) + AES-GCM tag(16)
  })

  it('plaintextTotal subtracts one frame overhead per chunk', () => {
    // 3 full chunks: ciphertext = 3*(CHUNK+28); plaintext = 3*CHUNK
    expect(plaintextTotal(3 * (PLAINTEXT_CHUNK_SIZE + 28), 3)).toBe(3 * PLAINTEXT_CHUNK_SIZE)
    // 1 short chunk of 10 plaintext bytes: ciphertext = 10+28
    expect(plaintextTotal(38, 1)).toBe(10)
  })

  describe('parseRange', () => {
    const total = 1000
    it('no header → full (200)', () => {
      expect(parseRange(null, total)).toEqual({ kind: 'full' })
      expect(parseRange(undefined, total)).toEqual({ kind: 'full' })
      expect(parseRange('', total)).toEqual({ kind: 'full' })
    })
    it('first probe bytes=0-1', () => {
      expect(parseRange('bytes=0-1', total)).toEqual({ kind: 'range', range: { start: 0, end: 1 } })
    })
    it('closed range bytes=A-B', () => {
      expect(parseRange('bytes=200-399', total)).toEqual({ kind: 'range', range: { start: 200, end: 399 } })
    })
    it('open range bytes=N- runs to the end', () => {
      expect(parseRange('bytes=500-', total)).toEqual({ kind: 'range', range: { start: 500, end: 999 } })
    })
    it('suffix range bytes=-N is the last N bytes', () => {
      expect(parseRange('bytes=-100', total)).toEqual({ kind: 'range', range: { start: 900, end: 999 } })
    })
    it('a suffix larger than the resource clamps to the whole resource', () => {
      expect(parseRange('bytes=-5000', total)).toEqual({ kind: 'range', range: { start: 0, end: 999 } })
    })
    it('an over-long end clamps to total-1 (RFC 7233)', () => {
      expect(parseRange('bytes=900-99999', total)).toEqual({ kind: 'range', range: { start: 900, end: 999 } })
    })
    it('start at/after total → unsatisfiable (416)', () => {
      expect(parseRange('bytes=1000-1001', total)).toEqual({ kind: 'unsatisfiable' })
      expect(parseRange('bytes=1500-', total)).toEqual({ kind: 'unsatisfiable' })
    })
    it('start > end, a zero suffix, garbage, or multipart → unsatisfiable', () => {
      expect(parseRange('bytes=500-400', total)).toEqual({ kind: 'unsatisfiable' })
      expect(parseRange('bytes=-0', total)).toEqual({ kind: 'unsatisfiable' })
      expect(parseRange('bytes=-', total)).toEqual({ kind: 'unsatisfiable' })
      expect(parseRange('rows=0-1', total)).toEqual({ kind: 'unsatisfiable' })
      expect(parseRange('bytes=0-1,2-3', total)).toEqual({ kind: 'unsatisfiable' })
    })
    it('any range on a zero-length resource is unsatisfiable', () => {
      expect(parseRange('bytes=0-0', 0)).toEqual({ kind: 'unsatisfiable' })
    })
  })

  describe('coveringChunks', () => {
    const cs = 100
    it('a range within one chunk covers exactly that chunk', () => {
      expect(coveringChunks({ start: 10, end: 90 }, cs)).toEqual({ first: 0, last: 0 })
    })
    it('a range spanning chunk boundaries covers every overlapped chunk', () => {
      expect(coveringChunks({ start: 90, end: 210 }, cs)).toEqual({ first: 0, last: 2 })
    })
    it('a range exactly on a boundary', () => {
      expect(coveringChunks({ start: 100, end: 199 }, cs)).toEqual({ first: 1, last: 1 })
    })
  })

  describe('clampChunkToRange', () => {
    const cs = 100
    it('a fully-covered middle chunk yields its whole span', () => {
      expect(clampChunkToRange(1, 100, { start: 50, end: 250 }, cs)).toEqual({ from: 0, to: 100 })
    })
    it('the first chunk is clipped at the range start', () => {
      expect(clampChunkToRange(0, 100, { start: 30, end: 250 }, cs)).toEqual({ from: 30, to: 100 })
    })
    it('the last chunk is clipped at the range end (inclusive)', () => {
      expect(clampChunkToRange(2, 100, { start: 30, end: 250 }, cs)).toEqual({ from: 0, to: 51 })
    })
    it('a short final chunk clips to its actual length', () => {
      expect(clampChunkToRange(2, 40, { start: 0, end: 999 }, cs)).toEqual({ from: 0, to: 40 })
    })
    it('a chunk wholly outside the range yields an empty slice (from === to)', () => {
      expect(clampChunkToRange(5, 100, { start: 0, end: 50 }, cs)).toEqual({ from: 0, to: 0 })
    })
  })

  it('header formatters', () => {
    expect(contentRange({ start: 200, end: 399 }, 1000)).toBe('bytes 200-399/1000')
    expect(unsatisfiedRange(1000)).toBe('bytes */1000')
  })
})
