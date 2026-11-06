// @vitest-environment node
// The write half only — this package never reads a kit back; `src/web/` does, with `@veyq/crypto`'s `parseKit`. So
// this test reads the PDF this package writes with exactly that parser, the way `src/web/`'s `importKitFromFile`
// does (`File.text()`). It is the test that would have gone red on the day the two codec copies diverged.
import { describe, expect, it } from 'vitest'
import { parseKit } from '@veyq/crypto/emergencyKit'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { renderKitPdf, type KitLanguage } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../i18n'

// A real-shaped kit: a Secret Key in its printed form, and an email the UTF-8 path has to carry.
const data = { email: 'zoë@veyq.app', secretKey: formatSecretKey(new Uint8Array(32).fill(0x11)) }

const latin1 = (bytes: Uint8Array) => Array.from(bytes, (c) => String.fromCharCode(c)).join('')

describe.each(SUPPORTED_LOCALES)('the kit PDF (%s)', (locale) => {
  // Strict on purpose: vue-i18n renders a missing key as the key itself, which would print "kit.pdfTitle" on the
  // one document the owner keeps offline and still leave the embedding intact.
  const language: KitLanguage = {
    locale,
    t: (key, named) => {
      if (!i18n.global.te(key, locale)) throw new Error(`missing ${locale} message: ${key}`)
      return i18n.global.t(key, named ?? {}, { locale })
    },
  }

  // The first render pays jspdf's cold import: ~1 s warm, but measured at 5.7 s on a cold transform cache.
  it('is one the shared reader finds the kit in, and prints the key and the address fields', { timeout: 30_000 }, async () => {
    const bytes = await renderKitPdf(data, language)
    expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-')
    expect(parseKit(await new File([bytes as BlobPart], 'kit.pdf').text())).toEqual(data)
    const text = latin1(bytes)
    const groups = data.secretKey.split('-')
    for (const line of [0, 1, 2]) expect(text).toContain(`(${groups.slice(line * 3, line * 3 + 3).join('-')})`)
    for (const n of [1, 2, 3]) expect(text).toContain(`/T (address${n})`)
    // The one row web's kit has and this one does not: the address it was made on (localhost means nothing elsewhere).
    expect(text).not.toContain('localhost')
    expect(text).toContain(`/URI (${i18n.global.t('kit.pdfHelpUrl', {}, { locale })})`)
  })
})
