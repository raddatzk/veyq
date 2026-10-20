// The Art. 18 report's fields as a text block to copy (ADR 0093 § 13, uc.moderation.report-to-an-authority): what the
// server answered from the case and its hold, laid out line by line for the authority's form — never a file, never
// content. Every time in UTC, the zone stated. The source port is a field like the others: what it is worth behind a
// further NAT is open (ADR 0093 § Legal basis, Still open 4), so no line says anything about it.
import type { ModerationCaseExport } from '../../../api/generated/moderation'

/** The labels the block is written with (the surface's language). */
export interface ExportLabels {
  case: string
  category: string
  origin: string
  timeZone: string
  reported: string
  measure: string
  lifted: string
  endedWithTarget: string
  uploaderAccount: string
  uploaderGuest: string
  viaLink: string
  guestWrite: string
  address: string
  port: string
  observed: string
  object: string
  noHeld: string
}

/** A time in UTC: to the second, or to the millisecond for a guest's write (the record keeps milliseconds). */
export function utc(d: Date, millis = false): string {
  const iso = d.toISOString()
  return `${iso.slice(0, 10)} ${millis ? iso.slice(11, 23) : iso.slice(11, 19)}`
}

/**
 * The block: the case and its category, what opened it, the time zone, each report's time, each measure, each uploader
 * the held rows name, and each held guest-write record with its address, port and time. [categoryLabel], [originLabel]
 * and [measureLabel] name the server's codes in words.
 */
export function renderExport(
  e: ModerationCaseExport,
  l: ExportLabels,
  categoryLabel: string,
  originLabel: string,
  measureLabel: (kind: string) => string,
): string {
  const lines = [
    `${l.case}: ${e.caseId}`,
    `${l.category}: ${categoryLabel}`,
    `${l.origin}: ${originLabel}`,
    `${l.timeZone}: ${e.timeZone}`,
    ...e.reportedAt.map((d) => `${l.reported}: ${utc(d)}`),
    ...e.measures.map((m) => `${l.measure}: ${measureLabel(m.kind)} ${m.targetId} — ${utc(m.takenAt)}${m.liftedAt ? ` (${m.endedWithTarget ? l.endedWithTarget : l.lifted} ${utc(m.liftedAt)})` : ''}`),
    ...e.uploaders.map((u) =>
      u.kind === 'ACCOUNT' ? `${l.uploaderAccount}: ${u.id}` : `${l.uploaderGuest}: ${u.id}${u.shareId ? ` (${l.viaLink} ${u.shareId})` : ''}`,
    ),
    ...e.guestWrites.map((g) =>
      `${l.guestWrite}: ${l.address} ${g.address}` + (g.port != null ? `, ${l.port} ${g.port}` : '') +
        `, ${l.observed} ${utc(g.observedAt, true)}, ${l.uploaderGuest} ${g.identityId}, ${l.object} ${g.objectKind} ${g.objectId}`,
    ),
  ]
  if (!e.held) lines.push(l.noHeld)
  return lines.join('\n')
}
