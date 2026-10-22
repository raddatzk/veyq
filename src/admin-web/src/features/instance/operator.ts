// uc.admin.operator-details — the values a host publishes about itself (ADR 0093 § 11, ADR 0035 decisions 6–7),
// Server-Owner only. Every one is optional and nothing warns (ADR 0093 § 14: no operating mode); an empty one is
// published as "not stated by this operator". Every rule the panel and `veyq-admin` share lives here, so neither can
// hold a laxer one (src/admin-web/CLAUDE.md):
//   - the privacy notice's recipients (ADR 0035 decision 8): what they do is a fixed kind the notice names in each of its
//     languages, and only an OTHER one carries the operator's own words ([recipientFits], the server's rule); a row as
//     the CLI types it ([parseRecipient]), and a suggestion the instance makes turned into a row of its kind the Server
//     Owner completes ([suggestionRow]) — never stored as is. Where they are is an ISO 3166-1 alpha-2 code of the
//     backend's list (`./countries`), which the notice names in each page's language.
import { instanceOperatorApi } from '../../api/client'
import { countryCode } from './countries'
import {
  OperatorRecipientKind,
  OperatorRecipientSuggestionKindEnum as SuggestionKind,
  type InstanceOperator,
  type InstanceOperatorUpdate,
  type OperatorRecipient,
  type OperatorRecipientSuggestion,
} from '../../api/generated/instance'

export {
  InstanceOperatorNotificationConfirmationEnum, OperatorRecipientKind, OperatorRecipientSuggestionKindEnum,
} from '../../api/generated/instance'
export type {
  InstanceOperator, InstanceOperatorUpdate, OperatorRecipient, OperatorRecipientSuggestion,
} from '../../api/generated/instance'

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

/** Clear every value, the notification address and its confirmation included. */
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
