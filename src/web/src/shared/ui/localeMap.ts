// The shape `LocaleMarkdownEditor.vue` edits, plus the one rule that makes it worth a named type:
// **a key's PRESENCE means that language exists.** `{ de: '' }` is a German text somebody started and left
// empty; `{}` is no German at all. Two flat `de`/`en` string refs cannot say that difference — which is exactly
// why the tenant-terms editor used to render an empty box for a language nobody had written, with nothing
// saying whether that emptiness was deliberate.
//
// **Where the distinction stops, and it is not here.** The tenant-terms backend drops a blank locale on
// normalize (`TenantTermsHandler.normalizeOne`), and an all-blank update is a no-op there. So an
// added-but-still-empty language is an *editing-session* state, never a published one: it is gone again after a
// save round-trip. That is the right server behaviour — an empty language is not a published translation — but
// it means this type can hold a state the server will not keep, and a reader who expects the round-trip to be
// lossless will be wrong.
export type LocaleMap = Record<string, string>

import { LOCALE_LABELS, type SupportedLocale } from '../../i18n'

/**
 * A language's own name — "Deutsch", "English" — regardless of the current UI locale.
 *
 * `LOCALE_LABELS` is the app's single spelling of that two-element set; the language picker in `ProfileMenu`
 * reads the same one. This function exists so no surface re-derives it: the locale editor used to carry a
 * hand-written `l === 'de' ? t('terms.localeDe') : t('terms.localeEn')`, and the terms panel a second copy, so
 * the set was written out three times. An unrecognised code falls back to the code itself, which is honest —
 * inventing a label for a locale the app does not support would hide the real bug.
 */
export function localeLabel(locale: string): string {
  return LOCALE_LABELS[locale as SupportedLocale] ?? locale
}
