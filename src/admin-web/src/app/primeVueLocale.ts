// PrimeVue's own texts are in the console's language (inbox #434). Its components read some strings from its
// config's `locale`, not from our catalog: the Dialog's and Toast's close button and the Password field's hidden
// prompt ("Enter a password", in every `<Password>`'s live region). This is the ONE place that sets them, from the
// catalog's `primevue` block. The console picks its language from the browser once (`i18n/index.ts`); it still
// follows a change of `i18n.global.locale`, so nothing here assumes there is no switch.
//
// Which keys: those a component this package renders reads, measured against primevue 4.5.5 by grepping each
// imported component's `config.locale.*` reads (2026-10-15): `aria.close` (Dialog, Toast, a closable Message) and
// `passwordPrompt` (Password; every one has `:feedback="false"`, so `weak`/`medium`/`strong` never show). The app
// surface has its own copy of this sync with its own, larger key set (`src/web/src/app/primeVueLocale.ts`) - no shared
// UI between the packages (ADR 0065).
import { watch, type App } from 'vue'
import { i18n } from '../i18n'

/** Keep the PrimeVue texts the console shows in its language, now and on every language change. Call after
 *  `app.use(PrimeVue)`. */
export function syncPrimeVueLocale(app: App): void {
  const config = app.config.globalProperties.$primevue.config
  // A new locale object each time, never a write into the one PrimeVue holds: that object is PrimeVue's module-level
  // default, shared by every app that installs it (web's copy found this - its control test went red).
  const apply = () => {
    const { t } = i18n.global
    const locale = config.locale
    config.locale = {
      ...locale,
      passwordPrompt: t('primevue.passwordPrompt'),
      aria: { ...locale?.aria, close: t('primevue.aria.close') },
    } as typeof config.locale
  }
  apply()
  watch(i18n.global.locale, apply)
}
