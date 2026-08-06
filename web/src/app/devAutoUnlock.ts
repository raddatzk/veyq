// DEV-ONLY auto-unlock (cr-dev-auto-unlock). A local-dev convenience so an operator who cannot type the password
// — a phone, or an agent barred by policy from entering credentials into a field — can reach the unlocked dev app
// hands-off, surviving front-end reloads and backend restarts.
//
// SECURITY — two independent reasons this can never reach production, plus a guard that proves it:
//   1. The ENTIRE real body lives inside `if (import.meta.env.DEV)`. Vite statically replaces that with `false` in a
//      build, so Rollup tree-shakes the whole branch (and the otherwise-unreferenced `runDevAutoUnlock`, its strings
//      and its `/__dev/seed-credentials` fetch) out of the prod bundle.
//   2. Its only credential source is the `apply:'serve'` `/__dev/seed-credentials` middleware (vite.config.ts),
//      which is dev-server code and is never part of `vite build`.
// `scripts/assert-no-dev-backdoor-in-dist.mjs` fails the build if either sentinel appears in dist/.
//
// It uses the app's REAL `login` + `unlockMasterKey` (the same flow the e2e tests use) — no new crypto, nothing the
// password path couldn't already do. Opt-in only: it fires solely when `?devlogin` is present (then persisted to a
// localStorage flag for in-app navigations), so a plain `npm run dev` is unchanged. NEVER remove the DEV gate.
import { login } from '../features/account/login/login'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { enrolDeviceKey, isRememberSupported } from '../features/account/rememberMe/deviceKeyWrap'
import { masterKey, vaultState } from './vault'

// The sentinel the dist-backdoor guard greps for — keep this literal in sync with the guard's SENTINELS.
const DEV_AUTOUNLOCK_MARKER = 'VEYQ_DEV_AUTOUNLOCK'
const OPT_IN_FLAG = 'veyq.dev-autologin'

/** Fire the dev auto-unlock when opted-in and still locked. Best-effort — any failure is logged, never thrown. */
async function runDevAutoUnlock(): Promise<void> {
  if (vaultState.unlocked) return
  // Opt-in: the `?devlogin` query param, or the flag it persisted so an in-app navigation keeps the intent.
  const optedIn =
    new URLSearchParams(window.location.search).has('devlogin') ||
    window.localStorage.getItem(OPT_IN_FLAG) === '1'
  if (!optedIn) return
  try {
    const res = await fetch('/__dev/seed-credentials')
    if (!res.ok) return // 404 ⇒ this machine never seeded → stay a no-op
    const { email, password, secretKey } = (await res.json()) as {
      email: string
      password: string
      secretKey: string
    }
    if (!email || !password || !secretKey) return
    // The real flow: a Persistent session (remember-me cookie) + the vault unlock, then enrol the password-less
    // device key so later boots restore without this branch at all.
    await login(email, password, LoginVerifyRequestSessionScopeEnum.Persistent)
    await unlockMasterKey(password, secretKey)
    if (isRememberSupported()) {
      const mk = masterKey()
      if (mk) await enrolDeviceKey(email, mk).catch(() => {})
    }
    window.localStorage.setItem(OPT_IN_FLAG, '1')
    console.info(`[${DEV_AUTOUNLOCK_MARKER}] dev auto-unlock succeeded for ${email}`)
  } catch (e) {
    console.warn(`[${DEV_AUTOUNLOCK_MARKER}] dev auto-unlock failed (non-fatal):`, e)
  }
}

/**
 * DEV-only, opt-in (`?devlogin`) auto-unlock of the local dev app. A no-op — and tree-shaken to nothing — in any
 * production build (see the security note above). Call from `boot()` after the normal remember-me restore.
 */
export async function maybeDevAutoUnlock(): Promise<void> {
  if (import.meta.env.DEV) {
    await runDevAutoUnlock()
  }
}
