// uc.admin.operator-details — the operating mode (ADR 0093 § 14, ADR 0088 decision 14) and the values a host publishes
// about itself (ADR 0093 § 11, ADR 0035 decisions 6–7), Server-Owner only. Every rule the setup step, the two panels and
// `veyq-admin` share lives here, so none of them can hold a laxer one (admin-web/CLAUDE.md):
//   - *private* is set only with the notice accepted ([operatingModeRequest]); the server records no tick, so this is
//     the one place it is asked;
//   - which preset switches a mode would change ([presetChanges]) — from the server's own preset, never a copy;
//   - the two warnings and when they show ([operatorWarnings]) — only in the organisation mode, never a refusal.
import { instanceOperatorApi } from '../../api/client'
import {
  OperatingMode,
  type InstanceOperatingMode,
  type InstanceOperatingModePresetEntry,
  type InstanceOperator,
  type InstanceOperatorUpdate,
} from '../../api/generated/instance'

export { OperatingMode, InstanceOperatorNotificationConfirmationEnum } from '../../api/generated/instance'
export type { InstanceOperatingMode, InstanceOperatingModePresetEntry, InstanceOperator, InstanceOperatorUpdate } from '../../api/generated/instance'

/** The capability entry a public link needs; the contact warning reads its effective state (ADR 0093 § 11). */
export const PUBLIC_SHARES_ID = 'core.public-shares'

/** Thrown when *private* is asked for without the notice accepted: nothing is sent. */
export class NoticeNotAccepted extends Error {
  constructor() {
    super('notice_not_accepted')
  }
}

/** Whether choosing [mode] needs the notice accepted first (ADR 0093 § 14: *private* needs the tick). */
export function needsAcceptance(mode: OperatingMode): boolean {
  return mode === OperatingMode.Private
}

/** The mode to send, or a refusal: *private* only with [accepted]. The setup step, the panel and the CLI all ask here. */
export function operatingModeRequest(mode: OperatingMode, accepted: boolean): OperatingMode {
  if (needsAcceptance(mode) && !accepted) throw new NoticeNotAccepted()
  return mode
}

/** The entry's value in [mode]: true = on. */
export function presetValue(entry: InstanceOperatingModePresetEntry, mode: OperatingMode): boolean {
  return mode === OperatingMode.Private ? entry.onInPrivate : entry.onInOrganisation
}

/** The preset entries whose own instance switch choosing [mode] would change, each with the state it would get. */
export function presetChanges(
  preset: InstanceOperatingModePresetEntry[],
  mode: OperatingMode,
): { entry: InstanceOperatingModePresetEntry; on: boolean }[] {
  return preset
    .map((entry) => ({ entry, on: presetValue(entry, mode) }))
    .filter(({ entry, on }) => entry.off === on)
}

/**
 * What admin-web warns about (ADR 0093 §§ 11 and 14, ADR 0035 decision 7). Only in the organisation mode; in private
 * neither shows, whatever is empty. The contact warning shows while public links are on and no contact is set; the
 * identification warning independently, while its required parts (name, address for service, email) are not all set —
 * the representative is asked only of a legal person, which the console cannot tell, so it is never part of it.
 */
export function operatorWarnings(op: InstanceOperator, publicSharesOn: boolean): { contact: boolean; identification: boolean } {
  if (op.operatingMode !== OperatingMode.Organisation) return { contact: false, identification: false }
  return {
    contact: publicSharesOn && !op.contactEmail,
    identification: !op.providerName || !op.providerAddress || !op.providerEmail,
  }
}

/** The mode in effect, whether one was ever chosen, and the preset with each entry's switch now. */
export function getOperatingMode(): Promise<InstanceOperatingMode> {
  return instanceOperatorApi().getInstanceOperatingMode()
}

/** Set the mode and with it the preset's instance switches, in one transaction on the server. *Private* needs
 *  [accepted]; without it nothing is sent ([NoticeNotAccepted]). */
export function setOperatingMode(mode: OperatingMode, accepted: boolean): Promise<InstanceOperatingMode> {
  const chosen = operatingModeRequest(mode, accepted)
  return instanceOperatorApi().setInstanceOperatingMode({ instanceOperatingModeUpdate: { mode: chosen } })
}

/** The values with the notification address and where its confirmation stands. */
export function getOperator(): Promise<InstanceOperator> {
  return instanceOperatorApi().getInstanceOperator()
}

/**
 * Store the values — a full replace: a value left out or null is cleared. A notification address that is set and not
 * confirmed gets a code through instance mail, once instance mail is confirmed (`instanceMailReady`). Refusal: 400
 * `invalid_request` (a malformed address or language tag, or a value over its bound); nothing is required.
 */
export function setOperator(update: InstanceOperatorUpdate): Promise<InstanceOperator> {
  return instanceOperatorApi().setInstanceOperator({ instanceOperatorUpdate: update })
}

/** Clear every value, the notification address and its confirmation included. The mode stays. */
export function clearOperator(): Promise<InstanceOperator> {
  return instanceOperatorApi().clearInstanceOperator()
}

/** Enter the code mailed to the notification address. Refusals: 409 `operator_code_invalid`, 429 `rate_limited`. */
export function confirmOperatorNotification(code: string): Promise<InstanceOperator> {
  return instanceOperatorApi().confirmOperatorNotification({ instanceOperatorConfirmation: { code } })
}

/** The values as an update that changes nothing — the base a partial change (the CLI's flags) is laid over. */
export function operatorUpdateOf(op: InstanceOperator): InstanceOperatorUpdate {
  return {
    // A value the read omits (the API sends no `null`) goes back as the `null` that means "none" — the same request
    // as before the API stopped sending `null`, field for field.
    contactEmail: op.contactEmail ?? null,
    contactLanguages: op.contactLanguages.length ? op.contactLanguages : null,
    statementEmail: op.statementEmail ?? null,
    notificationEmail: op.notificationEmail ?? null,
    providerName: op.providerName ?? null,
    providerAddress: op.providerAddress ?? null,
    providerEmail: op.providerEmail ?? null,
    providerRepresentative: op.providerRepresentative ?? null,
    supervisoryAuthority: op.supervisoryAuthority ?? null,
  }
}

/** A comma- or space-separated list of language tags as typed (`de, en`) → the tags, or null when none. */
export function parseLanguages(typed: string): string[] | null {
  const tags = typed.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean)
  return tags.length ? tags : null
}
