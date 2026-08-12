// Redirect-to-login on an unauthorized response (web) — the reaction half of `isUnauthorized` (cr-0155, audit
// #74/P3d). The line `if (isUnauthorized(e)) router.push({ name: 'login' })` was copy-pasted 24× across the
// panels/views; this is it, once. Redirect-ONLY — it does NOT clear the session (the full clear-then-redirect
// sign-out is `features/account/login/signOut`). Curried so it drops straight into a `useAsyncData`/`useAsyncAction`
// `onError`; inline `catch` sites call `handle401(router)(e)`.
import type { Router } from 'vue-router'
import { isUnauthorized } from './httpError'

export function handle401(router: Router): (e: unknown) => void {
  return (e) => {
    if (isUnauthorized(e)) void router.push({ name: 'login' })
  }
}
