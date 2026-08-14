// uc.account.onboard-device steps 5–7 (web) — what a device does once it has opened the grant, and the one
// part of this feature that is identical in both directions: *add a device* runs it after scanning, and *sign
// in with a QR code* will run the same function after its own binding step. That is why it lives in `pairing/`
// rather than beside the join card that happens to be its first caller.
//
// **The order is ADR 0059 §2's, not a preference:** adopt → unlock → register → enrol. The whole tail after
// adoption runs on an ordinary authenticated session — the key-material fetch is session-gated and so is the
// register write — and the *"stay signed in"* lifetime is fixed at adoption and can never be moved afterwards,
// which is why it is answered before the first call rather than at the end (ADR 0004 § web refresh cookie
// lifetimes).
import { tokenApi } from '../../../api/client'
import { TokenAdoptRequestSessionScopeEnum } from '../../../api/generated/auth'
import { setTokens } from '../../../app/session'
import { masterKey } from '../../../app/vault'
import { unlockWithGrantedMasterKey } from '../unlock/unlock'
import { enrolDeviceKey } from '../rememberMe/deviceKeyWrap'
import { cacheSessionVault } from '../rememberMe/sessionVault'
import { rememberThisDeviceWrap } from '../deviceRegister'
import { deviceId, guessDeviceLabel } from '../deviceIdentity'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import { i18n } from '../../../i18n'
import type { PairingGrant } from './pairing'

/**
 * The two adoption failures the caller must tell apart, because their remedies differ (D16):
 * - `gone` — the grant token was unknown, expired, superseded or its family revoked. Same message as every
 *   other terminal pairing state, but "start again" means a **new ceremony on the trusted device**: the token
 *   is spent and reloading here reaches nothing.
 * - `session_present` — this browser already carries a refresh cookie. Adoption is only for a device that
 *   holds no session here, so the remedy is not "start again" at all; it is "you are already signed in".
 */
export type OnboardTailFailure = 'gone' | 'session_present'

export class OnboardTailError extends Error {
  constructor(readonly reason: OnboardTailFailure) {
    super(`onboard_${reason}`)
    this.name = 'OnboardTailError'
  }
}

/**
 * Convert the handed-over refresh token into this device's own cookie-borne session.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ THIS CALL MAY BE PRESENTED EXACTLY ONCE, AND IT IS DELIBERATELY NOT WRAPPED IN `withRefresh`.         │
 * │ Adoption rotates the token it is given, so a second presentation of the same value is ADR 0004's      │
 * │ clone case and revokes the WHOLE family — the family of the device that has just arrived, which has   │
 * │ no password and no Secret Key to come back with. A lost response is therefore a terminal state and    │
 * │ not a case to try again: there is nothing safe to retry with.                                         │
 * │                                                                                                       │
 * │ Do not add a generic retry, a `withRefresh`, or an interceptor that can reach this line. `withRefresh` │
 * │ in particular would fire on exactly the 401 that means the token is already gone.                      │
 * └──────────────────────────────────────────────────────────────────────────────────────────────────────┘
 */
async function adopt(refreshToken: string, staySignedIn: boolean): Promise<void> {
  try {
    const res = await tokenApi().adoptSession({
      tokenAdoptRequest: {
        refreshToken,
        sessionScope: staySignedIn
          ? TokenAdoptRequestSessionScopeEnum.Persistent
          : TokenAdoptRequestSessionScopeEnum.Session,
      },
    })
    // `null` for the refresh token is what "it lives in the httpOnly cookie" is spelled as in this client — the
    // response deliberately omits a body token. The family id is why adoption returns the login response shape
    // at all: write moment 1, moments later, reads `session.familyId` to join this device to its family, and
    // the tail's unlock happens in this same page load, so it is there when that write runs.
    setTokens(res.accessToken, null, res.family.id)
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.unauthorized)) throw new OnboardTailError('gone')
    if (isHttpStatus(e, HttpStatus.badRequest)) throw new OnboardTailError('session_present')
    throw e
  }
}

/**
 * Run steps 5–7 on the device being onboarded: adopt the handed-over session, unlock from the handed-over
 * Master Key, write this device's own register entry under the name it was given, and settle the tier.
 *
 * `staySignedIn` decides two things at once (ADR 0005 §22, ADR 0004 § web refresh cookie lifetimes), and the
 * device being onboarded is the only one that can answer it — the granting device does not know whether this
 * machine is theirs (uc.account.onboard-device E5). Where the question cannot be asked at all — no IndexedDB,
 * or the instance has device trust switched off — the caller passes `false`, which is the only reachable tier.
 *
 * Throws {@link OnboardTailError} for the two adoption failures the caller renders differently; anything else
 * propagates as-is. **Do not call it twice for one grant** — see [adopt].
 */
export async function runOnboardTail(grant: PairingGrant, staySignedIn: boolean): Promise<void> {
  await adopt(grant.refreshToken, staySignedIn)
  // Write moment 1 rides inside this call and carries the handed-over label, so there is no register write of
  // our own here — a fourth write moment is exactly what en.account.device-register forbids.
  await unlockWithGrantedMasterKey(grant.email, grant.masterKey, grant.label)
  const mk = masterKey()
  if (!mk) return // the unlock above throws rather than returning locked; this is a type guard, not a branch
  const fallbackLabel = grant.label.trim() || guessDeviceLabel(i18n.global.t('sessions.unknownDevice'))
  if (staySignedIn) {
    // The password-less tier, exactly as any remembered device (ADR 0005 §18-20). Best-effort like every other
    // convenience enrolment: the vault is already open, so a failure here is friction next launch, not a lockout.
    const wrapId = await enrolDeviceKey(grant.email, mk).catch(() => null)
    // Write moment 2 — the one instant the new wrap id exists anywhere.
    if (wrapId) await rememberThisDeviceWrap(deviceId(), fallbackLabel, wrapId).catch(() => {})
  } else {
    // ADR 0005 §22's carve-out, and the reason this is NOT `enrolConvenienceWrap`'s declined arm: that one also
    // keeps a password `DEVICE` wrap so the next launch is email + password, and this device has never seen a
    // password to key one with. A linked device's Off tier is that recipe MINUS the `DEVICE` wrap — so closing
    // the browser ends it and the next visit is a fresh pairing. `trustThisDevice` is never called from here.
    await cacheSessionVault(grant.email, mk)
  }
}
