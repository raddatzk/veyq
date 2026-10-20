/**
 * A byte count in the reader's locale. The default is **decimal** (steps of 1000: kB, MB, GB, TB), the way Finder, the file
 * picker and the browser print a file's size, so the figure beside a file matches what the reader's own system shows. A
 * caller that works in powers of two - the tenant's storage, whose limit is typed in GiB - asks for `'binary'` (steps of
 * 1024, written KiB, MiB, GiB, TiB), so the unit says what the step is. Zero reads "0 B"; the caller decides whether an
 * absent size shows at all.
 */
export type ByteBase = 'decimal' | 'binary'

const UNITS: Record<ByteBase, readonly string[]> = {
  decimal: ['B', 'kB', 'MB', 'GB', 'TB'],
  binary: ['B', 'KiB', 'MiB', 'GiB', 'TiB'],
}

export function formatBytes(bytes: number, locale: string, base: ByteBase = 'decimal'): string {
  const units = UNITS[base]
  const step = base === 'decimal' ? 1000 : 1024
  let n = Math.max(0, bytes)
  let u = 0
  while (n >= step && u < units.length - 1) { n /= step; u += 1 }
  return `${new Intl.NumberFormat(locale === 'de' ? 'de-DE' : 'en-US', { maximumFractionDigits: u === 0 ? 0 : 1 }).format(n)} ${units[u]}`
}
