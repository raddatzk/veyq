// PrimeVue's own labels follow the app's language (inbox #385): its components read a few strings from its config's
// `locale`, not from our catalog — most visibly every Dialog's close button, whose accessible name was the English
// "Close" in a German UI. Core's, because every surface's dialogs share the one PrimeVue instance.
import { watch, type App } from 'vue'
import { i18n } from '../i18n'

/** Keep the PrimeVue labels the app shows in its language, now and on every language change. Call after
 *  `app.use(PrimeVue)`. */
export function syncPrimeVueLocale(app: App): void {
  const config = app.config.globalProperties.$primevue.config
  // A new locale object each time, never a write into the one PrimeVue holds: a write there reached a second app's
  // PrimeVue too (the control in `primeVueLocale.test.ts` went red), so that object is shared, not this app's own.
  const apply = () => {
    const locale = config.locale
    config.locale = { ...locale, aria: { ...locale?.aria, close: i18n.global.t('common.close') } } as typeof config.locale
  }
  apply()
  watch(i18n.global.locale, apply)
}
