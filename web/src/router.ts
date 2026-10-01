import { watch } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import { useServerState } from './shared/state/useServerState'
import { session } from './app/session'
import { vaultState } from './app/vault'
import { rememberRoute } from './app/pendingRoute'
import { termsStale } from './app/activeSpace'
import NotSetUpView from './views/NotSetUpView.vue'
import SpaceOfflineView from './views/SpaceOfflineView.vue'
import LoginView from './views/LoginView.vue'
import SettingsHubView from './views/SettingsHubView.vue'
import TenantJoinView from './views/TenantJoinView.vue'
import TermsAcceptView from './views/TermsAcceptView.vue'
import MemoryHomeView from './views/MemoryHomeView.vue'
import GroupInvitationsView from './views/GroupInvitationsView.vue'
import ShareView from './views/ShareView.vue'
import RecoverView from './views/RecoverView.vue'
import ConfirmEmailView from './views/ConfirmEmailView.vue'

// A fresh instance lands on /not-set-up; an already-set-up one on /login. First-run setup is NOT a route here
// any more — it moved to `admin-web` with the rest of the Server-Owner surface (ADR 0065), which is what lets
// an operator bootstrap over `http://localhost:<admin-port>` before `VEYQ_DOMAIN` resolves anywhere. Tenant
// administration left with it; what remains of the Server Owner in this app is a signpost in the settings hub.
// The Memory shell is a single authenticated route (`/`, `meta.layout: 'app'`, needs an unlocked vault) — the
// current tree position lives in the URL **#fragment** (`#~me/reisen-2026/rom`, cr-0031), resolved
// client-side, so the operator never sees it.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: MemoryHomeView, meta: { layout: 'app', requiresUnlock: true } },
    { path: '/not-set-up', name: 'not-set-up', component: NotSetUpView },
    // The suspended tenant's own domain (uc.spaces.scope-access A7): every API call here is refused, so every route
    // lands on this full-page notice — reached from boot, or mid-session once a call learns it.
    { path: '/offline', name: 'offline', component: SpaceOfflineView },
    { path: '/login', name: 'login', component: LoginView },
    // The settings hub (cr-0078, ADR 0022): one scope-contextual page. `:section?` selects a section (wide shows
    // the sidebar + that section; narrow drills in). Needs an unlocked vault — sections read the active space,
    // and each surface (member directory, tenant admin) enforces its own authz server-side (403).
    { path: '/settings/:section?', name: 'settings', component: SettingsHubView, meta: { layout: 'app', requiresUnlock: true } },
    // The invitee's pending group-consent proposals inbox (uc.spaces.consent-group-add, cr-0094). Needs an
    // unlocked vault — each proposal's group name is sealed to the caller's identity and decrypted on-device.
    { path: '/invitations', name: 'invitations', component: GroupInvitationsView, meta: { layout: 'app', requiresUnlock: true } },
    // Public invitee-acceptance deep link (the bootstrap secret rides in the #fragment). No session gate —
    // preview/register are public and the existing-owner path signs in within the view (cr-0010).
    { path: '/tenant/accept', name: 'tenant-accept', component: TenantJoinView },
    // The member terms accept-gate (uc.account.accept-terms, cr-0114). A standalone full-screen card (no shell)
    // so a member who hasn't accepted the tenant's current terms can ONLY accept or sign out — every attempt to
    // reach a `requiresUnlock` shell route bounces here (below). Not itself unlock-gated: it reads the active
    // tenant from `activeSpace` (set by the shell) and bounces to `/` if none is resolved yet.
    { path: '/terms', name: 'terms', component: TermsAcceptView },
    // Public anonymous viewer share (uc.sharing.share-public-viewer, cr-0100). No session gate — an account-less
    // visitor opens ONE Memory read-only; the share key rides the #fragment (`/s/{shareId}#k=<K_share>`), resolved
    // + decrypted client-side. `layout: 'plain'` gives the gallery the full viewport (not the narrow public card).
    { path: '/s/:shareId', name: 'share', component: ShareView, meta: { layout: 'plain' } },
    // Password recovery (uc.account.recover-access, cr-account-recovery). No session gate — the whole point is
    // that the caller has no password to get one with. The one-time token rides the #fragment, like every other
    // veyq secret in a link, and the view strips it from the address bar as soon as it has been read.
    { path: '/recover', name: 'recover', component: RecoverView },
    // Address confirmation (uc.account.confirm-email-address, ADR 0074). Needs a SESSION — the view confirms only for
    // the account signed in on this device — but deliberately not an unlocked vault (operational state, not content),
    // so it is neither `requiresUnlock` nor in the app shell. The one-time token rides the #fragment.
    { path: '/confirm-email', name: 'confirm-email', component: ConfirmEmailView },
    { path: '/:pathMatch(.*)*', redirect: { name: 'login' } },
  ],
})

router.beforeEach((to) => {
  const { serverState } = useServerState()
  // A suspended tenant's own domain answers nothing but `403 tenant_suspended` — only the notice is left.
  if (serverState.suspended) return to.name === 'offline' ? true : { name: 'offline' }
  if (to.name === 'offline') return { name: 'login' }
  // Boot hasn't answered yet (null) — let navigation through; boot resolves before mount.
  if (serverState.setupCompleted === null) return true
  if (!serverState.setupCompleted && to.name !== 'not-set-up') return { name: 'not-set-up' }
  if (serverState.setupCompleted && to.name === 'not-set-up') return { name: 'login' }
  // The Memory shell needs a session AND an unlocked vault (it derives K_SC from the vaulted identity).
  if (to.meta.requiresUnlock) {
    if (session.accessToken === null || !vaultState.unlocked) {
      // Remember the intended deep link (incl. #fragment) so unlock restores it instead of the root.
      rememberRoute(to.fullPath)
      return { name: 'login' }
    }
  }
  // Terms accept-gate (cr-0114, ADR 0035): a member who hasn't accepted the active tenant's current terms is
  // bounced to /terms before any shell route. Sits ABOVE unlock (a session gate) — but the shell routes are all
  // `requiresUnlock`, and `termsStale()` reads the active tenant the shell resolved, so gating on `requiresUnlock`
  // is exact. The remembered deep link is restored after acceptance (the terms view consumes pendingRoute). On a
  // cold boot the tenant isn't entered yet, so `termsStale()` is false here; the watcher below re-fires the gate
  // once `enterScope` resolves stale terms.
  if (to.meta.requiresUnlock && to.name !== 'terms' && termsStale()) {
    rememberRoute(to.fullPath)
    return { name: 'terms' }
  }
  // An already-unlocked session on the login card jumps into the shell. The remembered deep link (if any) is
  // restored by the login view after unlock — it is the single consumer of pendingRoute, so we don't take it
  // here (that would race the view to the one-shot value and drop it).
  if (to.name === 'login' && session.accessToken !== null && vaultState.unlocked) return { name: 'home' }
  return true
})

// Cold-boot / late-resolution trigger for the terms gate (cr-0114): the tenant is entered AFTER `beforeEach`
// already allowed `/` through (from `MemoryHomeView`'s mount), so `enterScope` flips `termsStale()` to true only
// afterward. This watcher catches that transition and bounces the (already-mounted) shell to /terms, remembering
// the current route so acceptance returns the member where they were.
watch(termsStale, (stale) => {
  const current = router.currentRoute.value
  if (stale && current.name !== 'terms' && current.meta.requiresUnlock) {
    rememberRoute(current.fullPath)
    void router.push({ name: 'terms' })
  }
})

// Mid-session: a call on this domain learned its tenant is suspended (`handleTenantSuspended`) — leave for the notice.
watch(
  () => useServerState().serverState.suspended,
  (suspended) => {
    if (suspended && router.currentRoute.value.name !== 'offline') void router.replace({ name: 'offline' })
  },
)

export default router
