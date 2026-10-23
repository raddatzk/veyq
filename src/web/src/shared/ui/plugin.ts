// What the layer needs installed into an app (ADR 0107): `main.ts` installs it, and so does a test that mounts the
// real components (`global: { plugins: [realI18n(), uiPlugin] }`). Its components' texts follow the app's language
// from the catalog.
import type { App } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Aura from '@primeuix/themes/aura'
import { syncPrimeVueLocale } from './primeVueLocale'
import 'primeicons/primeicons.css'

export const uiPlugin = {
  install(app: App): void {
    app.use(PrimeVue, { theme: { preset: Aura, options: { darkModeSelector: '.veyq-dark' } } })
    app.use(ToastService)
    syncPrimeVueLocale(app)
  },
}

/** The test twin, installed for every test by `src/test/setup.ts`: the same components, without the theme's styles
 *  and without the language sync (a test that is about the texts' language installs `uiPlugin` itself). */
export const uiTestPlugin = {
  install(app: App): void {
    app.use(PrimeVue)
    app.use(ToastService)
  },
}
