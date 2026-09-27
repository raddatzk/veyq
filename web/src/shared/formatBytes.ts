/**
 * A byte count in the reader's locale, in binary steps (1024) — the one formatter a file's size (the lightbox) and a
 * tenant's storage usage (the storage settings) share. Zero reads "0 B"; the caller decides whether an absent size
 * shows at all.
 */
export function formatBytes(bytes: number, locale: string): string {
  const units = ['B', 'kB', 'MB', 'GB', 'TB']
  let n = Math.max(0, bytes)
  let u = 0
  while (n >= 1024 && u < units.length - 1) { n /= 1024; u += 1 }
  return `${new Intl.NumberFormat(locale === 'de' ? 'de-DE' : 'en-US', { maximumFractionDigits: u === 0 ? 0 : 1 }).format(n)} ${units[u]}`
}
