// @vitest-environment happy-dom
// uc.memory.upload — what `media.ts` seals from a photo, over the REAL EXIF reader (inbox #799). Every other suite here
// mocks `shared/image/exifTags`, which proves the mapping against a record a test wrote and never against one the
// library produced; `shared/image/exifTags.test.ts` pins the reader's tags, and this file the whole chain from the
// file's bytes to the two sealed tiers' contents.
//
// **It is also the parity proof of the reader swap.** Every expectation below is what the client produced under
// `exifr` (captured 2026-10-30 from the same fixtures, before the swap) — except where exifr read nothing: a WebP (it
// had no WebP reader) and a HEIC whose `ftyp` is 52 bytes (it refused any over 50, which iOS 18 writes on an
// adaptive-HDR photo). Those two now read exactly what their siblings read. Change an expectation only for a reason a
// comment names. The fixtures are generated (`python3 scripts/make_exif_fixture.py`), never a third-party photo.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL as FileURL } from 'node:url'
import { prepareMedia, readCaptureTime, type MediaDeriver, type MediaExif } from './media'
import { qualifiedAttrNodeNames } from '../../test/attrNodeName'

qualifiedAttrNodeNames() // XMP's attribute-form properties, as a browser parses them

const blobOf = (name: string) =>
  new Blob([readFileSync(fileURLToPath(new FileURL(`./__fixtures__/${name}`, import.meta.url))) as BlobPart])

/** No pixels are derived here: the geometry is not what this file is about, and the fixtures carry 1x1 bodies. */
const noDecode = {
  deriveImage: async () => { throw new Error('not decoded here') },
  deriveVideoPoster: async () => { throw new Error('not decoded here') },
} as unknown as MediaDeriver

const FILE_DATE = 1_000_000
/** The EXIF wall clock, read in the device's zone — as exifr read it, so the dates sealed so far stay comparable. */
const TAKEN = new Date(2021, 6, 15, 18, 4, 5).getTime()

/** The main fixture's panel bundle: every field the panel shows, each from its first-choice tag. */
const BUNDLE: MediaExif = {
  v: 1, make: 'Canon', model: 'EOS R', lens: 'RF 50mm F1.8 STM', focalLength: 50, focalLength35: 75, fNumber: 1.8,
  exposureTime: 0.002, exposureCompensation: -0.7, iso: 200, flash: 9, orientation: 6, software: 'veyq fixture 1.0',
  artist: 'A. Photographer', copyright: '(c) 2021 A. Photographer', rating: 4, title: 'Marienplatz',
  description: 'Marienplatz at golden hour', altitude: 519.4, imgDirection: 215.3, imgDirectionRef: 'T', gpsAccuracy: 4.8,
  w: 4032, h: 3024,
}
/** A container that carries the TIFF block and no XMP: the title is XMP-only, the description falls back to IFD0's. */
const { title: _xmpOnly, ...BUNDLE_WITHOUT_XMP } = BUNDLE

const SAMPLE = { capturedAt: TAKEN, gpsLat: 48.136944444444445, gpsLng: 11.575 }

async function sealedFrom(name: string, mediaType: string) {
  const blob = blobOf(name)
  const { meta } = await prepareMedia({ blob, mediaType, lastModified: FILE_DATE, name }, noDecode)
  return { meta, captureTime: await readCaptureTime(blob, mediaType, FILE_DATE) }
}

describe('media.ts over the real EXIF reader', () => {
  it.each([
    ['exif-sample.jpg', 'image/jpeg', BUNDLE],
    ['exif-sample.png', 'image/png', BUNDLE],
    ['exif-sample.tif', 'image/tiff', BUNDLE_WITHOUT_XMP],
    ['exif-sample-le.tif', 'image/tiff', BUNDLE_WITHOUT_XMP],
    ['exif-sample.avif', 'image/avif', BUNDLE_WITHOUT_XMP],
    ['exif-sample.heic', 'image/heic', BUNDLE_WITHOUT_XMP],
    // exifr read nothing of these two; each now reads what its sibling above reads
    ['exif-sample.webp', 'image/webp', BUNDLE],
    ['exif-sample-ftyp52.heic', 'image/heic', BUNDLE_WITHOUT_XMP],
  ])('%s: the date and the position in the listed tier, the rest in the panel bundle', async (name, mediaType, exif) => {
    expect(await sealedFrom(name, mediaType)).toEqual({
      meta: { dims: null, ...SAMPLE, exif },
      captureTime: { capturedAt: TAKEN, capturedAtSource: 'exif' },
    })
  })

  // inbox #808: Lightroom writes a PNG's capture time in XMP alone; no reader took it, so the photo sorted by its file date
  it('takes the XMP capture date of a PNG that carries no EXIF, not its modify date', async () => {
    const taken = new Date(2022, 7, 7, 6, 5, 4).getTime() // exif:DateTimeOriginal, the first of the three
    expect(await sealedFrom('exif-sample-xmpdate.png', 'image/png')).toEqual({
      meta: { dims: null, capturedAt: taken, exif: { v: 1, software: 'veyq fixture 1.0' } },
      captureTime: { capturedAt: taken, capturedAtSource: 'exif' },
    })
  })

  it('takes each second choice where the first is missing: lens make, XMP software and rating, IFD0 size and date', async () => {
    const modified = new Date(2020, 0, 2, 3, 4, 5).getTime()
    expect(await sealedFrom('exif-sample-fallbacks.jpg', 'image/jpeg')).toEqual({
      meta: {
        dims: null, capturedAt: modified, gpsLat: -33.867777777777775, gpsLng: -70.67, // south and west: negative
        exif: { v: 1, make: 'Nikon', lens: 'Nikkor', software: 'darktable 4.8', rating: 3, altitude: -12.5, w: 640, h: 480 },
      },
      captureTime: { capturedAt: modified, capturedAtSource: 'exif' },
    })
  })

  it('takes the digitised date when there is no original one, and an IPTC object name for a title', async () => {
    const digitised = new Date(2019, 2, 4, 5, 6, 7).getTime()
    expect(await sealedFrom('exif-sample-iptc.jpg', 'image/jpeg')).toEqual({
      meta: { dims: null, capturedAt: digitised, exif: { v: 1, title: 'Hofgarten' } },
      captureTime: { capturedAt: digitised, capturedAtSource: 'exif' },
    })
  })
})
