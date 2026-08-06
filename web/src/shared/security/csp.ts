// Content-Security-Policy for the web client (cr-0133 / audit P2e). veyq is operator-blind: the browser holds
// decryption keys and renders decrypted content, so if hostile markup ever reached the page the real damage is
// an injected script exfiltrating keys/plaintext to a foreign host. `connect-src 'self'` is the backstop that
// forbids exactly that. The policy is GENERATED from the same map-provider config the app fetches from
// (`mapDefaults` → `vite.config`), so a self-hosted tile/geocoder deployment stays covered without hand-editing
// a policy string (which would silently drift from the origins it is meant to allow).
//
// Notes on the chosen directives:
// - script-src 'self': the entry is a hashed same-origin module; the app has NO inline script, NO eval/Function,
//   and NO WebAssembly (Argon2id is pure-JS @noble), so a strict script-src holds with no unsafe- escapes.
// - style-src needs 'unsafe-inline': PrimeVue/@primeuix inject runtime <style> tags + the app uses :style
//   bindings; neither is nonce-able. Style injection is far lower-risk than script, so this is an accepted gap.
// - img-src data: + blob: — favicon SVG / QR data URLs, and decrypted media object URLs. media-src blob: — same.
// - worker-src 'self': the Argon2 worker is a same-origin module worker (no blob: worker).
// - object-src/frame-src 'none': the app embeds no <object>/<embed>/<iframe>.
// - frame-ancestors is intentionally ABSENT: it is ignored in a <meta> CSP and only works as a real response
//   header; it (plus HSTS) is deferred to the future production web-serving component. See cc.security.csp.md.

export interface CspOptions {
  /** Basemap tile origin (Leaflet loads tiles as <img> → img-src), or null to allow no third-party tiles. */
  tileOrigin: string | null
  /** Reverse-geocoder origin (fetched via XHR → connect-src), or null to allow none. */
  geocoderOrigin: string | null
  /** Dev only: also allow the Vite HMR websocket (`ws:`). Never set for a production build. */
  dev?: boolean
}

// The HaveIBeenPwned range API — a fixed, privacy-preserving third-party the password-strength check calls
// client-direct (only a 5-hex SHA-1 prefix leaves the device, k-anonymity). It must be in connect-src.
const PWNED_ORIGIN = 'https://api.pwnedpasswords.com'

/** Build the Content-Security-Policy header/meta value from the resolved provider origins. */
export function buildCsp({ tileOrigin, geocoderOrigin, dev = false }: CspOptions): string {
  const imgSrc = ["'self'", 'data:', 'blob:']
  if (tileOrigin) imgSrc.push(tileOrigin)

  const connectSrc = ["'self'", PWNED_ORIGIN]
  if (geocoderOrigin) connectSrc.push(geocoderOrigin)
  if (dev) connectSrc.push('ws:') // Vite HMR socket — dev server only, never in a production build

  const directives: Record<string, string[]> = {
    'default-src': ["'self'"],
    'base-uri': ["'self'"],
    'object-src': ["'none'"],
    'frame-src': ["'none'"],
    'script-src': ["'self'"],
    'worker-src': ["'self'"],
    'style-src': ["'self'", "'unsafe-inline'"],
    'img-src': imgSrc,
    'media-src': ["'self'", 'blob:'],
    'font-src': ["'self'"],
    'connect-src': connectSrc,
    'form-action': ["'self'"],
  }

  return Object.entries(directives)
    .map(([name, sources]) => `${name} ${sources.join(' ')}`)
    .join('; ')
}

/**
 * Extract an http(s) origin from a provider URL for use in a CSP source list. Returns null when the input is
 * absent or not an absolute http(s) URL (e.g. a relative override or malformed value), so the caller can fall
 * back to the built-in default and never inject a garbage source.
 */
export function providerOrigin(url: string | undefined | null): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.origin : null
  } catch {
    return null
  }
}
