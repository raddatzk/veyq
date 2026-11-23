// @vitest-environment happy-dom
// PHASE 1 ONLY (cr-web-media-headers-on-the-core): records contracts/platform/media/v1-vectors.json from the web's own
// readers - imageHeader.ts, videoHeader.ts and exifTags.ts over ExifReader - so the core is held to the web's answers
// before it replaces them. Runs only with VEYQ_WRITE_VECTORS=1; deleted once the core writes the file.
import { describe, it } from 'vitest'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { imageSizeFromHeader } from './imageHeader'
import { videoCaptureTimeFromHeader, videoSizeFromHeader } from './videoHeader'
import { readExifTags, type ExifTags } from './exifTags'
import { qualifiedAttrNodeNames } from '../../test/attrNodeName'
import {
  type Part, type Entry, hex, assemble, cat, ascii, utf8, u16be, u16le, u32be, u32le, t, tiff, segment, exifSegment, xmpSegment,
  jpeg, box, ONE, IDENTITY, turned, tkhd, trak, ftyp, mvhd, keyedMeta, el, unknownSize, uint, EBML_ID as ID, riff, pngChunk,
} from '../../test/mediaBytes'

qualifiedAttrNodeNames()

const REPO = resolve(__dirname, '../../../../..')
const OUT = resolve(REPO, 'contracts/platform/media/v1-vectors.json')
const fixture = (path: string) => new Uint8Array(readFileSync(resolve(REPO, path)))
const photo = (name: string) => fixture(`src/web/src/features/memories/__fixtures__/${name}`)
const clip = (name: string) => fixture(`src/web/src/shared/image/__fixtures__/${name}`)

type Bounds = { end?: number; bytes?: number }
type Case = { rule: string; bytes: Part[]; bounds?: Bounds; expected?: unknown }
/** A case's parts; a run of 64 or more equal bytes inside the hex becomes a fill, so the file stays small. */
const parts = (...p: (Uint8Array | number[] | Part)[]): Part[] =>
  p.flatMap((x): Part[] => {
    if (typeof x === 'string' || (!Array.isArray(x) && !(x instanceof Uint8Array))) return [x as Part]
    const b = Array.from(x as ArrayLike<number>)
    const out: Part[] = []
    let from = 0
    for (let i = 0; i < b.length; ) {
      let j = i
      while (j < b.length && b[j] === b[i]) j++
      if (j - i >= 64) {
        if (i > from) out.push(hex(b.slice(from, i)))
        out.push({ fill: b[i], count: j - i })
        from = j
      }
      i = j
    }
    if (from < b.length) out.push(hex(b.slice(from)))
    return out
  })
const repeat = (b: Uint8Array, times: number): Part => ({ repeat: hex(b), times })
const fill = (count: number, value = 0) => ({ fill: value, count })

// --- images ------------------------------------------------------------------------------------------------------------

function exifOrientation(value: number, little = false): number[] {
  const o = { little }
  return exifSegment(tiff(o, [t.short(o, 0x0112, value)]))
}
const images: Case[] = [
  { rule: 'a JPEG baseline frame', bytes: parts(jpeg(4032, 3024)) },
  { rule: 'a progressive frame (SOF2)', bytes: parts(jpeg(640, 480, [], 0xc2)) },
  { rule: 'a lossless frame (SOF3) and an arithmetic one (SOF9)', bytes: parts(jpeg(33, 17, [], 0xc3)) },
  { rule: 'SOF9', bytes: parts(jpeg(33, 17, [], 0xc9)) },
  { rule: 'DHT (C4) is no frame header: the frame after it is', bytes: parts(jpeg(1200, 800, [segment(0xc4, new Array(30).fill(2))])) },
  { rule: 'after an APP0, a DQT and a DHT, which carry no size', bytes: parts(jpeg(1200, 800, [segment(0xe0, ascii('JFIF\0')), segment(0xdb, new Array(65).fill(1)), segment(0xc4, new Array(30).fill(2))])) },
  ...[1, 2, 3, 4].map((o) => ({ rule: `orientation ${o} keeps width and height`, bytes: parts(jpeg(4032, 3024, [exifOrientation(o)])) })),
  ...[5, 6, 7, 8].map((o) => ({ rule: `orientation ${o} swaps them, as the browser draws it turned`, bytes: parts(jpeg(4032, 3024, [exifOrientation(o)])) })),
  { rule: 'orientation 9 is none', bytes: parts(jpeg(4032, 3024, [exifOrientation(9)])) },
  { rule: 'a little-endian EXIF reads the same', bytes: parts(jpeg(4032, 3024, [exifOrientation(6, true)])) },
  { rule: 'the last EXIF orientation before the frame wins', bytes: parts(jpeg(4032, 3024, [exifOrientation(6), exifOrientation(1)])) },
  { rule: 'an APP1 that is not EXIF (XMP) turns nothing', bytes: parts(jpeg(4032, 3024, [segment(0xe1, ascii('http://ns.adobe.com/xap/1.0/\0<x/>'))])) },
  { rule: 'fill bytes and a restart marker before a segment', bytes: parts([0xff, 0xd8, 0xff, 0xff, 0xff, 0xd0], jpeg(320, 200).slice(2)) },
  {
    rule: 'a frame header past the first read (two large APP2 before it) is read by more windows, never the whole file',
    bytes: parts(jpeg(800, 600, [exifOrientation(6), segment(0xe2, new Array(65_000).fill(7)), segment(0xe2, new Array(65_000).fill(7))]), fill(4_000_000)),
    bounds: { bytes: 4 * 64 * 1024 },
  },
  { rule: 'a scan before any frame header', bytes: parts([0xff, 0xd8, 0xff, 0xda, 0, 2, 1]) },
  { rule: 'a segment length below two', bytes: parts([0xff, 0xd8, 0xff, 0xe0, 0, 1, 1, 2, 0x00, 0x00, 0, 0]) },
  { rule: 'a broken marker', bytes: parts([0xff, 0xd8, 0xff, 0xe0, 0, 4, 1, 2, 0x00, 0x00, 0, 0]) },
  { rule: 'cut inside the frame header', bytes: parts(jpeg(800, 600).slice(0, 9)) },
  { rule: 'the image ends before a frame', bytes: parts([0xff, 0xd8, 0xff, 0xd9]) },
  { rule: 'a frame header naming a zero height', bytes: parts(jpeg(640, 0)) },
  { rule: 'a PNG', bytes: parts([0x89, ...ascii('PNG\r\n\x1a\n'), ...u32be(13), ...ascii('IHDR'), ...u32be(1920), ...u32be(1080), 8, 6, 0, 0, 0]) },
  { rule: 'a PNG wider than 2^31', bytes: parts([0x89, ...ascii('PNG\r\n\x1a\n'), ...u32be(13), ...ascii('IHDR'), ...u32be(0x80000001), ...u32be(2), 8, 6, 0, 0, 0]) },
  { rule: 'a PNG whose first chunk is no IHDR', bytes: parts([0x89, ...ascii('PNG\r\n\x1a\n'), ...u32be(13), ...ascii('IHDX'), ...u32be(1920), ...u32be(1080), 8, 6, 0, 0, 0]) },
  { rule: 'a GIF89a', bytes: parts([...ascii('GIF89a'), ...u16le(320), ...u16le(240), 0, 0, 0]) },
  { rule: 'a GIF87a', bytes: parts([...ascii('GIF87a'), ...u16le(32), ...u16le(24), 0, 0, 0]) },
  { rule: 'a GIF naming a zero size', bytes: parts([...ascii('GIF89a'), 0, 0, 0, 0, 0]) },
  { rule: 'a lossy WebP (VP8)', bytes: parts(riff('VP8 ', [0, 0, 0, 0x9d, 0x01, 0x2a, ...u16le(1024), ...u16le(768), 0, 0])) },
  { rule: 'a lossy WebP\'s scale bits are not its size', bytes: parts(riff('VP8 ', [0, 0, 0, 0x9d, 0x01, 0x2a, ...u16le(1024 | 0xc000), ...u16le(768 | 0x4000), 0, 0])) },
  { rule: 'a VP8 without its start code', bytes: parts(riff('VP8 ', [0, 0, 0, 0x9d, 0x01, 0x2b, ...u16le(1024), ...u16le(768), 0, 0])) },
  { rule: 'a lossless WebP (VP8L)', bytes: parts(riff('VP8L', [0x2f, ...u32le((1024 - 1) | ((768 - 1) << 14)), 0, 0, 0, 0, 0])) },
  { rule: 'a VP8L without its signature', bytes: parts(riff('VP8L', [0x2e, ...u32le((1024 - 1) | ((768 - 1) << 14)), 0, 0, 0, 0, 0])) },
  { rule: 'an extended WebP (VP8X)', bytes: parts(riff('VP8X', [0, 0, 0, 0, 0xff, 0x0f, 0, 0x37, 0x0b, 0, 0, 0])) },
  { rule: 'a WebP of another first chunk', bytes: parts(riff('ALPH', [0, 0, 0, 0, 0xff, 0x0f, 0, 0x37, 0x0b, 0, 0, 0, 0, 0, 0, 0])) },
  { rule: 'a HEIC is the browser\'s', bytes: parts([0, 0, 0, 0x18, ...ascii('ftypheic'), 0, 0, 0, 0, ...ascii('mif1heic')]) },
  { rule: 'an AVIF is the browser\'s', bytes: parts([0, 0, 0, 0x1c, ...ascii('ftypavif'), 0, 0, 0, 0, ...ascii('avifmif1miaf')]) },
  { rule: 'no image at all', bytes: parts(ascii('hello, world, this is text')) },
  { rule: 'nothing', bytes: [] },
  ...['exif-sample.jpg', 'exif-sample.png', 'exif-sample.webp', 'exif-sample-fallbacks.jpg', 'exif-sample-iptc.jpg', 'exif-sample.heic', 'exif-sample.tif']
    .map((name) => ({ rule: `the fixture ${name}`, bytes: parts(photo(name)) })),
]

// --- videos ------------------------------------------------------------------------------------------------------------

const AT = Date.UTC(2024, 4, 12, 12, 3, 21)
const NOW = Date.UTC(2026, 9, 30)
const EPOCH_1904 = 2_082_844_800
const KEY = 'com.apple.quicktime.creationdate'
const largeBoxHead = (type: string, payload: number) => [...u32be(1), ...ascii(type), ...u32be(0), ...u32be(16 + payload)]
const mp4 = (...p: Uint8Array[]) => cat(ftyp, ...p)
const track = trak(tkhd(64, 36), 'vide')
const moovOf = (...p: Uint8Array[]) => mp4(box('moov', ...p, track))
const ebmlHeader = el(ID.ebml, el(ID.docType, ascii('webm')))
const audio = el(ID.entry, uint(ID.type, 2), el([0xe1], new Array(9).fill(0)))
const video = (...fields: Uint8Array[]) => el(ID.entry, uint(ID.type, 1), el(ID.video, uint(ID.pixelWidth, 1280), uint(ID.pixelHeight, 720), ...fields))
const cluster = unknownSize(ID.cluster, uint(ID.timecode, 0), new Uint8Array(512))
const webm = (...s: Uint8Array[]) => cat(ebmlHeader, unknownSize(ID.segment, ...s))

const videos: Case[] = [
  ...['landscape.mp4', 'portrait-moov-at-end.mov', 'clip.webm', 'clip.mkv', 'dated.mp4', 'dated-apple-keys.mov']
    .map((name) => ({ rule: `ffmpeg's ${name}`, bytes: parts(clip(name)) })),
  { rule: 'a sound and a subtitle track are skipped, whatever their tkhd names', bytes: parts(mp4(box('moov', box('mvhd', new Array(100).fill(0)), trak(tkhd(0, 0), 'soun'), trak(tkhd(640, 80), 'sbtl'), trak(tkhd(1920, 1080), 'vide')))) },
  { rule: 'a quarter turn swaps width and height', bytes: parts(mp4(box('moov', trak(tkhd(1920, 1080, turned(0, ONE, -ONE, 0)), 'vide')))) },
  { rule: 'the other quarter turn swaps them too', bytes: parts(mp4(box('moov', trak(tkhd(1920, 1080, turned(0, -ONE, ONE, 0)), 'vide')))) },
  { rule: 'a half turn does not', bytes: parts(mp4(box('moov', trak(tkhd(1920, 1080, turned(-ONE, 0, 0, -ONE)), 'vide')))) },
  { rule: 'a mirror is the browser\'s', bytes: parts(mp4(box('moov', trak(tkhd(1920, 1080, turned(-ONE, 0, 0, ONE)), 'vide')))) },
  { rule: 'a scale is the browser\'s', bytes: parts(mp4(box('moov', trak(tkhd(1920, 1080, turned(2 * ONE, 0, 0, 2 * ONE)), 'vide')))) },
  { rule: 'a version-1 tkhd, whose times are 64-bit', bytes: parts(mp4(box('moov', trak(tkhd(3840, 2160, IDENTITY, 1), 'vide')))) },
  { rule: 'a fractional size is rounded', bytes: parts(mp4(box('moov', trak(box('tkhd', [0, 0, 0, 3], new Array(20).fill(0), new Array(16).fill(0), IDENTITY.flatMap((n) => u32be(n)), u32be(Math.round(1279.5 * ONE)), u32be(Math.round(719.25 * ONE))), 'vide')))) },
  {
    rule: 'a moov behind 3 MiB of media data with a 64-bit size: two small reads, never the bytes between',
    bytes: parts(ftyp, largeBoxHead('mdat', 3 * 1024 * 1024), fill(3 * 1024 * 1024), box('moov', trak(tkhd(1280, 720), 'vide'))),
    bounds: { bytes: 2 * 64 * 1024 },
  },
  { rule: 'a last box that runs to the end of the file (size 0)', bytes: parts(ftyp, cat(u32be(0), box('moov', trak(tkhd(1280, 720), 'vide')).subarray(4))) },
  { rule: 'a track header that names no size', bytes: parts(mp4(box('moov', trak(tkhd(0, 0), 'vide')))) },
  { rule: 'no video track', bytes: parts(mp4(box('moov', trak(tkhd(0, 0), 'soun')))) },
  { rule: 'no moov', bytes: parts(ftyp, largeBoxHead('mdat', 64), fill(64)) },
  { rule: 'a box past its parent', bytes: parts(ftyp, u32be(4096), ascii('moov'), trak(tkhd(1920, 1080), 'vide')) },
  { rule: 'a truncated tkhd', bytes: parts((() => { const whole = cat(ftyp, box('moov', trak(tkhd(1920, 1080), 'vide'))); return whole.subarray(0, whole.length - 6) })()) },
  { rule: 'a JPEG is no container this reads', bytes: parts([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46]) },
  { rule: 'three bytes', bytes: parts([0, 0, 0]) },
  { rule: 'a file that does not open as an MP4 is not walked', bytes: parts(box('RIFF', ascii('AVI LIST')), box('moov', trak(tkhd(1920, 1080), 'vide'))) },
  { rule: 'an old QuickTime file opening with wide', bytes: parts(box('wide'), box('moov', trak(tkhd(320, 240), 'vide'))) },
  {
    rule: 'ten 100 KiB free boxes before the moov are within the 1 MiB read',
    bytes: parts(ftyp, ...Array.from({ length: 10 }, () => [cat(u32be(100 * 1024), ascii('free')), fill(100 * 1024 - 8)]).flat(), box('moov', trak(tkhd(640, 480), 'vide'))),
  },
  {
    rule: 'twenty are not: stops at 1 MiB read',
    bytes: parts(ftyp, ...Array.from({ length: 20 }, () => [cat(u32be(100 * 1024), ascii('free')), fill(100 * 1024 - 8)]).flat(), box('moov', trak(tkhd(640, 480), 'vide'))),
    bounds: { bytes: 1024 * 1024 },
  },
  { rule: 'a hundred tiny boxes are walked', bytes: parts(ftyp, repeat(box('free'), 100), box('moov', trak(tkhd(640, 480), 'vide'))) },
  { rule: 'five thousand are not: stops after 4,096', bytes: parts(ftyp, repeat(box('free'), 5000), box('moov', trak(tkhd(640, 480), 'vide'))) },
  { rule: 'Matroska: an unknown-size Segment, a sound track skipped, the pixel size', bytes: parts(webm(el(ID.info, new Array(20).fill(0)), el(ID.tracks, audio, video()), cluster)) },
  { rule: 'a display size in pixels is the size shown', bytes: parts(webm(el(ID.tracks, video(uint(ID.displayWidth, 1280), uint(ID.displayHeight, 960), uint(ID.displayUnit, 0))))) },
  { rule: 'a display width alone takes the pixel height', bytes: parts(webm(el(ID.tracks, video(uint(ID.displayWidth, 960))))) },
  { rule: 'a display size in another unit is the browser\'s', bytes: parts(webm(el(ID.tracks, video(uint(ID.displayWidth, 4), uint(ID.displayHeight, 3), uint(ID.displayUnit, 3))))) },
  { rule: 'a crop is the browser\'s', bytes: parts(webm(el(ID.tracks, video(uint(ID.cropTop, 8))))) },
  { rule: 'a zero crop is none', bytes: parts(webm(el(ID.tracks, video(uint(ID.cropTop, 0))))) },
  { rule: 'a projection is the browser\'s', bytes: parts(webm(el(ID.tracks, video(el(ID.projection, [0x76, 0x71, 0x81, 0]))))) },
  { rule: 'a track not typed video is skipped, even one with a Video element', bytes: parts(webm(el(ID.tracks, el(ID.entry, uint(ID.type, 2), el(ID.video, uint(ID.pixelWidth, 320), uint(ID.pixelHeight, 320))), video()))) },
  { rule: 'Tracks behind the first Cluster', bytes: parts(webm(el(ID.cluster, uint(ID.timecode, 0)), el(ID.tracks, video()))) },
  { rule: 'no video track before the Cluster', bytes: parts(webm(el(ID.tracks, audio), cluster)) },
  { rule: 'a Matroska size past its parent', bytes: parts(cat(ebmlHeader, el(ID.segment, el(ID.tracks, video())).subarray(0, 40))) },
  { rule: 'an EBML id of no length', bytes: parts(ebmlHeader, [0, 0, 0, 0, 0, 0, 0, 0]) },
  { rule: 'an mvhd version 0 time', bytes: parts(moovOf(mvhd(AT))) },
  { rule: 'an mvhd version 1 time (64-bit)', bytes: parts(moovOf(mvhd(AT, 1))) },
  { rule: 'Apple\'s key in a plain atom after other keys: its value by its index', bytes: parts(moovOf(mvhd(Date.UTC(2020, 0, 1)), keyedMeta([['com.apple.quicktime.make', 'Apple'], [KEY, '2024-05-12T14:03:21+0200']]))) },
  ...['2024-05-12T12:03:21Z', '2024-05-12T14:03:21+02:00', '2024-05-12T07:03:21-0500', '2024-05-12T12:03:21.250Z', '2024-02-29T12:00:00Z', '2024-13-12T12:03:21Z', '2024-00-12T12:03:21Z', '2024-05-12T24:00:00Z', '2024-05-12T12:60:00Z', '2024-05-12T12:03:21+2:00']
    .map((value) => ({ rule: `Apple's key as ${value} in a full box`, bytes: parts(moovOf(keyedMeta([[KEY, value]], { fullBox: true }))) })),
  ...([
    ['no zone', keyedMeta([[KEY, '2024-05-12T14:03:21']])],
    ['no such day', keyedMeta([[KEY, '2024-02-30T10:00:00Z']])],
    ['not UTF-8 text (type 23)', keyedMeta([[KEY, '2024-05-12T14:03:21+0200']], { type: 23 })],
  ] as const).map(([what, meta]) => ({ rule: `a key that names no instant falls back to the mvhd time: ${what}`, bytes: parts(moovOf(mvhd(Date.UTC(2023, 0, 2)), meta)) })),
  { rule: 'Apple\'s key in the udta\'s meta, as ffmpeg writes it', bytes: parts(moovOf(box('udta', keyedMeta([[KEY, '2024-05-12T12:03:21Z']], { fullBox: true })))) },
  { rule: 'an mvhd of zeros is no time', bytes: parts(moovOf(box('mvhd', new Array(100).fill(0)))) },
  { rule: 'a time counted from 1970 in a field that counts from 1904 (reads as 1958)', bytes: parts(moovOf(mvhd(-EPOCH_1904 * 1000 + AT))) },
  { rule: 'more than a day ahead of now is no time', bytes: parts(moovOf(mvhd(NOW + 2 * 86_400_000))) },
  { rule: 'a clock an hour ahead is still one', bytes: parts(moovOf(mvhd(NOW + 3_600_000))) },
  { rule: 'a key out of range does not hide a good mvhd time', bytes: parts(moovOf(mvhd(AT), keyedMeta([[KEY, '1904-01-01T00:00:00Z']]))) },
  { rule: 'a cut moov', bytes: parts(cat(ftyp, box('moov', mvhd(AT))).subarray(0, ftyp.length + 20)) },
  { rule: 'four bytes', bytes: parts([0, 0, 0, 0]) },
]

// --- EXIF ------------------------------------------------------------------------------------------------------------

const LE = { little: true }
const BE = { little: false }
/** A bare TIFF of IFD0 and an Exif IFD holding `exif`. */
const dated = (exif: Entry[], ifd0: Entry[] = []) => tiff(BE, ifd0, exif)
const original = (text: string | number[]) => [t.ascii(BE, 0x9003, text)]
const xmpPacket = (body: string) =>
  `<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">${body}</rdf:RDF></x:xmpmeta><?xpacket end="w"?>`
const NS = 'xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"'
const xmpJpeg = (body: string) => jpeg(1, 1, [xmpSegment(xmpPacket(body))])
const description = (attrs: string, children = '') => `<rdf:Description rdf:about="" ${NS} ${attrs}>${children}</rdf:Description>`

const MB4 = fill(4 * 1024 * 1024, 7)
const png = photo('exif-sample.png')
const webpFile = photo('exif-sample.webp')
const exif: Case[] = [
  ...['exif-sample.jpg', 'exif-sample.png', 'exif-sample.webp', 'exif-sample.tif', 'exif-sample-le.tif', 'exif-sample.avif', 'exif-sample.heic',
    'exif-sample-ftyp52.heic', 'exif-sample-xmpdate.png', 'exif-sample-fallbacks.jpg', 'exif-sample-iptc.jpg']
    .map((name) => ({ rule: `the fixture ${name}`, bytes: parts(photo(name)) })),
  (() => {
    const jpg = photo('exif-sample.jpg')
    const app0 = jpg.findIndex((b, i) => b === 0xff && jpg[i + 1] === 0xe0)
    return { rule: 'a JPEG with no metadata', bytes: parts(jpg.subarray(0, 2), jpg.subarray(app0)) }
  })(),
  { rule: 'bytes of no image (the library rejected them)', bytes: parts(fill(64, 7)) },
  { rule: 'a 4 MB JPEG is read in its first 128 KiB', bytes: parts(photo('exif-sample.jpg'), MB4), bounds: { end: 128 * 1024 } },
  {
    rule: 'a PNG whose pixels are 4 MB is read within its first 512 KiB, its eXIf found before them',
    bytes: parts(png.subarray(0, png.length - 12), [...u32be(4 * 1024 * 1024), ...ascii('IDAT')], MB4, [0, 0, 0, 0], png.subarray(png.length - 12)),
    bounds: { end: 512 * 1024 },
  },
  {
    rule: 'a WebP keeps its EXIF after its pixels: past its first 512 KiB nothing is found',
    bytes: parts(webpFile.subarray(0, 30), ascii('JUNK'), u32le(4 * 1024 * 1024), MB4, webpFile.subarray(30)),
    bounds: { end: 512 * 1024 },
  },
  { rule: 'a 4 MB little-endian TIFF is read within its first MiB', bytes: parts(photo('exif-sample-le.tif'), MB4), bounds: { end: 1024 * 1024 } },
  { rule: 'a 4 MB big-endian TIFF is read within its first MiB', bytes: parts(photo('exif-sample.tif'), MB4), bounds: { end: 1024 * 1024 } },
  ...([
    ['nothing it knows', new Uint8Array(12)],
    ['a RIFF that is no WebP', ascii('RIFF\0\0\0\0WAVE')],
    ['a WebP tag at the wrong place', ascii('RIFX\0\0\0\0WEBP')],
    ['II without the TIFF magic', ascii('IIxx')],
    ['II with half of it', [0x49, 0x49, 0x2a, 0x01]],
    ['II with the other half', [0x49, 0x49, 0x01, 0x00]],
    ['MM without the TIFF magic', [0x4d, 0x4d, 0x00, 0x00]],
    ['MM with the other half', [0x4d, 0x4d, 0x01, 0x2a]],
    ['a TIFF magic without its byte order', [0x49, 0x4d, 0x2a, 0x00]],
    ['a PNG signature without its first byte', ascii('xPNG')],
    ['a PNG first byte without its name', [0x89, 0x50, 0x4e, 0x00]],
  ] as const).map(([what, head]) => ({ rule: `${what}, then 4 MB: within 128 KiB`, bytes: parts(head as ArrayLike<number> as number[], MB4), bounds: { end: 128 * 1024 } })),
  // the wall clock's rules, as a DateTimeOriginal in a bare TIFF
  ...['2021:07:15 18:04:05', '2021:07:15', ' 2021-07-15 18:04:05 ', '2021-07-15T18:04:05', '2021:07:15 18:04', '    :  :     :  :  ', '0000:00:00 00:00:00',
    '0000:07:15 10:00:00', 'on 2021:07:15 18:04:05', '2021:00:15 10:00:00', '2021:07:00 10:00:00', '2021:13:15 10:00:00', '2021:02:30 25:61:61',
    '2021:07:15 18:04:05+02:00', '2021:07:15 18:04:05 trailing', '٢٠٢١:07:15 18:04:05', '2021:7:15 18:04:05']
    .map((text) => ({ rule: `an EXIF date ${JSON.stringify(text)}`, bytes: parts(dated(original(text))) })),
  { rule: 'the three dates, each in its IFD', bytes: parts(tiff(BE, [t.ascii(BE, 0x0132, '2020:01:02 03:04:05')], [t.ascii(BE, 0x9003, '2021:07:15 18:04:05'), t.ascii(BE, 0x9004, '2019:03:04 05:06:07')])) },
  { rule: 'a DateTimeOriginal in IFD0 is not the Exif IFD\'s', bytes: parts(tiff(BE, [t.ascii(BE, 0x9003, '2021:07:15 18:04:05')])) },
  { rule: 'a date of another type (a number) is none', bytes: parts(dated([t.long(BE, 0x9003, 20210715)])) },
  // a tag's shapes
  { rule: 'an ASCII tag of two NUL-separated strings gives its first', bytes: parts(tiff(BE, [t.ascii(BE, 0x8298, [...utf8('A. Photographer'), 0, ...utf8('B. Editor')])])) },
  { rule: 'a rational divided by zero, and a count of two, are no number', bytes: parts(dated([t.rational(BE, 0x829d, [18, 0]), t.short(BE, 0x8827, 100, 200)], [t.ascii(BE, 0x010f, 'Canon')])) },
  { rule: 'a signed rational', bytes: parts(dated([t.srational(BE, 0x9204, [-7, 10])], [t.ascii(BE, 0x010f, 'Canon')])) },
  { rule: 'a LONG width and a SHORT height', bytes: parts(tiff(BE, [t.long(BE, 0x0100, 70000), t.short(BE, 0x0101, 480)])) },
  { rule: 'an orientation and a rating', bytes: parts(tiff(LE, [t.short(LE, 0x0112, 8), t.short(LE, 0x4746, 5)])) },
  { rule: 'UTF-8 text in an ASCII tag', bytes: parts(tiff(BE, [t.ascii(BE, 0x013b, 'Jürgen Müller'), t.ascii(BE, 0x010f, 'Fujifilm')])) },
  { rule: 'Latin-1 bytes in an ASCII tag', bytes: parts(tiff(BE, [t.ascii(BE, 0x013b, [0x4a, 0xfc, 0x72, 0x67, 0x65, 0x6e])])) },
  { rule: 'an empty ASCII tag', bytes: parts(tiff(BE, [t.ascii(BE, 0x010f, ''), t.ascii(BE, 0x0110, 'X100')])) },
  { rule: 'trailing NULs and blanks', bytes: parts(tiff(BE, [t.ascii(BE, 0x010f, [...utf8('Canon  '), 0, 0]), t.ascii(BE, 0x0131, '  v1  ')])) },
  { rule: 'flash, focal lengths, exposure and the lens', bytes: parts(dated([t.short(BE, 0x9209, 16), t.rational(BE, 0x920a, [50, 1]), t.short(BE, 0xa405, 75), t.rational(BE, 0x829a, [1, 500]), t.ascii(BE, 0xa433, 'Canon'), t.ascii(BE, 0xa434, 'RF 50mm'), t.short(BE, 0xa002, 4032), t.long(BE, 0xa003, 3024)])) },
  { rule: 'a GPS position in the northern and eastern hemispheres', bytes: parts(tiff(BE, [], undefined, [t.ascii(BE, 1, 'N'), t.rational(BE, 2, [48, 1], [8, 1], [13, 1]), t.ascii(BE, 3, 'E'), t.rational(BE, 4, [11, 1], [34, 1], [30, 1])])) },
  { rule: 'a GPS position in the southern and western hemispheres', bytes: parts(tiff(LE, [], undefined, [t.ascii(LE, 1, 'S'), t.rational(LE, 2, [33, 1], [52, 1], [4, 1]), t.ascii(LE, 3, 'W'), t.rational(LE, 4, [70, 1], [40, 1], [12, 1])])) },
  { rule: 'half a coordinate is no position', bytes: parts(tiff(BE, [], undefined, [t.ascii(BE, 1, 'N'), t.rational(BE, 2, [48, 1], [8, 1], [13, 1])])) },
  { rule: 'a coordinate of two rationals is none', bytes: parts(tiff(BE, [], undefined, [t.ascii(BE, 1, 'N'), t.rational(BE, 2, [48, 1], [8, 1]), t.ascii(BE, 3, 'E'), t.rational(BE, 4, [11, 1], [34, 1], [30, 1])])) },
  { rule: 'a coordinate without its reference', bytes: parts(tiff(BE, [], undefined, [t.rational(BE, 2, [48, 1], [8, 1], [13, 1]), t.rational(BE, 4, [11, 1], [34, 1], [30, 1])])) },
  { rule: 'altitude below sea level, direction and accuracy', bytes: parts(tiff(BE, [], undefined, [t.byte(BE, 5, 1), t.rational(BE, 6, [125, 10]), t.ascii(BE, 0x10, 'M'), t.rational(BE, 0x11, [2153, 10]), t.rational(BE, 0x1f, [48, 10])])) },
  { rule: 'an IFD0 pointing past the block', bytes: parts(['M'.charCodeAt(0), 'M'.charCodeAt(0), 0, 42, 0, 0, 1, 0, 0, 0, 0, 0]) },
  { rule: 'a JPEG\'s TIFF in an APP1 after an APP0', bytes: parts(jpeg(1, 1, [segment(0xe0, ascii('JFIF\0')), exifSegment(dated(original('2021:07:15 18:04:05'), [t.ascii(BE, 0x010f, 'Canon')]))])) },
  { rule: 'two Exif APP1 segments', bytes: parts(jpeg(1, 1, [exifSegment(tiff(BE, [t.ascii(BE, 0x010f, 'First')])), exifSegment(tiff(BE, [t.ascii(BE, 0x0110, 'Second')]))])) },
  // XMP
  { rule: 'XMP attributes', bytes: parts(xmpJpeg(description('xmp:CreatorTool="Darktable 5.2" xmp:Rating="4" xmp:CreateDate="2022-08-08T07:06:05"'))) },
  { rule: 'XMP elements spanning lines', bytes: parts(xmpJpeg(description('', '\n  <xmp:Rating>\n    4\n  </xmp:Rating>\n  <dc:title>\n    <rdf:Alt>\n      <rdf:li xml:lang="de">Am See</rdf:li>\n      <rdf:li xml:lang="x-default">At the lake\n        by night &amp; fog</rdf:li>\n    </rdf:Alt>\n  </dc:title>\n  <dc:description><rdf:Alt><rdf:li xml:lang="de">nur eine Sprache</rdf:li></rdf:Alt></dc:description>\n'))) },
  { rule: 'an XMP Bag gives its first item', bytes: parts(xmpJpeg(description('', '<dc:title><rdf:Bag><rdf:li>one</rdf:li><rdf:li>two</rdf:li></rdf:Bag></dc:title>'))) },
  { rule: 'an XMP structure is no title', bytes: parts(xmpJpeg(description('', '<dc:title><rdf:Alt><rdf:li xml:lang="x-default"><b>bold</b></rdf:li></rdf:Alt></dc:title>'))) },
  { rule: 'an empty XMP alternative', bytes: parts(xmpJpeg(description('', '<dc:title><rdf:Alt></rdf:Alt></dc:title>'))) },
  { rule: 'an XMP entity and a character reference', bytes: parts(xmpJpeg(description('xmp:CreatorTool="A &amp; B &#233; &#x263A; &lt;x&gt; &quot;q&quot; &apos;a&apos;"'))) },
  { rule: 'XMP attributes in single quotes', bytes: parts(xmpJpeg(description("xmp:CreatorTool='single'"))) },
  { rule: 'an XMP rating with blanks around it', bytes: parts(xmpJpeg(description('xmp:Rating=" 5 "'))) },
  { rule: 'an XMP rating that is no number', bytes: parts(xmpJpeg(description('xmp:Rating="five"'))) },
  { rule: 'an XMP rating of blanks', bytes: parts(xmpJpeg(description('xmp:Rating="  "'))) },
  { rule: 'an XMP rating of -1 and a fraction', bytes: parts(xmpJpeg(description('xmp:Rating="-1"'))) },
  { rule: 'XMP rating as hex', bytes: parts(xmpJpeg(description('xmp:Rating="0x10"'))) },
  { rule: 'XMP capture dates: DateCreated before CreateDate, its fraction dropped', bytes: parts(xmpJpeg(description('photoshop:DateCreated="2022-08-08T07:06:05.000" xmp:CreateDate="2022-08-09T10:11:12"'))) },
  { rule: 'XMP exif:DateTimeOriginal first', bytes: parts(xmpJpeg(description('exif:DateTimeOriginal="2021-01-01T01:01:01" photoshop:DateCreated="2022-08-08T07:06:05"'))) },
  { rule: 'an XMP date with a zone: the wall clock', bytes: parts(xmpJpeg(description('xmp:CreateDate=" 2022-08-08T07:06:05+09:00 "'))) },
  { rule: 'an XMP date without seconds', bytes: parts(xmpJpeg(description('xmp:CreateDate="2022-08-08T07:06Z"'))) },
  { rule: 'an XMP day alone is midnight', bytes: parts(xmpJpeg(description('exif:DateTimeOriginal="2022-08-08"'))) },
  { rule: 'an unreadable first choice gives way to the next', bytes: parts(xmpJpeg(description('exif:DateTimeOriginal="2022" xmp:CreateDate="2022-08-08T07:06:05"'))) },
  { rule: 'a month alone and a modify date are none', bytes: parts(xmpJpeg(description('exif:DateTimeOriginal="2022-08" xmp:ModifyDate="2026-01-02T03:04:05"'))) },
  { rule: 'an XMP date of zeros is none', bytes: parts(xmpJpeg(description('xmp:CreateDate="0000-00-00T00:00:00"'))) },
  { rule: 'an XMP date in an element', bytes: parts(xmpJpeg(description('', '<xmp:CreateDate>2022-08-08T07:06:05</xmp:CreateDate>'))) },
  { rule: 'another prefix for the XMP namespace', bytes: parts(xmpJpeg(`<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/" xap:CreatorTool="Old Photoshop"></rdf:Description>`)) },
  { rule: 'a property of a foreign namespace by the same name', bytes: parts(xmpJpeg(`<rdf:Description rdf:about="" xmlns:foo="urn:example:other" foo:Rating="3" foo:CreatorTool="foreign"></rdf:Description>`)) },
  { rule: 'a default namespace element', bytes: parts(xmpJpeg(`<rdf:Description rdf:about=""><CreatorTool xmlns="http://ns.adobe.com/xap/1.0/">unprefixed</CreatorTool></rdf:Description>`)) },
  { rule: 'an attribute and an element of one name', bytes: parts(xmpJpeg(`${description('xmp:Rating="1"')}${description('', '<xmp:Rating>2</xmp:Rating>')}`)) },
  { rule: 'a CDATA title', bytes: parts(xmpJpeg(description('', '<dc:title><rdf:Alt><rdf:li xml:lang="x-default"><![CDATA[a <b> c]]></rdf:li></rdf:Alt></dc:title>'))) },
  { rule: 'a self-closing element is no value', bytes: parts(xmpJpeg(description('', '<xmp:CreatorTool/>'))) },
  { rule: 'a malformed packet', bytes: parts(xmpJpeg('<rdf:Description xmp:CreatorTool="x" <broken')) },
  { rule: 'two Descriptions', bytes: parts(xmpJpeg(`${description('xmp:CreatorTool="first"')}${description('xmp:Rating="2"')}`)) },
  { rule: 'XMP beside EXIF: both read, EXIF\'s date wins in the capture time', bytes: parts(jpeg(1, 1, [exifSegment(dated(original('2021:07:15 18:04:05'))), xmpSegment(xmpPacket(description('xmp:CreateDate="2022-08-08T07:06:05" xmp:CreatorTool="ed"')))])) },
  { rule: 'IPTC object name in a Photoshop APP13', bytes: parts(jpeg(1, 1, [segment(0xed, [...ascii('Photoshop 3.0'), 0, ...ascii('8BIM'), ...u16be(0x0404), 0, 0, ...u32be(13), 0x1c, 2, 5, ...u16be(8), ...ascii('Hofgarten'.slice(0, 8)), 0])])) },
  { rule: 'IPTC object name in UTF-8', bytes: parts(jpeg(1, 1, [segment(0xed, [...ascii('Photoshop 3.0'), 0, ...ascii('8BIM'), ...u16be(0x0404), 0, 0, ...u32be(5 + 6), 0x1c, 2, 5, ...u16be(6), ...utf8('Münch'), 0])])) },
  { rule: 'a PNG\'s eXIf', bytes: parts([0x89, ...ascii('PNG\r\n\x1a\n')], pngChunk('IHDR', [...u32be(1), ...u32be(1), 8, 6, 0, 0, 0]), pngChunk('eXIf', dated(original('2021:07:15 18:04:05'))), pngChunk('IEND', [])) },
  { rule: 'a PNG\'s compressed XMP iTXt', bytes: parts([0x89, ...ascii('PNG\r\n\x1a\n')], pngChunk('IHDR', [...u32be(1), ...u32be(1), 8, 6, 0, 0, 0]), pngChunk('iTXt', [...ascii('XML:com.adobe.xmp'), 0, 1, 0, 0, 0, 0x78, 0x9c, 3, 0, 0, 0, 0, 1]), pngChunk('IEND', [])) },
  { rule: 'a WebP\'s EXIF chunk with an Exif prefix', bytes: parts(riffChunks(['VP8X', [8, 0, 0, 0, 0, 0, 0, 0, 0, 0]], ['EXIF', [...ascii('Exif'), 0, 0, ...dated(original('2021:07:15 18:04:05'))]])) },
  { rule: 'a WebP\'s EXIF chunk without one', bytes: parts(riffChunks(['VP8X', [8, 0, 0, 0, 0, 0, 0, 0, 0, 0]], ['EXIF', [...dated(original('2021:07:15 18:04:05'))]])) },
]

function riffChunks(...chunks: [string, number[]][]): number[] {
  const body = chunks.flatMap(([kind, data]) => [...ascii(kind), ...u32le(data.length), ...data, ...(data.length & 1 ? [0] : [])])
  return [...ascii('RIFF'), ...u32le(4 + body.length), ...ascii('WEBP'), ...body]
}

// --- recording -------------------------------------------------------------------------------------------------------

const wall = (ms: number) => {
  const d = new Date(ms)
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  return `${p(d.getFullYear(), 4)}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
const DATES = new Set(['DateTimeOriginal', 'DateTimeDigitized', 'DateTime', 'xmpCapturedAt'])
const shown = (tags: ExifTags | undefined) =>
  tags === undefined ? null : Object.fromEntries(Object.entries(tags).sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => [k, DATES.has(k) ? wall(v as number) : v]))
const blobOf = (c: Case) => new Blob([assemble(c.bytes) as BlobPart])

describe.runIf(process.env.VEYQ_WRITE_VECTORS === '1')('write the media headers\' vectors from the web', () => {
  it('records every case', async () => {
    for (const c of images) c.expected = (await imageSizeFromHeader(blobOf(c))) ?? null
    for (const c of videos) c.expected = { size: (await videoSizeFromHeader(blobOf(c))) ?? null, capturedAt: await videoCaptureTimeFromHeader(blobOf(c), NOW) }
    for (const c of exif) {
      const answer = async (scope: 'date' | 'xmpDate' | 'all') => readExifTags(blobOf(c), scope).then(shown, () => 'rejects')
      c.expected = { date: await answer('date'), xmpDate: await answer('xmpDate'), all: await answer('all') }
    }
    const vectors = {
      format: 'veyq-media-headers-v1',
      about: 'The media headers (ADR 0155 decision 7.2, the fifth slice): an image\'s pixel size from its header, a video\'s display size and capture time from its container, and a photo\'s EXIF, XMP and IPTC tags. A case\'s bytes are parts: hex, or {fill, count} bytes of one value. A tag\'s date is the wall clock it names (YYYY-MM-DDTHH:MM:SS, read in the device\'s zone); a video\'s capture time is epoch ms against `now`. `bounds` caps what a read may reach: `end` the furthest byte, `bytes` the sum read.',
      now: NOW,
      images, videos, exif,
    }
    writeFileSync(OUT, JSON.stringify(vectors, null, 2) + '\n')
  }, 120_000)
})
void u16be
