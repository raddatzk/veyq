// The Emergency Kit codec — the `veyqkit:` token that carries an account's email and its Secret Key (the one
// `accountBirth` mints) in the kit's QR and in its PDF. The format is en.account.key-model § The Emergency Kit.
//
// It lives here, not in the two packages that use it, because it has two writers and one reader: `admin-web`
// writes the Server Owner's kit at setup, `web` writes a member's kit and is the only reader, at unlock. Each
// package once carried its own byte-identical copy, and each test suite round-tripped only its own — so a change
// to one copy kept every test green and broke the kits the other package wrote. The PDF writer stays per
// package: this package depends on `@noble/*` alone, and jspdf/qrcode must not become the shared package's.

import { b64url, fromB64url } from './base64'
import { readSecretKey } from './secretKey'

/** `secretKey` is the Secret Key's printed text form (`secretKey.ts`), the one `formatSecretKey` writes. */
export type KitData = { email: string; secretKey: string }

const PREFIX = 'veyqkit:'
/** 2: `sk` is the Secret Key's text form. Version 1 carried it as base64 and is refused: nothing wrote one in use. */
const VERSION = 2

// `fatal` and `ignoreBOM` keep the decoder exactly as strict as the `escape`/`unescape` base64 it replaced: the
// default decoder would turn invalid UTF-8 into U+FFFD and silently strip a leading BOM, and both are kits that
// were never written by `kitPayload`. With `ignoreBOM` the BOM survives into the text, and JSON.parse rejects it.
const utf8 = new TextDecoder(undefined, { fatal: true, ignoreBOM: true })

/**
 * The compact, machine-readable kit token (goes in the QR and is embedded in the PDF). The key is written in its
 * printed form; a key that does not read as one throws here, because a kit is the one copy a person keeps and a kit
 * no reader accepts fails exactly when it is needed.
 */
export function kitPayload(data: KitData): string {
  const sk = readSecretKey(data.secretKey)
  if (!sk.ok) throw new Error(`kitPayload: the Secret Key is ${sk.problem}`)
  sk.key.fill(0)
  return PREFIX + b64url(new TextEncoder().encode(JSON.stringify({ v: VERSION, email: data.email, sk: sk.text })))
}

/**
 * Parse the first kit token out of any text — a scanned QR string or the bytes of the kit PDF. Returns `null`
 * for anything it cannot read, including a token whose Secret Key does not read as one (`readSecretKey`). The key
 * comes back in its printed form, whatever spelling of it the token carried.
 */
export function parseKit(text: string): KitData | null {
  const m = new RegExp(PREFIX + '([A-Za-z0-9_-]+)').exec(text)
  if (!m) return null
  const token = m[1] // read outside the try, so the `!m` guard above — not the catch — is what answers "no token"
  try {
    // A token that decodes to JSON `null` throws on `.v` and lands in the catch like any other unreadable kit.
    const o = JSON.parse(utf8.decode(fromB64url(token))) as Record<string, unknown>
    if (o.v !== VERSION || typeof o.email !== 'string' || o.email === '') return null
    // Stryker disable next-line ConditionalExpression: equivalent mutant — a non-string `sk` makes `readSecretKey`
    // throw into the catch below; the check is here to narrow the type.
    if (typeof o.sk !== 'string') return null
    const sk = readSecretKey(o.sk)
    if (!sk.ok) return null
    sk.key.fill(0)
    return { email: o.email, secretKey: sk.text }
  } catch {
    return null
  }
}
