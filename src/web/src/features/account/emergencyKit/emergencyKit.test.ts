// @vitest-environment node
// The kit's DOM half. The `veyqkit:` codec is `@veyq/crypto/emergencyKit`'s subject and pinned there by a vector;
// what only this file can prove is that the PDF this package WRITES is one its own reader finds a kit in.
import { describe, expect, it } from 'vitest'
import { kitPayload } from '@veyq/crypto/emergencyKit'
import { importKitFromFile, renderKitPdf } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../../i18n'

// A real-shaped kit: 32-byte Secret Key, and an email the UTF-8 path has to carry.
const data = { email: 'zoë@veyq.app', secretKey: 'ERERERERERERERERERERERERERERERERERERERERERE=' }

describe('emergencyKit', () => {
  it('imports the kit from a file carrying the token (PDF/text path)', async () => {
    const file = new File([`%PDF-1.7 …/Keywords (${kitPayload(data)})… stream`], 'kit.pdf', {
      type: 'application/pdf',
    })
    expect(await importKitFromFile(file)).toEqual(data)
  })

  it('returns null for a file with no kit token', async () => {
    const file = new File(['just a normal note, no kit here'], 'note.txt', { type: 'text/plain' })
    expect(await importKitFromFile(file)).toBeNull()
  })

  // Writer to reader, through the real jsPDF output: the token rides in the PDF's metadata, and nothing but this
  // test notices if a change to the writer (compression, a localized title, a dropped `keywords`) buries it.
  describe.each(SUPPORTED_LOCALES)('the kit PDF (%s)', (locale) => {
    // Strict on purpose: vue-i18n renders a missing key as the key itself, which would print "kit.pdfTitle" on the
    // one document the user keeps offline and still leave the embedding intact.
    const t = (key: string) => {
      if (!i18n.global.te(key, locale)) throw new Error(`missing ${locale} message: ${key}`)
      return i18n.global.t(key, {}, { locale })
    }

    // The first render pays jspdf's cold import: ~1 s warm, but measured at 5.7 s on a cold transform cache.
    it('is one the reader finds the kit in', { timeout: 30_000 }, async () => {
      const bytes = await renderKitPdf(data, t)
      expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-')
      expect(await importKitFromFile(new File([bytes as BlobPart], 'kit.pdf'))).toEqual(data)
    })
  })
})
