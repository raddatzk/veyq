import { describe, it, expect } from 'vitest'
import { autoFadeMs, fadeLengthAt, fadeOutAtMs, pressFadeMs, FADE_MS } from './fades'

describe('the crossfade (ADR 0135 decision 7)', () => {
  const song = { startMs: 400, endMs: 180_400 }

  it('a track fades out over 5 s, ending at its trimmed end', () => {
    expect([autoFadeMs(song), fadeOutAtMs(song)]).toEqual([FADE_MS, 175_400])
  })

  it('a hand-pressed next or previous fades over 1 s', () => {
    expect(pressFadeMs(song)).toBe(1000)
  })

  it('a track shorter than two fades fades over a third of its span, and a press no longer than that', () => {
    const short = { startMs: 1000, endMs: 7000 }
    expect([autoFadeMs(short), fadeOutAtMs(short)]).toEqual([2000, 5000])
    const tiny = { startMs: 0, endMs: 1500 }
    expect([autoFadeMs(tiny), pressFadeMs(tiny)]).toEqual([500, 500])
  })

  it('a seek into the fade window shortens the fade to what is left; before it, the fade is whole', () => {
    expect(fadeLengthAt(song, 178_400)).toBe(2000)
    expect(fadeLengthAt(song, 175_400)).toBe(FADE_MS)
    expect(fadeLengthAt(song, 100_000)).toBe(FADE_MS)
    expect(fadeLengthAt(song, 190_000)).toBe(0)
  })
})
