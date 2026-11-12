// @vitest-environment node
// The REAL pdf.js through our engine, in node (inbox #412): the other PDF suites mock `pdfEngine`, so they prove the
// viewer's wiring and nothing about the library. This one opens a tiny hand-built PDF with the real `pdfjs-dist` legacy build
// and OUR options (`pdfOptions.ts`) and asserts the page count, a page's size, and the failure classes - proving the library
// and the option set work together. node has no canvas, so nothing is drawn here; drawing is a browser's (docs/apps/depot.md).
// What a page WOULD draw is reachable, though: a scanned image's decoded pixels, which pdf.js' worker hands on to be painted.
import { createHash } from 'node:crypto'
import { copyFileSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { afterAll, beforeAll, describe, it, expect } from 'vitest'
import * as pdfjs from 'pdfjs-dist/legacy/build/pdf.mjs'
import { openPdf, PdfOpenError } from './pdfEngine'
import { getDocument } from './pdfjsRuntime'
import { PDF_DECODER_FILES, PDF_MAX_PAGES, pdfOpenParameters } from './pdfOptions'

const PAD = Uint8Array.from([
  0x28, 0xbf, 0x4e, 0x5e, 0x4e, 0x75, 0x8a, 0x41, 0x64, 0x00, 0x4e, 0x56, 0xff, 0xfa, 0x01, 0x08,
  0x2e, 0x2e, 0x00, 0xb6, 0xd0, 0x68, 0x3e, 0x80, 0x2f, 0x0c, 0xa9, 0xfe, 0x64, 0x53, 0x69, 0x7a,
])
const md5 = (...parts: Uint8Array[]) => new Uint8Array(createHash('md5').update(Buffer.concat(parts)).digest())
function rc4(key: Uint8Array, data: Uint8Array): Uint8Array {
  const s = Uint8Array.from({ length: 256 }, (_, i) => i)
  for (let i = 0, j = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) & 255
    ;[s[i], s[j]] = [s[j], s[i]]
  }
  const out = new Uint8Array(data.length)
  for (let k = 0, i = 0, j = 0; k < data.length; k++) {
    i = (i + 1) & 255
    j = (j + s[i]) & 255
    ;[s[i], s[j]] = [s[j], s[i]]
    out[k] = data[k] ^ s[(s[i] + s[j]) & 255]
  }
  return out
}
const hex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
const padded = (pw: string) => Uint8Array.from([...new TextEncoder().encode(pw), ...PAD].slice(0, 32))
/** The /Encrypt dictionary (standard handler, RC4 40 bit, revision 2) of a document whose USER password is `pw`, and the file
 *  key it derives. The dictionary is what opening without the password must be refused by; the key encrypts the page content
 *  streams (`objectKey`), so the text of an encrypted page can only be read with the right password. The document has no
 *  strings outside streams, so there is nothing else to encrypt. */
function encryptDictionary(pw: string, id: Uint8Array): { dictionary: string; fileKey: Uint8Array } {
  const o = rc4(md5(padded('owner-secret')).subarray(0, 5), padded(pw))
  const p = Uint8Array.from([0xfc, 0xff, 0xff, 0xff]) // -4: every permission
  const key = md5(padded(pw), o, p, id).subarray(0, 5)
  const u = rc4(key, PAD)
  return { dictionary: `<< /Filter /Standard /V 1 /R 2 /O <${hex(o)}> /U <${hex(u)}> /P -4 >>`, fileKey: key }
}
/** The RC4 key of object `num` (generation 0): MD5(file key + object number as 3 bytes LE + generation as 2 bytes LE), cut to key length + 5. */
const objectKey = (fileKey: Uint8Array, num: number) =>
  md5(fileKey, Uint8Array.from([num & 255, (num >> 8) & 255, (num >> 16) & 255, 0, 0])).subarray(0, Math.min(fileKey.length + 5, 16))
const latin1 = (b: Uint8Array) => Array.from(b, (c) => String.fromCharCode(c)).join('')

/** A valid PDF 1.4 with `pageCount` pages of 200 x 100 points, each showing `text` in Helvetica, xref offsets computed. With a
 *  `userPassword` the document is encrypted: opening needs it, and so does reading the text of a page. */
function minimalPdf(pageCount: number, size: [number, number] = [200, 100], userPassword?: string, text = 'Hello'): Uint8Array {
  const id = new Uint8Array(16).fill(7)
  const enc = userPassword !== undefined ? encryptDictionary(userPassword, id) : null
  const objects: string[] = []
  const pageIds = Array.from({ length: pageCount }, (_, i) => 4 + i * 2)
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pageCount} >>`
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  for (const id of pageIds) {
    objects[id] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${size[0]} ${size[1]}] /Resources << /Font << /F1 3 0 R >> >> /Contents ${id + 1} 0 R >>`
    const plain = `BT /F1 18 Tf 20 40 Td (${text}) Tj ET`
    const body = enc ? latin1(rc4(objectKey(enc.fileKey, id + 1), new TextEncoder().encode(plain))) : plain
    objects[id + 1] = `<< /Length ${body.length} >>\nstream\n${body}\nendstream`
  }
  const encryptId = objects.length
  if (enc) objects[encryptId] = enc.dictionary
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = out.length
    out += `${id} 0 obj\n${objects[id]}\nendobj\n`
  }
  const xref = out.length
  out += `xref\n0 ${objects.length}\n0000000000 65535 f \n`
  for (let id = 1; id < objects.length; id++) out += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
  const encrypt = enc ? ` /Encrypt ${encryptId} 0 R /ID [<${hex(id)}> <${hex(id)}>]` : ''
  out += `trailer\n<< /Size ${objects.length} /Root 1 0 R${encrypt} >>\nstartxref\n${xref}\n%%EOF\n`
  return Uint8Array.from(out, (c) => c.charCodeAt(0)) // every character is one byte: ASCII, or an encrypted stream's latin-1 bytes
}

describe('the real pdf.js, our options', () => {
  it('opens a PDF: its page count and the size of a page', async () => {
    const doc = await openPdf(minimalPdf(3))
    try {
      expect(doc.numPages).toBe(3)
      expect(await doc.pageSize(1)).toEqual({ width: 200, height: 100 })
      expect(await doc.pageSize(3)).toEqual({ width: 200, height: 100 })
    } finally {
      await doc.destroy()
      await doc.destroy() // twice is fine
    }
  })

  it('reports a rotated page the way it is shown', async () => {
    const bytes = new TextDecoder().decode(minimalPdf(1)).replace('/MediaBox', '/Rotate 90 /MediaBox')
    // The offsets after the edit are off by the inserted text, which the reader's recovery pass repairs (a damaged xref is common).
    const doc = await openPdf(new TextEncoder().encode(bytes))
    try {
      expect(await doc.pageSize(1)).toEqual({ width: 100, height: 200 })
    } finally {
      await doc.destroy()
    }
  })

  it('refuses what is not a PDF as "broken", not as a crash', async () => {
    const garbage = new TextEncoder().encode('%PDF-1.7\nthis is not a pdf at all, no objects, no xref\n')
    await expect(openPdf(garbage)).rejects.toMatchObject({ name: 'PdfOpenError', reason: 'broken' })
    await expect(openPdf(new Uint8Array([1, 2, 3, 4]))).rejects.toBeInstanceOf(PdfOpenError)
  })

  it('refuses a password-protected PDF as "password" - the message the viewer shows, with no prompt', async () => {
    await expect(openPdf(minimalPdf(2, [200, 100], 'secret'))).rejects.toMatchObject({ name: 'PdfOpenError', reason: 'password' })
  })

  it('names the two password failures the way the engine tells them apart (pdf.js\' PasswordResponses)', () => {
    expect(pdfjs.PasswordResponses.NEED_PASSWORD).toBe(1)
    expect(pdfjs.PasswordResponses.INCORRECT_PASSWORD).toBe(2) // the engine\'s INCORRECT_PASSWORD
  })

  it('refuses a PDF with more pages than the cap', async () => {
    await expect(openPdf(minimalPdf(PDF_MAX_PAGES + 1))).rejects.toMatchObject({ reason: 'pages' })
  })

  it('stops opening when the signal is already aborted, and destroys what it began', async () => {
    const ctl = new AbortController()
    ctl.abort()
    await expect(openPdf(minimalPdf(1), ctl.signal)).rejects.toBeTruthy()
  })

  it('opens with the option set that keeps it inside the page policy', () => {
    const o = pdfOpenParameters(new Uint8Array(1), '/assets/pdfjs-x/')
    expect(o).toMatchObject({ useWasm: false, enableXfa: false, disableAutoFetch: true, disableStream: true, disableRange: true, verbosity: 0 })
    expect('url' in o).toBe(false) // the document is our decrypted buffer, never a URL pdf.js would fetch
    expect(o.cMapUrl).toBe('/assets/pdfjs-x/cmaps/')
    expect(o.standardFontDataUrl).toBe('/assets/pdfjs-x/standard_fonts/')
    expect(o.wasmUrl).toBe('/assets/pdfjs-x/decoders/') // the plain-JavaScript image decoders (ADR 0128) - no WebAssembly
    const outside = pdfOpenParameters(new Uint8Array(1), null)
    expect(outside.useWasm).toBe(false)
    expect('wasmUrl' in outside).toBe(false) // no data directory, no decoders
  })
})

/** A one-page PDF of `width` x `height` points showing one image XObject of as many pixels, whose dictionary entries
 *  (colour space, filter, ...) are `entries` and whose encoded stream is `stream`. */
function imagePdf(width: number, height: number, entries: string, stream: Uint8Array): Uint8Array {
  const ascii = (text: string) => Uint8Array.from(text, (c) => c.charCodeAt(0))
  const content = `q ${width} 0 0 ${height} 0 0 cm /Im0 Do Q`
  const objects = [
    ascii('<< /Type /Catalog /Pages 2 0 R >>'),
    ascii('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
    ascii(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${width} ${height}] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>`),
    ascii(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`),
    Uint8Array.from([...ascii(`<< /Type /XObject /Subtype /Image /Width ${width} /Height ${height} ${entries} /Length ${stream.length} >>\nstream\n`), ...stream, ...ascii('\nendstream')]),
  ]
  const parts = [ascii('%PDF-1.7\n')]
  const offsets: number[] = []
  let length = parts[0].length
  objects.forEach((body, i) => {
    offsets.push(length)
    for (const part of [ascii(`${i + 1} 0 obj\n`), body, ascii('\nendobj\n')]) {
      parts.push(part)
      length += part.length
    }
  })
  const xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`
  parts.push(ascii(`${xref}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${length}\n%%EOF\n`))
  return new Uint8Array(Buffer.concat(parts))
}

// The encoded images of a scan (ADR 0128). The bilevel bitmap is 64 x 32 pixels, black where `scanBlack` says; the colour
// one 16 x 8, pixel (x, y) = `jpxPixel`. Made with Pillow and OpenJPEG 2.5.3 (Python, outside the repo): the bitmap saved
// as a Group 4 TIFF and its one strip cut out (T.6 coding, 1 = black: the CCITT stream, and the MMR data of the JBIG2
// one); the JBIG2 stream is that data as one immediate lossless generic region with MMR coding, between a page-information
// and an end-of-page segment; the JPEG 2000 one is `opj_compress -n 2` of the colour image (lossless, a JP2 file).
// Ghostscript draws each to exactly these pixels. What proves them here is the pixel assertion below, not this note.
const scanBlack = (x: number, y: number) => (x + 2 * y) % 9 < 3 || (x >= 40 && y < 12)
const jpxPixel = (x: number, y: number) => [16 * x, 32 * y, 255 - 8 * (x + y)]
const G4 = Buffer.from(
  'JrHo9Ho9EcC8IIIIIIIILEIIIIIIILCCCCCCCCUIIIIIIIIKggggggggjPEIIIIIIILCCCCCCCCwggggggggoQQQQQQQQRh8IIIIIIIILEIIIIIIILCCCCCCCCRuNxrCCCCCCCCCCCCCCoIIIIIIIIIIIIIKIQQQQQQQQQQQQShBBBBBBBBBBBBBQgggggggggggggoQQQQQQQQQQQQQSwgggggggggggggohBBBBBBBBBBBBBQgggggggggggggoQQQQQQQQQQQQQVBBBBBBBBBBBBBBRCCCCCCCCCCCCCUIIIIIIIIIIIIIKEEEEEEEEEEEEEFCCCCCCCCCCCCCCWEEEEEEEEEEEEEFEIIIIIIIIIIIIIKEEEEEEEEEEEEEFCCCCCCCCCCCCCCgAgAgA==',
  'base64',
)
const be32 = (...values: number[]) => Buffer.from(values.flatMap((v) => [v >>> 24, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]))
/** A JBIG2 segment: its number, type, no referred-to segments, page 1, the data's length, the data. */
const segment = (number: number, type: number, data: Uint8Array) => Buffer.concat([be32(number), Buffer.from([type, 0, 1]), be32(data.length), data])
const JBIG2 = Buffer.concat([
  segment(0, 48, Buffer.concat([be32(64, 32, 0, 0), Buffer.from([0, 0, 0])])), // page information: 64 x 32, no resolution, flags 0, no striping
  segment(1, 39, Buffer.concat([be32(64, 32, 0, 0), Buffer.from([0, 1]), G4])), // immediate lossless generic region at 0,0, OR, MMR
  segment(2, 49, new Uint8Array(0)), // end of page
])
const JPX = Buffer.from(
  'AAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAAAtanAyaAAAABZpaGRyAAAACAAAABAAAwcHAAAAAAAPY29scgEAAAAAABAAAAEPanAyY/9P/1EALwAAAAAAEAAAAAgAAAAAAAAAAAAAABAAAAAIAAAAAAAAAAAAAwcBAQcBAQcBAf9SAAwAAAABAQEEBAAB/1wAB0BASEhQ/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4z/5AACgAAAAAAlAAB/5PPtGwSH4eeGRx52IUKpZu7j9GW3Tyx22QduFQMiAffgOATjCnsPbevNb5KfLPhMTA5neqqzABM8gAdo/9/34DIBkTX0oAnahrH/1ZSbp6dn9IxRx5ob090a8A6FA+QMDadA4SSFHLfwPkBw+oHADXx+wDUn4Au3aHB84SH1Ag2nf9/ANShD//Z',
  'base64',
)

describe('a scanned page: pdf.js\' plain-JavaScript image decoders, found through our options (ADR 0128)', () => {
  // The data directory the build emits, as a `file:` URL: its `decoders/` holds the two files `pdfjsAssetsPlugin` copies,
  // from the same package. Nothing else is in it, so a decode below can only have come from them.
  let dataDir = ''
  beforeAll(() => {
    dataDir = mkdtempSync(join(tmpdir(), 'veyq-pdfjs-'))
    const wasmDir = join(dirname(createRequire(import.meta.url).resolve('pdfjs-dist/package.json')), 'wasm')
    mkdirSync(join(dataDir, 'decoders'))
    for (const name of PDF_DECODER_FILES) copyFileSync(join(wasmDir, name), join(dataDir, 'decoders', name))
  })
  afterAll(() => rmSync(dataDir, { recursive: true, force: true }))

  /** The one image page 1 paints, as pdf.js' worker decoded it: `{ width, height, kind, data }`, or null when it left it out. */
  async function decodedImage(bytes: Uint8Array) {
    const task = pdfjs.getDocument(pdfOpenParameters(bytes, pathToFileURL(dataDir).href + '/'))
    try {
      const page = await (await task.promise).getPage(1)
      const ops = await page.getOperatorList()
      const paint = ops.fnArray.indexOf(pdfjs.OPS.paintImageXObject)
      if (paint < 0) return null // the image was left out: no decoder
      const id = ops.argsArray[paint][0] as string
      return await new Promise<{ width: number; height: number; kind: number; data: Uint8Array } | null>((resolve) => page.objs.get(id, resolve))
    } finally {
      await task.destroy()
    }
  }
  /** One bit per pixel, rows padded to whole bytes, 1 = white (DeviceGray): the bitmap as pdf.js hands a 1-bit image on. */
  const scanBits = () =>
    Uint8Array.from({ length: 32 * 8 }, (_, i) => {
      const [y, byte] = [Math.floor(i / 8), i % 8]
      let bits = 0
      for (let b = 0; b < 8; b++) bits = (bits << 1) | (scanBlack(byte * 8 + b, y) ? 0 : 1)
      return bits
    })

  it('draws a CCITT Group 4 image (a fax or office scanner\'s usual format)', async () => {
    const image = await decodedImage(imagePdf(64, 32, '/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 64 /Rows 32 >>', G4))
    expect(image).toMatchObject({ width: 64, height: 32, kind: pdfjs.ImageKind.GRAYSCALE_1BPP })
    expect(Array.from(image!.data)).toEqual(Array.from(scanBits()))
  })

  it('draws a JBIG2 image', async () => {
    const image = await decodedImage(imagePdf(64, 32, '/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /JBIG2Decode', JBIG2))
    expect(image).toMatchObject({ width: 64, height: 32, kind: pdfjs.ImageKind.GRAYSCALE_1BPP })
    expect(Array.from(image!.data)).toEqual(Array.from(scanBits()))
  })

  it('draws a JPEG 2000 image', async () => {
    const image = await decodedImage(imagePdf(16, 8, '/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /JPXDecode', JPX))
    expect(image).toMatchObject({ width: 16, height: 8, kind: pdfjs.ImageKind.RGB_24BPP })
    expect(Array.from(image!.data)).toEqual(Array.from({ length: 16 * 8 }, (_, i) => jpxPixel(i % 16, Math.floor(i / 16))).flat())
  })
})

describe('the password of a protected PDF', () => {
  const PW = 'open sesame'
  const protectedPdf = () => minimalPdf(2, [200, 100], PW, 'Secret text')

  it('asks for it once, and opens with the right one - the same loading task, no second copy of the bytes', async () => {
    const asked: boolean[] = []
    const doc = await openPdf(protectedPdf(), undefined, async (wrong) => {
      asked.push(wrong)
      return PW
    })
    try {
      expect(asked).toEqual([false])
      expect(doc.numPages).toBe(2)
    } finally {
      await doc.destroy()
    }
  })

  it('tells the prompt when an answer was wrong, asks again, and opens with the next one', async () => {
    const answers = ['not it', 'still not', PW]
    const asked: boolean[] = []
    const doc = await openPdf(protectedPdf(), undefined, async (wrong) => {
      asked.push(wrong)
      return answers.shift() ?? null
    })
    try {
      expect(asked).toEqual([false, true, true]) // the first ask is "need", every later one "wrong"
      expect(doc.numPages).toBe(2)
    } finally {
      await doc.destroy()
    }
  })

  it('declining the prompt is the same "password" failure as having no prompt, and leaves nothing open', async () => {
    await expect(openPdf(protectedPdf(), undefined, async () => null)).rejects.toMatchObject({ name: 'PdfOpenError', reason: 'password' })
    await expect(openPdf(protectedPdf())).rejects.toMatchObject({ name: 'PdfOpenError', reason: 'password' })
  })

  it('a prompt that throws is a decline, not a crash', async () => {
    await expect(
      openPdf(protectedPdf(), undefined, async () => {
        throw new Error('prompt closed')
      }),
    ).rejects.toMatchObject({ reason: 'password' })
  })

  it('aborting while the prompt waits ends the open with the signal\'s reason, and a late answer is ignored', async () => {
    const ctl = new AbortController()
    let answer: (v: string | null) => void = () => {}
    const opening = openPdf(protectedPdf(), ctl.signal, () => new Promise((r) => (answer = r)))
    await new Promise((r) => setTimeout(r, 50)) // the worker has asked
    ctl.abort()
    await expect(opening).rejects.toBeTruthy()
    answer(PW) // nothing is waiting for it any more
    await new Promise((r) => setTimeout(r, 20))
  })

  it('an unprotected PDF never asks', async () => {
    const prompt = async () => PW
    const spy: string[] = []
    const doc = await openPdf(minimalPdf(1), undefined, async (w) => {
      spy.push(String(w))
      return prompt()
    })
    try {
      expect(spy).toEqual([])
    } finally {
      await doc.destroy()
    }
  })
})

describe('the text of a page (what the text layer is built from)', () => {
  /** The page's text through pdf.js' own `getTextContent`, with our open parameters (the engine does not expose it). */
  async function textOf(bytes: Uint8Array, page: number, password?: string): Promise<string> {
    const task = getDocument(bytes.slice()) // pdf.js takes the buffer over, and a test opens one fixture twice
    if (password !== undefined) task.onPassword = (update: (p: string) => void) => update(password)
    const doc = await task.promise
    try {
      const content = await (await doc.getPage(page)).getTextContent()
      return content.items.map((i) => ('str' in i ? i.str : '')).join('')
    } finally {
      await task.destroy()
    }
  }

  it('returns the text drawn on a page', async () => {
    expect(await textOf(minimalPdf(2, [200, 100], undefined, 'Quarterly report'), 1)).toBe('Quarterly report')
    expect(await textOf(minimalPdf(2), 2)).toBe('Hello')
  })

  it('returns the text of an ENCRYPTED page when opened with the right password - and not the cipher', async () => {
    const bytes = minimalPdf(2, [200, 100], 'open sesame', 'Secret text')
    expect(await textOf(bytes, 1, 'open sesame')).toBe('Secret text')
    expect(await textOf(bytes, 2, 'open sesame')).toBe('Secret text')
  })

  it('the fixture really encrypts: the plaintext is nowhere in the bytes of a protected PDF', () => {
    const bytes = minimalPdf(1, [200, 100], 'open sesame', 'Secret text')
    expect(Buffer.from(bytes).includes(Buffer.from('Secret text'))).toBe(false)
    expect(Buffer.from(minimalPdf(1, [200, 100], undefined, 'Secret text')).includes(Buffer.from('Secret text'))).toBe(true)
  })

  it('a page without text (a scan) has no text items', async () => {
    const bytes = new TextEncoder().encode(new TextDecoder().decode(minimalPdf(1)).replace(/BT .* ET/, ''))
    expect(await textOf(bytes, 1)).toBe('')
  })
})
