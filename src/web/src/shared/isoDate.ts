// ISO calendar-day helpers (`YYYY-MM-DD`), ported verbatim from the design source
// `legacy/frontend-noe2ee/src/shared/utils/isoDate.ts` (cr-0067). A calendar day is a wall-clock day with no
// time or zone — the value the DatePicker binds to and the value a Memory's date range materializes into.

const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

/** Parse an `YYYY-MM-DD` string into a **local** midnight Date, or null when malformed / not a real day. */
export function isoDateToLocalDate(value: string): Date | null {
  const match = ISO_DATE_PATTERN.exec(value)
  if (!match) return null

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(year, month - 1, day)

  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) {
    return null
  }

  return date
}

/** Format a local Date as `YYYY-MM-DD` (its wall-clock day), or `''` when absent / invalid. */
export function localDateToIsoDate(value: Date | null | undefined): string {
  if (!value || Number.isNaN(value.getTime())) return ''

  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** Truncate an epoch-ms instant to its **local** calendar day (`YYYY-MM-DD`) — time and zone dropped. */
export function epochMsToIsoDate(ms: number): string {
  return localDateToIsoDate(new Date(ms))
}
