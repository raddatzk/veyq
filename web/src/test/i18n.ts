// The one way a unit test mounts with i18n (inbox #171): the app's OWN catalog, the app's OWN singleton.
//
// **Why the real catalog.** Tests used to hand-write a stub catalog per file. The stubs drifted: they kept keys the
// real catalog no longer had (`members.copyLink`, from the retired copy-link, among them), so those tests
// asserted against strings the app no longer shows, and stayed green. `eslint.config.js` keeps stubs out.
//
// **Why the singleton, not a fresh instance from the same messages.** Non-component code reads
// `i18n.global` directly (`onboardTail.ts`, `unlock.ts`). A second instance would leave that code on another
// catalog and another locale than the component under test.
//
// **Why a missing key fails the test by RECORD, not by throw.** A throwing `missing` handler only reddens a
// test when the throw escapes: a `t()` inside a `try` (a decoder building an error message), inside a
// re-render nobody awaits, or inside non-component code is swallowed, and the test stays green on a raw key.
// Recording every miss and asserting the list empty after each test cannot be swallowed, and it names the key.
//
// **Imported, not a global setup file.** Stryker runs vitest with the same `setupFiles`, and most tests mount
// nothing — so the catalog is installed by the tests that render text, through this helper.
import { afterEach } from 'vitest'
import { i18n } from '../i18n'

const missed: string[] = []

i18n.global.setMissingHandler((locale, key) => {
  missed.push(`${locale}:${key}`)
})

afterEach(() => {
  if (!missed.length) return
  const keys = [...new Set(missed.splice(0))].join(', ')
  throw new Error(`The test rendered keys the i18n catalog does not have: ${keys}`)
})

/** The app's i18n plugin, reset to English — pass it to `mount(…, { global: { plugins: [realI18n()] } })`. */
export function realI18n(): typeof i18n {
  i18n.global.locale.value = 'en'
  return i18n
}

/** Resolve a key through the real catalog, for an assertion that follows whatever the catalog says. A key the
 *  catalog lacks fails the test like a rendered one. */
export function t(key: string, named?: Record<string, unknown>): string {
  return named ? i18n.global.t(key, named) : i18n.global.t(key)
}
