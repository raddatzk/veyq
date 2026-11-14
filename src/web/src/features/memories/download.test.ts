// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { sniffMediaType, extensionOf, namedDownload } from './download'

const bytes = (...b: number[]) => new Uint8Array(b)
const ascii = (s: string) => Uint8Array.from(s, (c) => c.charCodeAt(0))

/** A minimal EBML header: the 4-byte magic, then a `DocType` element (id 42 82, single-byte-vint length) = `doc`. */
const ebml = (doc: string) => {
  const head = [0x1a, 0x45, 0xdf, 0xa3, 0x42, 0x82, 0x80 | doc.length]
  return Uint8Array.from([...head, ...ascii(doc)])
}

describe('sniffMediaType (full MIME — an untyped upload\'s blob meta type, the <video> branch, #104/#105/#397)', () => {
  it('reads the full MIME for the image / document signatures', () => {
    expect(sniffMediaType(bytes(0xff, 0xd8, 0xff, 0xe0))).toBe('image/jpeg')
    expect(sniffMediaType(bytes(0x89, 0x50, 0x4e, 0x47))).toBe('image/png')
    expect(sniffMediaType(bytes(0x47, 0x49, 0x46, 0x38))).toBe('image/gif')
    expect(sniffMediaType(bytes(0x25, 0x50, 0x44, 0x46))).toBe('application/pdf')

    const webp = new Uint8Array(16)
    webp.set(ascii('RIFF'), 0)
    webp.set(ascii('WEBP'), 8)
    expect(sniffMediaType(webp)).toBe('image/webp')
  })

  it('distinguishes the ISO-BMFF brands: heic (image), quicktime (.mov), else mp4', () => {
    const bmff = (brand: string) => {
      const b = new Uint8Array(16)
      b.set(ascii('ftyp'), 4)
      b.set(ascii(brand), 8)
      return b
    }
    expect(sniffMediaType(bmff('heic'))).toBe('image/heic')
    expect(sniffMediaType(bmff('qt  '))).toBe('video/quicktime')
    expect(sniffMediaType(bmff('isom'))).toBe('video/mp4')
    expect(sniffMediaType(bmff('mp42'))).toBe('video/mp4')
    // An M4A is audio, never a video (ADR 0135 decision 3). Lever: drop the M4A branch - all three read video/mp4.
    expect(['M4A ', 'M4B ', 'M4P '].map((b) => sniffMediaType(bmff(b)))).toEqual(['audio/mp4', 'audio/mp4', 'audio/mp4'])
    expect(extensionOf(sniffMediaType(bmff('M4A ')))).toBe('m4a')
  })

  it('handles EBML video — WebM vs Matroska via the DocType (no ftyp box, the ftyp-only trap)', () => {
    expect(sniffMediaType(ebml('webm'))).toBe('video/webm')
    expect(sniffMediaType(ebml('matroska'))).toBe('video/x-matroska')
    // EBML magic but an unreadable/absent DocType → the common playable case
    expect(sniffMediaType(bytes(0x1a, 0x45, 0xdf, 0xa3, 0x00, 0x00))).toBe('video/webm')
  })

  it('is undefined for an unknown / too-short signature', () => {
    expect(sniffMediaType(bytes(0x00, 0x01))).toBeUndefined()
    expect(sniffMediaType(new Uint8Array(0))).toBeUndefined()
  })
})

describe('extensionOf over a sniffed type (every type the sniff returns names an extension — core MIME_TO_EXT)', () => {
  const sniffedExt = (b: Uint8Array) => extensionOf(sniffMediaType(b)) ?? 'bin'

  it('recognises a known set of image / video / document signatures', () => {
    expect(sniffedExt(bytes(0xff, 0xd8, 0xff, 0xe0))).toBe('jpg')
    expect(sniffedExt(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a))).toBe('png')
    expect(sniffedExt(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))).toBe('gif')
    expect(sniffedExt(bytes(0x25, 0x50, 0x44, 0x46, 0x2d))).toBe('pdf')
  })

  it('reads the brand for RIFF/WEBP and ISO-BMFF (mp4 / heic) containers', () => {
    const webp = new Uint8Array(16)
    webp.set(ascii('RIFF'), 0)
    webp.set(ascii('WEBP'), 8)
    expect(sniffedExt(webp)).toBe('webp')

    const mp4 = new Uint8Array(16)
    mp4.set(ascii('ftyp'), 4)
    mp4.set(ascii('isom'), 8)
    expect(sniffedExt(mp4)).toBe('mp4')

    const heic = new Uint8Array(16)
    heic.set(ascii('ftyp'), 4)
    heic.set(ascii('heic'), 8)
    expect(sniffedExt(heic)).toBe('heic')
  })

  it('derives the video extensions, including EBML WebM / MKV', () => {
    expect(sniffedExt(ebml('webm'))).toBe('webm')
    expect(sniffedExt(ebml('matroska'))).toBe('mkv')
  })

  it('an unknown signature, an unnamed type or a prototype key names none', () => {
    expect(sniffedExt(bytes(0x00, 0x01))).toBe('bin')
    expect(extensionOf('image/tiff')).toBeUndefined()
    expect(extensionOf('toString')).toBeUndefined()
    expect(extensionOf(undefined)).toBeUndefined()
  })
})

describe('namedDownload', () => {
  it('combines the (sanitised) Memory name + a 1-based index + the extension', () => {
    expect(namedDownload('Rom 2026', 0, 'jpg')).toBe('Rom 2026 1.jpg')
    expect(namedDownload('Trip/2026', 2, 'png')).toBe('Trip 2026 3.png')
  })

  it('falls back to "memory" when the name reduces to nothing', () => {
    expect(namedDownload('   ', 0, 'bin')).toBe('memory 1.bin')
  })
})
