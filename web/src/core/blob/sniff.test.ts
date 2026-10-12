import { describe, it, expect } from 'vitest'
import {
  claimsSvg, claimsText, extensionOf, sniffImage, sniffPdf, sniffStream, sniffSvg, sniffText,
} from './sniff'

const ascii = (s: string) => Uint8Array.from(s, (c) => c.charCodeAt(0))
const cat = (...parts: (Uint8Array | number[])[]) => Uint8Array.from(parts.flatMap((p) => [...p]))
const box = (brand: string) => cat([0, 0, 0, 0x18], ascii('ftyp'), ascii(brand), [0, 0, 0, 0], ascii('isom'))
const utf8 = (s: string) => new TextEncoder().encode(s)

describe('sniffImage: the format comes from the bytes', () => {
  const bmp = new Uint8Array(30)
  bmp.set([0x42, 0x4d], 0)
  bmp[14] = 40
  it.each([
    ['jpeg', cat([0xff, 0xd8, 0xff, 0xe0], new Array(8).fill(0)), 'image/jpeg'],
    ['png', cat([0x89], ascii('PNG\r\n'), [0x1a, 0x0a], new Array(8).fill(0)), 'image/png'],
    ['gif87a', ascii('GIF87a'), 'image/gif'],
    ['gif89a', ascii('GIF89a'), 'image/gif'],
    ['webp', cat(ascii('RIFF'), [0, 0, 0, 0], ascii('WEBP')), 'image/webp'],
    ['avif', box('avif'), 'image/avif'],
    ['avif sequence', box('avis'), 'image/avif'],
    ['heic', box('heic'), 'image/heic'],
    ['heif (mif1)', box('mif1'), 'image/heic'],
    ['bmp', bmp, 'image/bmp'],
  ])('%s', (_name, bytes, type) => {
    expect(sniffImage(bytes)).toBe(type)
  })

  it.each([
    ['text starting with BM', utf8('BMW is a car maker. '.repeat(3))],
    ['an mp4', box('isom')],
    ['a RIFF wave', cat(ascii('RIFF'), [0, 0, 0, 0], ascii('WAVE'))],
    ['bytes of no format', Uint8Array.from({ length: 64 }, (_, i) => 1 + ((i * 37 + 11) % 200))],
    ['empty', new Uint8Array(0)],
    ['a short png prefix', cat([0x89], ascii('PNG'))],
  ])('is not an image: %s', (_name, bytes) => {
    expect(sniffImage(bytes)).toBeUndefined()
  })
})

describe('sniffText: valid UTF-8 (or BOM-marked UTF-16) with no control bytes', () => {
  it('accepts plain, multi-byte, tab/newline/ANSI-escape text as utf-8', () => {
    expect(sniffText(utf8('Hallo Welt\n\tÄÖÜ ß 日本語 😀\r\n\u001b[31mred'))).toBe('utf-8')
  })
  it('accepts utf-8 with a BOM', () => {
    expect(sniffText(cat([0xef, 0xbb, 0xbf], utf8('with bom')))).toBe('utf-8')
  })
  it('does not fail on a multi-byte sequence the prefix cut in half', () => {
    expect(sniffText(utf8('abc😀').subarray(0, 5))).toBe('utf-8')
  })
  it('a sequence cut at the end is an error only when the bytes are the whole file', () => {
    const cut = Uint8Array.from([0x63, 0x61, 0x66, 0xe9])
    expect(sniffText(cut)).toBe('utf-8')
    expect(sniffText(cut, true)).toBeUndefined()
  })
  it('accepts utf-16 only when a byte-order mark says so', () => {
    expect(sniffText(cat([0xff, 0xfe], [0x68, 0, 0x69, 0]))).toBe('utf-16le')
    expect(sniffText(cat([0xfe, 0xff], [0, 0x68, 0, 0x69]))).toBe('utf-16be')
    expect(sniffText(cat([0x68, 0, 0x69, 0]))).toBeUndefined() // the same bytes without a BOM hold NULs
  })
  it.each([
    ['a NUL byte', cat(utf8('text'), [0], utf8('more'))],
    ['another control byte', cat(utf8('text'), [0x01], utf8('more'))],
    ['windows-1252 (invalid utf-8)', Uint8Array.from([0x63, 0x61, 0x66, 0xe9, 0x20, 0x6f, 0x6b])],
    ['a png', cat([0x89], ascii('PNG\r\n'), [0x1a, 0x0a])],
    ['a lone continuation byte', Uint8Array.from([0x80, 0x41])],
  ])('rejects %s', (_name, bytes) => {
    expect(sniffText(bytes)).toBeUndefined()
  })
})

describe('sniffSvg: markup whose first element is <svg>', () => {
  it.each([
    ['bare', '<svg xmlns="http://www.w3.org/2000/svg"/>'],
    ['with xml prolog, comment and doctype', '<?xml version="1.0"?>\n<!-- made by hand -->\n<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "x.dtd">\n<svg width="1"></svg>'],
    ['after leading blanks', '\n  \t<svg></svg>'],
    ['upper case', '<SVG></SVG>'],
  ])('%s', (_n, src) => {
    expect(sniffSvg(utf8(src))).toBe(true)
  })
  it('accepts a BOM', () => {
    expect(sniffSvg(cat([0xef, 0xbb, 0xbf], utf8('<svg/>')))).toBe(true)
  })
  it.each([
    ['an html page', '<!doctype html><html><body><svg></svg></body></html>'],
    ['a script', '<script>alert(1)</script><svg/>'],
    ['svg-looking prose', 'the <svg> element is'],
    ['a name that merely starts alike', '<svgfoo/>'],
    ['binary', '\u0000<svg/>'],
  ])('is not svg: %s', (_n, src) => {
    expect(sniffSvg(utf8(src))).toBe(false)
  })
})

describe('sniffStream: the container of a video or audio', () => {
  const ebml = (doc: string) => cat([0x1a, 0x45, 0xdf, 0xa3], [0x42, 0x82, 0x80 | doc.length], ascii(doc))
  it.each([
    ['mp4 (isom)', box('isom'), 'video', 'video/mp4'],
    ['mp4 (mp42)', box('mp42'), 'video', 'video/mp4'],
    ['quicktime', box('qt  '), 'video', 'video/quicktime'],
    ['m4a', box('M4A '), 'audio', 'audio/mp4'],
    ['webm', ebml('webm'), 'video', 'video/webm'],
    ['matroska', ebml('matroska'), 'video', 'video/x-matroska'],
    ['ogg vorbis/opus', cat(ascii('OggS'), new Array(30).fill(0), ascii('vorbis')), 'audio', 'audio/ogg'],
    ['ogg theora', cat(ascii('OggS'), new Array(30).fill(0), [0x80], ascii('theora')), 'video', 'video/ogg'],
    ['wav', cat(ascii('RIFF'), [0, 0, 0, 0], ascii('WAVE')), 'audio', 'audio/wav'],
    ['flac', ascii('fLaC'), 'audio', 'audio/flac'],
    ['mp3 with ID3', ascii('ID3\u0004'), 'audio', 'audio/mpeg'],
    ['mp3 frame (MPEG1 layer III)', Uint8Array.from([0xff, 0xfb, 0x90, 0x00]), 'audio', 'audio/mpeg'],
    ['aac (ADTS)', Uint8Array.from([0xff, 0xf1, 0x50, 0x80]), 'audio', 'audio/aac'],
  ])('%s', (_n, bytes, element, type) => {
    expect(sniffStream(bytes)).toEqual({ element, type })
  })
  it.each([
    ['an avif image', box('avif')],
    ['a heic image', box('heic')],
    ['a png', cat([0x89], ascii('PNG\r\n'), [0x1a, 0x0a])],
    ['text', utf8('hello world')],
    ['a reserved mpeg version', Uint8Array.from([0xff, 0xe9, 0x90, 0x00])],
    ['a utf-16le text (its BOM looks like a layer I frame)', Uint8Array.from([0xff, 0xfe, 0x68, 0x00, 0x69, 0x00])],
    ['an mpeg frame with the invalid bitrate index', Uint8Array.from([0xff, 0xfb, 0xf0, 0x00])],
  ])('is not a stream: %s', (_n, bytes) => {
    expect(sniffStream(bytes)).toBeUndefined()
  })
})

describe('sniffPdf', () => {
  it('needs the header at byte 0', () => {
    expect(sniffPdf(ascii('%PDF-1.7\n%'))).toBe(true)
    expect(sniffPdf(utf8('A note that mentions %PDF- in its body'))).toBe(false)
  })
})

describe('the claims only narrow', () => {
  it.each([
    ['text/plain', 'a', true], ['text/markdown; charset=utf-8', null, true], ['application/json', null, true],
    ['application/vnd.api+json', null, true], ['image/svg+xml', null, true], ['', 'notes.MD', true], [null, 'main.rs', true],
    ['application/octet-stream', 'archive.zip', false], ['image/png', 'photo.png', false], [null, 'LICENSE', false],
  ])('claimsText(%s, %s) = %s', (type, name, expected) => {
    expect(claimsText(type, name)).toBe(expected)
  })
  it('claimsSvg wants the svg type or extension', () => {
    expect(claimsSvg('image/svg+xml', null)).toBe(true)
    expect(claimsSvg('', 'logo.SVG')).toBe(true)
    expect(claimsSvg('text/plain', 'logo.txt')).toBe(false)
  })
  it('extensionOf: a dot after at least one character', () => {
    expect(extensionOf('Bericht.PDF')).toBe('pdf')
    expect(extensionOf('.profile')).toBe('')
    expect(extensionOf('Rom 2026')).toBe('')
    expect(extensionOf(null)).toBe('')
  })
})
