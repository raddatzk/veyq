// uc.admin.capability-requirements (web) — the link rules a share dialog reads, and the last day its picker offers.
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { lastExpiryDay, linkRules } from './linkRules'
import { setTenantCapabilities, setCapabilities } from '../../shared/state/useCapabilities'
import { capabilityState } from '../../shared/state/capabilities'

function endOfDay(d: Date): Date {
  const e = new Date(d)
  e.setHours(23, 59, 59, 999)
  return e
}

describe('linkRules', () => {
  beforeEach(() => {
    capabilityState.entries = {}
    setTenantCapabilities(null)
  })

  it('reads the open tenant\'s values, and nothing required when the entry carries none', () => {
    setCapabilities({ setupRequired: false, suites: [], instanceSalt: '', version: { major: 0, build: 0 }, capabilities: [{ id: 'core.public-shares' }] })
    expect(linkRules()).toEqual({ passwordRequired: false, maxDays: null })
    setTenantCapabilities([{ id: 'core.public-shares', linkPassword: true, linkMaxDays: 7 }])
    expect(linkRules()).toEqual({ passwordRequired: true, maxDays: 7 })
  })

  it('offers as the last day the one whose end stays within the limit counted from when the link was made', () => {
    const madeAt = new Date(2026, 9, 16, 14, 30) // 16 Oct, afternoon
    const last = lastExpiryDay(7, madeAt)!
    expect([last.getFullYear(), last.getMonth(), last.getDate()]).toEqual([2026, 9, 22])
    expect(endOfDay(last).getTime()).toBeLessThanOrEqual(madeAt.getTime() + 7 * 24 * 3600 * 1000)
    // The next day would end past the limit: the picker must not offer it.
    const next = new Date(last)
    next.setDate(next.getDate() + 1)
    expect(endOfDay(next).getTime()).toBeGreaterThan(madeAt.getTime() + 7 * 24 * 3600 * 1000)
    expect(lastExpiryDay(null, madeAt)).toBeNull()
  })
})

// The last day against contracts/platform/share-links/cases.v1.json (`lastDay`), which the apps' link form answers too
// (inbox #1057: an edited link counts from when it was made). Each case names its zone; Node re-reads `TZ` when it is set.
type DayCase = { rule: string; maxDays: number | null; madeAt: string; now: string; zone: string; lastDay: string | null }
const dayCases = (JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/platform/share-links/cases.v1.json'), 'utf8')) as {
  lastDay: DayCase[]
}).lastDay
const isoDay = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

describe('lastExpiryDay against the share-link vectors', () => {
  const zone = process.env.TZ
  afterEach(() => {
    if (zone === undefined) delete process.env.TZ
    else process.env.TZ = zone
  })

  it.each(dayCases.map((c) => [c.rule, c] as const))('%s', (_, c) => {
    process.env.TZ = c.zone
    const last = lastExpiryDay(c.maxDays, new Date(c.madeAt))
    expect(last === null ? null : isoDay(last)).toBe(c.lastDay)
  })
})
