import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import { registerApplications } from './applications'
import { registerApplicationMessages } from './applicationMessages'
import { initializeTheme } from './shared/state/useThemeState'
import './style.css'
// The UI layer's own sheet (ADR 0107), after the app's: a sheet that styles a layer part compounds with its class.
import './shared/ui/layer.css'

async function bootstrap() {
  // Apply the stored theme before first paint (no flash of the wrong mode).
  initializeTheme()
  // Fill the application registry first: the shell asks it what to render. Build-time composition only
  // (ADR 0079 Decision 5); filled before boot() resolves the first route. The applications' strings with them (inbox #513).
  registerApplicationMessages()
  registerApplications()
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error banner) before mounting,
  // so the router lands on the right first screen.
  await boot()

  const app = createApp(App)
    .use(i18n)
    .use(router)

  router.isReady().then(() => app.mount('#app'))
}

void bootstrap()
