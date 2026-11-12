// An image's metadata tags, read on the device from its file - the one seam to the EXIF library, ExifReader
// (`mattiasw/ExifReader`, MPL-2.0; it replaced the abandoned `exifr`, inbox #799). Callers get [ExifTags]: the tags by
// their standard names, the library's shapes already undone (a rational is a number, an ASCII tag a string, a date
// epoch-ms, the GPS position signed decimal degrees), so what a caller maps from them is its own and a test can hand
// it a record without knowing the library. Formats: whatever the library reads - JPEG, TIFF, PNG, WebP, HEIC, AVIF
// among them, each pinned by `exifTags.test.ts` over a generated file (`scripts/make_exif_fixture.py`). Chosen over an
// own IFD reader because it is maintained (release 4.52.0, 2026-10-23), pure JavaScript, and needs no network, no
// `eval` and no WebAssembly (the CSP grants no `eval`, and WebAssembly only as a decision of its own per module, ADR
// 0127); owning the reader instead means owning a walker per container.
//
// How the library is called binds four things, which is why no other module imports it:
// 1. **Its ES module source, `exifreader/src/exif-reader.js`, never the package's main entry.** That entry is a webpack
//    UMD with literal `require('@xmldom/xmldom')`, `require('fs')`, `require('http')`, `require('https')` in it, which a
//    bundler follows: wherever the optional `@xmldom/xmldom` is installed (a plain `pnpm install` installs it) it would be
//    bundled. The source names none of them; it parses XMP with the browser's own `DOMParser`, which it is handed
//    (`XMP_PARSER`): under the page's Trusted Types a `DOMParser` refuses a plain string, so the parse goes through the
//    one policy's inert-XML door (ADR 0117) - without it every XMP tag would be lost to a refused, swallowed parse.
// 2. **Loaded on first use**, so the initial chunk does not carry it.
// 3. **Never the whole file.** At pick time every picked photo is read at once (`useOptimisticTiles`' probe), so what one
//    read holds is multiplied by the pick. The library gets a `File` and `length: 'auto'`: it reads 128 KiB, then on only
//    as far as the metadata reaches - a JPEG's head; a HEIC up to the end of its `Exif` item (inbox #801: where a phone
//    puts that item is unmeasured) - and holds what it read. A PNG or a WebP it can only walk through every byte before
//    the metadata, so it gets the first [CHUNKED_PREFIX] of one (exifr's options stopped its reader after six 64 KiB
//    chunks of any file; a PNG's `eXIf` usually precedes its pixels, a WebP's `EXIF` follows them, so a WebP larger than
//    that answers nothing). A bare TIFF names no container that mode finds; its first [TIFF_PREFIX] is handed over as
//    bytes (a camera's raw file is a TIFF of tens of MB, its tags at its head).
// 4. **Only the tags named here are parsed** (`includeTags`): no maker notes, ICC profile, thumbnail or Photoshop block,
//    and for a date only the three date tags - or, read only where those are missing, XMP's three capture dates
//    (`xmpDate`, inbox #808). What is not parsed cannot be mis-parsed.
// It fetches nothing: a URL or a path is the only input it would fetch for, and this module hands it neither.
import type { IncludeTagsOptions } from 'exifreader'
import { parseInertXml } from '../security/trustedTypes'

/** The tags a caller can read. Every field is optional: a tag the file does not carry, or carries unreadably, is absent. */
export type ExifTags = {
  // TIFF IFD0
  Make?: string
  Model?: string
  /** 1..8, raw (6 = rotate 90° clockwise), never the library's English phrase. */
  Orientation?: number
  Software?: string
  Artist?: string
  Copyright?: string
  /** The 0..5 star rating an editor wrote into IFD0. */
  Rating?: number
  ImageDescription?: string
  /** `DateTime`: when the file was last changed. */
  DateTime?: number
  ImageWidth?: number
  ImageLength?: number
  // Exif IFD
  ExposureTime?: number
  FNumber?: number
  ISOSpeedRatings?: number
  /** When the picture was taken. Every date is the camera's wall clock read in the device's time zone, epoch-ms. */
  DateTimeOriginal?: number
  /** When it was digitised - for a camera, the same moment. */
  DateTimeDigitized?: number
  /** EV, signed. */
  ExposureBiasValue?: number
  /** The raw bitfield: bit 0 is "fired", so 16 is truthy and means it did NOT. */
  Flash?: number
  FocalLength?: number
  FocalLengthIn35mmFilm?: number
  PixelXDimension?: number
  PixelYDimension?: number
  LensMake?: string
  LensModel?: string
  // GPS IFD
  /** Metres, unsigned; [GPSAltitudeRef] 1 means below sea level. */
  GPSAltitude?: number
  GPSAltitudeRef?: number
  GPSImgDirection?: number
  GPSImgDirectionRef?: string
  GPSHPositioningError?: number
  /** The position in signed decimal degrees (south and west negative). Both or neither - never half a coordinate. */
  latitude?: number
  longitude?: number
  // XMP
  /** When the picture was taken, as an editor's export may carry it in XMP only (a Lightroom PNG, inbox #808): the first
   *  of `exif:DateTimeOriginal`, `photoshop:DateCreated` and `xmp:CreateDate`, a wall clock as the EXIF dates are. */
  xmpCapturedAt?: number
  xmpCreatorTool?: string
  xmpRating?: number
  /** `dc:title` / `dc:description`: the `x-default` entry of the language alternative, else its first. */
  xmpTitle?: string
  xmpDescription?: string
  // IPTC
  iptcObjectName?: string
}

/** What to read: only the three date tags (the pick-time capture time), only XMP's capture dates (the pick-time
 *  fallback where the file has no EXIF date), or every tag [ExifTags] names. */
export type ExifScope = 'date' | 'xmpDate' | 'all'

const DATE_TAGS = ['DateTimeOriginal', 'DateTimeDigitized', 'DateTime']
/** XMP's capture dates, first choice first; never `xmp:ModifyDate` or `xmp:MetadataDate`, which an edit moves. */
const XMP_DATE_TAGS = ['DateTimeOriginal', 'DateCreated', 'CreateDate']

const ALL_EXIF_TAGS = [
  ...DATE_TAGS,
  'Make', 'Model', 'Orientation', 'Software', 'Artist', 'Copyright', 'Rating', 'ImageDescription', 'ImageWidth', 'ImageLength',
  'ExposureTime', 'FNumber', 'ISOSpeedRatings', 'ExposureBiasValue', 'Flash', 'FocalLength', 'FocalLengthIn35mmFilm',
  'PixelXDimension', 'PixelYDimension', 'LensMake', 'LensModel',
  'GPSAltitude', 'GPSAltitudeRef', 'GPSImgDirection', 'GPSImgDirectionRef', 'GPSHPositioningError',
  // GPSLatitude/GPSLongitude and their Refs are parsed for the `gps` group without being named: the library adds them.
]

const INCLUDE_TAGS: Record<ExifScope, IncludeTagsOptions> = {
  date: { exif: DATE_TAGS },
  xmpDate: { xmp: XMP_DATE_TAGS },
  all: { exif: ALL_EXIF_TAGS, gps: true, xmp: true, iptc: ['Object Name'] },
}

/** A tag as the library hands it back: `computed` for a TIFF tag, `value`/`description` for an XMP or IPTC one. */
type Tag = { value?: unknown; description?: unknown; computed?: unknown }
type Group = Record<string, Tag | undefined>
type Expanded = { exif?: Group; xmp?: Group; iptc?: Group; gps?: { Latitude?: unknown; Longitude?: unknown } }

const finite = (v: unknown): number | undefined => (Number.isFinite(v) ? (v as number) : undefined)

/** A TIFF ASCII tag. One that holds several NUL-separated strings (Copyright: photographer, then editor) gives its first. */
function text(tag: Tag | undefined): string | undefined {
  const c = tag?.computed
  if (typeof c === 'string') return c
  return Array.isArray(c) && typeof c[0] === 'string' ? c[0] : undefined
}

/** A TIFF number: SHORT/LONG as stored, RATIONAL divided out. A count above one (an array) is not one number. */
const num = (tag: Tag | undefined): number | undefined => finite(tag?.computed)

/**
 * An EXIF date - `YYYY:MM:DD HH:MM:SS`, a wall clock with no zone - as epoch-ms in the device's zone, which is what
 * exifr made of it and so what every capture time sealed so far carries. A date without a time is midnight. One a
 * camera writes when its clock was never set - blanks, or `0000:00:00 00:00:00` - is absent (exifr read both as a day
 * in 1899).
 */
function wallClock(tag: Tag | undefined): number | undefined {
  const s = text(tag)
  const m = s === undefined ? null : /^(\d{4})[:-](\d{2})[:-](\d{2})(?: (\d{2}):(\d{2}):(\d{2}))?/.exec(s.trim())
  return clockOf(m)
}

/**
 * An XMP date - ISO 8601, `YYYY-MM-DD` with an optional `Thh:mm[:ss[.s]]` and zone - as [wallClock] reads an EXIF one:
 * the wall clock in the device's zone, its fraction and zone dropped, so an export sorts where the camera's own file of
 * the same picture does (the client reads no EXIF offset either). A year or a month alone is no capture time.
 */
function xmpWallClock(tag: Tag | undefined): number | undefined {
  const s = xmpText(tag)
  return clockOf(s === undefined ? null : /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(s.trim()))
}

function clockOf(m: RegExpExecArray | null): number | undefined {
  if (!m) return undefined
  const [year, month, day, hours, minutes, seconds] = m.slice(1).map((part) => Number(part ?? 0))
  if (year === 0 || month === 0 || day === 0) return undefined
  return finite(new Date(year, month - 1, day, hours, minutes, seconds).getTime())
}

/** An XMP simple value or language alternative, as text: the `x-default` entry, else the first. */
function xmpText(tag: Tag | undefined): string | undefined {
  const v = tag?.value
  if (typeof v === 'string') return v
  if (!Array.isArray(v)) return undefined
  const entries = v as { value?: unknown; attributes?: Record<string, unknown> }[]
  const chosen = entries.find((e) => Object.values(e.attributes ?? {}).includes('x-default')) ?? entries[0]
  return typeof chosen?.value === 'string' ? chosen.value : undefined
}

/** An IPTC text tag: the library decodes its bytes into `description`. */
const iptcText = (tag: Tag | undefined): string | undefined => (typeof tag?.description === 'string' ? tag.description : undefined)

/** An XMP number, which XMP carries as text. */
function xmpNum(tag: Tag | undefined): number | undefined {
  const s = xmpText(tag)?.trim()
  return s ? finite(Number(s)) : undefined
}

/** Drop the absent fields, so a file with no readable tag answers `undefined` rather than an empty record. */
function present(tags: ExifTags): ExifTags | undefined {
  const kept = Object.fromEntries(Object.entries(tags).filter(([, v]) => v !== undefined)) as ExifTags
  return Object.keys(kept).length > 0 ? kept : undefined
}

/** [ExifTags] from the library's expanded groups - exported so a test can reach the shapes no generated file carries. */
export function tagsFrom(expanded: Expanded, scope: ExifScope): ExifTags | undefined {
  const exif = expanded.exif ?? {}
  const dates: ExifTags = {
    DateTimeOriginal: wallClock(exif.DateTimeOriginal),
    DateTimeDigitized: wallClock(exif.DateTimeDigitized),
    DateTime: wallClock(exif.DateTime),
  }
  if (scope === 'date') return present(dates)
  const xmp = expanded.xmp ?? {}
  const xmpCapturedAt = xmpWallClock(xmp.DateTimeOriginal) ?? xmpWallClock(xmp.DateCreated) ?? xmpWallClock(xmp.CreateDate)
  if (scope === 'xmpDate') return present({ xmpCapturedAt })
  const latitude = finite(expanded.gps?.Latitude)
  const longitude = finite(expanded.gps?.Longitude)
  const located = latitude !== undefined && longitude !== undefined
  return present({
    ...dates,
    Make: text(exif.Make),
    Model: text(exif.Model),
    Orientation: num(exif.Orientation),
    Software: text(exif.Software),
    Artist: text(exif.Artist),
    Copyright: text(exif.Copyright),
    Rating: num(exif.Rating),
    ImageDescription: text(exif.ImageDescription),
    ImageWidth: num(exif.ImageWidth),
    ImageLength: num(exif.ImageLength),
    ExposureTime: num(exif.ExposureTime),
    FNumber: num(exif.FNumber),
    ISOSpeedRatings: num(exif.ISOSpeedRatings),
    ExposureBiasValue: num(exif.ExposureBiasValue),
    Flash: num(exif.Flash),
    FocalLength: num(exif.FocalLength),
    FocalLengthIn35mmFilm: num(exif.FocalLengthIn35mmFilm),
    PixelXDimension: num(exif.PixelXDimension),
    PixelYDimension: num(exif.PixelYDimension),
    LensMake: text(exif.LensMake),
    LensModel: text(exif.LensModel),
    GPSAltitude: num(exif.GPSAltitude),
    GPSAltitudeRef: num(exif.GPSAltitudeRef),
    GPSImgDirection: num(exif.GPSImgDirection),
    GPSImgDirectionRef: text(exif.GPSImgDirectionRef),
    GPSHPositioningError: num(exif.GPSHPositioningError),
    latitude: located ? latitude : undefined,
    longitude: located ? longitude : undefined,
    xmpCapturedAt,
    xmpCreatorTool: xmpText(xmp.CreatorTool),
    xmpRating: xmpNum(xmp.Rating),
    xmpTitle: xmpText(xmp.title),
    xmpDescription: xmpText(xmp.description),
    iptcObjectName: iptcText(expanded.iptc?.['Object Name']),
  })
}

/** The most of a PNG or a WebP the library may read: it walks their chunks through every byte before the metadata. */
export const CHUNKED_PREFIX = 512 * 1024
/** The most of a bare TIFF read: its IFDs, where the tags are, start at its head. */
export const TIFF_PREFIX = 1024 * 1024

const ascii = (b: Uint8Array, at: number, text: string) => [...text].every((c, i) => b[at + i] === c.charCodeAt(0))

/** How a file is read, by its first bytes: a bare TIFF bounded and whole, a PNG or WebP bounded, any other in slices. */
function containerOf(head: Uint8Array): 'tiff' | 'chunked' | 'sliced' {
  const littleEndianTiff = ascii(head, 0, 'II') && head[2] === 0x2a && head[3] === 0
  const bigEndianTiff = ascii(head, 0, 'MM') && head[2] === 0 && head[3] === 0x2a
  if (littleEndianTiff || bigEndianTiff) return 'tiff'
  if (head[0] === 0x89 && ascii(head, 1, 'PNG')) return 'chunked'
  if (ascii(head, 0, 'RIFF') && ascii(head, 8, 'WEBP')) return 'chunked'
  return 'sliced'
}

/** The XMP block's parser: the browser's, through the policy; none where there is no `DOMParser` (the library then has none). */
const XMP_PARSER = () =>
  typeof DOMParser === 'undefined' ? undefined : { parseFromString: (xml: string) => parseInertXml(xml, 'application/xml') }

/**
 * Read `image`'s tags, or `undefined` when it carries none this reads. Rejects for a file the library cannot parse
 * (a damaged or unknown container) and for a read the browser refuses - the caller decides what that means.
 */
export async function readExifTags(image: Blob, scope: ExifScope): Promise<ExifTags | undefined> {
  const { load } = await import('exifreader/src/exif-reader.js')
  const includeTags = INCLUDE_TAGS[scope]
  const domParser = XMP_PARSER()
  const container = containerOf(new Uint8Array(await image.slice(0, 12).arrayBuffer()))
  if (container === 'tiff') {
    // `length: 'auto'` finds no container in a bare TIFF, so its head is handed over as bytes.
    const head = await image.slice(0, TIFF_PREFIX).arrayBuffer()
    return tagsFrom(load(head, { includeTags, domParser, computed: true, expanded: true }) as Expanded, scope)
  }
  const bounded = container === 'chunked' ? image.slice(0, CHUNKED_PREFIX) : image
  // A `File` is what makes the library read slices; a plain Blob would be taken for bytes and rejected.
  const file = bounded instanceof File ? bounded : new File([bounded], '')
  const expanded = await load(file, { includeTags, domParser, computed: true, expanded: true, includeOffsets: true, length: 'auto' })
  return tagsFrom(expanded as Expanded, scope)
}
