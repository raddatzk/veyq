// @vitest-environment node
// The media headers through the web's own adapters (`imageHeader.ts`, `videoHeader.ts`, `exifTags.ts`) against
// contracts/platform/media/v1-vectors.json (ADR 0155 decision 7.2, the media headers' slice): what the web calls answers every
// case the core's MediaVectorsTest answers, so a rule mutated in Kotlin turns this red too, and reads a picked file only in
// the slices each case bounds — never the whole file. The adapters hold no rule; this holds the mapping (null → undefined
// for the tags, a Blob's slices as the core's reads).
import { describe, it, expect, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { imageSizeFromHeader } from './imageHeader'
import { videoCaptureTimeFromHeader, videoSizeFromHeader } from './videoHeader'
import { CHUNKED_PREFIX, TIFF_PREFIX, readExifTags } from './exifTags'
import { assemble, type Part } from '../../test/mediaBytes'

type Case = { rule: string; bytes: Part[]; bounds?: { end?: number; bytes?: number }; expected: any }
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/platform/media/v1-vectors.json'), 'utf8')) as
  { now: number; images: Case[]; videos: Case[]; exif: Case[] }

/** A picked file that records every slice read from it, and refuses to be read whole. */
class RecordingFile extends File {
  ends: number[] = []
  read = 0
  slice(start = 0, end = this.size, type?: string): Blob {
    this.ends.push(end)
    this.read += Math.min(end, this.size) - start
    return super.slice(start, end, type)
  }
  arrayBuffer(): Promise<ArrayBuffer> {
    throw new Error('the whole file was read')
  }
}
const fileOf = (c: Case) => new RecordingFile([assemble(c.bytes) as BlobPart], 'picked')
function bounded(c: Case, file: RecordingFile) {
  if (c.bounds?.end !== undefined) expect(Math.max(0, ...file.ends), 'the furthest byte read').toBeLessThanOrEqual(c.bounds.end)
  if (c.bounds?.bytes !== undefined) expect(file.read, 'the bytes read').toBeLessThanOrEqual(c.bounds.bytes)
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0')
const wall = (ms: number) => {
  const d = new Date(ms)
  return `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
const DATES = new Set(['DateTimeOriginal', 'DateTimeDigitized', 'DateTime', 'xmpCapturedAt'])
const shown = (tags: object | undefined) =>
  tags === undefined ? null : Object.fromEntries(Object.entries(tags).map(([k, v]) => [k, DATES.has(k) ? wall(v as number) : v]))

describe('an image\'s size from its header', () => {
  vectors.images.forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const file = fileOf(c)
    expect(await imageSizeFromHeader(file)).toEqual(c.expected)
    bounded(c, file)
  }))
})

describe('a video\'s size and capture time from its container', () => {
  vectors.videos.forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const file = fileOf(c)
    expect({ size: await videoSizeFromHeader(file), capturedAt: await videoCaptureTimeFromHeader(fileOf(c), vectors.now) }).toEqual(c.expected)
    bounded(c, file)
  }))

  it('bounds a time ahead of the clock by now', async () => {
    const dated = vectors.videos.find((c) => c.rule === 'a clock an hour ahead is still one')!
    vi.useFakeTimers({ now: vectors.now - 86_400_000 })
    try {
      expect(await videoCaptureTimeFromHeader(fileOf(dated)), 'more than a day ahead of today').toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('a photo\'s tags', () => {
  vectors.exif.forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const answers: Record<string, unknown> = {}
    for (const scope of ['date', 'xmpDate', 'all'] as const) {
      const file = fileOf(c)
      answers[scope] = shown(await readExifTags(file, scope))
      bounded(c, file)
    }
    expect(answers).toEqual(c.expected)
  }))

  it('names the prefixes it reads', () => {
    expect(CHUNKED_PREFIX).toBe(512 * 1024)
    expect(TIFF_PREFIX).toBe(1024 * 1024)
  })

  it('rejects a read the browser refuses, for the caller to decide', async () => {
    const refusing = new File([new Uint8Array(64)], 'picked')
    vi.spyOn(refusing, 'slice').mockImplementation(() => { throw new Error('NotReadableError') })
    await expect(readExifTags(refusing, 'date')).rejects.toThrow('NotReadableError')
    await expect(imageSizeFromHeader(refusing)).rejects.toThrow('NotReadableError')
  })
})
