// Locale-aware formatting for a Memory's curated day range (cr-0067). Ported from the design source
// `legacy/frontend-noe2ee/src/features/memories/utils/memoryDateRange.ts`, generalized from its hardcoded
// `de-DE` / "Datum fehlt" to a caller-supplied locale so EN+DE both read naturally. The mode-specific wording
// ("since …", "no date yet") is composed by the component from i18n; this module only formats the day parts.
import { isoDateToLocalDate } from '../../shared/isoDate'

/** Format a single `YYYY-MM-DD` day for `locale` (e.g. `12.03.2026` / `03/12/2026`); `''` when malformed. */
export function formatDay(iso: string | undefined, locale: string): string {
  if (!iso) return ''
  const date = isoDateToLocalDate(iso)
  if (!date) return ''
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric' }).format(date)
}

/**
 * Format a closed day range for `locale`: a single day when `from === to` (or `to` absent), else `from – to`.
 * `''` when `from` is missing. Used for the `single`/`range` modes and a materialized `auto` range.
 */
export function formatClosedRange(from: string | undefined, to: string | undefined, locale: string): string {
  if (!from) return ''
  const start = formatDay(from, locale)
  if (!to || to === from) return start
  return `${start} – ${formatDay(to, locale)}`
}

/** A manual `range` is valid only when both ends are set and `to` is not before `from` (E4). */
export function isValidMemoryDateRange(from: string, to: string): boolean {
  return Boolean(from && to && to >= from)
}
