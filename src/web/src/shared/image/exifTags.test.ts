// @vitest-environment happy-dom
// The EXIF reader seam over the REAL library (ExifReader) - the one suite that runs it. Every other suite mocks
// `readExifTags`, which proves a mapping and never that the library yields a tag from the bytes: a tag name invented at
// the keyboard passes every mocked test and is absent forever in production. The fixtures are generated, never a
// third-party photo (`python3 scripts/make_exif_fixture.py`), one TIFF block in each container the client claims.
// happy-dom, not node: XMP is parsed with the browser's `DOMParser`, which node lacks.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL as FileURL } from 'node:url'
import { deflateSync } from 'node:zlib'
import { CHUNKED_PREFIX, TIFF_PREFIX, readExifTags, tagsFrom, type ExifTags } from './exifTags'
import { qualifiedAttrNodeNames } from '../../test/attrNodeName'

qualifiedAttrNodeNames() // XMP's attribute-form properties, as a browser parses them

const bytesOf = (name: string) =>
  new Uint8Array(readFileSync(fileURLToPath(new FileURL(`../../features/memories/__fixtures__/${name}`, import.meta.url))))
const blobOf = (name: string) => new Blob([bytesOf(name) as BlobPart])

/** What the generator's main TIFF block carries, as the seam hands it back. Dates are wall clocks in the device's zone. */
const SAMPLE: ExifTags = {
  ImageDescription: 'Marienplatz at golden hour', Make: 'Canon', Model: 'EOS R', Orientation: 6,
  Software: 'veyq fixture 1.0', Artist: 'A. Photographer', Rating: 4, Copyright: '(c) 2021 A. Photographer',
  ExposureTime: 0.002, FNumber: 1.8, ISOSpeedRatings: 200, DateTimeOriginal: new Date(2021, 6, 15, 18, 4, 5).getTime(),
  ExposureBiasValue: -0.7, Flash: 9, FocalLength: 50, PixelXDimension: 4032, PixelYDimension: 3024,
  FocalLengthIn35mmFilm: 75, LensModel: 'RF 50mm F1.8 STM',
  latitude: 48 + 8 / 60 + 13 / 3600, longitude: 11 + 34 / 60 + 30 / 3600,
  GPSAltitude: 519.4, GPSAltitudeRef: 0, GPSImgDirection: 215.3, GPSImgDirectionRef: 'T', GPSHPositioningError: 4.8,
}
/** The XMP packet the JPEG, PNG and WebP carry beside it. */
const SAMPLE_XMP: ExifTags = { xmpRating: 4, xmpCreatorTool: 'veyq fixture 1.0', xmpTitle: 'Marienplatz', xmpDescription: 'Marienplatz at golden hour' }

describe('readExifTags over the generated fixtures', () => {
  // toEqual, not toMatchObject: a tag the seam does not name, or one that leaks from another group, reddens too.
  it.each([
    ['exif-sample.jpg', { ...SAMPLE, ...SAMPLE_XMP }],
    ['exif-sample.png', { ...SAMPLE, ...SAMPLE_XMP }],
    ['exif-sample.webp', { ...SAMPLE, ...SAMPLE_XMP }],
    ['exif-sample.tif', SAMPLE],
    ['exif-sample-le.tif', SAMPLE],
    ['exif-sample.avif', SAMPLE],
    ['exif-sample.heic', SAMPLE],
    ['exif-sample-ftyp52.heic', SAMPLE],
  ])('%s yields every tag the client reads', async (name, expected) => {
    expect(await readExifTags(blobOf(name), 'all')).toEqual(expected)
  })

  // inbox #799: iOS 18 writes a 52-byte `ftyp` on an adaptive-HDR HEIC; exifr gave up on any over 50 bytes.
  it('reads a HEIC whose ftyp box is 52 bytes', async () => {
    const heic = bytesOf('exif-sample-ftyp52.heic')
    expect(new DataView(heic.buffer).getUint32(0)).toBe(52)
    expect(new TextDecoder().decode(heic.subarray(4, 8))).toBe('ftyp')
    expect(await readExifTags(new Blob([heic as BlobPart]), 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
  })

  // inbox #808: an editor's PNG export with its capture time in XMP alone, as attributes (Lightroom's form)
  it('reads the XMP capture date of a PNG without EXIF, only when asked for it, and never the modify date', async () => {
    const png = blobOf('exif-sample-xmpdate.png')
    const xmpCapturedAt = new Date(2022, 7, 7, 6, 5, 4).getTime()
    expect(await readExifTags(png, 'date')).toBeUndefined()
    expect(await readExifTags(png, 'xmpDate')).toEqual({ xmpCapturedAt })
    expect(await readExifTags(png, 'all')).toEqual({ xmpCapturedAt, xmpCreatorTool: 'veyq fixture 1.0' })
  })

  it('reads each second-choice source where the first is missing, and the southern and western hemispheres', async () => {
    expect(await readExifTags(blobOf('exif-sample-fallbacks.jpg'), 'all')).toEqual({
      ImageWidth: 640, ImageLength: 480, Make: 'Nikon', DateTime: new Date(2020, 0, 2, 3, 4, 5).getTime(), LensMake: 'Nikkor',
      latitude: -(33 + 52 / 60 + 4 / 3600), longitude: -(70 + 40 / 60 + 12 / 3600), GPSAltitude: 12.5, GPSAltitudeRef: 1,
      xmpRating: 3, xmpCreatorTool: 'darktable 4.8',
      // and nothing from `dc:creator` / `dc:rights`: no field of ours reads them - the panel's author and copyright are
      // EXIF's only (owner 2026-10-30, uc.memory.gallery)
    })
    expect(await readExifTags(blobOf('exif-sample-iptc.jpg'), 'all')).toEqual({
      DateTimeDigitized: new Date(2019, 2, 4, 5, 6, 7).getTime(), iptcObjectName: 'Hofgarten',
    })
  })

  it('reads only the three date tags for a date', async () => {
    expect(await readExifTags(blobOf('exif-sample.jpg'), 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(await readExifTags(blobOf('exif-sample-fallbacks.jpg'), 'date')).toEqual({ DateTime: new Date(2020, 0, 2, 3, 4, 5).getTime() })
    expect(await readExifTags(blobOf('exif-sample-iptc.jpg'), 'date')).toEqual({ DateTimeDigitized: new Date(2019, 2, 4, 5, 6, 7).getTime() })
  })

  it('answers undefined for a file with no metadata, and rejects one that is no image', async () => {
    const jpeg = bytesOf('exif-sample.jpg')
    const app0 = jpeg.findIndex((b, i) => b === 0xff && jpeg[i + 1] === 0xe0) // the JFIF segment the body starts with
    const bare = new Uint8Array([...jpeg.subarray(0, 2), ...jpeg.subarray(app0)])
    expect(await readExifTags(new Blob([bare as BlobPart]), 'all')).toBeUndefined()
    await expect(readExifTags(new Blob([new Uint8Array(64).fill(7) as BlobPart]), 'all')).rejects.toThrow()
  })
})

/** A picked file that records every range read from it, and refuses to be read whole. */
class RecordingFile extends File {
  ends: number[] = []
  slice(start?: number, end?: number, type?: string): Blob {
    this.ends.push(end ?? this.size)
    return super.slice(start, end, type)
  }
  arrayBuffer(): Promise<ArrayBuffer> {
    throw new Error('the whole file was read')
  }
}

const MB4 = new Uint8Array(4 * 1024 * 1024).fill(7)
const join = (...parts: Uint8Array[]) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  parts.reduce((at, p) => (out.set(p, at), at + p.length), 0)
  return out
}
function crc32(b: Uint8Array): number {
  let c = ~0
  for (const x of b) {
    c ^= x
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}
function pngChunk(kind: string, data: Uint8Array): Uint8Array {
  const out = new Uint8Array(12 + data.length)
  new DataView(out.buffer).setUint32(0, data.length)
  out.set(new TextEncoder().encode(kind), 4)
  out.set(data, 8)
  new DataView(out.buffer).setUint32(8 + data.length, crc32(out.subarray(4, 8 + data.length)))
  return out
}

// At pick time every picked file is read at once, so a read that holds a whole photo is multiplied by the pick.
describe('readExifTags reads a bounded head, never the whole file', () => {
  it('reads a 4 MB JPEG in one 128 KiB slice', async () => {
    const file = new RecordingFile([join(bytesOf('exif-sample.jpg'), MB4) as BlobPart], 'big.jpg')
    expect(await readExifTags(file, 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(Math.max(...file.ends)).toBe(128 * 1024)
  })

  it('reads at most the bounded prefix of a PNG whose pixels are 4 MB, and still finds the eXIf before them', async () => {
    const png = bytesOf('exif-sample.png')
    const idat = pngChunk('IDAT', new Uint8Array(deflateSync(MB4, { level: 0 })))
    const iend = png.length - 12 // the generator's last chunk is IEND, its idat the one before
    const file = new RecordingFile([join(png.subarray(0, iend), idat, png.subarray(iend)) as BlobPart], 'big.png')
    expect(await readExifTags(file, 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(Math.max(...file.ends)).toBe(CHUNKED_PREFIX)
  })

  // A WebP keeps its EXIF after its pixels: one larger than the bounded prefix answers nothing (the header, point 3).
  it('reads at most the bounded prefix of a WebP, and finds nothing past it', async () => {
    const webp = bytesOf('exif-sample.webp')
    const vp8x = 12 + 8 + 10 // RIFF header, then the VP8X chunk; a 4 MB padding chunk goes right after it
    const pad = join(new TextEncoder().encode('JUNK'), new Uint8Array(new Uint32Array([MB4.length]).buffer), MB4)
    const file = new RecordingFile([join(webp.subarray(0, vp8x), pad, webp.subarray(vp8x)) as BlobPart], 'big.webp')
    expect(await readExifTags(file, 'date')).toBeUndefined()
    expect(Math.max(...file.ends)).toBe(CHUNKED_PREFIX)
  })

  // Which way a file is read is decided by its first bytes alone; a near miss of a magic number is any other file.
  it.each([
    ['nothing it knows', new Uint8Array(12)],
    ['a RIFF that is no WebP', new TextEncoder().encode('RIFF\0\0\0\0WAVE')],
    ['a WebP tag at the wrong place', new TextEncoder().encode('RIFX\0\0\0\0WEBP')],
    ['II without the TIFF magic', new TextEncoder().encode('IIxx')],
    ['II with half of it', new Uint8Array([0x49, 0x49, 0x2a, 0x01])],
    ['II with the other half', new Uint8Array([0x49, 0x49, 0x01, 0x00])],
    ['MM without the TIFF magic', new Uint8Array([0x4d, 0x4d, 0x00, 0x00])],
    ['MM with the other half', new Uint8Array([0x4d, 0x4d, 0x01, 0x2a])],
    ['a TIFF magic without its byte order', new Uint8Array([0x49, 0x4d, 0x2a, 0x00])],
    ['a PNG signature without its first byte', new TextEncoder().encode('xPNG')],
    ['a PNG first byte without its name', new Uint8Array([0x89, 0x50, 0x4e, 0x00])],
  ])('reads %s as any other file: one 128 KiB slice', async (_, head) => {
    const file = new RecordingFile([join(head, MB4) as BlobPart], 'unknown')
    await readExifTags(file, 'date').catch(() => undefined)
    expect(Math.max(...file.ends)).toBe(128 * 1024)
  })

  it('reads at most the bounded prefix of a large TIFF, either byte order', async () => {
    const le = new RecordingFile([join(bytesOf('exif-sample-le.tif'), MB4) as BlobPart], 'big-le.tif')
    expect(await readExifTags(le, 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(Math.max(...le.ends)).toBe(TIFF_PREFIX)
  })

  it('reads at most the bounded prefix of a large TIFF', async () => {
    const file = new RecordingFile([join(bytesOf('exif-sample.tif'), MB4) as BlobPart], 'big.tif')
    expect(await readExifTags(file, 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(Math.max(...file.ends)).toBe(TIFF_PREFIX)
  })
})

// The shapes no generated file carries, through the mapping alone.
describe('tagsFrom', () => {
  const tag = (computed: unknown) => ({ computed })

  it('gives a position only when both halves are there', () => {
    expect(tagsFrom({ gps: { Latitude: 48.1 } }, 'all')).toBeUndefined()
    expect(tagsFrom({ gps: { Longitude: 11.5 } }, 'all')).toBeUndefined()
    expect(tagsFrom({ gps: { Latitude: 48.1, Longitude: 11.5 } }, 'all')).toEqual({ latitude: 48.1, longitude: 11.5 })
  })

  it('takes the first string of an ASCII tag that holds several, and no number from an array or a division by zero', () => {
    expect(tagsFrom({ exif: { Copyright: tag(['A. Photographer', 'B. Editor']), FNumber: tag(null), ISOSpeedRatings: tag([100, 200]) } }, 'all'))
      .toEqual({ Copyright: 'A. Photographer' })
    expect(tagsFrom({ exif: { Make: tag({ 0: 'Canon' }), Model: tag([7]), FNumber: tag('1.8') } }, 'all')).toBeUndefined()
  })

  it('reads a wall clock without a time as midnight and a blank one as nothing', () => {
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('2021:07:15') } }, 'date')).toEqual({ DateTimeOriginal: new Date(2021, 6, 15).getTime() })
    // dashes, as some writers put them (exifr split on either)
    expect(tagsFrom({ exif: { DateTimeOriginal: tag(' 2021-07-15 18:04:05 ') } }, 'date')).toEqual({ DateTimeOriginal: SAMPLE.DateTimeOriginal })
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('    :  :     :  :  ') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('0000:00:00 00:00:00') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('0000:07:15 10:00:00') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('on 2021:07:15 18:04:05') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('2021:00:15 10:00:00') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag('2021:07:00 10:00:00') } }, 'date')).toBeUndefined()
    expect(tagsFrom({ exif: { DateTimeOriginal: tag(20210715) } }, 'date')).toBeUndefined()
  })

  it('gives a date scope the dates and nothing else', () => {
    expect(tagsFrom({ exif: { Make: tag('Canon'), DateTime: tag('2020:01:02 03:04:05') } }, 'date'))
      .toEqual({ DateTime: new Date(2020, 0, 2, 3, 4, 5).getTime() })
  })

  it('takes XMP\'s capture dates in their order as wall clocks, fraction and zone dropped, and a year or month alone as none', () => {
    const v = (value: string) => ({ value })
    const at = new Date(2022, 7, 8, 7, 6, 5).getTime()
    expect(tagsFrom({ xmp: { DateCreated: v('2022-08-08T07:06:05.000'), CreateDate: v('2022-08-09T10:11:12') } }, 'xmpDate'))
      .toEqual({ xmpCapturedAt: at })
    expect(tagsFrom({ xmp: { CreateDate: v(' 2022-08-08T07:06:05+09:00 ') } }, 'xmpDate')).toEqual({ xmpCapturedAt: at })
    expect(tagsFrom({ xmp: { CreateDate: v('2022-08-08T07:06Z') } }, 'xmpDate')).toEqual({ xmpCapturedAt: new Date(2022, 7, 8, 7, 6).getTime() })
    expect(tagsFrom({ xmp: { DateTimeOriginal: v('2022-08-08') } }, 'xmpDate')).toEqual({ xmpCapturedAt: new Date(2022, 7, 8).getTime() })
    // an unreadable first choice gives way to the next
    expect(tagsFrom({ xmp: { DateTimeOriginal: v('2022'), CreateDate: v('2022-08-08T07:06:05') } }, 'xmpDate')).toEqual({ xmpCapturedAt: at })
    expect(tagsFrom({ xmp: { DateTimeOriginal: v('2022-08'), ModifyDate: v('2026-01-02T03:04:05') } }, 'xmpDate')).toBeUndefined()
    expect(tagsFrom({ xmp: { CreateDate: v('0000-00-00T00:00:00') } }, 'xmpDate')).toBeUndefined()
    // the date scope never answers from XMP: the capture time asks for it only where EXIF has none
    expect(tagsFrom({ xmp: { CreateDate: v('2022-08-08T07:06:05') } }, 'date')).toBeUndefined()
  })

  it('takes the x-default entry of an XMP language alternative, else its first, and an XMP number only when it is one', () => {
    const alt = (...entries: [string, string][]) => ({ value: entries.map(([lang, value]) => ({ value, attributes: { 'xml:lang': lang } })) })
    expect(tagsFrom({ xmp: { title: alt(['de', 'Rathaus'], ['x-default', 'Town hall']), description: alt(['de', 'Abend']) } }, 'all'))
      .toEqual({ xmpTitle: 'Town hall', xmpDescription: 'Abend' })
    // a structured value is not text, and an empty alternative has no entry to take
    expect(tagsFrom({ xmp: { title: { value: [{ value: { nested: 'x' }, attributes: {} }] }, description: { value: [] } } }, 'all')).toBeUndefined()
    expect(tagsFrom({ xmp: { Rating: { value: ' 5 ' } } }, 'all')).toEqual({ xmpRating: 5 })
    expect(tagsFrom({ xmp: { Rating: { value: 'five' } } }, 'all')).toBeUndefined()
    expect(tagsFrom({ xmp: { Rating: { value: '  ' } } }, 'all')).toBeUndefined()
  })
})
