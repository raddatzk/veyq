// The Emergency Kit codec — the `veyqkit:` token that carries an account's email, its Secret Key (the one
// `accountBirth` mints) and, where the writer knows it, the origin of the server the kit was made on, in the kit's QR
// and in its PDF. The format is en.account.key-model § The Emergency Kit, pinned by contracts/crypto/vectors/v1.json
// § emergency_kit_token, which the native apps' reader is held to as well.
//
// It lives here, not in the two packages that use it, because it has two writers and one reader: `admin-web`
// writes the Server Owner's kit at setup, `web` writes a member's kit and is the only reader, at unlock. Each
// package once carried its own byte-identical copy, and each test suite round-tripped only its own — so a change
// to one copy kept every test green and broke the kits the other package wrote. The PDF writer stays per
// package: this package depends on `@noble/*` alone, and jspdf/qrcode must not become the shared package's.

import { b64url, fromB64url } from './base64'
import { formatSecretKey, readSecretKey, secretKeyBytes } from './secretKey'

/**
 * `secretKey` is the Secret Key's printed text form (`secretKey.ts`), the one `formatSecretKey` writes. `server` is the
 * origin of the server the kit was made on (`https://veyq.example.com`), absent where the writer knows none: a hint for
 * the reader, never a check — an account may be reached under more than one address.
 */
export type KitData = { email: string; secretKey: string; server?: string }

const PREFIX = 'veyqkit:'
/**
 * 3: `srv` carries the server's origin. Every earlier version is refused, as no kit of one is in use: 1 carried the key
 * as base64, 2 had no server.
 */
const VERSION = 3

/**
 * Whether `s` is an http or https origin spelled exactly as the URL standard serializes it — no path, no trailing slash,
 * no default port, a lower-case host. The one spelling, so the token of a kit is the same on every writer and a reader in
 * another language can hold it to the same vector.
 */
export function isKitServer(s: unknown): s is string {
  // Stryker disable next-line ConditionalExpression: equivalent mutant — a non-string never equals the origin string
  // compared below; the check is here to narrow the type and to keep `URL` from coercing it.
  if (typeof s !== 'string') return false
  let url: URL
  try {
    url = new URL(s)
  } catch {
    return false
  }
  return (url.protocol === 'https:' || url.protocol === 'http:') && url.origin === s
}

// `fatal` and `ignoreBOM` keep the decoder exactly as strict as the `escape`/`unescape` base64 it replaced: the
// default decoder would turn invalid UTF-8 into U+FFFD and silently strip a leading BOM, and both are kits that
// were never written by `kitPayload`. With `ignoreBOM` the BOM survives into the text, and JSON.parse rejects it.
const utf8 = new TextDecoder(undefined, { fatal: true, ignoreBOM: true })

/**
 * The compact, machine-readable kit token (goes in the QR and is embedded in the PDF). The key is written in its
 * printed form; a key that does not read as one throws here, and so does a server that is no origin, because a kit is
 * the one copy a person keeps and a kit no reader accepts fails exactly when it is needed.
 */
export function kitPayload(data: KitData): string {
  if (data.server !== undefined && !isKitServer(data.server)) throw new Error('kitPayload: the server is not an origin')
  const sk = readSecretKey(data.secretKey)
  if (!sk.ok) throw new Error(`kitPayload: the Secret Key is ${sk.problem}`)
  sk.key.fill(0)
  // The key order is the vector's: v, email, sk, srv — and JSON.stringify leaves `srv` out where it is undefined.
  return PREFIX + b64url(new TextEncoder().encode(JSON.stringify({ v: VERSION, email: data.email, sk: sk.text, srv: data.server })))
}

/**
 * Parse the first kit token out of any text — a scanned QR string or the bytes of the kit PDF. Returns `null`
 * for anything it cannot read, including a token whose Secret Key does not read as one (`readSecretKey`) or whose
 * server is no origin (`isKitServer`). The key
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
    if (o.srv !== undefined && !isKitServer(o.srv)) return null
    // Stryker disable next-line ConditionalExpression: equivalent mutant — a non-string `sk` makes `secretKeyBytes`
    // throw into the catch below; the check is here to narrow the type.
    if (typeof o.sk !== 'string') return null
    const key = secretKeyBytes(o.sk) // a malformed or mistyped key throws SecretKeyUnreadable into the catch
    const secretKey = formatSecretKey(key)
    key.fill(0)
    return o.srv === undefined ? { email: o.email, secretKey } : { email: o.email, secretKey, server: o.srv }
  } catch {
    return null
  }
}
