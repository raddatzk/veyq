import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import Aura from '@primeuix/themes/aura'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import { initializeTheme } from './shared/state/useThemeState'
import './style.css'
import 'primeicons/primeicons.css'

async function bootstrap() {
  // Apply the stored theme before first paint (no flash of the wrong mode).
  initializeTheme()
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error banner) before mounting,
  // so the router lands on the right first screen.
  await boot()

  const app = createApp(App)
    .use(PrimeVue, { theme: { preset: Aura, options: { darkModeSelector: '.veyq-dark' } } })
    .use(ToastService)
    .use(i18n)
    .use(router)
  app.directive('tooltip', Tooltip)

  router.isReady().then(() => app.mount('#app'))
}

void bootstrap()
