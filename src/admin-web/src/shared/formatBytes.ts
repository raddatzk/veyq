/**
 * A byte count in the reader's locale, in binary steps (1024) — a tenant's storage usage and quota in the console.
 * `web/` carries the same seven lines (`web/src/shared/formatBytes.ts`): the two packages share crypto and nothing
 * else (ADR 0065), and a formatter is not worth a shared package.
 */
export function formatBytes(bytes: number, locale: string): string {
  const units = ['B', 'kB', 'MB', 'GB', 'TB']
  let n = Math.max(0, bytes)
  let u = 0
  while (n >= 1024 && u < units.length - 1) { n /= 1024; u += 1 }
  return `${new Intl.NumberFormat(locale === 'de' ? 'de-DE' : 'en-US', { maximumFractionDigits: u === 0 ? 0 : 1 }).format(n)} ${units[u]}`
}
