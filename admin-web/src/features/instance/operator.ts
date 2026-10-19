// uc.admin.operator-details — the operating mode (ADR 0093 § 14, ADR 0088 decision 14) and the values a host publishes
// about itself (ADR 0093 § 11, ADR 0035 decisions 6–7), Server-Owner only. Every rule the setup step, the two panels and
// `veyq-admin` share lives here, so none of them can hold a laxer one (admin-web/CLAUDE.md):
//   - *private* is set only with the notice accepted ([operatingModeRequest]); the server records no tick, so this is
//     the one place it is asked;
//   - which preset switches a mode would change ([presetChanges]) — from the server's own preset, never a copy;
//   - the warnings and when they show ([operatorWarnings]) — only in the organisation mode, never a refusal;
//   - the privacy notice's recipients (ADR 0035 decision 8): what they do is a fixed kind the notice names in each of its
//     languages, and only an OTHER one carries the operator's own words ([recipientFits], the server's rule); a row as
//     the CLI types it ([parseRecipient]), and a suggestion the instance makes turned into a row of its kind the Server
//     Owner completes ([suggestionRow]) — never stored as is. Where they are is an ISO 3166-1 alpha-2 code of the
//     backend's list (`./countries`), which the notice names in each page's language.
import { instanceOperatorApi } from '../../api/client'
import { countryCode } from './countries'
import {
  OperatingMode,
  OperatorRecipientKind,
  OperatorRecipientSuggestionKindEnum as SuggestionKind,
  type InstanceOperatingMode,
  type InstanceOperatingModePresetEntry,
  type InstanceOperator,
  type InstanceOperatorUpdate,
  type OperatorRecipient,
  type OperatorRecipientSuggestion,
} from '../../api/generated/instance'

export {
  OperatingMode, InstanceOperatorNotificationConfirmationEnum, OperatorRecipientKind, OperatorRecipientSuggestionKindEnum,
} from '../../api/generated/instance'
export type {
  InstanceOperatingMode, InstanceOperatingModePresetEntry, InstanceOperator, InstanceOperatorUpdate, OperatorRecipient,
  OperatorRecipientSuggestion,
} from '../../api/generated/instance'

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
 * What admin-web warns about (ADR 0093 §§ 11 and 14, ADR 0035 decisions 7 and 8). Only in the organisation mode; in
 * private none shows, whatever is empty. The contact warning shows while public links are on and no contact is set; the
 * identification warning independently, while its required parts (name, address for service, email) are not all set —
 * the representative is asked only of a legal person, which the console cannot tell, so it is never part of it; the
 * privacy warning independently, while the notice names no recipient or no supervisory authority (it then shows "not
 * stated by this operator") — the data-protection officer is never part of it, since only some controllers need one.
 */
export function operatorWarnings(
  op: InstanceOperator,
  publicSharesOn: boolean,
): { contact: boolean; identification: boolean; privacy: boolean } {
  if (op.operatingMode !== OperatingMode.Organisation) return { contact: false, identification: false, privacy: false }
  return {
    contact: publicSharesOn && !op.contactEmail,
    identification: !op.providerName || !op.providerAddress || !op.providerEmail,
    privacy: !op.recipients.length || !op.supervisoryAuthority,
  }
}

/** The kinds in the order the console offers them, OTHER last. */
export const RECIPIENT_KINDS: readonly OperatorRecipientKind[] = Object.values(OperatorRecipientKind)

/** The recipient kind a suggestion's row gets — the suggestion's own kind, which the contract keeps a subset of. */
const SUGGESTED_KIND: Record<SuggestionKind, OperatorRecipientKind> = {
  [SuggestionKind.ObjectStorage]: OperatorRecipientKind.ObjectStorage,
  [SuggestionKind.MailRelay]: OperatorRecipientKind.MailRelay,
}

/** Whether [r]'s description fits its kind, as the server holds it: required for OTHER, absent for every other kind. */
export function recipientFits(r: Pick<OperatorRecipient, 'kind' | 'description'>): boolean {
  return (r.kind === OperatorRecipientKind.Other) === Boolean(r.description?.trim())
}

/** A row offered for [s], of the kind the instance knows it to be — for the Server Owner to complete with the
 *  provider's name and country, and to keep or drop. The host stands in for the name until then. */
export function suggestionRow(s: OperatorRecipientSuggestion): OperatorRecipient {
  return { name: s.host, kind: SUGGESTED_KIND[s.kind], description: null, country: null }
}

/** Whether a recipient already names [s]'s host — the console then offers it no more. */
export function suggestionTaken(s: OperatorRecipientSuggestion, recipients: Pick<OperatorRecipient, 'name' | 'description'>[]): boolean {
  const host = s.host.toLowerCase()
  return recipients.some((r) => `${r.name} ${r.description ?? ''}`.toLowerCase().includes(host))
}

/** The kinds as the CLI spells them (`object-storage`), each to the wire's. */
export const CLI_RECIPIENT_KINDS: Readonly<Record<string, OperatorRecipientKind>> = Object.fromEntries(
  RECIPIENT_KINDS.map((k) => [k.toLowerCase().replace(/_/g, '-'), k]),
)

/**
 * A recipient as the CLI takes it: `<name>|<kind>|<country>`, the kind one of [CLI_RECIPIENT_KINDS] and an `other` one
 * with its description after a colon (`other:sends sign-in texts`), the country an ISO 3166-1 alpha-2 code (`DE`, in
 * either case) and optional; null when malformed — an unknown kind, an `other` without its words, words on any other
 * kind ([recipientFits]), or a country that is no code of the backend's list ([countryCode]).
 */
export function parseRecipient(typed: string): OperatorRecipient | null {
  const [name, what, country, ...rest] = typed.split('|').map((x) => x.trim())
  if (rest.length || !name || !what) return null
  const colon = what.indexOf(':')
  const kind = CLI_RECIPIENT_KINDS[(colon < 0 ? what : what.slice(0, colon)).trim().toLowerCase()]
  const description = colon < 0 ? null : what.slice(colon + 1).trim() || null
  const code = country ? countryCode(country) : null
  if (country && !code) return null
  const r: OperatorRecipient = { name, kind, description, country: code }
  return kind && recipientFits(r) ? r : null
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
    recipients: op.recipients.map((r) => ({
      name: r.name, kind: r.kind, description: r.description ?? null, country: r.country ?? null,
    })),
    dataProtectionOfficer: op.dataProtectionOfficer ?? null,
  }
}

/** A comma- or space-separated list of language tags as typed (`de, en`) → the tags, or null when none. */
export function parseLanguages(typed: string): string[] | null {
  const tags = typed.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean)
  return tags.length ? tags : null
}
