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
import { leaveLiveSession, liveSessionAddress, sameAccountAddress } from '../features/account/login/liveSession'
import { signOut, takeSignOutNotice, type SignOutNotice } from '../features/account/login/signOut'
import { unlockMasterKey, unlockWithDevice } from '../features/account/unlock/unlock'
import { KdfProfileRefused } from '@veyq/crypto/v1'
import { trustThisDevice, untrustThisDevice, isTrusted, trustedEmails } from '../features/account/trustDevice/trustDevice'
import {
  enrolDeviceKey,
  clearDeviceKey,
  isRememberSupported,
  rememberedWrapId,
  rememberedEmail,
} from '../features/account/rememberMe/deviceKeyWrap'
import { cacheSessionVault, clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { useCapabilities } from '../shared/state/useCapabilities'
import { useAsyncAction } from '../shared/state/useAsyncAction'
import { CORE_DEVICE_LINKING, CORE_REMEMBER_DEVICE, CORE_REMEMBER_DEVICE_PASSWORDLESS } from '../features/platform/capabilities'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { parseKit, type KitData } from '@veyq/crypto/emergencyKit'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import OperatorIdentification from '../core/operator/OperatorIdentification.vue'
import { parsePairingPayload } from '../features/account/pairing/pairing'
import JoinDeviceCard from '../features/account/pairing/JoinDeviceCard.vue'
import SignInWithCodeCard from '../features/account/pairing/SignInWithCodeCard.vue'
import { rememberThisDeviceWrap } from '../features/account/deviceRegister'
import LockedSignOut from '../features/account/sessions/LockedSignOut.vue'
import { deviceId, guessDeviceLabel } from '../features/account/deviceIdentity'
import { vaultState, masterKey } from '../app/vault'
import { session } from '../app/session'
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
// This device holds a convenience record for the entered email whose switch is off: the server withholds that wrap
// from the key material without deleting it (ADR 0088 decision 5), so the device needs the Secret Key, and the hint
// under that field says why instead of calling it a first sign-in.
const heldBack = ref(false)
// The session this tab holds while the screen is up: one a boot restored without opening the vault (a wrap withheld or
// gone, a session vault lost with its tab, the document a sign-out that never reached the server loads —
// uc.account.sign-out E4, where this card's sign-out is the retry), or the one this screen's own login made.
const liveSession = ref(session.accessToken !== null)
// The account `liveSession` belongs to, once known: the typed address after this screen's own login, the server's
// copy for a restored one (read at the first submit that needs it). Typing never drops the session (inbox #454): the
// submit compares, see `useOrLeaveLiveSession`.
let liveAddress: string | null = null
// The lifetime this screen's own login asked for; null for a restored session, whose cookie the client cannot read.
let liveScope: LoginVerifyRequestSessionScopeEnum | null = null
// The current submit stands on a session of the typed account → a failure from here on is the unlock's, which is what
// the error copy says. (A Secret-Key retry re-unlocks rather than re-logs in through `liveSession`.)
const sessionReady = ref(false)
// The single per-device choice. Drives the login cookie (on → persistent) AND which convenience wrap is
// auto-enrolled. Only offered where IndexedDB + WebCrypto exist (a wrap can't otherwise be persisted).
const remember = ref(false)
// The two convenience wraps are two register leaves (ADR 0088), each needing this browser's device-key support too:
// - "Remember this device" (the toggle, and the password-less DEVICE_KEY wrap it enrols) needs
//   `core.remember-device.passwordless`;
// - the password DEVICE wrap enrolled on the toggle-off path needs `core.remember-device`.
// An operator can mandate a full password every time, disabling either. Where one is off its wrap is never enrolled —
// matching the server, which refuses that method with 422 capability_disabled — and where the passwordless one is off
// the toggle is hidden. Each reads ONE id: the server ANDs the path, so passwordless is absent when its parent is off.
const rememberSupported = isRememberSupported() && hasCapability(CORE_REMEMBER_DEVICE_PASSWORDLESS)
const deviceWrapSupported = isRememberSupported() && hasCapability(CORE_REMEMBER_DEVICE)
// Linking a device (uc.account.onboard-device, `core.device-linking`): off, neither direction is offered — no "sign in
// with code" entry, and a scanned pairing code is not recognised, so the join card never replaces the form. The server
// refuses every /api/pairing/* call then anyway (422 capability_disabled).
const linkingSupported = hasCapability(CORE_DEVICE_LINKING)
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
// uc.account.onboard-device — the secret of a pairing channel this device just scanned. Non-empty means the
// join card has replaced the form (routeless, like the locked state above it): this device is being let in from
// one that is already signed in, so it has no email, no password and no Secret Key to type here.
const joinSecret = ref('')
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
  // falls back to needing it. A DEVICE wrap whose switch is off is withheld, so it unlocks nothing either: asking
  // for the Secret Key up front spares the member a submit that can only fail over to it.
  const trusted = email.value ? await isTrusted(email.value).catch(() => false) : false
  needsSecretKey.value = !(trusted && deviceWrapSupported)
  heldBack.value = trusted ? !deviceWrapSupported : await rememberedWhileOff()
}

/** This device keeps a password-less DEVICE_KEY record for the entered email while that switch is off. One wrap per
 *  device (ADR 0005 §9), so such a device holds no DEVICE wrap to fall back on: only the floor is left. */
async function rememberedWhileOff(): Promise<boolean> {
  return !!email.value && !rememberSupported && (await rememberedWrapId(email.value).catch(() => null)) !== null
}

/** The email of this device's one local record, or null (inbox #450). Both convenience tiers keep the email in
 *  plaintext on the device (the remember-me record names it, a trust record is keyed by it), so filling it in shows
 *  nothing the device does not already hold, and the field no longer reads "first time on this device" on a device
 *  that has a record. Records of two accounts are no answer: the screen does not pick an account for the member. */
async function deviceEmail(): Promise<string | null> {
  if (!isRememberSupported()) return null
  const emails = new Set(await trustedEmails().catch(() => []))
  const remembered = await rememberedEmail().catch(() => null)
  if (remembered) emails.add(remembered)
  return emails.size === 1 ? [...emails][0] : null
}

/** Prefill the email from the device's record, unless the member has typed one in the meantime. A prefill is a claim
 *  like a typed address: whether it keeps a restored session is the submit's comparison, not the record's word. */
async function prefillEmail() {
  const known = await deviceEmail()
  if (!known || email.value) return
  email.value = known
}

/**
 * Whether the session this tab holds serves this submit as it is (inbox #454). A live session of the typed account
 * does: the unlock runs inside it, with no second login and no second family. Otherwise it is ended at the server
 * first (`leaveLiveSession`), so the login that follows replaces it instead of leaving it listed in Devices and
 * sessions until it expires — first, because a logout clears the browser's refresh cookies, the new one included.
 *
 * The account is the server's answer, never the record's or the field's: the cookie may have revived another
 * account's session than the device record names. A submit that turned "remember me" on is not served by a session
 * whose cookie may be a session cookie — a refresh never raises a lifetime (ADR 0004) and the client cannot read it —
 * so it is replaced by a persistent login; with the toggle off (or on §E3's entry, which forces `SESSION`) nothing new
 * was chosen, and the session keeps the lifetime its own login picked.
 */
async function useOrLeaveLiveSession(staySignedIn: boolean): Promise<boolean> {
  if (!liveSession.value) return false
  liveAddress ??= await liveSessionAddress()
  const sameAccount = sameAccountAddress(liveAddress, email.value)
  if (sameAccount && !(staySignedIn && liveScope !== LoginVerifyRequestSessionScopeEnum.Persistent)) return true
  await leaveLiveSession()
  liveSession.value = false
  liveAddress = null
  liveScope = null
  return false
}

/** Log in with what is on the form, unless the live session serves it; then the submit stands on a session.
 *  `staySignedIn` is whether this submit chose "remember me" (`useOrLeaveLiveSession`). */
async function signInUnlessLive(staySignedIn: boolean, scope: () => Promise<LoginVerifyRequestSessionScopeEnum>) {
  if (!(await useOrLeaveLiveSession(staySignedIn))) {
    const chosen = await scope()
    await login(email.value, password.value, chosen)
    liveSession.value = true
    liveAddress = email.value
    liveScope = chosen
  }
  sessionReady.value = true
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
    // A DEVICE_KEY wrap withheld by its switch is not replaced (ADR 0088 decision 5): dropping it would delete the
    // wrap the server only holds back, and its local key with it, so switching back on would restore nothing. The
    // device keeps its record and needs the floor until then; an explicit forget still clears it.
    if (deviceWrapSupported && !(await rememberedWhileOff())) {
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
    // retry, or a live session of the typed account, skips straight to unlock.
    sessionReady.value = false
    await signInUnlessLive(remember.value, async () =>
      // Web is cookie-based: the toggle picks the refresh-cookie lifetime — persistent ("remember me") vs a
      // session cookie (survives reloads, gone on browser close, §22).
      //
      // A device that keeps its DEVICE_KEY record while `passwordless` is off keeps the persistent scope it chose
      // with the toggle, which is hidden now (inbox #450). A session cookie here would replace the device's
      // persistent one, so after a switch-on and a browser restart the device would meet its restored wrap with no
      // session and land on this screen, where the toggle left off makes it a trusted device and drops the wrap:
      // ADR 0088 decision 5's "switching back on restores every remembered device" would hold only until the
      // browser closes. This widens nothing a switched-off instance forbids. The cookie lifetime is a session
      // matter (ADR 0004), which the server grants any web login and no switch gates; the switch's subject is the
      // wrap, which the server keeps withholding, so the persistent session opens no vault: after a restart the
      // device still lands here and needs the password and the Secret Key until the switch is on again. A device
      // without such a record never chose "stay signed in", so it still gets the session scope.
      remember.value || (await rememberedWhileOff())
        ? LoginVerifyRequestSessionScopeEnum.Persistent
        : LoginVerifyRequestSessionScopeEnum.Session,
    )
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
    // time must not try to log in again — nor does a live session of the typed account, restored or not.
    // The toggle is not this entry's choice (it forces SESSION), so it replaces no restored session either.
    await signInUnlessLive(false, async () => LoginVerifyRequestSessionScopeEnum.Session)
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
  // uc.account.onboard-device E6: the session ended under a grant, so the panel that would have said so is gone.
  else if (left === 'grantInterrupted') warning.value = t('pairing.grantInterrupted')
  // inbox #322: any other ending this tab did not ask for — a refused refresh, or another tab's ending.
  else if (left === 'refused') warning.value = t('login.sessionEnded')
  else if (left === 'otherTab') warning.value = t('login.sessionEndedOtherTab')
  // uc.account.erase-account: this tab erased its own account, so the panel that asked is gone with the session.
  else if (left === 'erased') notice.value = t('erasure.ended')
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
  return parseKit(text) !== null || (linkingSupported && parsePairingPayload(text) !== null)
}

/** The half of that dispatch the shared control cannot own: a decoded string it accepted but did not
 *  recognise as a kit is this screen's pairing payload. */
function onForeignScan(text: string) {
  joinSecret.value = linkingSupported ? (parsePairingPayload(text) ?? '') : ''
}

/** The join completed: this device now holds its own session and an open vault, so it leaves like any other
 *  successful unlock does. */
async function onJoinDone() {
  joinSecret.value = ''
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
  joinSecret.value = ''
}

// The address decides which account the submit is for, and the submit decides what that means for a live session
// (`useOrLeaveLiveSession`); typing drops nothing. The IndexedDB trust lookup that decides whether to show the
// Secret-Key field is debounced so it runs shortly after the user pauses typing (the field appears without waiting
// for a blur) while still not flickering on every keystroke; blur (@change) resolves it immediately.
let resolveTimer: ReturnType<typeof setTimeout> | undefined
function scheduleResolve() {
  clearTimeout(resolveTimer)
  resolveTimer = setTimeout(() => void resolveDeviceState(), 250)
}
function onEmailChange() {
  clearTimeout(resolveTimer)
  void resolveDeviceState()
}

watch(email, scheduleResolve)

onBeforeUnmount(() => clearTimeout(resolveTimer))

onMounted(async () => {
  showSignOutNotice(takeSignOutNotice())
  await prefillEmail().catch(() => {}) // a convenience: a record that cannot be read leaves the field empty
  await resolveDeviceState()
})
</script>

<template>
  <Card class="login-card">
    <template #title>
      {{
        lockedEntry ? t('sessions.lockedTitle')
        : joinSecret ? t('pairing.joinTitle')
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
        v-else-if="joinSecret"
        :secret="joinSecret"
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
          <small class="field-hint">{{ t(heldBack ? 'unlock.deviceHeldBack' : 'unlock.secretKeyHint') }}</small>
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
          v-if="!liveSession && linkingSupported"
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
          v-if="!liveSession"
          type="button"
          :label="t('recovery.forgot')"
          severity="secondary"
          text
          @click="router.push({ name: 'recover' })"
        />
        <Button
          v-if="liveSession"
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
    <!-- Who runs this instance (ADR 0035 decision 7): shown where the Server Owner published it, absent otherwise. -->
    <template #footer>
      <div class="login-operator">
        <OperatorIdentification />
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
.login-operator {
  text-align: center;
}
.login-redirecting .pi-spinner {
  font-size: 1.5rem;
  color: var(--app-muted);
}
</style>
