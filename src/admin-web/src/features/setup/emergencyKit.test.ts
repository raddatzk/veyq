// @vitest-environment node
// The write half only — this package never reads a kit back; `src/web/` does, with `@veyq/crypto`'s `parseKit`. So
// this test reads the PDF this package writes with exactly that parser, the way `src/web/`'s `importKitFromFile`
// does (`File.text()`). It is the test that would have gone red on the day the two codec copies diverged.
import { describe, expect, it } from 'vitest'
import { parseKit } from '@veyq/crypto/emergencyKit'
import { renderKitPdf } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../i18n'

// A real-shaped kit: 32-byte Secret Key, and an email the UTF-8 path has to carry.
const data = { email: 'zoë@veyq.app', secretKey: 'ERERERERERERERERERERERERERERERERERERERERERE=' }

describe.each(SUPPORTED_LOCALES)('the kit PDF (%s)', (locale) => {
  // Strict on purpose: vue-i18n renders a missing key as the key itself, which would print "kit.pdfTitle" on the
  // one document the owner keeps offline and still leave the embedding intact.
  const t = (key: string) => {
    if (!i18n.global.te(key, locale)) throw new Error(`missing ${locale} message: ${key}`)
    return i18n.global.t(key, {}, { locale })
  }

  // The first render pays jspdf's cold import: ~1 s warm, but measured at 5.7 s on a cold transform cache.
  it('is one the shared reader finds the kit in', { timeout: 30_000 }, async () => {
    const bytes = await renderKitPdf(data, t)
    expect(new TextDecoder().decode(bytes.subarray(0, 5))).toBe('%PDF-')
    expect(parseKit(await new File([bytes as BlobPart], 'kit.pdf').text())).toEqual(data)
  })
})
