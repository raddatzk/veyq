// @vitest-environment node
// The kit's DOM half. The `veyqkit:` codec is `@veyq/crypto/emergencyKit`'s subject and pinned there by a vector;
// what only this file can prove is that the PDF this package WRITES is one its own reader finds a kit in.
import { describe, expect, it } from 'vitest'
import { kitPayload } from '@veyq/crypto/emergencyKit'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { importKitFromFile, renderKitPdf, type KitLanguage } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../../i18n'

// A real-shaped kit: a Secret Key in its printed form, and an email the UTF-8 path has to carry.
const data = { email: 'zoë@veyq.app', secretKey: formatSecretKey(new Uint8Array(32).fill(0x11)) }

/** The strict `t` of a locale: vue-i18n renders a missing key as the key itself, which would print "kit.pdfTitle" on the
 *  one document the user keeps offline and still leave the embedding intact. */
function strictKitLanguage(locale: (typeof SUPPORTED_LOCALES)[number]): KitLanguage {
  return {
    locale,
    t: (key, named) => {
      if (!i18n.global.te(key, locale)) throw new Error(`missing ${locale} message: ${key}`)
      return i18n.global.t(key, named ?? {}, { locale })
    },
  }
}

const latin1 = (bytes: Uint8Array) => Array.from(bytes, (c) => String.fromCharCode(c)).join('')

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

  it('refuses to render a kit whose Secret Key does not read as one', async () => {
    const typo = { ...data, secretKey: data.secretKey.slice(0, -1) + (data.secretKey.endsWith('A') ? 'B' : 'A') }
    await expect(renderKitPdf(typo, strictKitLanguage('en'), { createdOn: 'veyq.example.org' })).rejects.toThrow(/typo/)
  })

  // Writer to reader, through the real jsPDF output: the token rides in the PDF's metadata, and nothing but this
  // test notices if a change to the writer (compression, a localized title, a dropped `keywords`) buries it.
  describe.each(SUPPORTED_LOCALES)('the kit PDF (%s)', (locale) => {
    // The first render pays jspdf's cold import: ~1 s warm, but measured at 5.7 s on a cold transform cache.
    it('is one the reader finds the kit in, and prints what the person needs', { timeout: 30_000 }, async () => {
      const bytes = await renderKitPdf(data, strictKitLanguage(locale), { createdOn: 'familie.example.org', now: new Date('2026-11-06T12:00:00Z') })
      expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-')
      expect(await importKitFromFile(new File([bytes as BlobPart], 'kit.pdf'))).toEqual(data)
      // jsPDF writes its text uncompressed, so the printed strings are in the bytes: the key's three lines (three
      // groups each, hyphens kept), the address the kit was made on, and the three form fields for the others.
      const text = latin1(bytes)
      const groups = data.secretKey.split('-')
      for (const line of [0, 1, 2]) expect(text).toContain(`(${groups.slice(line * 3, line * 3 + 3).join('-')})`)
      expect(text).toContain('(familie.example.org)')
      for (const n of [1, 2, 3]) expect(text).toContain(`/T (address${n})`)
      expect(text).toContain(`/URI (${i18n.global.t('kit.pdfHelpUrl', {}, { locale })})`)
    })
  })
})
