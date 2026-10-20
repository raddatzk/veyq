// PrimeVue's own texts follow the app's language (inbox #385, #434). Its components read some strings from its
// config's `locale`, not from our catalog: every Dialog's and Toast's close button, the Password field's hidden
// prompt ("Enter a password", in every `<Password>`'s live region), a TabList's scroll arrows, a Select's status
// texts, the DatePicker's calendar. This is the ONE place that sets them - from the catalog's `primevue` block (and
// `common.close`), at start and on every language change. No component writes PrimeVue's locale itself: the
// DatePicker of a share dialog stayed English while only `IsoDateField` did.
//
// Which keys: those a component the app renders reads, measured against primevue 4.5.5 by grepping each imported
// component's `config.locale.*` reads (2026-10-15). A newly used component or prop brings its own - `showButtonBar`
// reads `today`/`clear`, a Select's `filter` reads `searchMessage`/`emptySearchMessage`, `<Password :feedback>`
// reads `weak`/`medium`/`strong`, `showTime` the `am`/`pm`/hour keys - into this mapping and both catalog blocks.
// Every other key stays PrimeVue's English default, unseen.
import { watch, type App } from 'vue'
import type { PrimeVueLocaleOptions } from 'primevue/config'
import { i18n } from '../i18n'

/** A list from the catalog (day and month names), each entry resolved. A missing or non-list key throws: a calendar
 *  with `undefined` month names fails silently on screen. */
function list(key: string): string[] {
  const raw: unknown = i18n.global.tm(key)
  if (!Array.isArray(raw) || raw.length === 0) throw new Error(`PrimeVue locale: '${key}' is not a list in the catalog`)
  return raw.map((m) => i18n.global.rt(m as string))
}

/** PrimeVue's locale texts in the app's active language. */
export function primeVueLocaleTexts(): Partial<PrimeVueLocaleOptions> {
  const { t } = i18n.global
  return {
    passwordPrompt: t('primevue.passwordPrompt'),
    // PrimeVue fills its own `{0}` in; the catalog spells it `{count}`, as every other count in it.
    selectionMessage: t('primevue.selectionMessage', { count: '{0}' }),
    emptySelectionMessage: t('primevue.emptySelectionMessage'),
    emptyMessage: t('primevue.emptyMessage'),
    firstDayOfWeek: Number(t('primevue.firstDayOfWeek')),
    dayNamesMin: list('primevue.dayNamesMin'),
    monthNames: list('primevue.monthNames'),
    monthNamesShort: list('primevue.monthNamesShort'),
    chooseDate: t('primevue.chooseDate'),
    chooseMonth: t('primevue.chooseMonth'),
    chooseYear: t('primevue.chooseYear'),
    prevMonth: t('primevue.prevMonth'),
    nextMonth: t('primevue.nextMonth'),
    prevYear: t('primevue.prevYear'),
    nextYear: t('primevue.nextYear'),
    prevDecade: t('primevue.prevDecade'),
    nextDecade: t('primevue.nextDecade'),
  }
}

/** Keep the PrimeVue texts the app shows in its language, now and on every language change. Call after
 *  `app.use(PrimeVue)`. */
export function syncPrimeVueLocale(app: App): void {
  const config = app.config.globalProperties.$primevue.config
  // A new locale object each time, never a write into the one PrimeVue holds: a write there reached a second app's
  // PrimeVue too (the control in `primeVueLocale.test.ts` went red), so that object is shared, not this app's own.
  const apply = () => {
    const { t } = i18n.global
    const locale = config.locale
    config.locale = {
      ...locale,
      ...primeVueLocaleTexts(),
      aria: { ...locale?.aria, close: t('common.close'), previous: t('primevue.aria.previous'), next: t('primevue.aria.next') },
    } as typeof config.locale
  }
  apply()
  watch(i18n.global.locale, apply)
}
