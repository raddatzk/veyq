// @vitest-environment node
// The write half only — this package never reads a kit back; `src/web/` does, with `@veyq/crypto`'s `parseKit`. So
// this test reads the PDF this package writes with exactly that parser, the way `src/web/`'s `importKitFromFile`
// does (`File.text()`). It is the test that would have gone red on the day the two codec copies diverged.
import { describe, expect, it } from 'vitest'
import { parseKit } from '@veyq/crypto/emergencyKit'
import { formatSecretKey } from '@veyq/crypto/secretKey'
import { kitServerOf, renderKitPdf, type KitLanguage } from './emergencyKit'
import { i18n, SUPPORTED_LOCALES } from '../../i18n'

// A real-shaped kit: a Secret Key in its printed form, and an email the UTF-8 path has to carry.
const data = { email: 'zoë@veyq.app', secretKey: formatSecretKey(new Uint8Array(32).fill(0x11)) }

const latin1 = (bytes: Uint8Array) => Array.from(bytes, (c) => String.fromCharCode(c)).join('')

/** Each AcroForm field's name and value, as jsPDF writes them uncompressed (`/T (name)` … `/V (value)` in one object). */
function fieldValues(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const obj of text.split('endobj')) {
    const name = /\/T \(([^)]*)\)/.exec(obj)
    if (name) out[name[1]] = /\/V \(([^)]*)\)/.exec(obj)?.[1] ?? ''
  }
  return out
}

describe('kitServerOf', () => {
  it('reads nothing typed as no server', () => {
    expect(kitServerOf('')).toEqual({})
    expect(kitServerOf('   ')).toEqual({})
  })

  it('reads a host, with or without a scheme and a port, as its origin', () => {
    expect(kitServerOf('familie.example.org')).toEqual({ server: 'https://familie.example.org' })
    expect(kitServerOf(' Familie.Example.org ')).toEqual({ server: 'https://familie.example.org' })
    expect(kitServerOf('https://familie.example.org/')).toEqual({ server: 'https://familie.example.org' })
    expect(kitServerOf('https://familie.example.org:443')).toEqual({ server: 'https://familie.example.org' })
    expect(kitServerOf('familie.example.org:8443')).toEqual({ server: 'https://familie.example.org:8443' })
    expect(kitServerOf('http://veyq.localhost:8081')).toEqual({ server: 'http://veyq.localhost:8081' })
  })

  it('refuses what is no address veyq is served at', () => {
    for (const typed of ['familie.example.org/app', 'https://familie.example.org/?x=1', 'https://a@familie.example.org', 'ftp://familie.example.org', 'not an address', 'https://']) {
      expect(kitServerOf(typed), typed).toBe('invalid')
    }
  })
})

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
    expect(parseKit(await new File([bytes as BlobPart], 'kit.pdf').text())).toStrictEqual(data)
    const text = latin1(bytes)
    const groups = data.secretKey.split('-')
    for (const line of [0, 1, 2]) expect(text).toContain(`(${groups.slice(line * 3, line * 3 + 3).join('-')})`)
    // No address typed: no server in the token, and every address field empty (this page's localhost means nothing).
    expect(fieldValues(text)).toEqual({ address1: '', address2: '', address3: '' })
    expect(text).not.toContain('localhost')
    expect(text).toContain(`/URI (${i18n.global.t('kit.pdfHelpUrl', {}, { locale })})`)
  })

  it('carries a typed address in the token and its host in the first field', { timeout: 30_000 }, async () => {
    const withServer = { ...data, server: 'https://familie.example.org' }
    const bytes = await renderKitPdf(withServer, language)
    expect(parseKit(await new File([bytes as BlobPart], 'kit.pdf').text())).toStrictEqual(withServer)
    expect(fieldValues(latin1(bytes))).toEqual({ address1: 'familie.example.org', address2: '', address3: '' })
  })
})
