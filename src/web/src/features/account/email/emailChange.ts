// uc.account.change-email (web, ADR 0074 Decision 8): change the address this account signs in with.
//
// The address is the auth salt's input (ADR 0003), so the auth keypair moves with it: this derives the keypair under
// the NEW address's salt from the unchanged password and sends its public half, which the server keeps pending and
// installs together with the address once the link mailed there is opened in this account's session. Nothing else
// changes — the Master Key, every wrap and the Secret Key are untouched (the KEKs use stored random salts).
//
// **It moves the address a password reset is sent to, so it is gated like a password change** (ADR 0060 Decision 2):
// the password and the Secret Key are proven against the stored floor here first (`proveColdCredential`, the shared
// live proof), then the server checks the attestation by the CURRENT auth key (the password) and the Secret-Key
// proof (the Secret Key) for itself. A session alone moves nothing.
import * as v1 from '@veyq/crypto/v1'
import { deriveAuthKeypairAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountEmailChangeApi, loginApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { account } from '../../../app/account'
import { ColdCredentialRefused, proveColdCredential, staleAsRejected } from '../coldCredential'
import type { ColdCredentialRefusal } from '../coldCredential'
import { HttpStatus, httpStatus, isCodedError } from '../../../shared/httpError'

/** Thrown before any request that writes. Each reason is the shared proof's. */
export class EmailChangeRefused extends Error {
  constructor(public readonly reason: ColdCredentialRefusal) {
    super(`email change refused: ${reason}`)
    this.name = 'EmailChangeRefused'
  }
}

/**
 * Ask for the change. Resolves when the server answered `202` — which says only that IF the address can be used, a
 * link went to it: the answer is the same for an address another account holds (uc.account.change-email E1).
 */
export async function requestEmailChange(newEmail: string, password: string, secretKeyB64: string): Promise<void> {
  const mk = await proveColdCredential(password, secretKeyB64).catch(staleAsRejected).catch((e: unknown) => {
    if (e instanceof ColdCredentialRefused) throw new EmailChangeRefused(e.reason)
    throw e
  })
  // The proof's Master Key is not needed past the proof: this flow re-wraps nothing.
  mk.fill(0)
  // The attestation binds the account; its id comes from the key material every unlock reads.
  if (!account.userId) throw new EmailChangeRefused('vaultLocked')
  const secretKey = fromB64std(secretKeyB64.trim())
  const address = newEmail.trim()

  try {
    // The instance salt is a public constant, served with a login challenge (as the password change reads it).
    const challenge = await loginApi().requestLoginChallenge({ loginChallengeRequest: { email: account.email } })
    const instanceSalt = fromB64std(challenge.instanceSalt)
    const currentAuth = await deriveAuthKeypairAsync(password, v1.deriveAuthSalt(instanceSalt, account.email), v1.ARGON)
    const newAuth = await deriveAuthKeypairAsync(password, v1.deriveAuthSalt(instanceSalt, address), v1.ARGON)

    const attestationSignature = b64std(
      v1.authSign(
        currentAuth.privateKey,
        v1.emailChangeAttestationStatement({ userId: account.userId, email: address, authPublicKey: newAuth.publicKey }),
      ),
    )
    const proof = v1.deriveSecretKeyProofKeypair(secretKey)
    const secretKeyProofSignature = b64std(
      v1.secretKeyProofSign(
        proof.privateKey,
        v1.emailChangeProofStatement({
          currentAuthPublicKey: currentAuth.publicKey,
          authPublicKey: newAuth.publicKey,
          email: address,
        }),
      ),
    )
    proof.privateKey.fill(0)
    currentAuth.privateKey.fill(0)
    newAuth.privateKey.fill(0)

    await withRefresh(() =>
      accountEmailChangeApi().requestEmailChange({
        emailChangeRequest: {
          newEmail: address,
          authPublicKey: b64std(newAuth.publicKey),
          attestationSignature,
          secretKeyProofSignature,
        },
      }),
    )
  } finally {
    secretKey.fill(0)
  }
}

/** Install the change with the token from the mail. Only the account that asked can. */
export function confirmEmailChange(token: string): Promise<void> {
  return withRefresh(() => accountEmailChangeApi().confirmEmailChange({ emailChangeConfirmRequest: { token } }))
}

/**
 * The message for a refused request, by status: the contract gives each exactly one meaning here. A `403` is one of
 * the two proofs, both of which the shared proof above already checked against the floor — so it reads as the same
 * "password or Secret Key did not match" the local refusal says.
 */
export function requestErrorKey(e: unknown): string {
  if (e instanceof EmailChangeRefused) {
    return e.reason === 'credentialRejected'
      ? 'emailChange.errorCredential'
      : e.reason === 'vaultLocked'
        ? 'emailChange.errorLocked'
        : 'emailChange.errorFailed'
  }
  switch (httpStatus(e)) {
    case HttpStatus.forbidden:
      return 'emailChange.errorCredential'
    case HttpStatus.serviceUnavailable:
      return 'emailConfirm.mailUnavailable'
    case HttpStatus.notFound:
      return 'emailConfirm.notMember'
    case HttpStatus.tooManyRequests:
      return 'emailConfirm.rateLimited'
    default:
      return 'emailChange.errorFailed'
  }
}

/** What the confirmation page shows for a refused link. A `404` carries two codes: the neutral refusal of the link,
 *  and a page served on a space the account is not a member of. */
export type ConfirmRefusal = 'invalid' | 'notMember' | 'mailUnavailable' | 'signInFirst' | 'failed'

export async function confirmRefusal(e: unknown): Promise<ConfirmRefusal> {
  if (await isCodedError(e, HttpStatus.notFound, 'tenant_not_found')) return 'notMember'
  switch (httpStatus(e)) {
    case HttpStatus.notFound:
      return 'invalid'
    case HttpStatus.serviceUnavailable:
      return 'mailUnavailable'
    case HttpStatus.unauthorized:
      return 'signInFirst'
    default:
      return 'failed'
  }
}
