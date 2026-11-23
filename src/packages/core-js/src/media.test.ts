import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'media.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const m = await import('@veyq/core-js/media')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/media/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/media/v1-vectors.json'), 'utf8'))

/** A case's bytes from its parts: hex, `{fill, count}`, `{repeat, times}`. */
function bytesOf(parts: any[]): Uint8Array {
  const flat = parts.flatMap((p) => (typeof p === 'object' && 'repeat' in p ? new Array(p.times).fill(p.repeat) : [p]))
  const out = new Uint8Array(flat.reduce((n, p) => n + (typeof p === 'string' ? p.length / 2 : p.count), 0))
  let at = 0
  for (const p of flat) {
    if (typeof p === 'string') for (let i = 0; i < p.length; i += 2) out[at++] = parseInt(p.slice(i, i + 2), 16)
    else {
      out.fill(p.fill, at, at + p.count)
      at += p.count
    }
  }
  return out
}

/** A file over [b] that keeps the furthest byte a read asked for and the sum of what the reads asked for; each read a slice
 *  from the middle of a larger buffer, so the façade must read the view, not its whole buffer. */
function recording(b: Uint8Array) {
  const rec = { end: 0, bytes: 0, file: { size: b.length, read: async (from: number, length: number) => {
    rec.end = Math.max(rec.end, from + length)
    rec.bytes += length
    const slice = b.subarray(from, Math.min(b.length, from + length))
    const outer = new Uint8Array(slice.length + 8).fill(0x7a)
    outer.set(slice, 3)
    return outer.subarray(3, 3 + slice.length)
  } } }
  return rec
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0')
const wall = (ms: number) => {
  const d = new Date(ms)
  return `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
const DATES = new Set(['DateTimeOriginal', 'DateTimeDigitized', 'DateTime', 'xmpCapturedAt'])
const shown = (tags: Record<string, unknown> | null) =>
  tags === null ? null : Object.fromEntries(Object.entries(tags).map(([k, x]) => [k, DATES.has(k) ? wall(x as number) : x]))

function bounded(c: any, rec: { end: number; bytes: number }) {
  if (c.bounds?.end !== undefined) expect(rec.end, 'the furthest byte read').toBeLessThanOrEqual(c.bounds.end)
  if (c.bounds?.bytes !== undefined) expect(rec.bytes, 'the bytes read').toBeLessThanOrEqual(c.bounds.bytes)
}

// The media headers' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers every
// case the core's MediaVectorsTest answers on the JVM and natively, within the same bounds on what it reads.
describe('images', () => {
  ;(v.images as any[]).forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const rec = recording(bytesOf(c.bytes))
    expect(await m.imageSize(rec.file)).toEqual(c.expected)
    bounded(c, rec)
  }))
})

describe('videos', () => {
  ;(v.videos as any[]).forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const b = bytesOf(c.bytes)
    const rec = recording(b)
    expect({ size: await m.videoSize(rec.file), capturedAt: await m.videoCaptureTime(recording(b).file, v.now) }).toEqual(c.expected)
    bounded(c, rec)
  }))
})

describe('exif', () => {
  ;(v.exif as any[]).forEach((c, i) => it(`#${i} ${c.rule}`, async () => {
    const b = bytesOf(c.bytes)
    const answers: Record<string, unknown> = {}
    for (const scope of ['date', 'xmpDate', 'all']) {
      const rec = recording(b)
      answers[scope] = shown((await m.exifTags(rec.file, scope)) as Record<string, unknown> | null)
      bounded(c, rec)
    }
    expect(answers).toEqual(c.expected)
  }))

  it('a read the file refuses rejects, as any read of the file would', async () => {
    const refused = { size: 4096, read: async (): Promise<Uint8Array> => { throw new Error('refused') } }
    await expect(m.exifTags(refused, 'all')).rejects.toThrow('refused')
    await expect(m.imageSize(refused)).rejects.toThrow('refused')
    await expect(m.videoSize(refused)).rejects.toThrow('refused')
    await expect(m.videoCaptureTime(refused, 0)).rejects.toThrow('refused')
    // a refusal after the head, inside a container's walk, too
    const jpeg = Uint8Array.from([0xff, 0xd8, 0xff, 0xe1, 0, 16, ...new Array(200).fill(0)])
    const later = { size: 1 << 20, read: async (from: number, length: number) => {
      if (from === 0 && length === 12) return jpeg.subarray(0, 12)
      throw new Error('refused later')
    } }
    await expect(m.exifTags(later, 'all')).rejects.toThrow('refused later')
  })
})

describe('the reads\' bounds', () => {
  it('names the prefixes the tags are read from', () => {
    expect(m.exifChunkedPrefix()).toBe(512 * 1024)
    expect(m.exifTiffPrefix()).toBe(1024 * 1024)
  })
})
