<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../features/account/login/login'
import { signOut, takeSignOutNotice, type SignOutNotice } from '../features/account/login/signOut'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { KdfProfileRefused } from '@veyq/crypto/v1'
import { trustThisDevice, untrustThisDevice, isTrusted } from '../features/account/trustDevice/trustDevice'
import { enrolDeviceKey, clearDeviceKey, isRememberSupported } from '../features/account/rememberMe/deviceKeyWrap'
import { cacheSessionVault, clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { useCapabilities } from '../shared/state/useCapabilities'
import { useAsyncAction } from '../shared/state/useAsyncAction'
import { ACCOUNT_DEVICE_TRUST } from '../features/platform/capabilities'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { parseKit, type KitData } from '../features/account/emergencyKit/emergencyKit'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import { parsePairingPayload } from '../features/account/pairing/pairing'
import JoinDeviceCard from '../features/account/pairing/JoinDeviceCard.vue'
import SignInWithCodeCard from '../features/account/pairing/SignInWithCodeCard.vue'
import { rememberThisDeviceWrap } from '../features/account/deviceRegister'
import LockedSignOut from '../features/account/sessions/LockedSignOut.vue'
import { deviceId, guessDeviceLabel } from '../features/account/deviceIdentity'
import { vaultState, masterKey } from '../app/vault'
import { session } from '../app/session'
import { takeAuthPrefill } from '../app/authPrefill'
import { takeRoute } from '../app/pendingRoute'

// uc.account.remember-this-device (web, cr-0054) — ONE adaptive login/unlock screen with ONE "remember me"
// toggle (ADR 0005 §17-21). The same password authenticates AND derives the DEVICE-wrap KEK, so login + unlock
// collapse into a single submit. The screen shows only the fields this device still needs: the FIRST sign-in
// per device (no DEVICE wrap yet) adds the **Secret Key** field; once a wrap exists it is email + password
// only; a remembered device opens straight in at boot (no screen). On a successful unlock the client
// AUTO-ENROLS this device's single convenience wrap from the in-memory Master Key: remember off → a password
// DEVICE wrap; remember on → a password-less DEVICE_KEY wrap + the persistent session cookie. One wrap per
// device (§9): enrolling one drops the other. The Secret Key is typed once per device, then forgotten.
const { t } = useI18n()
const { hasCapability } = useCapabilities()
const router = useRouter()
const email = ref('')
const password = ref('')
const secretKey = ref('')
const error = ref('')
// Adaptive: the Secret-Key field shows unless THIS device has a password DEVICE wrap for the entered email
// (a trusted device unlocks with email + password and never shows it). Default true (shown) — the safe
// direction: a first sign-in and a fresh origin (empty per-origin IndexedDB) both need it, so we show it up
// front and only HIDE it once we positively confirm the entered email is trusted on this device. Resolved
// against the local trust record on mount + when the email changes.
const needsSecretKey = ref(true)
// Login already succeeded for the current inputs → a Secret-Key retry must re-unlock, not re-login. Seeded from a
// session boot restored without unlocking — e.g. the document a sign-out that never reached the server loads
// (uc.account.sign-out E4): the refresh cookie is still live, and this card's sign-out is then the retry.
const sessionReady = ref(session.accessToken !== null)
// The single per-device choice. Drives the login cookie (on → persistent) AND which convenience wrap is
// auto-enrolled. Only offered where IndexedDB + WebCrypto exist (a wrap can't otherwise be persisted).
const remember = ref(false)
// "Remember this device" needs BOTH this browser's device-key support AND the instance's `account.device-trust`
// capability (ADR 0032): an operator can mandate a full password every time, disabling device-local convenience
// wraps. When off, the toggle is hidden AND enrolConvenienceWrap enrols no wrap — matching the server, which
// rejects enrolling one with 422 capability_disabled.
const rememberSupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
// uc.account.sessions §E3 — the LOCKED entry point. The screen built for a lost laptop is reached from the
// device you would actually be holding: a fresh or foreign one, with no Emergency Kit. The Secret-Key field is
// `required`, and HTML5 constraint validation (not the router — `/login` carries no `meta` at all) is what
// closes this form, so the entry is a `type="button"` beside the submit that logs in and attempts NO unlock.
const lockedEntry = ref(false)
// Set by that entry and by its way back: this device persists NOTHING at any tier — no convenience wrap, no
// session vault. Without it the way-back unlock would end in `enrolConvenienceWrap()` and hand a foreign machine
// a saved key, which is the exact asset the flow protects. Cleared only by a sign-out, whose new document starts
// this screen afresh — which is what puts the "this is my new laptop after all" case one ordinary sign-in away
// from the normal behaviour.
const noPersist = ref(false)
// What the last sign-out in this tab left for this screen to say (see `showSignOutNotice`).
const notice = ref('')
const warning = ref('')
// uc.account.onboard-device — the handle of a pairing channel this device just scanned. Non-empty means the
// join card has replaced the form (routeless, like the locked state above it): this device is being let in from
// one that is already signed in, so it has no email, no password and no Secret Key to type here.
const joinHandle = ref('')
// uc.account.onboard-device § Main flow — sign in with a QR code. The OTHER direction's entry, and the other
// routeless card: this device displays a code and the signed-in one scans it. Deliberately NOT gated on
// `scanSupported` — a camera-less laptop is precisely this direction's case, and ADR 0059 §1's "no direction is
// the camera one" is only true if this way in is offered without one.
const signInWithCode = ref(false)

/** Decide whether to show the Secret-Key field for the entered email: needed unless this device already has a
 *  password DEVICE wrap for it (a remembered device auto-opens at boot, so if it reached this screen its
 *  restore failed and it correctly falls back to needing the Secret Key). Defaults to "needed" on any error. */
async function resolveDeviceState() {
  // No email yet → unknown account, keep the Secret Key shown (the safe first-sign-in default). With an email
  // it's needed unless this device already carries a password DEVICE wrap for it (then email + password
  // unlock it). A remembered device auto-opens at boot; if it reached this screen its restore failed, so it
  // falls back to needing it.
  needsSecretKey.value = email.value ? !(await isTrusted(email.value).catch(() => false)) : true
}

/** Write moment 2 of the device register (en.account.device-register): record the device↔wrap join at the ONE
 *  moment the new wrap id exists — every enrolment mints a fresh one (ADR 0005 §9). Best-effort and silent: a
 *  join that does not land leaves the device un-escalatable in the sessions view (§E4) and is re-asserted from
 *  this device's local record at the next unlock (write moment 1). The enrolment modules stay ignorant of the
 *  register — they hand the id back, and this caller records it. */
async function noteWrapOfThisDevice(wrapId: string | null) {
  if (!wrapId) return
  await rememberThisDeviceWrap(deviceId(), guessDeviceLabel(t('sessions.unknownDevice')), wrapId).catch(() => {})
}

/** Auto-enrol this device's convenience tier from the in-memory Master Key, per "remember me" (§18, §22).
 *  - **on** (persistent): a password-less DEVICE_KEY wrap (IndexedDB) — opens straight in across a restart.
 *  - **off** (session-scoped): the Master Key in `sessionStorage` (this browser session) PLUS a password DEVICE
 *    wrap so the next launch after a close is email + password (never the Secret Key).
 *  One tier per device, so the other is dropped first. Best-effort — the unlock already succeeded, so a failed
 *  enrolment just means a bit more friction next time (no silent lockout). */
async function enrolConvenienceWrap() {
  // §E3: the locked entry and its way back persist nothing, at any tier. This is the single guard for both the
  // convenience wrap and the session vault below, because every tier this screen enrols runs through here.
  if (noPersist.value) return
  const mk = masterKey()
  if (!mk) return
  if (remember.value) {
    await clearSessionVault() // drop the session-scoped vault — this device opts into persistence
    if (rememberSupported) {
      await untrustThisDevice(email.value).catch(() => {}) // drop a prior password DEVICE wrap
      await noteWrapOfThisDevice(await enrolDeviceKey(email.value, mk).catch(() => null))
    }
  } else {
    await cacheSessionVault(email.value, mk) // survive reloads this browser session
    if (rememberSupported) {
      await clearDeviceKey().catch(() => {}) // drop a prior password-less DEVICE_KEY wrap
      await noteWrapOfThisDevice(await trustThisDevice(email.value, password.value, mk).catch(() => null))
    }
  }
}

async function finishAndRoute() {
  password.value = ''
  secretKey.value = ''
  await router.push(takeRoute() ?? { name: 'home' })
}

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    // The password both authenticates and derives the unlock KEK, so a first submit logs in; a Secret-Key
    // retry (session already up) skips straight to unlock.
    if (!sessionReady.value) {
      // Web is cookie-based: the toggle picks the refresh-cookie lifetime — persistent ("remember me") vs a
      // session cookie (survives reloads, gone on browser close, §22).
      const scope = remember.value
        ? LoginVerifyRequestSessionScopeEnum.Persistent
        : LoginVerifyRequestSessionScopeEnum.Session
      await login(email.value, password.value, scope)
      sessionReady.value = true
    }
    if (!needsSecretKey.value) {
      // Returning trusted device — unlock with only the password (the DEVICE wrap).
      if (await unlockWithDevice(email.value, password.value).catch(() => false)) {
        // "remember me" on now → upgrade this device to the password-less wrap (the toggle is the per-device
        // choice, §18). Off → keep the DEVICE wrap, but refresh the session vault so reloads stay prompt-free.
        if (remember.value) await enrolConvenienceWrap()
        else if (!noPersist.value) await cacheSessionVault(email.value, masterKey()!)
        await finishAndRoute()
        return
      }
      // The device's wrap can't unlock (a stale local trust record whose server wrap is gone — e.g. it was
      // reset, ADR 0005 E2). This is NOT a failure: reveal the Secret-Key field (no error) and let the user
      // finish with it; the next submit re-unlocks + re-enrols, replacing the stale record. The local key is
      // intentionally kept (no self-wipe on a single server response — operator-blind anti-withholding).
      needsSecretKey.value = true
      return
    }
    // First sign-in / degraded device — unlock with the on-screen Secret Key, then auto-enrol the wrap.
    await unlockMasterKey(password.value, secretKey.value)
    await enrolConvenienceWrap()
    await finishAndRoute()
  },
  {
    // Before login: wrong password / unknown account → one generic failure. After: wrong Secret Key (keep the
    // session + inputs for a retry). Both non-enumerable. A served key-derivation profile this client does not
    // write (ADR 0017 rule 5) is the server's fault — say so, rather than send them to retype a right password.
    onError: (e) => {
      error.value =
        e instanceof KdfProfileRefused
          ? t('common.unreachable')
          : sessionReady.value ? t('unlock.failed') : t('login.failed')
    },
  },
)

async function submit() {
  error.value = ''
  notice.value = ''
  warning.value = ''
  await runSubmit()
}

// --- §E3: the locked entry point ---

/**
 * Log in **without attempting an unlock**, and show the locked state. Two things are deliberate here.
 *
 * The scope is **forced to `SESSION`** regardless of the remember-me toggle: an abandoned flow on a foreign
 * browser must not leave a persistent refresh cookie behind. Because that toggle is visible on the form this
 * button sits on, the locked state says so in its own copy — otherwise the control would silently lie.
 *
 * And no unlock is attempted at all, rather than attempted and swallowed: not unlocking is the intent on this
 * path, so a red "unlock failed" would be the screen contradicting the button that reached it.
 */
const { run: runLockedEntry, pending: enteringLocked } = useAsyncAction(
  async () => {
    // Re-entrant on purpose: the way back leaves the session up, so bailing out to the locked state a second
    // time must not try to log in again.
    if (!sessionReady.value) {
      await login(email.value, password.value, LoginVerifyRequestSessionScopeEnum.Session)
      sessionReady.value = true
    }
    lockedEntry.value = true
  },
  { onError: () => { error.value = t('login.failed') } },
)

async function enterLocked() {
  error.value = ''
  notice.value = ''
  warning.value = ''
  await runLockedEntry()
}

/** A sign-out ends in a new document (uc.account.sign-out), so what it has to say arrives here, read once. A
 *  sign-out that did not reach the server leaves the refresh cookie live: this card then comes back signed in,
 *  and its own sign-out button (below) is the fallback both warnings point at. */
function showSignOutNotice(left: SignOutNotice | null) {
  if (left === 'everywhere') notice.value = t('sessions.lockedDone')
  else if (left === 'everywhereUnconfirmed') warning.value = t('sessions.lockedDoneStillHere')
  else if (left === 'unconfirmed') warning.value = t('login.signOutUnconfirmed')
}

/** The way back: reveal the Secret-Key field again on the still-live session, persisting nothing. */
function onLockedUnlock() {
  lockedEntry.value = false
  noPersist.value = true
}

/** A kit arrived (uploaded, scanned or pasted) — the control has already filled the Secret Key; this screen
 *  also takes its email. Setting the email re-resolves the device state via its watcher; a kit is
 *  authoritative, so it overwrites any typed-in email. */
function applyKit(kit: KitData) {
  email.value = kit.email
  error.value = ''
}

/** Which decoded strings this screen's scanner should stop on. `SecretKeyField` keeps this a caller-supplied
 *  predicate precisely so this screen can widen it — and keeping it a predicate is what lets the camera scan
 *  past a foreign QR instead of reporting it as a failure.
 *
 *  ONE scan entry serves both codes this app produces: an Emergency Kit (`veyqkit:`) and a pairing channel
 *  (`veyqpair:`). A second button would be a second camera lifecycle, a second permission prompt and a second
 *  no-camera path — in the one place where a subtly different second solution is worst — and it would ask the
 *  user to know which of two visually identical codes they are holding. */
function acceptsScan(text: string): boolean {
  return parseKit(text) !== null || parsePairingPayload(text) !== null
}

/** The half of that dispatch the shared control cannot own: a decoded string it accepted but did not
 *  recognise as a kit is this screen's pairing payload. */
function onForeignScan(text: string) {
  joinHandle.value = parsePairingPayload(text) ?? ''
}

/** The join completed: this device now holds its own session and an open vault, so it leaves like any other
 *  successful unlock does. */
async function onJoinDone() {
  joinHandle.value = ''
  await finishAndRoute()
}

/** The QR sign-in completed. Same shape as the join above and for the same reason — the tail both directions
 *  share is what left this device signed in and unlocked. */
async function onSignInWithCodeDone() {
  signInWithCode.value = false
  await finishAndRoute()
}

/** Back to the sign-in form. Nothing was granted; the next attempt is a fresh channel from a fresh start here,
 *  never a resume of the one just left (the answer slot is write-once — ADR 0059 §6). */
function onSignInWithCodeCancel() {
  signInWithCode.value = false
}

/** Back to the sign-in form. Nothing was granted, or nothing more can be done on that channel — the next
 *  attempt is a fresh ceremony on the other device, started by a fresh scan. */
function onJoinCancel() {
  joinHandle.value = ''
}

// A different email is a different account. The watcher drops sessionReady on every keystroke (cheap — a
// re-login is required the moment the email changes, even before blur). The expensive IndexedDB trust lookup
// that decides whether to show the Secret-Key field is debounced so it runs shortly after the user pauses
// typing (the field appears without waiting for a blur) while still not flickering on every keystroke; blur
// (@change) resolves it immediately.
let resolveTimer: ReturnType<typeof setTimeout> | undefined
function scheduleResolve() {
  clearTimeout(resolveTimer)
  resolveTimer = setTimeout(() => void resolveDeviceState(), 250)
}
function onEmailChange() {
  clearTimeout(resolveTimer)
  void resolveDeviceState()
}

watch(email, () => {
  sessionReady.value = false
  scheduleResolve()
})

onBeforeUnmount(() => clearTimeout(resolveTimer))

onMounted(async () => {
  // Setup hands over email + Secret Key once, for the first sign-in. Both pre-fill the (adaptive) screen — the
  // device is fresh, so the Secret-Key field is shown. A returning user has no prefill.
  showSignOutNotice(takeSignOutNotice())
  const prefill = takeAuthPrefill()
  if (prefill) {
    email.value = prefill.email
    secretKey.value = prefill.secretKey
  }
  await resolveDeviceState()
})
</script>

<template>
  <Card class="login-card">
    <template #title>
      {{
        lockedEntry ? t('sessions.lockedTitle')
        : joinHandle ? t('pairing.joinTitle')
        : signInWithCode ? t('pairing.signInTitle')
        : t('login.title')
      }}
    </template>
    <template #content>
      <!-- §E3's locked state: signed in, deliberately not unlocked, one action. It replaces the form rather than
           sitting beside it — every field here would need the Secret Key this person does not have. -->
      <LockedSignOut v-if="lockedEntry" @unlock="onLockedUnlock" />

      <!-- uc.account.onboard-device — the device being let in. Same shape as the locked state and for the same
           reason: routeless, replacing the form, because none of these fields applies to a device that is about
           to be handed everything by another one. No route is added for it anywhere. -->
      <JoinDeviceCard
        v-else-if="joinHandle"
        :handle="joinHandle"
        @done="onJoinDone"
        @cancel="onJoinCancel"
      />

      <!-- uc.account.onboard-device — the other direction, from the same card and by the same mechanism: this
           device displays, the signed-in one scans. Routeless like the two above it; nothing reaches router.ts. -->
      <SignInWithCodeCard
        v-else-if="signInWithCode"
        @done="onSignInWithCodeDone"
        @cancel="onSignInWithCodeCancel"
      />

      <!-- One adaptive sign-in: email + password always; the Secret-Key field only on the first sign-in per
           device (or when a device wrap is unusable). A single submit logs in AND unlocks. -->
      <form v-else-if="!vaultState.unlocked" class="veyq-form" @submit.prevent="submit">
        <label>
          {{ t('common.email') }}
          <InputText
            v-model="email"
            type="email"
            name="email"
            autocomplete="username"
            required
            @change="onEmailChange"
          />
        </label>
        <label>
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="login-password"
            :input-props="{ name: 'password', autocomplete: 'current-password' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <label v-if="needsSecretKey">
          {{ t('unlock.secretKey') }}
          <SecretKeyField
            v-model="secretKey"
            input-id="unlock-secret-key"
            :accepts="acceptsScan"
            required
            @kit="applyKit"
            @scanned="onForeignScan"
          />
          <small class="field-hint">{{ t('unlock.secretKeyHint') }}</small>
        </label>
        <div v-if="rememberSupported" class="checkbox-row">
          <Checkbox v-model="remember" input-id="remember-me" binary />
          <label for="remember-me">
            {{ t('login.rememberMe') }}
            <small class="field-hint">{{ t('login.rememberMeHint') }}</small>
          </label>
        </div>
        <Button
          type="submit"
          :label="busy ? t('login.submitting') : t('login.submit')"
          icon="pi pi-sign-in"
          :loading="busy"
        />
        <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
        <Message v-if="notice" severity="success" :closable="false" class="form-message">{{ notice }}</Message>
        <Message v-if="warning" severity="warn" :closable="false" class="form-message">{{ warning }}</Message>
        <!-- §E3's entry point. `type="button"` is what makes it work at all: the gate on this form is HTML5
             constraint validation, which runs on submit only, so a submit-typed control would be blocked by the
             very `required` Secret-Key field this person cannot fill. Offered exactly where that field is. -->
        <Button
          v-if="needsSecretKey"
          type="button"
          :label="t('sessions.lockedEntry')"
          icon="pi pi-lock"
          severity="secondary"
          text
          :loading="enteringLocked"
          :disabled="!email || !password"
          @click="enterLocked"
        />
        <!-- uc.account.onboard-device — the way in for a device that has neither a password nor an Emergency
             Kit: another device it is signed in on hands it everything. `type="button"` for the same reason
             §E3's entry above is one — the gate on this form is HTML5 constraint validation, which a
             submit-typed control would trip on the very fields this person cannot fill.

             It carries NO `scanSupported` gate, unlike the scan icon in the Secret-Key field. This device only
             DISPLAYS a code; the camera it needs is on the other one. Gating it here would withhold the entry
             from exactly the hardware it exists for (ADR 0059 §1). -->
        <Button
          v-if="!sessionReady"
          type="button"
          :label="t('pairing.signInEntry')"
          icon="pi pi-qrcode"
          severity="secondary"
          text
          @click="signInWithCode = true"
        />
        <!-- Recovery needs no session, so it is reachable from here even when sign-in keeps failing — which is
             exactly when someone needs it. -->
        <Button
          v-if="!sessionReady"
          type="button"
          :label="t('recovery.forgot')"
          severity="secondary"
          text
          @click="router.push({ name: 'recover' })"
        />
        <Button
          v-if="sessionReady"
          type="button"
          :label="t('login.signOut')"
          icon="pi pi-sign-out"
          severity="secondary"
          text
          @click="signOut()"
        />
      </form>

      <!-- Unlocked → redirecting into the shell. No confirmation card; the navigation is the feedback. -->
      <div v-else class="login-redirecting" aria-hidden="true">
        <span class="pi pi-spin pi-spinner" />
      </div>
    </template>
  </Card>
</template>

<style scoped>
/* The unlocked state only flashes for the moment before the redirect — a neutral spinner, no card. */
.login-redirecting {
  display: grid;
  place-items: center;
  padding: 1.5rem 0;
}
.login-redirecting .pi-spinner {
  font-size: 1.5rem;
  color: var(--app-muted);
}
</style>
