// @vitest-environment happy-dom
// The curator note's plaintext and the device's memory of its acceptance (uc.sharing.share-note, ADR 0036 point 2).
// Its round trip through a real seal and the guest's resolve is `manageShares.test.ts` and `resolvePublicShare.test.ts`;
// the gate on the page is `views/ShareView.memory.test.ts`.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { NO_NOTE, decodeShareNote, encodeShareNote, isNoteAccepted, noteDigest, recordNoteAccepted } from './shareNote'

beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('the plaintext', () => {
  it('round-trips both shapes', () => {
    const plain = { texts: { de: 'Hallo', en: 'Hello' }, mustAccept: false }
    const asked = { texts: { de: 'Hallo', en: 'Hello' }, mustAccept: true }
    expect(decodeShareNote(encodeShareNote(plain))).toEqual(plain)
    expect(decodeShareNote(encodeShareNote(asked))).toEqual(asked)
  })

  it("writes today's flat map with the switch off — a link without it is what it always was", () => {
    expect(encodeShareNote({ texts: { en: 'Hello' }, mustAccept: false })).toBe(JSON.stringify({ en: 'Hello' }))
    expect(encodeShareNote({ texts: { en: 'Hello' }, mustAccept: true })).toBe(JSON.stringify({ texts: { en: 'Hello' }, mustAccept: true }))
  })

  it("reads an older client's flat map as a note that never asks", () => {
    expect(decodeShareNote(JSON.stringify({ de: 'Hallo' }))).toEqual({ texts: { de: 'Hallo' }, mustAccept: false })
    expect(decodeShareNote('{}')).toEqual(NO_NOTE)
  })

  it('reads anything else as no note at all (null), never as a gate', () => {
    for (const plaintext of [
      JSON.stringify({ texts: { en: 'x' }, mustAccept: 'true' }),
      JSON.stringify({ texts: { en: 'x' }, mustAccept: null }),
      JSON.stringify({ texts: { en: 'x' } }),
      JSON.stringify({ texts: null, mustAccept: true }),
      JSON.stringify({ texts: { en: { md: 'x' } }, mustAccept: true }),
      JSON.stringify({ texts: { en: 'x' }, mustAccept: true, from: 'someone' }),
      JSON.stringify({ en: 'x', de: 2 }),
      JSON.stringify([{ en: 'x' }]),
      JSON.stringify(null),
      '{',
    ]) {
      expect(decodeShareNote(plaintext), plaintext).toBeNull()
    }
  })
})

describe("the device's memory of an acceptance", () => {
  const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_n'
  const digestOf = (text: string) => noteDigest(v1.utf8(encodeShareNote({ texts: { en: text }, mustAccept: true })))

  it('remembers exactly this share and this text: an edited note, or another share, asks again', () => {
    const v1Digest = digestOf('Read me')
    expect(isNoteAccepted(SHARE, v1Digest)).toBe(false)
    recordNoteAccepted(SHARE, v1Digest)
    expect(isNoteAccepted(SHARE, v1Digest)).toBe(true)
    expect(isNoteAccepted(SHARE, digestOf('Read me, edited')), 'an edited note').toBe(false)
    expect(isNoteAccepted(`${SHARE.slice(0, -1)}x`, v1Digest), 'another share').toBe(false)
  })

  it('stores the share id and the digest only — no note text, no account', () => {
    recordNoteAccepted(SHARE, digestOf('A secret greeting'))
    const stored = Object.keys(localStorage).map((k) => [k, localStorage.getItem(k)])
    expect(stored).toEqual([[`veyq:share-note-accepted:${SHARE}:${digestOf('A secret greeting')}`, '1']])
    expect(JSON.stringify(stored)).not.toContain('secret')
  })

  it('a device whose storage throws asks every time and never throws itself', () => {
    const digest = digestOf('Read me')
    vi.stubGlobal('localStorage', {
      getItem: () => { throw new Error('SecurityError') },
      setItem: () => { throw new Error('QuotaExceededError') },
    })
    expect(() => recordNoteAccepted(SHARE, digest)).not.toThrow()
    expect(isNoteAccepted(SHARE, digest)).toBe(false)
  })
})
