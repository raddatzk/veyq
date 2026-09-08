import { createRouter, createWebHistory } from 'vue-router'
import { serverState } from './shared/state/serverState'
import { session } from './app/session'
import SetupView from './views/SetupView.vue'
import LoginView from './views/LoginView.vue'
import AdminConsoleView from './views/AdminConsoleView.vue'

// Three screens, and the routing between them is the whole of this app's navigation:
//
//   /setup   first run only — no session exists yet, by construction (it mints the first account)
//   /login   Server-Owner sign-in
//   /        the console (tenants + release), behind the root Server-Owner gate the view itself applies
//
// The GATE is the point (ADR 0065). In `web/` there was no router-level artifact to lift: `probeServerRole()`
// decided at data-fetch time whether to OFFER a settings entry, so a non-owner just never saw a link. Here the
// whole app is the admin surface, so `/` must answer "may this caller be here" with a readable refusal, not a
// blank page. The guard below only handles what it can decide WITHOUT a round trip (setup pending, no token);
// the owner-vs-non-owner distinction needs the backend's own 403 and therefore lives in AdminConsoleView,
// which renders the refusal. Authorization stays server-side on every call either way.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'console', component: AdminConsoleView },
    { path: '/setup', name: 'setup', component: SetupView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:pathMatch(.*)*', redirect: { name: 'console' } },
  ],
})

router.beforeEach((to) => {
  // Boot hasn't answered yet (null) or the instance is unreachable — let navigation through; App renders the
  // unreachable state instead of a console that cannot load.
  if (serverState.setupCompleted === null) return true
  if (!serverState.setupCompleted && to.name !== 'setup') return { name: 'setup' }
  if (serverState.setupCompleted && to.name === 'setup') return { name: 'login' }
  if (to.name === 'console' && session.accessToken === null) return { name: 'login' }
  if (to.name === 'login' && session.accessToken !== null) return { name: 'console' }
  return true
})

export default router
