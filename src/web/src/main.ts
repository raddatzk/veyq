// FIRST, before any library module evaluates: it creates the Trusted Types policies the page's CSP names, and Leaflet
// writes `innerHTML` the moment it loads (ADR 0117, shared/security/trustedTypes.ts).
import './shared/security/trustedTypes'
import { createApp } from 'vue'
import App from './App.vue'
import router, { preloadRoute } from './router'
import { i18n } from './i18n'
import { boot } from './app/boot'
import { markStartStep, reloadOnChunkFailure, runStart } from './app/startSignals'
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
  // Every page is a chunk of its own (inbox #1310): start fetching the one this address opens now, so it travels while
  // boot() asks the server rather than after. The navigation below awaits the same module.
  preloadRoute(window.location.pathname + window.location.search + window.location.hash)
  // Discover the instance state (fresh → setup, set-up → login, unreachable → error banner) before mounting,
  // so the router lands on the right first screen.
  await boot()
  markStartStep('route')

  const app = createApp(App)
    .use(i18n)
    .use(router)

  await router.isReady()
  app.mount('#app')
}

// The start reports itself to the start watchdog (ADR 0136, `app/startSignals.ts`, `boot/watchdog.ts`): `mounted` after
// the mount, `failed` when bootstrap() or the first route rejects, and a chunk that no longer loads as `failed` too. Until
// the mount, index.html's start screen is what shows — never a blank page.
reloadOnChunkFailure(window)
void runStart(bootstrap)
