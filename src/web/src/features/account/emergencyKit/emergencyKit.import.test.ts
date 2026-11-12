// @vitest-environment node
// The kit import's fallback chain (en.account.key-model § The Emergency Kit), through the REAL pdf.js: a kit whose
// file no longer carries the `veyqkit:` token raw — re-saved by a viewer, or printed to PDF — is still a kit.
//
// The fixtures are a kit the web renderer wrote (fake data: `alex.beispiel@example.org`, the Secret Key of the bytes
// 0..31, made at `https://familie.example.org`, so its first address field holds that host), then put through macOS
// PDFKit, measured 2026-11-12 (first 2026-11-06, re-made for the token's version 3): `kit-pdfkit-saved.pdf` is that kit
// with its two empty address fields filled and saved the way Preview saves (PDFKit read the first field's value as
// written, rewrote the file and kept `/Keywords (veyqkit:…)` raw);
// `kit-printed.pdf` is the saved one redrawn page by page into a new Quartz PDF, which is what "Print → Save as PDF"
// does — the drawing survives, the document information does not. Not measured: the real Preview app, Chrome,
// Firefox, Acrobat. `kit-photo.pdf` simulates a phone photo of the printed sheet saved by a scanner app (ImageMagick 7,
// from the PDFKit-saved kit drawn at 2480 px): the sheet at 82 % of a 1512 x 2016 frame on a brown background, a
// perspective shift of up to 1.75 % of the frame at the corners, 1.25° rotation, a light gradient, blur sigma 0.6 px,
// Gaussian noise (attenuate 0.25), JPEG quality 72 with 4:2:0 chroma, wrapped one image per page at 200 dpi. Measured
// 2026-11-06 at a 3024 px wide photo, one distortion at a time, step (c) still reads the QR at a 10.5 % corner shift
// and 7.5° rotation, and stops at blur sigma 3.6 px (2.4 px reads) and noise attenuate 0.75 (0.5 reads); all four
// together read at twice this fixture's levels and fail at three times. pdf.js draws in node through its own
// `@napi-rs/canvas`, so step (c) runs here as in a browser.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { kitPayload } from '@veyq/crypto/emergencyKit'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { readPdf } from '../../../shared/pdf/pdfEngine'
import { importKitFromFile } from './emergencyKit'

const KIT = {
  email: 'alex.beispiel@example.org',
  secretKey: formatSecretKey(Uint8Array.from({ length: 32 }, (_, i) => i)),
  server: 'https://familie.example.org',
}

const fixture = (name: string) => new Uint8Array(readFileSync(fileURLToPath(new URL(`./__fixtures__/${name}`, import.meta.url))))
const latin1 = (bytes: Uint8Array) => Array.from(bytes, (c) => String.fromCharCode(c)).join('')
const asFile = (bytes: Uint8Array, type = 'application/pdf') => new File([bytes as BlobPart], 'kit.pdf', { type })

/** What pdf.js reads as the document's `Keywords` — a copy of the bytes, because pdf.js takes the buffer over. */
async function keywordsOf(bytes: Uint8Array): Promise<string> {
  const reading = await readPdf(bytes.slice())
  try {
    return await reading.keywords()
  } finally {
    await reading.destroy()
  }
}

/** A one-page PDF 1.4 with offsets computed, whose document information carries `keywords` as written (a PDF string
 *  literal or hex string) and whose page draws nothing — so only step (b) can find a kit in it. */
function pdfWithKeywords(keywords: string): Uint8Array {
  const objects = [
    '',
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] >>',
    `<< /Keywords ${keywords} >>`,
  ]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = out.length
    out += `${id} 0 obj\n${objects[id]}\nendobj\n`
  }
  const xref = out.length
  out += `xref\n0 ${objects.length}\n0000000000 65535 f \n`
  for (let id = 1; id < objects.length; id++) out += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
  out += `trailer\n<< /Size ${objects.length} /Root 1 0 R /Info 4 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Uint8Array.from(out, (c) => c.charCodeAt(0))
}

/** `text` as a PDF hex string in UTF-16BE with its byte-order mark — how some writers re-encode the Info strings. */
const utf16Hex = (text: string) =>
  '<FEFF' + Array.from(text, (c) => c.charCodeAt(0).toString(16).padStart(4, '0')).join('').toUpperCase() + '>'

describe('importing a kit that is no longer the file the renderer wrote', () => {
  it('(a) a kit re-saved by PDFKit, with its address fields filled: the token is still in the bytes', async () => {
    const bytes = fixture('kit-pdfkit-saved.pdf')
    expect(latin1(bytes)).toContain('veyqkit:')
    expect(await importKitFromFile(asFile(bytes))).toEqual(KIT)
  })

  it('(c) a kit printed to PDF: neither the bytes nor the metadata carry the token, the QR on the page does', { timeout: 30_000 }, async () => {
    const bytes = fixture('kit-printed.pdf')
    // The premise, asserted: (a) and (b) have nothing to find, so the kit below can only have come from the page.
    expect(latin1(bytes)).not.toContain('veyqkit:')
    expect(await keywordsOf(bytes)).not.toContain('veyqkit:')
    expect(await importKitFromFile(asFile(bytes))).toEqual(KIT)
    // A browser does not always type a dropped file: the PDF is recognised by its first bytes.
    expect(await importKitFromFile(asFile(bytes, ''))).toEqual(KIT)
  })

  it('(c) a photographed kit saved as a PDF, as a scanner app does: one JPEG on the page, found on the drawn page', { timeout: 30_000 }, async () => {
    const bytes = fixture('kit-photo.pdf')
    expect(latin1(bytes)).not.toContain('veyqkit:')
    expect(await keywordsOf(bytes)).not.toContain('veyqkit:')
    expect(await importKitFromFile(asFile(bytes))).toEqual(KIT)
  })

  it('(b) a kit whose Keywords a writer re-encoded (UTF-16 hex): not in the bytes, read through pdf.js', async () => {
    const bytes = pdfWithKeywords(utf16Hex(kitPayload(KIT)))
    expect(latin1(bytes)).not.toContain('veyqkit:')
    expect(await importKitFromFile(asFile(bytes))).toEqual(KIT)
  })

  it('is null for a PDF that carries no kit anywhere — and for one pdf.js cannot open', async () => {
    expect(await importKitFromFile(asFile(pdfWithKeywords('(just some keywords)')))).toBeNull()
    expect(await importKitFromFile(asFile(new TextEncoder().encode('%PDF-1.7\nnot a pdf at all\n')))).toBeNull()
  })
})
