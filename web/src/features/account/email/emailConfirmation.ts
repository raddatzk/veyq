// uc.account.confirm-email-address (web, ADR 0074): the caller's address as the server holds it, whether it is
// confirmed, and the confirmation mail. A fact the screen shows, never a gate — nothing in the app branches on it.
import { accountEmailApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { HttpStatus, httpStatus } from '../../../shared/httpError'
import type { AccountEmail } from '../../../api/generated/account'

export type { AccountEmail } from '../../../api/generated/account'

/** The address a reset would mail — the server's copy, not what this device remembers typing. */
export function getAccountEmail(): Promise<AccountEmail> {
  return withRefresh(() => accountEmailApi().getAccountEmail())
}

/** Mail a confirmation link to the caller's own address, through the relay of the space this page is served on. */
export function requestEmailConfirmation(): Promise<void> {
  return withRefresh(() => accountEmailApi().requestEmailConfirmation())
}

/** Confirm with the token from a confirmation mail. Only the account it was minted for can. */
export function confirmEmail(token: string): Promise<void> {
  return withRefresh(() => accountEmailApi().confirmEmail({ emailConfirmationRequest: { token } }))
}

/**
 * The message for a refused confirmation request, by status alone: the contract gives each of these exactly one
 * meaning on this endpoint (`503` no mail, `404` not a member of this space, `429` budget), so no code is read.
 * There is deliberately no copy-link branch — a link shown on this screen proves nothing about the mailbox.
 */
export function requestErrorKey(e: unknown): string {
  switch (httpStatus(e)) {
    case HttpStatus.serviceUnavailable:
      return 'emailConfirm.mailUnavailable'
    case HttpStatus.notFound:
      return 'emailConfirm.notMember'
    case HttpStatus.tooManyRequests:
      return 'emailConfirm.rateLimited'
    default:
      return 'emailConfirm.requestFailed'
  }
}
