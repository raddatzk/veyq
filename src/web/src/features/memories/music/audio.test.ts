import { describe, it, expect, vi } from 'vitest'
import { checkAudioFile, formatTrackLength, isAudioMeta, musicTitle, MUSIC_MAX_BYTES } from './audio'
import { AudioUndecodable } from './audioFacts'

const ascii = (s: string) => Uint8Array.from(s, (c) => c.charCodeAt(0))
const FACTS = { durationMs: 3000, trimStartMs: 0, trimEndMs: 3000 }

/** The heads the Music tab is handed: what core's `sniffStream` types each as. */
function m4a(): Uint8Array {
  const b = new Uint8Array(64)
  b.set(ascii('ftyp'), 4)
  b.set(ascii('M4A '), 8)
  return b
}
const id3Mp3 = () => Uint8Array.from([...ascii('ID3'), 4, 0, 0, 0, 0, 0, 0, ...new Array(54).fill(0)])
/** An MPEG-1 Layer III frame header (FF FB 90 64): no ID3 tag before it. */
const frameMp3 = () => Uint8Array.from([0xff, 0xfb, 0x90, 0x64, ...new Array(60).fill(0)])
function wav(): Uint8Array {
  const b = new Uint8Array(64)
  b.set(ascii('RIFF'), 0)
  b.set(ascii('WAVE'), 8)
  return b
}
function mp4Video(): Uint8Array {
  const b = new Uint8Array(64)
  b.set(ascii('ftyp'), 4)
  b.set(ascii('isom'), 8)
  return b
}
const jpeg = () => Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, ...new Array(60).fill(0)])
const file = (head: Uint8Array, name: string, type = '') => new File([head as BlobPart], name, { type })

describe('checkAudioFile: a picked file is typed by its bytes and measured before anything is uploaded (E1)', () => {
  it('types an M4A, an MP3 (with an ID3 tag or a bare frame) and a WAV from their heads, never from the browser\'s guess', async () => {
    const measure = vi.fn(async () => FACTS)
    const types = []
    for (const f of [file(m4a(), 'a.m4a', 'video/mp4'), file(id3Mp3(), 'b.mp3'), file(frameMp3(), 'c.mp3', 'audio/mp3'), file(wav(), 'd.wav', 'audio/x-wav')]) {
      const verdict = await checkAudioFile(f, measure)
      types.push('mediaType' in verdict ? verdict.mediaType : verdict.problem)
    }
    expect(types).toEqual(['audio/mp4', 'audio/mpeg', 'audio/mpeg', 'audio/wav'])
    expect(measure).toHaveBeenCalledTimes(4)
  })

  it('hands the facts the decode measured over with the file', async () => {
    const f = file(wav(), 'd.wav')
    await expect(checkAudioFile(f, async () => FACTS)).resolves.toEqual({ file: f, mediaType: 'audio/wav', facts: FACTS })
  })

  it('refuses a file over 200 MiB without reading it', async () => {
    const f = file(wav(), 'big.wav')
    Object.defineProperty(f, 'size', { value: MUSIC_MAX_BYTES + 1 })
    const measure = vi.fn(async () => FACTS)
    const slice = vi.spyOn(f, 'slice')
    await expect(checkAudioFile(f, measure)).resolves.toEqual({ problem: 'tooLarge' })
    expect([slice.mock.calls.length, measure.mock.calls.length]).toEqual([0, 0])
    const atCap = file(wav(), 'cap.wav')
    Object.defineProperty(atCap, 'size', { value: MUSIC_MAX_BYTES })
    await expect(checkAudioFile(atCap, measure)).resolves.toMatchObject({ mediaType: 'audio/wav' })
  })

  it('refuses what is no audio by its bytes - a photo, a video, an audio name on other bytes - without decoding it', async () => {
    const measure = vi.fn(async () => FACTS)
    for (const f of [file(jpeg(), 'p.jpg'), file(mp4Video(), 'v.mp4'), file(jpeg(), 'fake.mp3', 'audio/mpeg'), file(new Uint8Array(0), 'empty.mp3')]) {
      await expect(checkAudioFile(f, measure)).resolves.toEqual({ problem: 'notAudio' })
    }
    expect(measure).not.toHaveBeenCalled()
  })

  it('refuses a file this browser cannot decode', async () => {
    await expect(checkAudioFile(file(id3Mp3(), 'b.mp3'), async () => { throw new AudioUndecodable() })).resolves.toEqual({ problem: 'undecodable' })
  })
})

describe('what is a music track, and how it is named', () => {
  it('isAudioMeta: an audio type, and nothing else', () => {
    expect(['audio/mpeg', 'Audio/MP4 ', 'audio/wav'].map((type) => isAudioMeta({ type }))).toEqual([true, true, true])
    expect(['video/mp4', 'image/jpeg', 'application/gpx+xml', ''].map((type) => isAudioMeta({ type }))).toEqual([false, false, false, false])
    expect([isAudioMeta(null), isAudioMeta(undefined), isAudioMeta({ type: 3 })]).toEqual([false, false, false])
  })

  it('musicTitle: the file name without its extension', () => {
    expect(musicTitle('Summer Song.mp3')).toBe('Summer Song')
    expect(musicTitle('01. Intro.final.m4a')).toBe('01. Intro.final')
    expect(musicTitle('.hidden')).toBe('.hidden')
    expect(musicTitle('noext')).toBe('noext')
    expect([musicTitle('  '), musicTitle(undefined), musicTitle('.mp3 ')]).toEqual([null, null, '.mp3'])
  })

  it('formatTrackLength: m:ss, and h:mm:ss from an hour on', () => {
    expect([0, 999, 61_000, 599_499, 3_600_000, 3_725_000].map(formatTrackLength)).toEqual(['0:00', '0:01', '1:01', '9:59', '1:00:00', '1:02:05'])
  })
})
