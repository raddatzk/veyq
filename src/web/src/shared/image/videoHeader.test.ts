// A video's display size from its container's header (inbox #793), read from slices of the file, never by a player.
//
// The four files in `__fixtures__/` are ffmpeg's own (8.1), one frame of `testsrc2` at 64x36, metadata stripped, so each
// is a muxer's real layout and not this reader's idea of it:
//   ffmpeg -f lavfi -i testsrc2=size=64x36:rate=1 -frames:v 1 -c:v libx264 -pix_fmt yuv420p -map_metadata -1 \
//     -fflags +bitexact -flags:v +bitexact [-movflags +faststart] <out>
// `landscape.mp4` with `+faststart` (its `moov` first); `portrait-moov-at-end.mov` without it (its `moov` behind the media
// data, as a phone writes it), remuxed with `-display_rotation 90 -c copy`, so its matrix turns it upright (Chromium
// reports it 36x64); `clip.webm` VP9 (`-c:v libvpx-vp9 -b:v 50k`); `clip.mkv` H.264. The two dated ones carry a capture
// time (inbox #794): `dated.mp4` as `landscape.mp4` plus `-metadata creation_time=2024-05-12T12:03:21Z` (its `mvhd`), and
// `dated-apple-keys.mov` without `+faststart`, plus `-movflags use_metadata_tags -metadata creation_time=2024-05-12T12:00:00Z
// -metadata com.apple.quicktime.creationdate=2024-05-12T14:03:21+0200` (an `mvhd` time AND Apple's key, three minutes
// apart, in a `meta` ffmpeg writes as a full box). The rest is built byte by byte here.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { videoCaptureTimeFromHeader, videoSizeFromHeader } from './videoHeader'

const fixture = (name: string) =>
  new Blob([readFileSync(fileURLToPath(new URL(`./__fixtures__/${name}`, import.meta.url))) as BlobPart])

afterEach(() => vi.restoreAllMocks())

/** The slices a read made, as [start, end) pairs. */
function slices(blob: Blob): [number, number][] {
  const calls: [number, number][] = []
  const slice = blob.slice.bind(blob)
  vi.spyOn(blob, 'slice').mockImplementation((start = 0, end = blob.size) => {
    calls.push([start, end])
    return slice(start, end)
  })
  return calls
}
const bytesRead = (calls: [number, number][]) => calls.reduce((n, [a, b]) => n + (b - a), 0)

describe('files as ffmpeg writes them', () => {
  it('an MP4 whose index comes first', async () => {
    await expect(videoSizeFromHeader(fixture('landscape.mp4'))).resolves.toEqual({ w: 64, h: 36 })
  })
  it('a QuickTime file whose index comes last and whose matrix turns it a quarter: the size as it is shown', async () => {
    await expect(videoSizeFromHeader(fixture('portrait-moov-at-end.mov'))).resolves.toEqual({ w: 36, h: 64 })
  })
  it('a WebM and a Matroska file', async () => {
    await expect(videoSizeFromHeader(fixture('clip.webm'))).resolves.toEqual({ w: 64, h: 36 })
    await expect(videoSizeFromHeader(fixture('clip.mkv'))).resolves.toEqual({ w: 64, h: 36 })
  })
})

// --- ISO BMFF, built ------------------------------------------------------------------------------------------------

const cat = (...parts: (Uint8Array | number[])[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of parts) {
    out.set(p, at)
    at += p.length
  }
  return out
}
const u32 = (n: number) => [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]
const ascii = (s: string) => [...s].map((c) => c.charCodeAt(0))
const box = (type: string, ...body: (Uint8Array | number[])[]) => {
  const payload = cat(...body)
  return cat(u32(8 + payload.length), ascii(type), payload)
}
/** A box with a 64-bit `largesize`, as a recorder writes an `mdat` it cannot size in advance. */
const largeBox = (type: string, payload: Uint8Array) => cat(u32(1), ascii(type), u32(0), u32(16 + payload.length), payload)

const ONE = 0x10000
const IDENTITY = [ONE, 0, 0, 0, ONE, 0, 0, 0, 0x40000000]
const turned = (a: number, b: number, c: number, d: number) => [a, b, 0, c, d, 0, 0, 0, 0x40000000]
function tkhd(w: number, h: number, matrix = IDENTITY, version = 0) {
  const times = version === 1 ? new Array(32).fill(0) : new Array(20).fill(0)
  return box('tkhd', [version, 0, 0, 3], times, new Array(16).fill(0), matrix.flatMap(u32), u32(w * ONE), u32(h * ONE))
}
const hdlr = (handler: string) => box('hdlr', [0, 0, 0, 0], u32(0), ascii(handler), new Array(12).fill(0), [0])
const trak = (header: Uint8Array, handler: string) => box('trak', header, box('mdia', box('mdhd', new Array(24).fill(0)), hdlr(handler)))
const ftyp = box('ftyp', ascii('isom'), u32(0x200), ascii('isomiso2avc1mp41'))
const mp4 = (...parts: Uint8Array[]) => new Blob([cat(ftyp, ...parts) as BlobPart])

describe('ISO BMFF: the video track’s tkhd, turned by its matrix', () => {
  it('skips a sound and a subtitle track, whatever their tkhd names, and takes the video one', async () => {
    const tracks = [trak(tkhd(0, 0), 'soun'), trak(tkhd(640, 80), 'sbtl'), trak(tkhd(1920, 1080), 'vide')]
    const moov = box('moov', box('mvhd', new Array(100).fill(0)), ...tracks)
    await expect(videoSizeFromHeader(mp4(moov))).resolves.toEqual({ w: 1920, h: 1080 })
  })

  it('a quarter turn either way swaps width and height; a half turn does not', async () => {
    const turn = async (m: number[]) => videoSizeFromHeader(mp4(box('moov', trak(tkhd(1920, 1080, m), 'vide'))))
    await expect(turn(turned(0, ONE, -ONE, 0))).resolves.toEqual({ w: 1080, h: 1920 })
    await expect(turn(turned(0, -ONE, ONE, 0))).resolves.toEqual({ w: 1080, h: 1920 })
    await expect(turn(turned(-ONE, 0, 0, -ONE))).resolves.toEqual({ w: 1920, h: 1080 })
  })

  it('a matrix that is no plain turn - a mirror, a scale - is left to the browser', async () => {
    const m = async (matrix: number[]) => videoSizeFromHeader(mp4(box('moov', trak(tkhd(1920, 1080, matrix), 'vide'))))
    await expect(m(turned(-ONE, 0, 0, ONE))).resolves.toBeNull()
    await expect(m(turned(2 * ONE, 0, 0, 2 * ONE))).resolves.toBeNull()
  })

  it('reads a version-1 tkhd, whose times are 64-bit', async () => {
    await expect(videoSizeFromHeader(mp4(box('moov', trak(tkhd(3840, 2160, IDENTITY, 1), 'vide'))))).resolves.toEqual({ w: 3840, h: 2160 })
  })

  it('a moov behind 3 MiB of media data with a 64-bit size: two small reads, never the bytes between', async () => {
    const blob = mp4(largeBox('mdat', new Uint8Array(3 * 1024 * 1024)), box('moov', trak(tkhd(1280, 720), 'vide')))
    const calls = slices(blob)
    await expect(videoSizeFromHeader(blob)).resolves.toEqual({ w: 1280, h: 720 })
    expect(calls.every(([a, b]) => b - a <= 64 * 1024), 'each read at most a 64 KiB window').toBe(true)
    expect(bytesRead(calls)).toBeLessThanOrEqual(2 * 64 * 1024)
  })

  it('a last box that runs to the end of the file (size 0)', async () => {
    const moov = box('moov', trak(tkhd(1280, 720), 'vide'))
    await expect(videoSizeFromHeader(mp4(cat(u32(0), moov.subarray(4))))).resolves.toEqual({ w: 1280, h: 720 })
  })

  it('a track header that names no size (a fragmented recording) or no video track at all answers null', async () => {
    await expect(videoSizeFromHeader(mp4(box('moov', trak(tkhd(0, 0), 'vide'))))).resolves.toBeNull()
    await expect(videoSizeFromHeader(mp4(box('moov', trak(tkhd(0, 0), 'soun'))))).resolves.toBeNull()
    await expect(videoSizeFromHeader(mp4(largeBox('mdat', new Uint8Array(64))))).resolves.toBeNull()
  })

  it('a damaged header answers null, never rejects: a box past its parent, a truncated tkhd, bytes of no container', async () => {
    const past = cat(ftyp, u32(4096), ascii('moov'), trak(tkhd(1920, 1080), 'vide'))
    await expect(videoSizeFromHeader(new Blob([past as BlobPart]))).resolves.toBeNull()
    const whole = cat(ftyp, box('moov', trak(tkhd(1920, 1080), 'vide')))
    await expect(videoSizeFromHeader(new Blob([whole.subarray(0, whole.length - 6) as BlobPart]))).resolves.toBeNull()
    await expect(videoSizeFromHeader(new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46]) as BlobPart]))).resolves.toBeNull()
    await expect(videoSizeFromHeader(new Blob([new Uint8Array(3) as BlobPart]))).resolves.toBeNull()
  })

  it('a file that does not open as an MP4 or QuickTime file does is not walked, whatever boxes follow', async () => {
    const other = cat(box('RIFF', ascii('AVI LIST')), box('moov', trak(tkhd(1920, 1080), 'vide')))
    await expect(videoSizeFromHeader(new Blob([other as BlobPart]))).resolves.toBeNull()
  })

  it('stops at 1 MiB read: a moov that only a longer walk would reach is left to the browser', async () => {
    // each `free` box is 100 KiB, so every next header lies outside the window before it: one 64 KiB read per box
    const walk = (boxes: number) =>
      mp4(...Array.from({ length: boxes }, () => box('free', new Uint8Array(100 * 1024 - 8))), box('moov', trak(tkhd(640, 480), 'vide')))
    await expect(videoSizeFromHeader(walk(10))).resolves.toEqual({ w: 640, h: 480 })
    const far = walk(20)
    const calls = slices(far)
    await expect(videoSizeFromHeader(far)).resolves.toBeNull()
    expect(bytesRead(calls)).toBeLessThanOrEqual(1024 * 1024)
  })

  it('stops after 4,096 boxes: a file of many tiny ones is left to the browser', async () => {
    const walk = (boxes: number) => mp4(...Array.from({ length: boxes }, () => box('free')), box('moov', trak(tkhd(640, 480), 'vide')))
    await expect(videoSizeFromHeader(walk(100))).resolves.toEqual({ w: 640, h: 480 })
    await expect(videoSizeFromHeader(walk(5000))).resolves.toBeNull()
  })
})

// --- EBML, built ----------------------------------------------------------------------------------------------------

/** An EBML element: its id's bytes, then its size as an 8-byte vint (or all ones: unknown), then its payload. */
const el = (id: number[], ...body: (Uint8Array | number[])[]) => {
  const payload = cat(...body)
  const n = payload.length
  return cat(id, [0x01, 0, 0, (n / 2 ** 32) & 255, ...u32(n)], payload)
}
const unknownSize = (id: number[], ...body: (Uint8Array | number[])[]) => cat(id, [0x01, 255, 255, 255, 255, 255, 255, 255], ...body)
const uint = (id: number[], n: number) => el(id, n > 0xffff ? u32(n) : [(n >> 8) & 255, n & 255])
const ID = {
  ebml: [0x1a, 0x45, 0xdf, 0xa3],
  docType: [0x42, 0x82],
  segment: [0x18, 0x53, 0x80, 0x67],
  info: [0x15, 0x49, 0xa9, 0x66],
  tracks: [0x16, 0x54, 0xae, 0x6b],
  entry: [0xae],
  type: [0x83],
  video: [0xe0],
  pixelWidth: [0xb0],
  pixelHeight: [0xba],
  displayWidth: [0x54, 0xb0],
  displayHeight: [0x54, 0xba],
  displayUnit: [0x54, 0xb2],
  cropTop: [0x54, 0xbb],
  projection: [0x76, 0x70],
  cluster: [0x1f, 0x43, 0xb6, 0x75],
  timecode: [0xe7],
}
const header = el(ID.ebml, el(ID.docType, ascii('webm')))
const audio = el(ID.entry, uint(ID.type, 2), el([0xe1], new Array(9).fill(0)))
const video = (...fields: Uint8Array[]) => el(ID.entry, uint(ID.type, 1), el(ID.video, uint(ID.pixelWidth, 1280), uint(ID.pixelHeight, 720), ...fields))
const cluster = unknownSize(ID.cluster, uint(ID.timecode, 0), new Uint8Array(512))
/** As MediaRecorder writes it: a Segment and Clusters of unknown size. */
const webm = (...segment: Uint8Array[]) => new Blob([cat(header, unknownSize(ID.segment, ...segment)) as BlobPart])

describe('Matroska: the first video TrackEntry’s Video element', () => {
  it('an unknown-size Segment is descended into; a sound track is skipped; the pixel size', async () => {
    await expect(videoSizeFromHeader(webm(el(ID.info, new Array(20).fill(0)), el(ID.tracks, audio, video()), cluster))).resolves.toEqual({ w: 1280, h: 720 })
  })

  it('a display size given in pixels is the size shown; in another unit, the browser answers', async () => {
    const shown = video(uint(ID.displayWidth, 1280), uint(ID.displayHeight, 960), uint(ID.displayUnit, 0))
    await expect(videoSizeFromHeader(webm(el(ID.tracks, shown)))).resolves.toEqual({ w: 1280, h: 960 })
    const unit = video(uint(ID.displayWidth, 4), uint(ID.displayHeight, 3), uint(ID.displayUnit, 3))
    await expect(videoSizeFromHeader(webm(el(ID.tracks, unit)))).resolves.toBeNull()
  })

  it('a crop or a projection is left to the browser', async () => {
    await expect(videoSizeFromHeader(webm(el(ID.tracks, video(uint(ID.cropTop, 8)))))).resolves.toBeNull()
    await expect(videoSizeFromHeader(webm(el(ID.tracks, video(el(ID.projection, [0x76, 0x71, 0x81, 0])))))).resolves.toBeNull()
  })

  it('a track not typed video is skipped, even one that carries a Video element', async () => {
    const mistyped = el(ID.entry, uint(ID.type, 2), el(ID.video, uint(ID.pixelWidth, 320), uint(ID.pixelHeight, 320)))
    await expect(videoSizeFromHeader(webm(el(ID.tracks, mistyped, video())))).resolves.toEqual({ w: 1280, h: 720 })
  })

  it('stops at the first Cluster: Tracks behind it, or none, answer null', async () => {
    await expect(videoSizeFromHeader(webm(el(ID.cluster, uint(ID.timecode, 0)), el(ID.tracks, video())))).resolves.toBeNull()
    await expect(videoSizeFromHeader(webm(el(ID.tracks, audio), cluster))).resolves.toBeNull()
  })

  it('a damaged element answers null: a size past its parent, an id of no length', async () => {
    const past = cat(header, el(ID.segment, el(ID.tracks, video())).subarray(0, 40))
    await expect(videoSizeFromHeader(new Blob([past as BlobPart]))).resolves.toBeNull()
    await expect(videoSizeFromHeader(new Blob([cat(header, [0, 0, 0, 0, 0, 0, 0, 0]) as BlobPart]))).resolves.toBeNull()
  })
})

// --- the capture time (inbox #794) ---------------------------------------------------------------------------------

const AT = Date.UTC(2024, 4, 12, 12, 3, 21)
const NOW = Date.UTC(2026, 9, 30)
const EPOCH_1904 = 2_082_844_800
const u64 = (n: number) => [...u32(Math.floor(n / 2 ** 32)), ...u32(n % 2 ** 32)]
function mvhd(ms: number, version = 0) {
  const s = Math.floor(ms / 1000) + EPOCH_1904
  const times = version === 1 ? [...u64(s), ...u64(s)] : [...u32(s), ...u32(s)]
  return box('mvhd', [version, 0, 0, 0], times, new Array(80).fill(0))
}
const utf8 = (s: string) => new TextEncoder().encode(s)
/** A QuickTime `meta` of `mdta` keys, each with its UTF-8 value; `fullBox` puts version and flags first, as ffmpeg does. */
function keyedMeta(entries: [string, string][], { fullBox = false, type = 1 } = {}) {
  const keys = box('keys', [0, 0, 0, 0], u32(entries.length), ...entries.map(([k]) => cat(u32(8 + k.length), ascii('mdta'), ascii(k))))
  const items = entries.map(([, v], i) => {
    const data = box('data', u32(type), u32(0), utf8(v))
    return cat(u32(8 + data.length), u32(i + 1), data)
  })
  return box('meta', fullBox ? [0, 0, 0, 0] : [], hdlr('mdta'), keys, box('ilst', ...items))
}
const KEY = 'com.apple.quicktime.creationdate'
const captured = (blob: Blob) => videoCaptureTimeFromHeader(blob, NOW)

describe('capture time: files as ffmpeg writes them', () => {
  it('an MP4 whose mvhd names the time', async () => {
    await expect(captured(fixture('dated.mp4'))).resolves.toBe(AT)
  })
  it('Apple’s creationdate key wins over the mvhd time, its offset applied, in a moov behind the media data', async () => {
    await expect(captured(fixture('dated-apple-keys.mov'))).resolves.toBe(AT)
  })
  it('a file whose muxer wrote no time, and a Matroska or WebM file, answer null', async () => {
    await expect(captured(fixture('landscape.mp4'))).resolves.toBeNull()
    await expect(captured(fixture('clip.webm'))).resolves.toBeNull()
    await expect(captured(fixture('clip.mkv'))).resolves.toBeNull()
  })
})

describe('capture time: built', () => {
  const track = trak(tkhd(64, 36), 'vide')
  const moovOf = (...parts: Uint8Array[]) => mp4(box('moov', ...parts, track))

  it('mvhd version 0 and version 1 (64-bit times)', async () => {
    await expect(captured(moovOf(mvhd(AT)))).resolves.toBe(AT)
    await expect(captured(moovOf(mvhd(AT, 1)))).resolves.toBe(AT)
  })

  it('Apple’s key in a plain atom, as Apple writes it, and after other keys: its value by its index', async () => {
    const meta = keyedMeta([['com.apple.quicktime.make', 'Apple'], [KEY, '2024-05-12T14:03:21+0200']])
    await expect(captured(moovOf(mvhd(Date.UTC(2020, 0, 1)), meta))).resolves.toBe(AT)
  })

  it('every zone form: Z, +HH:MM, a negative offset, fractions of a second', async () => {
    for (const [value, at] of [
      ['2024-05-12T12:03:21Z', AT],
      ['2024-05-12T14:03:21+02:00', AT],
      ['2024-05-12T07:03:21-0500', AT],
      ['2024-05-12T12:03:21.250Z', AT],
    ] as const) {
      await expect(captured(moovOf(keyedMeta([[KEY, value]], { fullBox: true }))), value).resolves.toBe(at)
    }
  })

  it('a key that names no instant falls back to the mvhd time: no zone, no such day, not UTF-8 text', async () => {
    const fallback = Date.UTC(2023, 0, 2)
    for (const meta of [
      keyedMeta([[KEY, '2024-05-12T14:03:21']]),
      keyedMeta([[KEY, '2024-02-30T10:00:00Z']]),
      keyedMeta([[KEY, '2024-05-12T14:03:21+0200']], { type: 23 }),
    ]) {
      await expect(captured(moovOf(mvhd(fallback), meta))).resolves.toBe(fallback)
    }
  })

  it('no time: zero, a time counted from 1970 in a field that counts from 1904, more than a day ahead', async () => {
    const zero = box('mvhd', new Array(100).fill(0))
    await expect(captured(moovOf(zero))).resolves.toBeNull()
    await expect(captured(moovOf(mvhd(-EPOCH_1904 * 1000 + AT)))).resolves.toBeNull() // reads as 1958
    await expect(captured(moovOf(mvhd(NOW + 2 * 86_400_000)))).resolves.toBeNull()
    await expect(captured(moovOf(mvhd(NOW + 3_600_000)))).resolves.toBe(NOW + 3_600_000) // a clock an hour off is still one
  })

  it('a key out of range does not hide a good mvhd time', async () => {
    await expect(captured(moovOf(mvhd(AT), keyedMeta([[KEY, '1904-01-01T00:00:00Z']])))).resolves.toBe(AT)
  })

  it('a damaged header answers null, never throws', async () => {
    const cut = cat(ftyp, box('moov', mvhd(AT))).subarray(0, ftyp.length + 20)
    await expect(captured(new Blob([cut as BlobPart]))).resolves.toBeNull()
    await expect(captured(new Blob([new Uint8Array(4) as BlobPart]))).resolves.toBeNull()
  })
})
