// The pictures of the capture, drawn on a canvas in the page — never a photo of anyone (ADR 0087 §7: made up and
// generated). A "photo" is a flat landscape in one of a few palettes, as a JPEG with an Exif block that carries its
// capture time and camera, so the gallery orders it and the Memory dates itself as it would a camera's.
import type { Page } from '@playwright/test'

export interface Scene {
  sky: [string, string]
  hills: string[]
  sun?: string
  water?: string
  towers?: string
}

export const SCENES: Scene[] = [
  { sky: ['#f6c28b', '#f9e7c9'], hills: ['#c97b4a', '#9c5a3c', '#6e3f2f'], sun: '#fff3d6' },
  { sky: ['#7fb7e6', '#d7ecfb'], hills: ['#8fbf7a', '#5f9a5a', '#3d6e45'], sun: '#fffbe8' },
  { sky: ['#9fc6e0', '#e9f3f8'], hills: ['#b9a58c'], water: '#5d93b8', towers: '#c7a27a' },
  { sky: ['#e8a0a8', '#fbe3d7'], hills: ['#7d6b9a', '#5a4b78'], water: '#8e7fae', sun: '#fff0e6' },
  { sky: ['#6b8fb8', '#c9d8ea'], hills: ['#d9d4c7', '#a9b4a2', '#6f8170'] },
  { sky: ['#f2d06b', '#fbf0c4'], hills: ['#d79b4f', '#b6773a'], towers: '#a3593c', sun: '#ffffff' },
  { sky: ['#87c6c2', '#e1f4f1'], hills: ['#e8d9b0'], water: '#3f8f9a' },
  { sky: ['#c3b4e3', '#ece6f8'], hills: ['#9ab7a0', '#6d927a', '#4c6b58'], sun: '#fff8ea' },
  { sky: ['#ffd1a8', '#fff1e0'], hills: ['#e0b97a'], water: '#6fa6c9', towers: '#d98c5f', sun: '#fffaf0' },
]

/** One scene as JPEG bytes ([width]×[height]), drawn in [page]. */
export async function drawJpeg(page: Page, scene: Scene, width = 1600, height = 1067): Promise<Buffer> {
  const dataUrl = await page.evaluate(({ scene, width, height }) => {
    const c = document.createElement('canvas')
    c.width = width
    c.height = height
    const g = c.getContext('2d')!
    const sky = g.createLinearGradient(0, 0, 0, height * 0.7)
    sky.addColorStop(0, scene.sky[0])
    sky.addColorStop(1, scene.sky[1])
    g.fillStyle = sky
    g.fillRect(0, 0, width, height)
    if (scene.sun) {
      g.fillStyle = scene.sun
      g.beginPath()
      g.arc(width * 0.72, height * 0.28, height * 0.09, 0, Math.PI * 2)
      g.fill()
    }
    if (scene.towers) {
      g.fillStyle = scene.towers
      for (let i = 0; i < 7; i++) {
        const w = width * (0.05 + (i % 3) * 0.015)
        const h = height * (0.18 + ((i * 37) % 11) * 0.02)
        const x = width * (0.08 + i * 0.12)
        g.fillRect(x, height * 0.62 - h, w, h)
        if (i % 2 === 0) {
          g.beginPath()
          g.arc(x + w / 2, height * 0.62 - h, w / 2, Math.PI, 0)
          g.fill()
        }
      }
    }
    scene.hills.forEach((color, i) => {
      g.fillStyle = color
      g.beginPath()
      const base = height * (0.55 + i * 0.12)
      g.moveTo(0, height)
      g.lineTo(0, base)
      for (let x = 0; x <= width; x += width / 8) {
        g.quadraticCurveTo(x + width / 16, base - height * (0.06 + ((i + x / width) % 0.5) * 0.12), x + width / 8, base)
      }
      g.lineTo(width, height)
      g.closePath()
      g.fill()
    })
    if (scene.water) {
      g.fillStyle = scene.water
      g.fillRect(0, height * 0.78, width, height * 0.22)
      g.fillStyle = 'rgba(255,255,255,0.25)'
      for (let i = 0; i < 12; i++) g.fillRect(width * ((i * 0.083) % 1), height * (0.82 + (i % 4) * 0.04), width * 0.06, 3)
    }
    return c.toDataURL('image/jpeg', 0.86)
  }, { scene, width, height })
  return Buffer.from(dataUrl.split(',')[1]!, 'base64')
}

/** A square picture for a profile or a group icon: a circle of colour on a soft ground, with initials. */
export async function drawBadge(page: Page, initials: string, colors: [string, string]): Promise<Buffer> {
  const dataUrl = await page.evaluate(({ initials, colors }) => {
    const c = document.createElement('canvas')
    c.width = 800
    c.height = 800
    const g = c.getContext('2d')!
    const bg = g.createLinearGradient(0, 0, 800, 800)
    bg.addColorStop(0, colors[0])
    bg.addColorStop(1, colors[1])
    g.fillStyle = bg
    g.fillRect(0, 0, 800, 800)
    g.fillStyle = 'rgba(255,255,255,0.85)'
    g.font = '600 300px "Avenir Next", Inter, sans-serif'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.fillText(initials, 400, 420)
    return c.toDataURL('image/png')
  }, { initials, colors })
  return Buffer.from(dataUrl.split(',')[1]!, 'base64')
}

// --------------------------------------------------------------------------------------------------------------- Exif

const ASCII = 2
const LONG = 4
const RATIONAL = 5

type Field = [number, number, Buffer | number | [number, number][]]

function ifd(fields: Field[], offset: number): Buffer {
  const sorted = [...fields].sort((a, b) => a[0] - b[0])
  const entriesLength = 2 + sorted.length * 12 + 4
  const entries: Buffer[] = []
  const values: Buffer[] = []
  let valuesLength = 0
  const count = Buffer.alloc(2)
  count.writeUInt16BE(sorted.length)
  for (const [tag, type, value] of sorted) {
    const entry = Buffer.alloc(12)
    entry.writeUInt16BE(tag, 0)
    entry.writeUInt16BE(type, 2)
    if (type === LONG) {
      entry.writeUInt32BE(1, 4)
      entry.writeUInt32BE(value as number, 8)
    } else {
      const pairs = type === RATIONAL ? (value as [number, number][]) : []
      const raw = type === RATIONAL
        ? Buffer.concat(pairs.map(([n, d]) => { const b = Buffer.alloc(8); b.writeUInt32BE(n, 0); b.writeUInt32BE(d, 4); return b }))
        : Buffer.concat([value as Buffer, Buffer.from([0])])
      entry.writeUInt32BE(type === RATIONAL ? pairs.length : raw.length, 4)
      if (raw.length <= 4) raw.copy(entry, 8)
      else {
        entry.writeUInt32BE(offset + entriesLength + valuesLength, 8)
        const padded = raw.length % 2 ? Buffer.concat([raw, Buffer.from([0])]) : raw
        values.push(padded)
        valuesLength += padded.length
      }
    }
    entries.push(entry)
  }
  return Buffer.concat([count, ...entries, Buffer.alloc(4), ...values])
}

/** Degrees as the three rationals Exif writes (degrees, minutes, hundredths of a second). */
const dms = (deg: number): [number, number][] => {
  const d = Math.floor(deg)
  const m = Math.floor((deg - d) * 60)
  return [[d, 1], [m, 1], [Math.round(((deg - d) * 60 - m) * 60 * 100), 100]]
}

/** [jpeg] with an Exif APP1 segment after its SOI: camera make and model, DateTimeOriginal ([taken], local time) and,
 *  given [at], where it was taken (north and east only — the made-up places are). */
export function withExif(jpeg: Buffer, taken: string, at?: { lat: number; lng: number }, make = 'Example', model = 'Camera One'): Buffer {
  const stamp = Buffer.from(taken.replace(/-/g, ':').replace('T', ' ').slice(0, 19), 'ascii')
  const ifd0Fields: Field[] = [
    [0x010f, ASCII, Buffer.from(make, 'ascii')],
    [0x0110, ASCII, Buffer.from(model, 'ascii')],
    [0x8769, LONG, 0],
  ]
  if (at) ifd0Fields.push([0x8825, LONG, 0])
  const ifd0Length = ifd(ifd0Fields, 8).length
  const exifOffset = 8 + ifd0Length
  const exif = ifd([[0x9003, ASCII, stamp], [0x9004, ASCII, stamp]], exifOffset)
  const gpsOffset = exifOffset + exif.length
  const gps = at
    ? ifd([[0x0001, ASCII, Buffer.from('N')], [0x0002, RATIONAL, dms(at.lat)], [0x0003, ASCII, Buffer.from('E')], [0x0004, RATIONAL, dms(at.lng)]], gpsOffset)
    : Buffer.alloc(0)
  const ifd0 = ifd(ifd0Fields.map(([t, ty, v]): Field => [t, ty, t === 0x8769 ? exifOffset : t === 0x8825 ? gpsOffset : v]), 8)
  const tiff = Buffer.concat([Buffer.from('MM\x00\x2a\x00\x00\x00\x08', 'binary'), ifd0, exif, gps])
  const body = Buffer.concat([Buffer.from('Exif\x00\x00', 'binary'), tiff])
  const header = Buffer.alloc(4)
  header.writeUInt16BE(0xffe1, 0)
  header.writeUInt16BE(body.length + 2, 2)
  if (jpeg[0] !== 0xff || jpeg[1] !== 0xd8) throw new Error('not a JPEG')
  return Buffer.concat([jpeg.subarray(0, 2), header, body, jpeg.subarray(2)])
}

/** A photo to upload: a drawn scene, taken at [taken] (and at [at]). */
export async function photo(page: Page, index: number, taken: string, at?: { lat: number; lng: number }): Promise<Buffer> {
  return withExif(await drawJpeg(page, SCENES[index % SCENES.length]!), taken, at)
}
