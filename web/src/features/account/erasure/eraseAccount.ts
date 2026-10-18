// uc.account.erase-account (web, ADR 0101): a member erases their own account.
//
// The preview names the tenants whose last owner the account is (decision 9): one with other members needs the
// person's choice — delete it along, or name one of its members the new owner — and one with no other member is
// deleted with the account. The request proves the PASSWORD only (decision 1, "the way a password change does" its
// attestation): an Ed25519 signature with the auth key derived exactly as sign-in derives it, over the account id
// and a fresh login challenge the server spends with the request. No Secret Key: erasing re-wraps nothing.
//
// A `200` means every session of the account has ended, this tab's included. So nothing here calls the server again —
// not the logout (it would answer 401), not the remember-me wrap's delete (the server's wraps go with the purge). The
// browser clears its OWN records for the account (decision 4: session vault, remembered and trusted device, trust
// marks) and the session ends the way a sign-out ends one, leaving the sign-in page the `erased` notice.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountErasureApi, loginApi } from '../../../api/client'
import type { ErasureTenant, LastOwnerChoice } from '../../../api/generated/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { endSession } from '../../../app/sessionEnd'
import { explainEnding } from '../../../app/endingNotice'
import { account } from '../../../app/account'
import { masterKey } from '../../../app/vault'
import { clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { clearSessionVault } from '../rememberMe/sessionVault'
import { clearTrust } from '../trustDevice/deviceKey'
import { forgetMarks } from '../trustMarks'
import { HttpStatus, decodeHttpError } from '../../../shared/httpError'

export type { ErasureTenant, LastOwnerChoice } from '../../../api/generated/account'

/** What the panel can offer: the tenants the erasure decides about, or the Server Owner's refusal (A2). */
export type ErasurePreview = { kind: 'ready'; tenants: ErasureTenant[] } | { kind: 'serverOwner' }

/** Read the preview. The Server Owner's account is never erased: its `409` is an answer here, not a failure. */
export async function loadErasurePreview(): Promise<ErasurePreview> {
  try {
    const { tenants } = await withRefresh(() => accountErasureApi().getOwnAccountErasurePreview())
    return { kind: 'ready', tenants }
  } catch (e) {
    const { status, code } = await decodeHttpError(e)
    if (status === HttpStatus.conflict && code === 'server_owner_account') return { kind: 'serverOwner' }
    throw e
  }
}

/** Thrown before any request: the proof binds the account id and address, which only an unlocked session knows. */
export class EraseAccountRefused extends Error {
  constructor(public readonly reason: 'vaultLocked') {
    super(`account erasure refused: ${reason}`)
    this.name = 'EraseAccountRefused'
  }
}

/**
 * Erase the signed-in account. `choices` holds one entry per tenant the preview lists with other members
 * (`newOwnerId` null deletes it along). Resolves once the ending has started the sign-in page's load; a refusal
 * rejects with the server's error and changes nothing — not on the server, not in this browser.
 */
export async function eraseOwnAccount(password: string, choices: LastOwnerChoice[]): Promise<void> {
  const userId = account.userId
  const email = account.email
  if (!userId || !email) throw new EraseAccountRefused('vaultLocked')

  // The challenge is the nonce the proof binds, and it carries the instance salt sign-in derives under.
  const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email } })
  const auth = await deriveAuthKeypairAsync(password, v1.deriveAuthSalt(fromB64std(challenge.instanceSalt), email), v1.ARGON)
  let attestationSignature: string
  try {
    attestationSignature = b64std(
      v1.authSign(auth.privateKey, v1.eraseAccountAttestationStatement({ userId, nonce: fromB64std(challenge.nonce) })),
    )
  } finally {
    auth.privateKey.fill(0)
  }

  await withRefresh(() =>
    accountErasureApi().eraseOwnAccount({
      eraseOwnAccountRequest: { nonce: challenge.nonce, attestationSignature, lastOwnerChoices: choices },
    }),
  )

  // Every session of the account is over now. Any request still running in this tab will see its refresh refused and
  // start the ending itself — and then it says `erased` too, not that the session merely ended.
  await explainEnding('erased', () => forgetThisBrowser(email))
  await endSession('erased')
}

/** This browser's records for the erased account (ADR 0101 decision 4). Local only, each best-effort: a record left
 *  behind opens nothing — its account cannot sign in, and its wraps go with the purge. */
async function forgetThisBrowser(email: string): Promise<void> {
  await clearSessionVault().catch(() => undefined)
  await clearDeviceKey(false).catch(() => undefined)
  await clearTrust(email).catch(() => undefined)
  const mk = masterKey()
  if (mk) {
    try {
      await forgetMarks(mk)
    } finally {
      mk.fill(0)
    }
  }
}

/** The message for a refused erasure, by the contract's one meaning per status and code. Nothing was written. */
export async function erasureErrorKey(e: unknown): Promise<string> {
  if (e instanceof EraseAccountRefused) return 'erasure.errorLocked'
  const { status, code } = await decodeHttpError(e)
  if (status === HttpStatus.forbidden) return 'erasure.errorPassword'
  if (status === HttpStatus.conflict) {
    if (code === 'last_owner_choice_required') return 'erasure.errorChoiceRequired'
    if (code === 'moderation_last_holder') return 'erasure.errorModerationLastHolder'
    if (code === 'server_owner_account') return 'erasure.serverOwner'
  }
  return 'erasure.errorFailed'
}
