// What the web writes for a size, a day, a moment, a Memory's day and range, an amount of money, a list and a track's
// length, in English and German: the vectors the apps' core is held to (ADR 0154 decision 5,
// `src/mobile/words/format-vectors.json`, written by `scripts/format-vectors.mts`, re-rendered by this module's test).
// Each case calls the web's own formatter, or `Intl` with the options its call sites pass where the format is an
// inline `Intl.DateTimeFormat` (`dateStyle: 'medium'`, with `timeStyle: 'short'` for a moment; `de-DE` / `en-US`). A
// moment is written in UTC here, so the file is the same in every zone; the core turns an instant into the device's
// local wall clock first. Test support (`src/test/`, a consumer of core and the applications alike): no app code imports it.
import { formatBytes } from '../shared/formatBytes'
import { decimalOf, formatMoney, knownCurrencies, minorUnitsOf } from '../features/expenses/amounts'
import { formatClosedRange, formatDay } from '../features/memories/memoryDateRange'
import { formatTrackLength } from '../features/memories/music/audio'
import { formatDistance } from '../features/memories/track/trackFacts'
import { joinList } from '../core/node/selection'

export const LANGUAGES = ['en', 'de'] as const
type Language = (typeof LANGUAGES)[number]
type Said = Record<Language, string>

/** The locale tag the web's date call sites and `formatBytes` hand `Intl`. */
const tag = (l: Language) => (l === 'de' ? 'de-DE' : 'en-US')

/** Both languages' text of one input. */
function said(write: (l: Language) => string): Said {
  return { en: write('en'), de: write('de') }
}

/** A fixed pseudo-random sequence (a 32-bit LCG), so the file is the same on every run. */
function sequence(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0
    return s / 2 ** 32
  }
}

function sizes(): number[] {
  const edges = [
    0, 1, 999, 1000, 1001, 1049, 1050, 1051, 1449, 1450, 1500, 9949, 9950, 99_949, 99_950, 999_949, 999_950, 999_999,
    1_000_000, 1_049_999, 1_050_000, 1_500_000, 1_234_567_890, 999_950_000_000, 1_000_000_000_000, 1_500_000_000_000,
    999_949_999_999_999, 999_950_000_000_000, 5_000_000_000_000_000, Number.MAX_SAFE_INTEGER,
  ]
  const next = sequence(1154)
  const random: number[] = []
  // each magnitude from bytes to terabytes, so the rounding of every unit is seen many times
  for (let digits = 1; digits <= 15; digits++) {
    for (let i = 0; i < 12; i++) random.push(Math.floor(next() * 10 ** digits))
  }
  // exact ties of the one fraction digit (x.y5 of a unit), where rounding half away from zero shows
  for (let i = 0; i < 60; i++) {
    const unit = 1000 ** (1 + Math.floor(next() * 4))
    random.push((Math.floor(next() * 99_999) * 10 + 5) * (unit / 100))
  }
  return [...new Set([...edges, ...random])]
}

function days(): string[] {
  const out = ['1970-01-01', '2000-02-29', '1999-12-31', '2026-11-20', '9999-12-31', '0001-01-01', '0999-07-04']
  for (let m = 1; m <= 12; m++) out.push(`2026-${String(m).padStart(2, '0')}-${String(m === 2 ? 28 : m + 3).padStart(2, '0')}`)
  return out
}

function moments(): number[] {
  const base = Date.UTC(2026, 10, 20)
  const out = [0, base, base + 5 * 60_000, base + 12 * 3_600_000, base + 13 * 3_600_000 + 5 * 60_000,
    base + 23 * 3_600_000 + 59 * 60_000 + 59_999, base + 9 * 3_600_000 + 7 * 60_000, Date.UTC(2000, 1, 29, 1, 2, 3),
    -1, -86_400_000]
  const next = sequence(2026)
  for (let i = 0; i < 24; i++) out.push(Math.floor(next() * 4_102_444_800_000))
  return out
}

function lengths(): number[] {
  return [0, 1, 499, 500, 501, 999, 1000, 1499, 1500, 9_999, 59_499, 59_500, 60_000, 61_000, 599_000, 3_599_499,
    3_599_500, 3_600_000, 3_661_000, 36_000_000, 359_999_999, -1000]
}

/** A track's length in metres (`trackFacts.ts`' `formatDistance`): around the kilometre and the decimal's rounding. */
const DISTANCES = [0, 0.4, 0.5, 1, 849.6, 999.4, 999.5, 1000, 1049, 1050, 1234.5, 12_000, 12_340, 12_350, 99_999, 1_234_567.8]

const AMOUNTS = [123_456_789, -5, 0, 1, 100]

export interface FormatVectors {
  readonly size: readonly ({ bytes: number } & Said)[]
  readonly day: readonly ({ iso: string } & Said)[]
  readonly moment: readonly ({ epochMs: number } & Said)[]
  readonly memoryDay: readonly ({ iso: string } & Said)[]
  readonly dayRange: readonly ({ from: string; to: string } & Said)[]
  readonly money: readonly ({ amount: number; currency: string; minorUnits: number } & Said)[]
  readonly number: readonly ({ amount: number; minorUnits: number } & Said)[]
  readonly list: readonly ({ items: string[] } & Said)[]
  readonly length: readonly { ms: number; text: string }[]
  readonly dayLabel: readonly ({ iso: string; withYear: boolean } & Said)[]
  readonly distance: readonly ({ meters: number } & Said)[]
}

/** Every vector, rendered now by the web's formatters. */
export function formatVectors(): FormatVectors {
  const dayOf = (iso: string, l: Language, time: boolean) => {
    const [y, m, d] = iso.split('-').map(Number)
    const at = new Date(Date.UTC(2000, 0, 1))
    at.setUTCFullYear(y!, m! - 1, d!)
    return new Intl.DateTimeFormat(tag(l), { dateStyle: 'medium', ...(time ? { timeStyle: 'short' } : {}), timeZone: 'UTC' }).format(at)
  }
  const money: (FormatVectors['money'][number])[] = []
  for (const currency of knownCurrencies()) {
    const digits = minorUnitsOf(currency)
    for (const amount of AMOUNTS) money.push({ amount, currency, minorUnits: digits, ...said((l) => formatMoney(BigInt(amount), currency, digits, l)) })
  }
  // an amount sealed with other digits than today's table gives its currency (ADR 0108 decision 9)
  for (const [currency, digits] of [['EUR', 0], ['EUR', 3], ['JPY', 2], ['KWD', 4], ['USD', 1]] as const) {
    money.push({ amount: 123_456_789, currency, minorUnits: digits, ...said((l) => formatMoney(123_456_789n, currency, digits, l)) })
  }
  const number: (FormatVectors['number'][number])[] = []
  for (const minorUnits of [0, 1, 2, 3, 4]) {
    for (const amount of AMOUNTS) {
      number.push({
        amount, minorUnits,
        ...said((l) => new Intl.NumberFormat(l, { minimumFractionDigits: minorUnits, maximumFractionDigits: minorUnits })
          .format(decimalOf(BigInt(amount), minorUnits) as unknown as number)),
      })
    }
  }
  const ranges: [string, string][] = [['2026-03-12', '2026-03-12'], ['2026-03-12', '2026-04-02'], ['2025-12-30', '2026-01-02'], ['2026-03-12', '']]
  const lists = [['a'], ['a', 'b'], ['a', 'b', 'c'], ['“eins”', '“zwei”', '“drei”', '“vier”']]
  return {
    size: sizes().map((bytes) => ({ bytes, ...said((l) => formatBytes(bytes, l)) })),
    day: days().map((iso) => ({ iso, ...said((l) => dayOf(iso, l, false)) })),
    moment: moments().map((epochMs) => ({
      epochMs,
      ...said((l) => new Intl.DateTimeFormat(tag(l), { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(epochMs)),
    })),
    memoryDay: days().filter((iso) => !iso.startsWith('0')).map((iso) => ({ iso, ...said((l) => formatDay(iso, l)) })),
    dayRange: ranges.map(([from, to]) => ({ from, to, ...said((l) => formatClosedRange(from, to || undefined, l)) })),
    money,
    number,
    list: lists.map((items) => ({ items, ...said((l) => joinList(l, items)) })),
    length: lengths().map((ms) => ({ ms, text: formatTrackLength(ms) })),
    // a gallery filter's day chip (`galleryFilter.ts`' label: the year only where the Memory spans more than one)
    dayLabel: days().filter((iso) => !iso.startsWith('0')).flatMap((iso) => [false, true].map((withYear) => ({
      iso, withYear,
      ...said((l) => new Intl.DateTimeFormat(l, { timeZone: 'UTC', day: 'numeric', month: 'long', ...(withYear ? { year: 'numeric' } : {}) })
        .format(new Date(`${iso}T12:00:00Z`))),
    }))),
    distance: DISTANCES.map((meters) => ({ meters, ...said((l) => formatDistance(meters, l)) })),
  }
}
