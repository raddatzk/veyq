// A video's display size read from its container's header, never by a player (inbox #793), as `imageHeader.ts` reads a
// photo's: the pick-time tile probe and the upload (`mediaDeriver.ts` `probeDimensions`) need only the size, and a
// `<video>` that loads a clip's metadata is a media player of its own, with a demuxer and a share of the renderer, for as
// long as it lives. Picking 20 clips made 20 of them at once (profile S2v, inbox #786).
//
// The two container families a camera, a phone or a browser's recorder hands a page:
//  - **MP4 / QuickTime** (ISO BMFF): the top-level boxes are walked by their headers only, so a `moov` behind the media
//    data (a phone's recording, written without `faststart`) costs one more read, never the bytes in between; a box may
//    name a 64-bit size. The size is the video track's `tkhd` width and height (16.16 fixed point), turned by its matrix
//    as a player shows it: a quarter turn (a phone held upright) swaps them, as Chromium's `videoWidth`/`videoHeight` do.
//    A matrix that is not a plain rotation answers null.
//  - **WebM / Matroska** (EBML): Segment → Tracks → the first TrackEntry of TrackType 1 → Video's PixelWidth and
//    PixelHeight, or its DisplayWidth and DisplayHeight where they are given in pixels (DisplayUnit 0). An element of
//    unknown size (MediaRecorder's Segment) is descended into; Tracks precede the first Cluster, so the walk stops there.
//    Matroska carries no rotation a player applies, so none is read; a track with a Projection, a crop or a display size
//    in another unit answers null.
// Anything else - another container, a fragmented MP4 whose `tkhd` names no size, a damaged header - answers null, and
// the caller asks the browser instead. The reads are slices of the Blob, 64 KiB at a time and at most 1 MiB in all; the
// walk visits at most 4,096 boxes or elements. Nothing here holds the file.
//
// The same walk reads a video's **capture time** (inbox #794), which a copy, a download or a messenger leaves alone where
// they rewrite the file's own date. Only MP4 / QuickTime carry one: Apple's `com.apple.quicktime.creationdate`, an
// ISO 8601 time with the local offset, in the `mdta` keys of a `meta` in the `moov` (ffmpeg puts that `meta` in the
// `moov`'s `udta`), else the `mvhd` creation time (seconds since 1904, UTC), which a phone writes as the time it started
// recording. A zero or anything before 1970-01-02 is an encoder that wrote no time (or counted from the wrong epoch),
// and a time more than a day ahead of the clock is no capture time either; both answer null. Matroska's `DateUTC` is
// not read: the format defines it as when the file was muxed, and a browser's recorder writes none.
import type { Dimensions } from './mediaDeriver'

/** Bytes per read: a `moov` of a short clip fits whole; a longer one's track headers sit at its start. */
const WINDOW = 64 * 1024
/** Bytes the walk may read in all: a header it does not reach within this is not one it reads. */
const MAX_READ = 1024 * 1024
/** Boxes or elements the walk may visit: bounds a file of many tiny ones that the byte cap alone would not. */
const MAX_VISITS = 4096

const u32be = (b: Uint8Array, at: number) => ((b[at] << 24) >>> 0) + ((b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3])
const i32be = (b: Uint8Array, at: number) => (b[at] << 24) | (b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3]
const fourcc = (b: Uint8Array, at: number) => String.fromCharCode(b[at], b[at + 1], b[at + 2], b[at + 3])

/** A size, or null where either side is not a positive whole number of pixels. */
const sized = (w: number, h: number): Dimensions | null =>
  Number.isFinite(w) && Number.isFinite(h) && w >= 1 && h >= 1 ? { w: Math.round(w), h: Math.round(h) } : null

/** Reads the file in windows, within the byte cap and the visit cap; `null` once either is spent or past the end. */
class HeaderReader {
  private window = { at: 0, bytes: new Uint8Array(0) }
  private read = 0
  private visits = 0
  constructor(readonly blob: Blob) {}

  /** The bytes [at, at + length), or fewer at the file's end; null where none are there or the cap is spent. */
  async view(at: number, length: number): Promise<Uint8Array | null> {
    if (at < 0 || at >= this.blob.size) return null
    const end = Math.min(this.blob.size, at + length)
    const { at: from, bytes } = this.window
    if (at < from || end > from + bytes.length) {
      const size = Math.min(this.blob.size, at + Math.max(length, WINDOW)) - at
      if (this.read + size > MAX_READ) return null
      this.read += size
      this.window = { at, bytes: new Uint8Array(await this.blob.slice(at, at + size).arrayBuffer()) }
    }
    return this.window.bytes.subarray(at - this.window.at, end - this.window.at)
  }

  /** Counts one box or element visited; false once the walk has visited its most. */
  visit(): boolean {
    return ++this.visits <= MAX_VISITS
  }
}

/**
 * The display size a video file's container header names, as a player shows it, or null for a container this does not
 * read or a header it cannot. Never rejects for a malformed file; a read the browser refuses rejects, as any read would.
 */
export async function videoSizeFromHeader(blob: Blob): Promise<Dimensions | null> {
  const r = new HeaderReader(blob)
  const head = await r.view(0, 8)
  if (!head || head.length < 8) return null
  if (u32be(head, 0) === 0x1a45dfa3) return matroskaSize(r)
  if (TOP_LEVEL.has(fourcc(head, 4))) return bmffSize(r)
  return null
}

/**
 * The capture time (epoch ms) a video file's container header names, or null for a container that carries none, a
 * header this cannot read or a time that is no capture time. Never rejects for a malformed file; a read the browser
 * refuses rejects, as any read would. `now` bounds a time ahead of the clock.
 */
export async function videoCaptureTimeFromHeader(blob: Blob, now: number = Date.now()): Promise<number | null> {
  const r = new HeaderReader(blob)
  const head = await r.view(0, 8)
  if (!head || head.length < 8 || !TOP_LEVEL.has(fourcc(head, 4))) return null
  const moov = await child(r, { body: 0, end: r.blob.size }, 'moov')
  if (!moov) return null
  const plausible = (at: number | null) => (at !== null && at >= EARLIEST_CAPTURE && at <= now + DAY ? at : null)
  const udta = await child(r, moov, 'udta')
  const meta = (await child(r, moov, 'meta')) ?? (udta && (await child(r, udta, 'meta'))) // Apple's place, then ffmpeg's
  const tagged = meta && plausible(parseIsoTime(await quickTimeKey(r, meta, CREATION_DATE_KEY)))
  return tagged ?? plausible(await mvhdCreation(r, moov))
}

// --- MP4 / QuickTime -----------------------------------------------------------------------------------------------

/** The box types an ISO BMFF or QuickTime file may open with (an old QuickTime file has no `ftyp`). */
const TOP_LEVEL = new Set(['ftyp', 'moov', 'mdat', 'wide', 'free', 'skip', 'pnot'])

type Box = { type: string; body: number; end: number }

/** The box at `at`, inside a parent that ends at `limit`: its type, where its body starts and where it ends. */
async function boxAt(r: HeaderReader, at: number, limit: number): Promise<Box | null> {
  if (!r.visit()) return null
  const h = await r.view(at, 16)
  if (!h || h.length < 8) return null
  const type = fourcc(h, 4)
  let size = u32be(h, 0)
  let body = at + 8
  if (size === 1) {
    if (h.length < 16) return null
    size = u32be(h, 8) * 2 ** 32 + u32be(h, 12) // a 64-bit `largesize`
    body = at + 16
  } else if (size === 0) {
    size = limit - at // the last box, to the end of its parent
  }
  const end = at + size
  return end >= body && end <= limit ? { type, body, end } : null
}

/** The first child of `parent` of `type`, walking its children by their headers; null where none is or a header is bad. */
async function child(r: HeaderReader, parent: { body: number; end: number }, type: string): Promise<Box | null> {
  for (let at = parent.body; at < parent.end; ) {
    const box = await boxAt(r, at, parent.end)
    if (!box) return null
    if (box.type === type) return box
    at = box.end
  }
  return null
}

async function bmffSize(r: HeaderReader): Promise<Dimensions | null> {
  const moov = await child(r, { body: 0, end: r.blob.size }, 'moov')
  if (!moov) return null
  for (let at = moov.body; at < moov.end; ) {
    const box = await boxAt(r, at, moov.end)
    if (!box) return null
    if (box.type === 'trak') {
      const size = await videoTrackSize(r, box)
      if (size) return size
    }
    at = box.end
  }
  return null
}

/** A `trak`'s display size where its media handler is video (`hdlr` `vide`); null for any other track. */
async function videoTrackSize(r: HeaderReader, trak: Box): Promise<Dimensions | null> {
  const mdia = await child(r, trak, 'mdia')
  const hdlr = mdia && (await child(r, mdia, 'hdlr'))
  const handler = hdlr && (await r.view(hdlr.body + 8, 4)) // version and flags, pre_defined, then handler_type
  if (!handler || handler.length < 4 || fourcc(handler, 0) !== 'vide') return null
  const tkhd = await child(r, trak, 'tkhd')
  if (!tkhd) return null
  const version = await r.view(tkhd.body, 1)
  if (!version || version.length < 1) return null
  const matrixAt = tkhd.body + (version[0] === 1 ? 52 : 40) // past the times, the track id, layer, group and volume
  if (matrixAt + 44 > tkhd.end) return null
  const m = await r.view(matrixAt, 44)
  if (!m || m.length < 44) return null
  const [a, b, c, d] = [i32be(m, 0), i32be(m, 4), i32be(m, 12), i32be(m, 16)] // {a b u / c d v / x y w}, 16.16
  const w = u32be(m, 36) / 65536
  const h = u32be(m, 40) / 65536
  const one = 0x10000
  const upright = b === 0 && c === 0 && Math.abs(a) === one && a === d // none, or a half turn
  const quarter = a === 0 && d === 0 && Math.abs(b) === one && b === -c // a quarter turn either way
  if (upright) return sized(w, h)
  if (quarter) return sized(h, w)
  return null // a flip, a scale or a skew: the browser answers
}

/** Seconds from 1904-01-01 (ISO BMFF's epoch) to 1970-01-01. */
const EPOCH_1904 = 2_082_844_800
const DAY = 86_400_000
/** A time before this is an encoder that wrote zero or counted from 1970 into a field that counts from 1904. */
const EARLIEST_CAPTURE = DAY
const CREATION_DATE_KEY = 'com.apple.quicktime.creationdate'

/** The `mvhd` creation time in epoch ms; null where the box is missing or short. */
async function mvhdCreation(r: HeaderReader, moov: Box): Promise<number | null> {
  const mvhd = await child(r, moov, 'mvhd')
  const b = mvhd && (await r.view(mvhd.body, 12))
  if (!b || b.length < 8) return null
  const seconds = b[0] === 1 ? (b.length < 12 ? null : u32be(b, 4) * 2 ** 32 + u32be(b, 8)) : u32be(b, 4)
  return seconds === null ? null : (seconds - EPOCH_1904) * 1000
}

/**
 * The UTF-8 value a QuickTime `meta` (`hdlr` `mdta`, then `keys` and `ilst`) holds for `key`; null where there is none.
 * Apple writes this `meta` as a plain atom, an ISO writer as a full box with four bytes of version and flags first.
 */
async function quickTimeKey(r: HeaderReader, meta: Box, key: string): Promise<string | null> {
  const first = await r.view(meta.body, 12)
  if (!first || first.length < 12) return null
  const body = META_CHILDREN.has(fourcc(first, 4)) ? meta.body : META_CHILDREN.has(fourcc(first, 8)) ? meta.body + 4 : null
  if (body === null) return null
  const atoms = { body, end: meta.end }
  const keys = await child(r, atoms, 'keys')
  const index = keys && (await keyIndex(r, keys, key))
  const ilst = index && (await child(r, atoms, 'ilst'))
  if (!index || !ilst) return null
  for (let at = ilst.body; at < ilst.end; ) {
    const item = await boxAt(r, at, ilst.end)
    if (!item) return null
    const id = await r.view(at + 4, 4) // an item's type is the key's 1-based index, not a fourcc
    if (id && id.length === 4 && u32be(id, 0) === index) return dataText(r, item)
    at = item.end
  }
  return null
}

const META_CHILDREN = new Set(['hdlr', 'keys', 'ilst'])

/** The 1-based index `key` has in a `keys` atom (version and flags, a count, then size + namespace + name each). */
async function keyIndex(r: HeaderReader, keys: Box, key: string): Promise<number | null> {
  const count = await r.view(keys.body + 4, 4)
  if (!count || count.length < 4) return null
  let at = keys.body + 8
  for (let i = 1; i <= u32be(count, 0) && at + 8 <= keys.end && r.visit(); i++) {
    const h = await r.view(at, 8)
    if (!h || h.length < 8) return null
    const size = u32be(h, 0)
    if (size < 8 || at + size > keys.end) return null
    if (fourcc(h, 4) === 'mdta' && size - 8 === key.length) {
      const name = await r.view(at + 8, size - 8)
      if (name && new TextDecoder().decode(name) === key) return i
    }
    at += size
  }
  return null
}

/** The text of an `ilst` item's `data` atom (type 1, UTF-8); null for any other type. */
async function dataText(r: HeaderReader, item: Box): Promise<string | null> {
  const data = await child(r, item, 'data')
  if (!data || data.end - data.body < 8 || data.end - data.body > 256) return null
  const b = await r.view(data.body, data.end - data.body)
  if (!b || b.length < 8 || u32be(b, 0) !== 1) return null // the type indicator, then the locale
  return new TextDecoder('utf-8', { fatal: true }).decode(b.subarray(8))
}

/** `YYYY-MM-DDTHH:MM:SS[.fff](Z|±HH[:]MM)` in epoch ms; null without a zone, since a local time names no instant. */
function parseIsoTime(text: string | null): number | null {
  const m = text?.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|([+-])(\d{2}):?(\d{2}))$/)
  if (!m) return null
  const [, y, mo, d, h, mi, s, zone, sign, oh, om] = m
  const local = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s)
  const offset = zone === 'Z' ? 0 : (sign === '-' ? -1 : 1) * (+oh * 60 + +om) * 60_000
  const at = local - offset
  return new Date(local).getUTCDate() === +d && +h < 24 && +mi < 60 && +s < 60 ? at : null
}

// --- WebM / Matroska -----------------------------------------------------------------------------------------------

const EBML = {
  segment: 0x18538067,
  cluster: 0x1f43b675,
  tracks: 0x1654ae6b,
  trackEntry: 0xae,
  trackType: 0x83,
  video: 0xe0,
  pixelWidth: 0xb0,
  pixelHeight: 0xba,
  displayWidth: 0x54b0,
  displayHeight: 0x54ba,
  displayUnit: 0x54b2,
  projection: 0x7670,
  crops: [0x54aa, 0x54bb, 0x54cc, 0x54dd],
}

type Element = { id: number; body: number; end: number; unknown: boolean }

/** An EBML variable-length integer at `at` of `b`: its value (the length marker kept for an id) and its length. */
function vint(b: Uint8Array, at: number, maxLength: number, keepMarker: boolean): { value: number; length: number; ones: boolean } | null {
  const first = b[at]
  if (first === undefined || first === 0) return null
  let length = 1
  while (!(first & (0x80 >> (length - 1)))) length++
  if (length > maxLength || at + length > b.length) return null
  const bits = first & (0xff >> length)
  let value = keepMarker ? first : bits
  let ones = bits === 0xff >> length
  for (let i = 1; i < length; i++) {
    value = value * 256 + b[at + i]
    ones &&= b[at + i] === 0xff
  }
  return { value, length, ones }
}

/** The element at `at` inside a parent that ends at `limit`; an unknown size (all ones) reaches the parent's end. */
async function elementAt(r: HeaderReader, at: number, limit: number): Promise<Element | null> {
  if (!r.visit()) return null
  const h = await r.view(at, 12)
  if (!h) return null
  const id = vint(h, 0, 4, true)
  const size = id && vint(h, id.length, 8, false)
  if (!id || !size) return null
  const body = at + id.length + size.length
  const end = size.ones ? limit : body + size.value
  return end >= body && end <= limit ? { id: id.value, body, end, unknown: size.ones } : null
}

/** The unsigned integer an element holds (at most 8 bytes); null where it does not read. */
async function uintOf(r: HeaderReader, el: Element): Promise<number | null> {
  const length = el.end - el.body
  if (el.unknown || length < 1 || length > 8) return null
  const b = await r.view(el.body, length)
  if (!b || b.length < length) return null
  return b.reduce((n, byte) => n * 256 + byte, 0)
}

/** Each child of `parent`, in order, until `visit` answers a value (or `false` to stop); null where a header is bad. */
async function eachElement<T>(
  r: HeaderReader,
  parent: { body: number; end: number },
  visit: (el: Element) => Promise<T | null | false>,
): Promise<T | null> {
  for (let at = parent.body; at < parent.end; ) {
    const el = await elementAt(r, at, parent.end)
    if (!el) return null
    const answer = await visit(el)
    if (answer === false) return null
    if (answer !== null) return answer
    if (el.unknown) return null // only a Segment or a Cluster may have no size, and neither is skipped over
    at = el.end
  }
  return null
}

async function matroskaSize(r: HeaderReader): Promise<Dimensions | null> {
  return eachElement(r, { body: 0, end: r.blob.size }, async (el) => {
    if (el.id !== EBML.segment) return null // the EBML header, then the Segment
    return (await segmentSize(r, el)) ?? false
  })
}

async function segmentSize(r: HeaderReader, segment: Element): Promise<Dimensions | null> {
  return eachElement(r, segment, async (el) => {
    if (el.id === EBML.cluster) return false // Tracks precede the first Cluster: none came
    if (el.id !== EBML.tracks) return null
    return (await eachElement(r, el, (entry) => (entry.id === EBML.trackEntry ? videoEntrySize(r, entry) : Promise.resolve(null)))) ?? false
  })
}

/** A TrackEntry's display size where it is a video track (TrackType 1); null for any other track. */
async function videoEntrySize(r: HeaderReader, entry: Element): Promise<Dimensions | null> {
  const track: { type?: number | null; video?: Element; projected?: boolean } = {}
  await eachElement(r, entry, async (el) => {
    if (el.id === EBML.trackType) track.type = await uintOf(r, el)
    if (el.id === EBML.video) track.video = el
    return null
  })
  if (track.type !== 1 || !track.video) return null
  const v: Partial<Record<number, number | null>> = {}
  await eachElement(r, track.video, async (el) => {
    if (el.id === EBML.projection) track.projected = true
    else v[el.id] = await uintOf(r, el)
    return null
  })
  if (track.projected || EBML.crops.some((id) => (v[id] ?? 0) !== 0)) return null
  const pixels = sized(v[EBML.pixelWidth] ?? 0, v[EBML.pixelHeight] ?? 0)
  const display = v[EBML.displayWidth] !== undefined || v[EBML.displayHeight] !== undefined
  if (!display) return pixels
  if ((v[EBML.displayUnit] ?? 0) !== 0) return null // a display size in centimetres, inches or an aspect ratio
  return sized(v[EBML.displayWidth] ?? pixels?.w ?? 0, v[EBML.displayHeight] ?? pixels?.h ?? 0)
}
