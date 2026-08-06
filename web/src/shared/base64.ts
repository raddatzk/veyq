// base64url (RFC 4648 §5, unpadded) — the encoding used everywhere on the wire (sealed bytes, blobs, tokens).
// This is plain transport encoding, not crypto, so it lives here rather than in the crypto suite. Previously
// ~30 modules each carried their own byte-identical copy (the decode had subtly drifted into three variants).

/** Bytes → unpadded base64url string. */
export function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Bytes -> unpadded standard base64, as required by PHC string fields. */
export function phcB64(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/=+$/, '')
}

// Padded STANDARD base64 (RFC 4648 §4), the encoding the setup contract's wire fields require
// (`contentEncoding: base64`, validated by `[A-Za-z0-9+/]*={0,2}`). Distinct from b64url above: the operator-
// blind API contract is the source of truth and pins standard base64, so opaque ciphertext/public-key fields
// crossing that boundary use this, not the url-safe transport encoding used inside the app.

/** Bytes → padded standard base64. */
export function b64std(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s)
}

/** Standard base64 string (padded or not) → bytes. */
export function fromB64std(s: string): Uint8Array {
  return Uint8Array.from(atob(s + '='.repeat((4 - (s.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

/** base64url string (padded or not) → bytes. */
export function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}
