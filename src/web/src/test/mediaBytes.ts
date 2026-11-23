// Builders for the media headers' vector cases (contracts/platform/media/v1-vectors.json): a case's bytes are a list of
// parts - hex, or a byte repeated `count` times (a large padding stays small in the file) - and these build the containers
// the cases are made of: a TIFF block of typed IFD entries, JPEG segments, ISO BMFF boxes, EBML elements, RIFF chunks.
// Shared by the vector writer of phase 1 and the web's vector test, which turns the parts back into bytes.

/** A part of a case's bytes: hex, `count` bytes of `fill`, or the hex `repeat` written `times` times. */
export type Part = string | { fill: number; count: number } | { repeat: string; times: number }

export const hex = (b: ArrayLike<number>) => Array.from(b, (x) => (x & 0xff).toString(16).padStart(2, '0')).join('')

/** The bytes a case's parts make. */
export function assemble(parts: Part[]): Uint8Array {
  const flat = parts.flatMap((p) => (typeof p === 'object' && 'repeat' in p ? new Array<Part>(p.times).fill(p.repeat) : [p]))
  const length = flat.reduce((n, p) => n + (typeof p === 'string' ? p.length / 2 : 'count' in p ? p.count : 0), 0)
  const out = new Uint8Array(length)
  let at = 0
  for (const p of flat) {
    if (typeof p === 'string') {
      for (let i = 0; i < p.length; i += 2) out[at++] = parseInt(p.slice(i, i + 2), 16)
    } else if ('count' in p) {
      out.fill(p.fill, at, at + p.count)
      at += p.count
    }
  }
  return out
}

export const cat = (...parts: (Uint8Array | number[])[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}
export const u16be = (n: number) => [(n >> 8) & 0xff, n & 0xff]
export const u16le = (n: number) => [n & 0xff, (n >> 8) & 0xff]
export const u32be = (n: number) => [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff]
export const u32le = (n: number) => [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff]
export const ascii = (s: string) => [...s].map((c) => c.charCodeAt(0))
export const utf8 = (s: string) => [...new TextEncoder().encode(s)]

// --- TIFF ------------------------------------------------------------------------------------------------------------

/** One IFD entry: its tag, TIFF type and count, and its value's bytes in the block's byte order. */
export type Entry = { tag: number; type: number; count: number; data: number[] }
export type Order = { little: boolean }
const o16 = (o: Order, n: number) => (o.little ? u16le(n) : u16be(n))
const o32 = (o: Order, n: number) => (o.little ? u32le(n) : u32be(n))

export const t = {
  ascii: (o: Order, tag: number, s: string | number[]): Entry => {
    const data = [...(typeof s === 'string' ? utf8(s) : s), 0]
    return { tag, type: 2, count: data.length, data }
  },
  short: (o: Order, tag: number, ...v: number[]): Entry => ({ tag, type: 3, count: v.length, data: v.flatMap((x) => o16(o, x)) }),
  long: (o: Order, tag: number, ...v: number[]): Entry => ({ tag, type: 4, count: v.length, data: v.flatMap((x) => o32(o, x)) }),
  rational: (o: Order, tag: number, ...v: [number, number][]): Entry =>
    ({ tag, type: 5, count: v.length, data: v.flatMap(([n, d]) => [...o32(o, n), ...o32(o, d)]) }),
  srational: (o: Order, tag: number, ...v: [number, number][]): Entry =>
    ({ tag, type: 10, count: v.length, data: v.flatMap(([n, d]) => [...o32(o, n >>> 0), ...o32(o, d >>> 0)]) }),
  byte: (o: Order, tag: number, ...v: number[]): Entry => ({ tag, type: 1, count: v.length, data: v }),
}

/** A TIFF block (header at 0): IFD0's entries, and an Exif and a GPS IFD where given, each pointed at from IFD0. */
export function tiff(o: Order, ifd0: Entry[], exif?: Entry[], gps?: Entry[]): Uint8Array {
  const size = (entries: Entry[]) => 2 + 12 * entries.length + 4 + entries.reduce((n, e) => n + (e.data.length > 4 ? e.data.length + (e.data.length & 1) : 0), 0)
  const pointers = (exif ? 1 : 0) + (gps ? 1 : 0)
  const ifd0Size = size(ifd0) + 12 * pointers
  const exifAt = 8 + ifd0Size
  const gpsAt = exifAt + (exif ? size(exif) : 0)
  const head = [...(o.little ? ascii('II') : ascii('MM')), ...o16(o, 42), ...o32(o, 8)]
  const all0 = [...ifd0, ...(exif ? [t.long(o, 0x8769, exifAt)] : []), ...(gps ? [t.long(o, 0x8825, gpsAt)] : [])]
  const ifd = (entries: Entry[], at: number) => {
    const table: number[] = [...o16(o, entries.length)]
    const data: number[] = []
    let dataAt = at + 2 + 12 * entries.length + 4
    for (const e of entries) {
      table.push(...o16(o, e.tag), ...o16(o, e.type), ...o32(o, e.count))
      if (e.data.length <= 4) table.push(...e.data, ...new Array(4 - e.data.length).fill(0))
      else {
        table.push(...o32(o, dataAt + data.length))
        data.push(...e.data, ...(e.data.length & 1 ? [0] : []))
      }
    }
    void dataAt
    return [...table, ...o32(o, 0), ...data]
  }
  return new Uint8Array([...head, ...ifd(all0, 8), ...(exif ? ifd(exif, exifAt) : []), ...(gps ? ifd(gps, gpsAt) : [])])
}

// --- JPEG ------------------------------------------------------------------------------------------------------------

/** A segment: marker, length (its own two bytes included), payload. */
export const segment = (marker: number, payload: ArrayLike<number>) => [0xff, marker, ...u16be(payload.length + 2), ...Array.from(payload)]
/** An APP1 `Exif` segment around a TIFF block. */
export const exifSegment = (block: Uint8Array) => segment(0xe1, [...ascii('Exif'), 0, 0, ...block])
/** An APP1 XMP segment around a packet. */
export const xmpSegment = (packet: string) => segment(0xe1, [...ascii('http://ns.adobe.com/xap/1.0/'), 0, ...utf8(packet)])
/** A JPEG up to its frame header (SOF `marker`, `w` × `h`), the segments before it as given, and some scan bytes. */
export const jpeg = (w: number, h: number, before: number[][] = [], marker = 0xc0) =>
  [0xff, 0xd8, ...before.flat(), ...segment(marker, [8, ...u16be(h), ...u16be(w), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]), 0xff, 0xda, 0, 2, 1, 2, 3]

// --- ISO BMFF --------------------------------------------------------------------------------------------------------

export const box = (type: string, ...body: (Uint8Array | number[])[]) => {
  const payload = cat(...body)
  return cat(u32be(8 + payload.length), ascii(type), payload)
}
export const ONE = 0x10000
export const IDENTITY = [ONE, 0, 0, 0, ONE, 0, 0, 0, 0x40000000]
export const turned = (a: number, b: number, c: number, d: number) => [a, b, 0, c, d, 0, 0, 0, 0x40000000]
export function tkhd(w: number, h: number, matrix = IDENTITY, version = 0) {
  const times = version === 1 ? new Array(32).fill(0) : new Array(20).fill(0)
  return box('tkhd', [version, 0, 0, 3], times, new Array(16).fill(0), matrix.flatMap((n) => u32be(n >>> 0)), u32be(w * ONE), u32be(h * ONE))
}
export const hdlr = (handler: string) => box('hdlr', [0, 0, 0, 0], u32be(0), ascii(handler), new Array(12).fill(0), [0])
export const trak = (header: Uint8Array, handler: string) => box('trak', header, box('mdia', box('mdhd', new Array(24).fill(0)), hdlr(handler)))
export const ftyp = box('ftyp', ascii('isom'), u32be(0x200), ascii('isomiso2avc1mp41'))
const EPOCH_1904 = 2_082_844_800
const u64 = (n: number) => [...u32be(Math.floor(n / 2 ** 32)), ...u32be(n % 2 ** 32)]
export function mvhd(ms: number, version = 0) {
  const s = Math.floor(ms / 1000) + EPOCH_1904
  const times = version === 1 ? [...u64(s), ...u64(s)] : [...u32be(s), ...u32be(s)]
  return box('mvhd', [version, 0, 0, 0], times, new Array(80).fill(0))
}
/** A QuickTime `meta` of `mdta` keys, each with its UTF-8 value; `fullBox` puts version and flags first, as ffmpeg does. */
export function keyedMeta(entries: [string, string][], { fullBox = false, type = 1 } = {}) {
  const keys = box('keys', [0, 0, 0, 0], u32be(entries.length), ...entries.map(([k]) => cat(u32be(8 + k.length), ascii('mdta'), ascii(k))))
  const items = entries.map(([, v], i) => {
    const data = box('data', u32be(type), u32be(0), utf8(v))
    return cat(u32be(8 + data.length), u32be(i + 1), data)
  })
  return box('meta', fullBox ? [0, 0, 0, 0] : [], hdlr('mdta'), keys, box('ilst', ...items))
}

// --- EBML ------------------------------------------------------------------------------------------------------------

/** An EBML element: its id's bytes, then its size as an 8-byte vint, then its payload. */
export const el = (id: number[], ...body: (Uint8Array | number[])[]) => {
  const payload = cat(...body)
  const n = payload.length
  return cat(id, [0x01, 0, 0, (n / 2 ** 32) & 255, ...u32be(n)], payload)
}
export const unknownSize = (id: number[], ...body: (Uint8Array | number[])[]) => cat(id, [0x01, 255, 255, 255, 255, 255, 255, 255], ...body)
export const uint = (id: number[], n: number) => el(id, n > 0xffff ? u32be(n) : [(n >> 8) & 255, n & 255])
export const EBML_ID = {
  ebml: [0x1a, 0x45, 0xdf, 0xa3], docType: [0x42, 0x82], segment: [0x18, 0x53, 0x80, 0x67], info: [0x15, 0x49, 0xa9, 0x66],
  tracks: [0x16, 0x54, 0xae, 0x6b], entry: [0xae], type: [0x83], video: [0xe0], pixelWidth: [0xb0], pixelHeight: [0xba],
  displayWidth: [0x54, 0xb0], displayHeight: [0x54, 0xba], displayUnit: [0x54, 0xb2], cropTop: [0x54, 0xbb],
  projection: [0x76, 0x70], cluster: [0x1f, 0x43, 0xb6, 0x75], timecode: [0xe7],
}

// --- RIFF / PNG ------------------------------------------------------------------------------------------------------

export const riff = (chunk: string, body: number[]) => [...ascii('RIFF'), ...u32le(4 + 8 + body.length), ...ascii('WEBP'), ...ascii(chunk), ...u32le(body.length), ...body]
function crc32(b: Uint8Array): number {
  let c = ~0
  for (const x of b) {
    c ^= x
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}
export function pngChunk(kind: string, data: ArrayLike<number>): Uint8Array {
  const out = new Uint8Array(12 + data.length)
  new DataView(out.buffer).setUint32(0, data.length)
  out.set(ascii(kind), 4)
  out.set(Array.from(data), 8)
  new DataView(out.buffer).setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)))
  return out
}
