// The stable client-side device id + its best-effort display label (en.account.device-register).
//
// Two things live here because they are the two halves of "which device is this, and what do we call it" — and
// both are answers the *client* has to invent, because the server must never learn either.

const DEVICE_ID_KEY = 'veyq:device-id'

/**
 * The stable, opaque id of this device/browser profile — minted lazily on first need and kept for the lifetime
 * of the profile's storage.
 *
 * **Why `localStorage` and not one of the IndexedDB stores.** The three IndexedDB DBs (`veyq-trust`,
 * `veyq-remember`, `veyq-session`) hold key material, and they are *deliberately* wiped on a boot with no live
 * session and wholesale on instance setup. An id living beside them would be destroyed by those same wipes, so
 * **every session expiry would mint a new device**: the register would fill with one entry per expiry while
 * every real device kept losing its name. This id must outlive a session precisely because a device does.
 *
 * **Why that is not a downgrade in classification.** It is not key material and authorises nothing — it is an
 * opaque label-key that only ever leaves this client *sealed inside the register*. Storing it beside the other
 * non-secret `veyq:` client state is the honest classification. (A malicious operator serving the client could
 * read it — but at that point it can read the Master Key, so this adds no new class of exposure; ADR 0026.)
 *
 * **A private window gets a fresh one, and that is correct.** Its storage partition is discarded when the last
 * such window closes, so each private session is genuinely a new, disposable device to the register. The cost is
 * register growth, which the register bounds by eviction rather than by pretending the id survived.
 *
 * Storage can throw (some privacy modes deny access outright). Then this returns a per-page-load id: the
 * register write is best-effort anyway, so an un-storable device shows up unnamed rather than breaking anything.
 */
export function deviceId(): string {
  try {
    const existing = localStorage.getItem(DEVICE_ID_KEY)
    if (existing) return existing
    const minted = crypto.randomUUID()
    localStorage.setItem(DEVICE_ID_KEY, minted)
    return minted
  } catch {
    return crypto.randomUUID()
  }
}

/** Test seam + the "forget this browser entirely" path; not wired to any user-facing action today. */
export function clearDeviceId(): void {
  try {
    localStorage.removeItem(DEVICE_ID_KEY)
  } catch {
    /* nothing to clear if storage is denied */
  }
}

// Ordered longest-match-first: every Chromium derivative also claims "Chrome", and Chrome also claims "Safari".
// Matching in this order is what keeps Edge from being reported as Chrome and Chrome as Safari.
const BROWSERS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\bEdg[A-Za-z]*\//, 'Edge'],
  [/\bOPR\/|\bOpera\//, 'Opera'],
  [/\bSamsungBrowser\//, 'Samsung Internet'],
  [/\bFirefox\/|\bFxiOS\//, 'Firefox'],
  [/\bChrome\/|\bCriOS\//, 'Chrome'],
  [/\bSafari\//, 'Safari'],
]

const PLATFORMS: ReadonlyArray<readonly [RegExp, string]> = [
  [/\biPhone\b/, 'iPhone'],
  [/\biPad\b/, 'iPad'],
  [/\bAndroid\b/, 'Android'],
  [/\bMac OS X\b|\bMacintosh\b/, 'macOS'],
  [/\bWindows\b/, 'Windows'],
  [/\bCrOS\b/, 'ChromeOS'],
  [/\bLinux\b/, 'Linux'],
]

function match(ua: string, table: ReadonlyArray<readonly [RegExp, string]>): string | null {
  for (const [re, label] of table) if (re.test(ua)) return label
  return null
}

/**
 * A best-effort label for **this** device, e.g. `Firefox · macOS`.
 *
 * A browser cannot read a machine name, so this is a guess and never a device identity — it is a *seed* the user
 * renames. Two identical guesses are therefore a starting point, not a dead end. The browser and platform names
 * are proper nouns and stay untranslated; only the fully-unknown fallback needs a localized string, which the
 * caller supplies so this module carries no i18n dependency.
 *
 * Deliberately **not** derived from `navigator.userAgentData`: it is Chromium-only, so it would give a
 * *different* answer per browser family for no gain — one UA parse is the honest common denominator, and this
 * value is cosmetic.
 */
export function guessDeviceLabel(unknownLabel: string, ua: string = navigator.userAgent): string {
  const browser = match(ua, BROWSERS)
  const platform = match(ua, PLATFORMS)
  if (browser && platform) return `${browser} · ${platform}`
  return browser ?? platform ?? unknownLabel
}
