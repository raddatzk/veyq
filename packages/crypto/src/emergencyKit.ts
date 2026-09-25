// The Emergency Kit codec — the `veyqkit:` token that carries an account's email and its Secret Key (the one
// `accountBirth` mints) in the kit's QR and in its PDF. The format is en.account.key-model § The Emergency Kit.
//
// It lives here, not in the two packages that use it, because it has two writers and one reader: `admin-web`
// writes the Server Owner's kit at setup, `web` writes a member's kit and is the only reader, at unlock. Each
// package once carried its own byte-identical copy, and each test suite round-tripped only its own — so a change
// to one copy kept every test green and broke the kits the other package wrote. The PDF writer stays per
// package: this package depends on `@noble/*` alone, and jspdf/qrcode must not become the shared package's.

import { b64url, fromB64std, fromB64url } from './base64'

export type KitData = { email: string; secretKey: string }

const PREFIX = 'veyqkit:'

// `fatal` and `ignoreBOM` keep the decoder exactly as strict as the `escape`/`unescape` base64 it replaced: the
// default decoder would turn invalid UTF-8 into U+FFFD and silently strip a leading BOM, and both are kits that
// were never written by `kitPayload`. With `ignoreBOM` the BOM survives into the text, and JSON.parse rejects it.
const utf8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true })

/** The compact, machine-readable kit token (goes in the QR and is embedded in the PDF). */
export function kitPayload(data: KitData): string {
  return PREFIX + b64url(new TextEncoder().encode(JSON.stringify({ v: 1, email: data.email, sk: data.secretKey })))
}

/**
 * Parse the first kit token out of any text — a scanned QR string or the bytes of the kit PDF. Returns `null`
 * for anything it cannot read, including a token whose Secret Key is not the 32 bytes unlock will decode it to.
 */
export function parseKit(text: string): KitData | null {
  const m = new RegExp(PREFIX + '([A-Za-z0-9_-]+)').exec(text)
  if (!m) return null
  try {
    // A token that decodes to JSON `null` throws on `.v` and lands in the catch like any other unreadable kit.
    const o = JSON.parse(utf8.decode(fromB64url(m[1]))) as Record<string, unknown>
    if (o.v !== 1 || typeof o.email !== 'string' || o.email === '') return null
    // Stryker disable next-line ConditionalExpression: equivalent mutant. No JSON value other than a string
    // stringifies to base64 that `fromB64std` decodes to 32 bytes (an array gains commas or over-padding, a
    // number is at most ~22 characters), so dropping this check changes no result; it is here to narrow the type.
    if (typeof o.sk !== 'string') return null
    if (fromB64std(o.sk).length !== 32) return null
    return { email: o.email, secretKey: o.sk }
  } catch {
    return null
  }
}
