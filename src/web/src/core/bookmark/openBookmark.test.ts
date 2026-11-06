// @vitest-environment happy-dom
// Opening a bookmark and handing its share's report on (ADR 0120 decisions 5 and 6): this origin in the same tab with the
// password handed in memory, another origin in a new tab that learns nothing of this one, its password shown to copy and
// never put in a URL.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { keptLink } from './bookmarkLink'
import { bookmarkOpenRequest, openAway, openBookmark, type OpenDoors } from './openBookmark'
import { takeHandedSharePassword } from '../sharing/handedPassword'

const ID = 'H'.repeat(43)
const KEY = b64url(new Uint8Array(32).fill(9))
const HERE = 'https://veyq.example.org'
const here = keptLink(`${HERE}/s/${ID}#k=${KEY}`)!
const away = keptLink(`https://photos.other.example/s/${ID}#k=${KEY}`)!

function doors() {
  return { origin: HERE, navigate: vi.fn<OpenDoors['navigate']>(), openAway: vi.fn<OpenDoors['openAway']>() }
}

afterEach(() => {
  bookmarkOpenRequest.value = null
  takeHandedSharePassword(ID)
  vi.unstubAllGlobals()
})

describe('openBookmark', () => {
  it('opens a link of this origin in this tab through the router, and hands its password to the share page once', () => {
    const d = doors()
    openBookmark({ link: here, password: 'pw' }, false, d)
    expect(d.navigate).toHaveBeenCalledWith({ path: `/s/${ID}`, hash: `#k=${KEY}` })
    expect(d.openAway).not.toHaveBeenCalled()
    expect(bookmarkOpenRequest.value).toBeNull()
    expect(takeHandedSharePassword(ID)).toBe('pw')
    expect(takeHandedSharePassword(ID), 'taken once').toBeNull()
  })

  it('hands no password where the bookmark keeps none', () => {
    const d = doors()
    openBookmark({ link: here, password: null }, false, d)
    expect(d.navigate).toHaveBeenCalledOnce()
    expect(takeHandedSharePassword(ID)).toBeNull()
  })

  it('opens a link of another origin in a new tab, and hands nothing to this origin\'s pages', () => {
    const d = doors()
    openBookmark({ link: away, password: null }, false, d)
    expect(d.openAway).toHaveBeenCalledWith(away.link)
    expect(d.navigate).not.toHaveBeenCalled()
    expect(takeHandedSharePassword(ID)).toBeNull()
  })

  it('shows another origin\'s password to copy first, and opens nothing until the member follows the link', () => {
    const d = doors()
    openBookmark({ link: away, password: 'pw' }, false, d)
    expect(bookmarkOpenRequest.value).toEqual({ url: away.link, host: 'photos.other.example', password: 'pw', report: false })
    expect(d.openAway).not.toHaveBeenCalled()
    expect(d.navigate).not.toHaveBeenCalled()
    expect(takeHandedSharePassword(ID), 'no other origin is handed a password').toBeNull()
  })

  it('hands a report to the share page where the share lives: here, there, and through the password dialog', () => {
    const d = doors()
    openBookmark({ link: here, password: null }, true, d)
    expect(d.navigate).toHaveBeenCalledWith({ path: `/s/${ID}`, hash: `#k=${KEY}&report` })
    openBookmark({ link: away, password: null }, true, d)
    expect(d.openAway).toHaveBeenCalledWith(`${away.link}&report`)
    openBookmark({ link: away, password: 'pw' }, true, d)
    expect(bookmarkOpenRequest.value).toMatchObject({ url: `${away.link}&report`, report: true })
  })

  it('a new tab is opened with no opener and no referrer', () => {
    const open = vi.fn()
    vi.stubGlobal('open', open)
    openAway(away.link)
    expect(open).toHaveBeenCalledWith(away.link, '_blank', 'noopener,noreferrer')
  })
})
