// uc.memory.upload — **the one test in this suite that runs the REAL `exifr`.**
//
// Every other test mocks it (`media.test.ts:12`), which proves our *mapping* and never that the library yields
// a tag under the options we pass. A tag name invented at the keyboard therefore passes every mocked test and
// is `undefined` forever in production. This file is the gap, and it caught four such guesses on its first run
// — see the assertions below, each of which is a name or a shape the plan got wrong.
//
// The fixture is generated, never a third-party photo: `python3 scripts/make_exif_fixture.py` writes
// `__fixtures__/exif-sample.jpg` from the JPEG/TIFF spec with no dependency, so it is auditable byte for byte
// and carries exactly the tags the client claims to read. Re-run it when that list changes.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import exifr from 'exifr'
import { EXIF_PARSE_OPTIONS } from './media'

const FIXTURE = fileURLToPath(new URL('./__fixtures__/exif-sample.jpg', import.meta.url))

describe('exifr, un-mocked, over the generated fixture', () => {
  it('yields every tag the client reads, under the options the client passes', async () => {
    const raw = (await exifr.parse(readFileSync(FIXTURE), EXIF_PARSE_OPTIONS)) as Record<string, unknown>
    expect(raw).toBeTruthy()

    // camera + lens, already read today
    expect(raw).toMatchObject({ Make: 'Canon', Model: 'EOS R', LensModel: 'RF 50mm F1.8 STM' })
    expect(raw).toMatchObject({ FocalLength: 50, FNumber: 1.8, ISO: 200, ExposureTime: 0.002 })
    expect(raw.DateTimeOriginal).toBeInstanceOf(Date)
    expect(raw).toMatchObject({ ExifImageWidth: 4032, ExifImageHeight: 3024 })

    // (1) The plan said `ExposureBiasValue`. There is no such key — exifr names it `ExposureCompensation`,
    // and a mocked test would never have noticed.
    expect(raw.ExposureBiasValue).toBeUndefined()
    expect(raw.ExposureCompensation).toBe(-0.7)

    // (2) `Flash` is only the bitfield the plan assumed with `translateValues: false`. Under exifr's default
    // it is the English string "Flash fired, compulsory flash mode", and `Flash & 0x1` on a string is 0 —
    // i.e. "never fired", always. The option set is what makes the bit test legal.
    expect(raw.Flash).toBe(9)

    // (3) The same option revives `Orientation` as the NUMBER the existing code has always assumed. Under the
    // default it is "Rotate 90 CW", so `finiteNum(raw.Orientation)` yielded undefined and the field this
    // client has sealed since cr-0064 was never once populated.
    expect(raw.Orientation).toBe(6)

    // (4) GPSAltitudeRef is neither a number nor a string: exifr hands back a byte-indexed object.
    expect(raw.GPSAltitude).toBe(519.4)
    expect(raw.GPSAltitudeRef).toMatchObject({ 0: 0 })
    expect(raw).toMatchObject({ GPSImgDirection: 215.3, GPSImgDirectionRef: 'T', GPSHPositioningError: 4.8 })
    expect(raw.latitude).toBeCloseTo(48.136944, 5)
    expect(raw.longitude).toBeCloseTo(11.575, 5)

    // provenance: `Software`/`Artist`/`Copyright` from IFD0, `Rating` and the titles from XMP — which is what
    // proves `xmp: true` is actually in the option set rather than assumed.
    expect(raw).toMatchObject({ Software: 'veyq fixture 1.0', Artist: 'A. Photographer' })
    expect(raw.Copyright).toContain('A. Photographer')
    expect(raw.Rating).toBe(4)
    expect(raw.title).toMatchObject({ value: 'Marienplatz' })
    expect(raw.description).toMatchObject({ value: 'Marienplatz at golden hour' })
  })
})
