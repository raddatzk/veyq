import { instanceApi } from '../../api/client'
import { InstanceMailSettingsConfirmationEnum, type InstanceMailSettings, type InstanceMailSettingsUpdate } from '../../api/generated/instance'

export type { InstanceMailSettings, InstanceMailSettingsUpdate } from '../../api/generated/instance'
export { InstanceMailSettingsConfirmationEnum, InstanceMailSettingsConfigSecretStateEnum } from '../../api/generated/instance'

/**
 * The instance's own mail settings (uc.admin.learn-of-new-releases, ADR 0097 decisions 3 and 5) — Server-Owner only.
 * They send release notices, and only to the Server Owner's account address (`address`); they are never a tenant's
 * mail, which stays each tenant owner's in `src/web/`. The password is never returned, only `passwordSet` (ADR 0050).
 */
export function getInstanceMail(): Promise<InstanceMailSettings> {
  return instanceApi().getInstanceMailSettings()
}

/**
 * Save the settings. Every save asks again: with a host set, the server mails a one-time code to the Server Owner's
 * address, and `confirmation` says whether it went out (`CODE_SENT`) or could not be delivered (`SEND_FAILED`). The
 * password is write-only — omit it to keep the stored one. Refusals: 400 `invalid_request` (a malformed field, or a
 * host without a from address), 503 `mail_secret_unavailable` (a password but no config-secret key to seal it).
 */
export function updateInstanceMail(update: InstanceMailSettingsUpdate): Promise<InstanceMailSettings> {
  return instanceApi().updateInstanceMailSettings({ instanceMailSettingsUpdate: update })
}

/** Enter the code the test mail carried. Refusals: 409 `mail_code_invalid` (none outstanding, expired, spent, or not
 *  it — save again for a new one), 429 `rate_limited`. */
export function confirmInstanceMail(code: string): Promise<InstanceMailSettings> {
  return instanceApi().confirmInstanceMail({ instanceMailConfirmation: { code } })
}

/**
 * Whether a Server Owner's statement of reasons cannot be mailed because no instance mail is set up (ADR 0093 § 7) — what
 * the console warns of before a step over content. Settings that are set up but not yet confirmed still send, so only
 * `NOT_CONFIGURED` counts. Best effort: a failed read answers false, and the step's answer still says what the mail
 * reached (`statementDelivery`).
 */
export async function statementMailNotSetUp(): Promise<boolean> {
  try {
    return (await getInstanceMail()).confirmation === InstanceMailSettingsConfirmationEnum.NotConfigured
  } catch {
    return false
  }
}
