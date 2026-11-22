import { describe, it, expect, afterEach } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'sharing.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const s = await import('@veyq/core-js/sharing')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/share-links/cases.v1.json (4 levels up from src/packages/core-js/src/)
const cases = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/share-links/cases.v1.json'), 'utf8'))

// The share links' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers every
// case the core's ShareLinkVectorsTest answers on the JVM and natively.
const fragmentOf = (input: string) => (input.trim().includes('#') ? input.trim().slice(input.trim().indexOf('#')) : '')

describe('parse', () => {
  ;(cases.parse as any[]).forEach((c, i) => {
    it(`#${i} ${c.input.slice(0, 60)}`, () => {
      const p = s.parseShareLink(c.input)
      if ('link' in c) {
        expect({ link: p.link, origin: p.origin, shareId: p.shareId, report: p.report, problem: p.problem ?? null }).toEqual({
          link: c.link, origin: c.origin, shareId: c.shareId, report: c.report === true, problem: null,
        })
        expect(s.hostOf(p.origin!)).toBe(c.host)
        expect(s.asksForReport(fragmentOf(c.input))).toBe(c.report === true)
      } else {
        expect(p.problem).toBe(c.problem)
        expect(p.link ?? null).toBeNull()
      }
    })
  })
})

describe('kept', () => {
  ;(cases.kept as any[]).forEach((c, i) => {
    it(`#${i}`, () => expect(s.keptLink(c.input)?.link ?? null).toBe(c.link))
  })
  it('a kept link carries only its link, origin and share id', () => {
    const k = s.keptLink(cases.kept[0].input)!
    expect(Object.keys(k).sort()).toEqual(['link', 'origin', 'shareId'])
    expect(s.reportLinkOf(k.link)).toBe(`${k.link}&report`)
  })
})

describe('report', () => {
  ;(cases.report as any[]).forEach((c, i) => {
    it(`#${i} ${JSON.stringify(c.fragment).slice(0, 40)}`, () => {
      expect(s.asksForReport(c.fragment)).toBe(c.asks)
      expect(s.withoutReport(c.fragment)).toBe(c.without)
    })
  })
})

describe('lastDay', () => {
  const zone = process.env.TZ
  afterEach(() => {
    if (zone === undefined) delete process.env.TZ
    else process.env.TZ = zone
  })
  const iso = (day: number) => new Date(day * 86_400_000).toISOString().slice(0, 10)
  ;(cases.lastDay as any[]).forEach((c) => {
    it(c.rule, () => {
      // the browser's zone is what the core reads (`LocalDays` over `Date`); Node re-reads TZ when it is set
      process.env.TZ = c.zone
      const day = s.lastEndDay(c.maxDays, Date.parse(c.madeAt))
      expect(day === null || day === undefined ? null : iso(day)).toBe(c.lastDay)
    })
  })
})

describe('embed', () => {
  ;(cases.embed as any[]).forEach((c) => {
    it(c.rule, () => expect(s.embedding(c.embedding, c.setup, c.majors) ?? null).toBe(c.page))
  })
  it('reads another instance\'s answer by type, never trusting its shape', () => {
    const caps = [{ id: 'core.public-shares' }, { id: 'core.public-shares.embeddable' }]
    expect(s.embedding(true, { version: { major: '3' }, capabilities: caps } as any, [3])).toBe('unreachable')
    expect(s.embedding(true, { version: { major: 3.5 }, capabilities: caps } as any, [3])).toBe('otherVersion')
    expect(s.embedding(true, { version: { major: 3 }, capabilities: { length: 2 } } as any, [3])).toBe('unreachable')
    expect(s.embedding(true, { version: { major: 3 }, capabilities: [null, 7, ...caps] } as any, [3]) ?? null).toBeNull()
  })
})

describe('window', () => {
  ;(cases.window as any[]).forEach((c) => {
    it(`major ${c.major}`, () => expect([...s.olderMajorsInWindow(c.major)]).toEqual(c.older))
  })
})

describe('the shapes', () => {
  it('no shape the façade takes or returns names a password, a token or a sealed byte', () => {
    const types = readFileSync(resolve(dist, 'veyq-mobile-composition.d.mts'), 'utf8')
    for (const shape of ['LinkShape', 'ParsedShape', 'SetupShape', 'VersionShape', 'CapabilityShape']) {
      const body = new RegExp(`interface ${shape} \\{([^}]*)\\}`).exec(types)?.[1]
      expect(body, `${shape} is declared`).toBeDefined()
      expect(body!).not.toMatch(/password|token|sealed|wrapped|encrypted/i)
    }
  })
})
