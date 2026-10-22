import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import { registerApplications } from './applications'
import { registerApplicationMessages } from './applicationMessages'
import { initializeTheme } from './shared/state/useThemeState'
import './style.css'
// After style.css: the layer brings the icon font's sheet, whose `.pi` rules must come after the app's own (ui.css says
// where two classes are needed to win against it).
import { uiPlugin } from './shared/ui/plugin'

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
    .use(uiPlugin)
    .use(router)

  router.isReady().then(() => app.mount('#app'))
}

void bootstrap()
