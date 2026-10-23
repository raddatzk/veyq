// Content-Security-Policy for the admin console (ADR 0065). `vite.config.ts` injects it as a <meta http-equiv> so
// it ships inside the static build with no server needed (self-hostable by default), and mirrors it onto the dev
// server as a real header. Strictly tighter than src/web/'s: this app loads NO third-party origin but one — no tiles,
// no geocoder, no fonts — so `default-src 'self'` needs one escape hatch: `blob:` images for the Emergency Kit PDF the
// browser hands to the operator to save.
//
// style-src has no 'unsafe-inline' (inbox #523): the built CSS is same-origin files and Vue applies :style bindings
// through the CSSOM, which style-src does not govern. The fixed <style> elements the components we use render are
// allowed by their hashes; the dev server, which injects every stylesheet as a <style>, adds a nonce (the one way dev differs).
// worker-src is spelled out although script-src 'self' already covers the Argon2 worker by fallback, so no edit of
// script-src can widen what may run as a worker (inbox #527).
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

/** The fixed <style> elements the reka-ui components we use render, by hash: SelectViewport's (under VeyqSelect) and
 *  ComboboxViewport's (under VeyqCombobox), each hiding its list's scrollbar. csp.test.ts recomputes the set from the
 *  installed reka-ui over every component this client imports, so an upgrade or a new import cannot slip by. */
export const REKA_STYLE_HASHES: readonly string[] = [
  "'sha256-60LHlRjW/B3CtzIoE/Lf1/NEDvko9efWMFaGVhHu/cs='",
  "'sha256-0sLsI2a+NIcumVvBF9zD/ArGqlZR2xfnxsALPmK7nj8='",
]

/** The policy; `devStyleNonce` only on the dev server, for the <style> elements Vite injects (`html.cspNonce`). */
export function adminCsp(devStyleNonce?: string): string {
  return [
    "default-src 'self'",
    "script-src 'self'",
    "worker-src 'self'",
    ["style-src 'self'", ...REKA_STYLE_HASHES, ...(devStyleNonce ? [`'nonce-${devStyleNonce}'`] : [])].join(' '),
    "img-src 'self' data: blob:",
    "font-src 'self' data:",
    `connect-src 'self' ${PWNED_ORIGIN}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
}

/** The built policy, the one the console ships. */
export const ADMIN_CSP = adminCsp()
