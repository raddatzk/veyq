// uc.memory.tracks (ADR 0130 decision 4) — the numbers beside a track, as words in the reader's locale: its distance, its
// duration, its climb and its descent, each left out where the file does not give it (no times, no elevations).
import type { TrackStats } from './gpx'

type Translate = (key: string, named: Record<string, unknown>) => string

/** A length in the reader's locale: metres under one kilometre, else kilometres to one decimal. */
export function formatDistance(meters: number, locale: string): string {
  if (meters < 1000) return new Intl.NumberFormat(locale, { style: 'unit', unit: 'meter', maximumFractionDigits: 0 }).format(meters)
  return new Intl.NumberFormat(locale, { style: 'unit', unit: 'kilometer', maximumFractionDigits: 1 }).format(meters / 1000)
}

/** A height in metres, whole. */
export function formatMeters(meters: number, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'unit', unit: 'meter', maximumFractionDigits: 0 }).format(meters)
}

/** The facts of a track in their order, each a phrase of its own: distance, duration, climb, descent. */
export function trackFacts(stats: TrackStats, locale: string, t: Translate): string[] {
  const facts = [formatDistance(stats.distanceMeters, locale)]
  if (stats.durationMs !== undefined) {
    const minutes = Math.round(stats.durationMs / 60_000)
    const h = Math.floor(minutes / 60)
    const m = minutes % 60
    facts.push(h > 0 ? t('memory.tracks.durationHours', { h, m }) : t('memory.tracks.durationMinutes', { m }))
  }
  if (stats.ascentMeters !== undefined) facts.push(t('memory.tracks.ascent', { value: formatMeters(stats.ascentMeters, locale) }))
  if (stats.descentMeters !== undefined) facts.push(t('memory.tracks.descent', { value: formatMeters(stats.descentMeters, locale) }))
  return facts
}
