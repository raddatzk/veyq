// What a public link must carry in the open space (ADR 0088 decision 8, uc.admin.capability-requirements): the
// requirements on `core.public-shares` in effect — a password, an end date within N days of the day the link is made.
// Every share dialog reads them here, the one place, because they constrain the dialog's form rather than decide whether
// it is offered (that is the capability, which the host hands down as a right). The server refuses a link that misses
// one (`422 link_password_required` / `link_expiry_required`), so these only make the form say so before the click.
import { useCapabilities } from '../../shared/state/useCapabilities'
import { CORE_PUBLIC_SHARES } from '../../features/platform/capabilities'

const DAY_MS = 24 * 60 * 60 * 1000

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
 * picked day: that end must not pass `madeAt + maxDays` (the server's rule, counted from when the link was made), so the
 * last day is the one before the day `madeAt + maxDays` falls on. Null while no limit is in effect.
 */
export function lastExpiryDay(maxDays: number | null, madeAt: Date = new Date()): Date | null {
  if (maxDays === null) return null
  const limit = new Date(madeAt.getTime() + maxDays * DAY_MS)
  const day = new Date(limit.getFullYear(), limit.getMonth(), limit.getDate())
  day.setDate(day.getDate() - 1)
  return day
}
