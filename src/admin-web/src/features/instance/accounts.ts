// uc.account.erase-account (admin-web) — the Server Owner's door to an account erasure (ADR 0101 decisions 1-3 and 9):
// a request that reached them another way than the member's own settings. Thin facade over the generated
// `instance-accounts` client, shared by the console's Accounts panel and the CLI's `account erase` /
// `account stop-erasure` / `account erasures`. Operator-blind: an address in, an address and three dates out — nothing
// about what the account holds.
import { instanceAccountsApi } from '../../api/client'
import type { PendingAccountErasure } from '../../api/generated/instance'

export type { PendingAccountErasure } from '../../api/generated/instance'
export { AccountErasureRequester } from '../../api/generated/instance'

/** Every pending erasure, earliest due first (the server's order). An erased account is no longer listed. */
export async function listAccountErasures(): Promise<PendingAccountErasure[]> {
  return (await instanceAccountsApi().listAccountErasures()).erasures
}

/**
 * Erase the account at this address: it stops at once (sessions end, sign-in refused, member removal in every tenant)
 * and is purged after the erasure's own window (`veyq.account.erasure.window`, 14 days). Refusals: 404 `account_not_found`; 409 `server_owner_account`,
 * `account_erasure_pending`, and `moderation_last_holder` — the account alone holds the moderation key, which goes with
 * it at this door (ADR 0101 decision 9): asked again with `loseModerationKey` once the Server Owner was warned, it is
 * erased all the same. A last owner is NOT refused — its tenant is left ownerless.
 */
export function eraseAccount(address: string, opts: { loseModerationKey?: boolean } = {}): Promise<PendingAccountErasure> {
  return instanceAccountsApi().eraseAccount({
    serverOwnerAccountErasureRequest: {
      address: address.trim(),
      ...(opts.loseModerationKey ? { confirmModerationKeyLoss: true } : {}),
    },
  })
}

/** Whether a refusal of {@link eraseAccount} is the last holder's: erasing it needs the warning and `loseModerationKey`. */
export const isLastKeyHolder = (code: string | null | undefined): boolean => code === 'moderation_last_holder'

/** Stop a pending erasure before the purge; the account signs in again. 404 `account_erasure_not_found`. */
export function stopAccountErasure(address: string): Promise<void> {
  return instanceAccountsApi().stopAccountErasure({ accountAddressRequest: { address: address.trim() } })
}

/**
 * The console's typed confirmation: the address typed twice, the same both times (case-insensitive, as the server
 * matches an account address). An erasure cannot be undone after the purge, and a typo would erase someone else.
 */
export function addressConfirmed(address: string, again: string): boolean {
  const a = address.trim().toLowerCase()
  return a.length > 0 && a === again.trim().toLowerCase()
}
