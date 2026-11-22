// The link a bookmark keeps (ADR 0120 decision 4): checked and normalized when it is pasted, never fetched. The cases are
// contracts/platform/share-links/cases.v1.json (ADR 0150 decision 2), which the platform's Kotlin answers for the apps
// (`ShareLinkVectorsTest`) and, through `@veyq/core-js/sharing`, for this adapter: a change to the link's shape changes the
// file, and every side goes red until they agree. Every refusal there stands
// beside the accepted link it differs from in one part, so a parser that refused everything would be red.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { asksForReport, hostOf, keptLink, parseShareLink, reportLinkOf, withoutReport } from './bookmarkLink'

type Accepted = { input: string; link: string; origin: string; shareId: string; host: string; report?: boolean }
type Refused = { input: string; problem: string; moderation?: boolean }
type Cases = {
  format: string
  maxLinkLength: number
  parse: (Accepted | Refused)[]
  kept: { input: string; link: string | null }[]
  report: { fragment: string; asks: boolean; without: string }[]
}

const FILE = resolve(__dirname, '../../../../../contracts/platform/share-links/cases.v1.json')
const cases = JSON.parse(readFileSync(FILE, 'utf8')) as Cases
const accepted = cases.parse.filter((c): c is Accepted => 'link' in c)
const refused = cases.parse.filter((c): c is Refused => 'problem' in c)
const fragmentOf = (input: string) => {
  const at = input.trim().indexOf('#')
  return at < 0 ? '' : input.trim().slice(at)
}

describe('the cases file', () => {
  it('is the format this test reads, holds both kinds of case and the longest link kept', () => {
    expect(cases.format).toBe('veyq-share-links-v1')
    expect(cases.maxLinkLength).toBe(2048) // contracts/platform/share-bookmark-meta.v1.schema.json's maxLength
    expect(accepted.length).toBeGreaterThan(5)
    expect(new Set(refused.map((c) => c.problem))).toEqual(
      new Set(['empty', 'tooLong', 'notAUrl', 'notHttps', 'credentials', 'notAShare', 'noKey']),
    )
  })
})

describe('parseShareLink', () => {
  it.each(accepted.map((c) => [c.input.slice(0, 80), c] as const))('accepts %s', (_, c) => {
    expect(parseShareLink(c.input)).toEqual({ ok: true, value: { link: c.link, origin: c.origin, shareId: c.shareId } })
    expect(hostOf(keptLink(c.link)!)).toBe(c.host)
    expect(asksForReport(fragmentOf(c.input))).toBe(c.report === true)
  })

  it.each(refused.map((c) => [c.input.slice(0, 80), c] as const))('refuses %s', (_, c) => {
    expect(parseShareLink(c.input)).toEqual({ ok: false, problem: c.problem })
    // a moderator's review is the share page's own mode (`#moderation` exactly), never a link's report
    expect(fragmentOf(c.input) === '#moderation').toBe(c.moderation === true)
  })
})

describe('keptLink', () => {
  it.each(cases.kept.map((c) => [c.input.slice(0, 80), c] as const))('reads back %s only as its normalized form', (_, c) => {
    expect(keptLink(c.input)?.link ?? null).toBe(c.link)
  })

  it('reads back nothing that is no string', () => {
    expect(keptLink(42)).toBeNull()
    expect(keptLink(undefined)).toBeNull()
  })
})

describe('a share page\'s fragment', () => {
  it.each(cases.report.map((c) => [JSON.stringify(c.fragment).slice(0, 40), c] as const))('%s: asks for the report dialog or not, and keeps the rest', (_, c) => {
    expect(asksForReport(c.fragment)).toBe(c.asks)
    expect(withoutReport(c.fragment)).toBe(c.without)
  })
})

describe('the report hand-off in the fragment', () => {
  const LINK = accepted[0]!.link
  const KEY = LINK.slice(LINK.indexOf('#k=') + 3)

  it('adds one parameter the share page reads beside k=, and the page can drop it again', () => {
    const kept = keptLink(LINK)!
    const report = reportLinkOf(kept)
    expect(report).toBe(`${LINK}&report`)
    const hash = report.slice(report.indexOf('#'))
    expect(asksForReport(hash)).toBe(true)
    expect(withoutReport(hash)).toBe(`#k=${KEY}`)
    // the page's own key reader still finds the key beside it (`views/ShareView.vue`'s `(?:^|&)k=([^&]+)`)
    expect(/(?:^|&)k=([^&]+)/.exec(hash.slice(1))?.[1]).toBe(KEY)
  })

  it('reads no report where none was asked, and a parameter that merely contains the word is none', () => {
    expect(asksForReport(`#k=${KEY}`)).toBe(false)
    expect(asksForReport('#reported=1')).toBe(false)
    expect(asksForReport('#moderation')).toBe(false)
    expect(withoutReport('#report')).toBe('')
  })
})
