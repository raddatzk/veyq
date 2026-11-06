// The link a bookmark keeps (ADR 0120 decision 4): checked and normalized when it is pasted, never fetched. Every refusal
// stands beside the accepted link it differs from in one part, so a parser that refused everything would be red.
import { describe, expect, it } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { asksForReport, hostOf, keptLink, parseShareLink, reportLinkOf, withoutReport, MAX_LINK_LENGTH } from './bookmarkLink'

const ID = 'Ab3_-'.padEnd(43, 'x')
const KEY = b64url(new Uint8Array(32).fill(7))
const LINK = `https://photos.example.org/s/${ID}#k=${KEY}`

const problem = (input: string) => {
  const r = parseShareLink(input)
  return r.ok ? null : r.problem
}

describe('parseShareLink', () => {
  it('keeps a share link of another instance as it is: origin, path, key', () => {
    expect(parseShareLink(LINK)).toEqual({ ok: true, value: { link: LINK, origin: 'https://photos.example.org', shareId: ID } })
    expect(hostOf(keptLink(LINK)!)).toBe('photos.example.org')
    expect(hostOf(keptLink(`https://photos.example.org:8443/s/${ID}#k=${KEY}`)!)).toBe('photos.example.org:8443')
  })

  it('normalizes what is pasted: whitespace, the host\'s case, a query and every other fragment parameter are dropped, a port is kept', () => {
    const r = parseShareLink(`  https://Photos.EXAMPLE.org:8443/s/${ID}?node=abc#x=1&k=${KEY}&report  `)
    expect(r).toEqual({ ok: true, value: { link: `https://photos.example.org:8443/s/${ID}#k=${KEY}`, origin: 'https://photos.example.org:8443', shareId: ID } })
  })

  it('takes plain http only for a development host', () => {
    for (const host of ['localhost:8081', 'veyq.localhost:8081', '127.0.0.1:5173', '[::1]:8080']) {
      expect(problem(`http://${host}/s/${ID}#k=${KEY}`), host).toBeNull()
    }
    expect(problem(`http://photos.example.org/s/${ID}#k=${KEY}`)).toBe('notHttps')
    expect(problem(`ftp://photos.example.org/s/${ID}#k=${KEY}`)).toBe('notAUrl')
    expect(problem(`javascript:alert(1)//s/${ID}#k=${KEY}`)).toBe('notAUrl')
  })

  it('refuses what is no link at all, or nothing', () => {
    expect(problem('')).toBe('empty')
    expect(problem('   ')).toBe('empty')
    expect(problem('photos.example.org/s/x')).toBe('notAUrl')
  })

  it('refuses a user name or password in the address', () => {
    expect(problem(`https://ada:secret@photos.example.org/s/${ID}#k=${KEY}`)).toBe('credentials')
    expect(problem(`https://ada@photos.example.org/s/${ID}#k=${KEY}`)).toBe('credentials')
  })

  it('refuses a link that is no share link: another path, a share id of another length, a trailing segment', () => {
    expect(problem(`https://photos.example.org/x/${ID}#k=${KEY}`)).toBe('notAShare')
    expect(problem(`https://photos.example.org/s/${ID.slice(1)}#k=${KEY}`)).toBe('notAShare')
    expect(problem(`https://photos.example.org/s/${ID}/more#k=${KEY}`)).toBe('notAShare')
    expect(problem(`https://photos.example.org/prefix/s/${ID}#k=${KEY}`)).toBe('notAShare')
  })

  it('refuses a link without its whole key: none, a moderation review, a short one, one that is no base64url', () => {
    expect(problem(`https://photos.example.org/s/${ID}`)).toBe('noKey')
    expect(problem(`https://photos.example.org/s/${ID}#moderation`)).toBe('noKey')
    expect(problem(`https://photos.example.org/s/${ID}#k=${KEY.slice(1)}`)).toBe('noKey')
    expect(problem(`https://photos.example.org/s/${ID}#k=${KEY.slice(1)}=`)).toBe('noKey')
    expect(problem(`https://photos.example.org/s/${ID}#k=${KEY.slice(1)}+`)).toBe('noKey')
    expect(problem(`https://photos.example.org/s/${ID}#kk=${KEY}`)).toBe('noKey')
  })

  it('refuses a link longer than it keeps', () => {
    const long = `https://${'a'.repeat(MAX_LINK_LENGTH)}.example.org/s/${ID}#k=${KEY}`
    expect(problem(long)).toBe('tooLong')
  })
})

describe('keptLink', () => {
  it('reads back only the normalized form itself', () => {
    expect(keptLink(LINK)?.link).toBe(LINK)
    expect(keptLink(`${LINK}&report`)).toBeNull()
    expect(keptLink(`https://Photos.example.org/s/${ID}#k=${KEY}`)).toBeNull()
    expect(keptLink(42)).toBeNull()
    expect(keptLink(undefined)).toBeNull()
  })
})

describe('the report hand-off in the fragment', () => {
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
