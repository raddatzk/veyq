// The moderator's own appointment (uc.moderation.serve-as-moderator): read it, accept it, confirm the address the
// instance mail reaches, and step down. Thin over the generated client; the role and the key proof are the server's.
import { moderationApi } from '../../api/client'
import type { ModeratorAppointment } from '../../api/generated/moderation'
import { withRefresh } from '../../app/sessionRefresh'
import { clearModerationKeys } from './moderationKeys'
import { proofFor } from './proof'

export type { ModeratorAppointment }

/** The caller's appointment (`NONE`, `PENDING`, `ACCEPTED`) and where its address confirmation stands. */
export const myAppointment = (): Promise<ModeratorAppointment> =>
  withRefresh(() => moderationApi().getMyModeratorAppointment())

/** Accept a pending appointment; the server mails a code to the account's address. `404 not_appointed`. */
export const acceptAppointment = (): Promise<ModeratorAppointment> =>
  withRefresh(() => moderationApi().acceptModeratorAppointment())

/** Mail a new code. `403 not_moderator`, `429 rate_limited`. */
export const sendAddressCode = (): Promise<ModeratorAppointment> =>
  withRefresh(() => moderationApi().sendModeratorAddressCode())

/** Confirm the address with the mailed code. `409 mail_code_invalid`, `429 rate_limited`. */
export const confirmAddress = (code: string): Promise<ModeratorAppointment> =>
  withRefresh(() => moderationApi().confirmModeratorAddress({ moderatorAddressConfirmation: { code: code.trim() } }))

/** Step down, key-proved; every held key is zeroed after. `409 moderation_last_holder`. */
export async function stepDown(): Promise<void> {
  await withRefresh(async () => moderationApi().stepDownAsModerator(await proofFor('stepDownAsModerator')))
  clearModerationKeys()
}
