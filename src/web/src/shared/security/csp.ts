// Content-Security-Policy for the web client (cr-0133 / audit P2e, ADR 0069). veyq is operator-blind: the browser holds
// decryption keys and renders decrypted content, so if hostile markup ever reached the page the real damage is
// an injected script exfiltrating keys/plaintext to a foreign host. `connect-src 'self'` is the backstop that
// forbids exactly that. The policy is GENERATED from the same map-provider config the app fetches from
// (`mapDefaults` → `vite.config`), so a self-hosted tile/geocoder deployment stays covered without hand-editing
// a policy string (which would silently drift from the origins it is meant to allow).
//
// Notes on the chosen directives:
// - script-src 'self': the entry is a hashed same-origin module; the app has NO inline script, NO eval/Function,
//   and NO WebAssembly (Argon2id is pure-JS @noble), so a strict script-src holds with no unsafe- escapes.
// - style-src 'self' with no 'unsafe-inline' (inbox #523): the built CSS is same-origin files, and Vue applies :style
//   bindings through the CSSOM, which style-src does not govern. The one <style> element a component we use renders
//   is allowed by its hash (REKA_STYLE_HASHES). The dev server adds a nonce, because Vite injects every
//   stylesheet as a <style> element there (`devStyleNonce`).
// - img-src data: + blob: — favicon SVG / QR data URLs, and decrypted media object URLs. media-src blob: — same.
// - worker-src 'self': every worker — the Argon2 worker, the media pixel-stage worker, the gallery's listing opener and
//   pdf.js' — is a same-origin module worker (no blob: worker); `verify:csp` asserts the emitted policy says so.
// - object-src/frame-src 'none': the app embeds no <object>/<embed>/<iframe>.
// - frame-ancestors 'none': no page on any origin may frame the app (anti-clickjacking). It only works in a
//   response header, which is one reason the policy is never a <meta>: the build emits it as a file the backend's
//   app shell sends as a header, and the dev server sends it as one too. See cc.security.csp.md.

export interface CspOptions {
  /** Dev only: the basemap tile origin (Leaflet loads tiles as <img> → img-src), or null to allow none. */
  tileOrigin: string | null
  /** Dev only: the reverse-geocoder origin (fetched via XHR → connect-src), or null to allow none. */
  geocoderOrigin: string | null
  /** Dev only: the breached-password range API's origin (connect-src), or null to allow none. */
  breachOrigin: string | null
  /** Dev only: also allow the Vite HMR websocket (`ws:`). Never set for a production build. */
  dev?: boolean
  /** Dev only: the nonce Vite puts on the <style> elements it injects (`html.cspNonce`, new per dev-server start). */
  devStyleNonce?: string
}

/** The fixed <style> elements the reka-ui components we use render, by hash, so style-src needs no 'unsafe-inline':
 *  SelectViewport's (under VeyqSelect), which hides the list's scrollbar. csp.test.ts recomputes the set from the
 *  installed reka-ui over every component this client imports, so an upgrade or a new import cannot slip by. */
export const REKA_STYLE_HASHES: readonly string[] = ["'sha256-60LHlRjW/B3CtzIoE/Lf1/NEDvko9efWMFaGVhHu/cs='"]

/** The emitted policy's file name inside `dist`. The backend's app shell reads it by the same name
 *  (`AppShell.POLICY_FILE`) and refuses to boot without it, which is what catches the two drifting apart. */
export const CSP_POLICY_FILE = 'content-security-policy.txt'

/**
 * Build the Content-Security-Policy value. The built policy names no outside service: the map tiles, the geocoder and
 * the breached-password check are the Server Owner's settings (uc.admin.configure-external-services), and the backend
 * appends the origin of each one in use to `img-src` / `connect-src` on every response (ADR 0069, ADR 0088 decision 7).
 * Only the dev server, which serves the app itself, passes their origins here (`vite.config.ts`).
 */
export function buildCsp({ tileOrigin, geocoderOrigin, breachOrigin, dev = false, devStyleNonce }: CspOptions): string {
  const styleSrc = ["'self'", ...REKA_STYLE_HASHES]
  if (devStyleNonce) styleSrc.push(`'nonce-${devStyleNonce}'`) // Vite's injected stylesheets — dev server only

  const imgSrc = ["'self'", 'data:', 'blob:']
  if (tileOrigin) imgSrc.push(tileOrigin)

  const connectSrc = ["'self'"]
  if (geocoderOrigin) connectSrc.push(geocoderOrigin)
  if (breachOrigin) connectSrc.push(breachOrigin)
  if (dev) connectSrc.push('ws:') // Vite HMR socket — dev server only, never in a production build

  const directives: Record<string, string[]> = {
    'default-src': ["'self'"],
    'base-uri': ["'self'"],
    'object-src': ["'none'"],
    'frame-src': ["'none'"],
    'frame-ancestors': ["'none'"],
    'script-src': ["'self'"],
    'worker-src': ["'self'"],
    'style-src': styleSrc,
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
