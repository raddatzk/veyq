import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import { registerApplications } from './applications'
import { initializeTheme } from './shared/state/useThemeState'
import { syncPrimeVueLocale } from './app/primeVueLocale'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  // Apply the stored theme before first paint (no flash of the wrong mode).
  initializeTheme()
  // Fill the application registry first: the shell asks it what to render. Build-time composition only
  // (ADR 0079 Decision 5); filled before boot() resolves the first route.
  registerApplications()
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error banner) before mounting,
  // so the router lands on the right first screen.
  await boot()

  const app = createApp(App)
    .use(PrimeVue, { theme: { preset: Aura, options: { darkModeSelector: '.veyq-dark' } } })
    .use(ToastService)
    .use(i18n)
    .use(router)
  app.directive('tooltip', Tooltip)
  syncPrimeVueLocale(app)

  router.isReady().then(() => app.mount('#app'))
}

void bootstrap()
