// Content-Security-Policy for the admin console (ADR 0065). `vite.config.ts` injects it as a <meta http-equiv> so
// it ships inside the static build with no server needed (self-hostable by default), and mirrors it onto the dev
// server as a real header so dev == prod. Strictly tighter than src/web/'s: this app loads NO third-party origin but
// one — no tiles, no geocoder, no fonts — so `default-src 'self'` needs no escape hatches beyond the two the
// toolchain forces (inline styles from the PrimeVue theme, and `blob:` for the Emergency Kit PDF the browser
// hands to the operator to save).
//
// frame-ancestors is deliberately ABSENT: a <meta> policy ignores it, so writing it here only reads as a
// guarantee. Anti-framing comes from the container's nginx, which sends `X-Frame-Options: DENY` as a real header
// (`src/admin-web/nginx.conf.template`). See docs/crosscutting/cc.security.csp.md.

/** The pwned-password range API's default origin (k-anonymity: only a 5-hex SHA-1 prefix leaves the device) — the ONE
 *  outbound call this surface makes, automatically over a finished password, and only while the instance's manifest
 *  carries `core.breached-password` (ADR 0088 decision 7). This policy is a static `<meta>` served by nginx, outside
 *  the backend, so it cannot follow the Server Owner's setting: a mirror at another origin is refused here and setup
 *  says the check could not run (uc.admin.configure-external-services A3), as on a firewalled admin container. */
export const PWNED_ORIGIN = 'https://api.pwnedpasswords.com'

export const ADMIN_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  `connect-src 'self' ${PWNED_ORIGIN}`,
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join('; ')
