// What a public link must carry in the open space (ADR 0088 decision 8, uc.admin.capability-requirements): the
// requirements on `core.public-shares` in effect — a password, an end date within N days of the day the link is made.
// Every share dialog reads them here, the one place, because they constrain the dialog's form rather than decide whether
// it is offered (that is the capability, which the host hands down as a right). The server refuses a link that misses
// one (`422 link_password_required` / `link_expiry_required`), so these only make the form say so before the click.
import { lastEndDay } from '@veyq/core-js/sharing'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { CORE_PUBLIC_SHARES } from '../../features/platform/capabilities'

export interface LinkRules {
  /** Every link needs a password. */
  passwordRequired: boolean
  /** A link ends at most this many days after it was made; null while no limit is in effect. */
  maxDays: number | null
}

export function linkRules(): LinkRules {
  const entry = useCapabilities().capabilityValues(CORE_PUBLIC_SHARES)
  return { passwordRequired: entry?.linkPassword === true, maxDays: entry?.linkMaxDays ?? null }
}

/**
 * The last local day a link made at [madeAt] may end on under [maxDays], for a date picker that saves the END of the
 * picked day (local midnight of that day); null while no limit is in effect. The rule is the platform's Kotlin
 * (`sharing/LinkDays.kt`, ADR 0155 decision 7.2, drawn through `@veyq/core-js/sharing`), the apps' link form counts by the
 * same function: from when the link was MADE, as the server counts — an edited link too (inbox #1057).
 */
export function lastExpiryDay(maxDays: number | null, madeAt: Date = new Date()): Date | null {
  const day = lastEndDay(maxDays, madeAt.getTime())
  return day == null ? null : new Date(1970, 0, 1 + day)
}
