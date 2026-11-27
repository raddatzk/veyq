// The *Open in the app* rule (ADR 0150 § O2): which page shows the banner and which `veyq:` link it opens — and that the
// two links are constants that carry nothing; what the share page copies is the page's link normalized as a bookmark's.
// Apple's Smart App Banner: no tag without an App Store id, and Safari on iOS alone draws it.
import { describe, expect, it } from 'vitest'
import { APP_LINK, APP_OPEN, MAILED_PAGES, bannerForm, hrefOf, linkToCopy, placeHref } from './openInApp'
import { safariDrawsItsBanner, smartAppBannerContent } from './smartAppBanner'

const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const KEY = 'S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2V5S2U'

describe('the two links', () => {
  it('are veyq://open and veyq://link; without a place neither holds a fragment, query or key', () => {
    expect(APP_OPEN).toBe('veyq://open')
    expect(APP_LINK).toBe('veyq://link')
    expect(hrefOf('open')).toBe('veyq://open')
    expect(hrefOf('link')).toBe('veyq://link')
    for (const href of [hrefOf('open'), hrefOf('link')]) expect(href).not.toMatch(/[#?@]|k=/)
  })

  it('Open a link never carries the place', () => {
    expect(hrefOf('link', ORIGIN, { scopeId: SCOPE, nodeId: NODE })).toBe('veyq://link')
  })
})

const ORIGIN = 'https://photos.example.org'
const SCOPE = '0b9e2a52-6a3e-4f3c-9d55-0d1f7e6f2a10'
const NODE = '7c1d8e4f-2b3a-4c5d-8e9f-0a1b2c3d4e5f'

describe('the place veyq://open carries (inbox #1415)', () => {
  it('the origin and the opaque ids, nothing else', () => {
    expect(placeHref(ORIGIN, { scopeId: SCOPE, nodeId: NODE })).toBe(`veyq://open?i=${encodeURIComponent(ORIGIN)}&s=${SCOPE}&n=${NODE}`)
    expect(placeHref(ORIGIN, { scopeId: SCOPE, nodeId: null })).toBe(`veyq://open?i=${encodeURIComponent(ORIGIN)}&s=${SCOPE}`)
    expect(placeHref('http://veyq.localhost:8081', { section: 'tenant-members' })).toBe('veyq://open?i=http%3A%2F%2Fveyq.localhost%3A8081&settings=tenant-members')
    expect(hrefOf('open', ORIGIN, { scopeId: SCOPE, nodeId: NODE })).toBe(placeHref(ORIGIN, { scopeId: SCOPE, nodeId: NODE }))
  })

  it('a value not of its shape gives plain veyq://open: no name, slug or key can ride it', () => {
    expect(placeHref(ORIGIN, null)).toBe('veyq://open')
    expect(placeHref(ORIGIN, { scopeId: 'reisen-2026', nodeId: null })).toBe('veyq://open')
    expect(placeHref(ORIGIN, { scopeId: SCOPE, nodeId: 'rom' })).toBe('veyq://open')
    expect(placeHref(ORIGIN, { section: 'Members & more' })).toBe('veyq://open')
    expect(placeHref(`${ORIGIN}/s/x#k=key`, { scopeId: SCOPE, nodeId: NODE })).toBe('veyq://open')
    expect(placeHref('https://user:pw@photos.example.org', { scopeId: SCOPE, nodeId: NODE })).toBe('veyq://open')
    expect(placeHref('javascript:alert(1)', { scopeId: SCOPE, nodeId: NODE })).toBe('veyq://open')
  })
})

describe('which page shows which form', () => {
  it('a share page opens Open a link; its review shows none', () => {
    expect(bannerForm('share', `#k=${KEY}`, false)).toBe('link')
    expect(bannerForm('share', `#k=${KEY}&report`, false)).toBe('link')
    expect(bannerForm('share', '#moderation', false)).toBeNull()
  })

  it('a mailed page shows it only once its step is done, opening the app where it is', () => {
    expect(MAILED_PAGES).toEqual(['tenant-accept', 'recover', 'confirm-email', 'confirm-email-change'])
    for (const page of MAILED_PAGES) {
      expect(bannerForm(page, '', false)).toBeNull()
      expect(bannerForm(page, '#tok', false)).toBeNull()
      expect(bannerForm(page, '', true)).toBe('open')
    }
  })

  it('every other page opens the app where it is; the moderation surface shows none', () => {
    for (const page of ['home', 'login', 'settings', 'invitations', 'terms', 'privacy-notice', 'not-set-up', undefined, null]) {
      expect(bannerForm(page, '#~me/reisen', false)).toBe('open')
    }
    expect(bannerForm('moderation', '', false)).toBeNull()
    expect(bannerForm('moderation', '', true)).toBeNull()
  })
})

describe('what the share page copies', () => {
  it('the page link normalized as a bookmark keeps it: no &report, no query, no other parameter', () => {
    const normalized = `https://photos.example.org/s/${SHARE_ID}#k=${KEY}`
    expect(linkToCopy(normalized)).toBe(normalized)
    expect(linkToCopy(`https://photos.example.org/s/${SHARE_ID}#k=${KEY}&report`)).toBe(normalized)
    expect(linkToCopy(`https://photos.example.org/s/${SHARE_ID}?x=1#report&k=${KEY}`)).toBe(normalized)
  })

  it('nothing without a key', () => {
    expect(linkToCopy(`https://photos.example.org/s/${SHARE_ID}`)).toBeNull()
    expect(linkToCopy(`https://photos.example.org/s/${SHARE_ID}#moderation`)).toBeNull()
  })
})

describe("Apple's Smart App Banner", () => {
  const IPHONE_SAFARI = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'
  const IPAD_DESKTOP = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15'
  const IPHONE_CHROME = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1'
  const ANDROID_CHROME = 'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'

  it('no tag while the app has no App Store id, or with one that is not digits', () => {
    expect(smartAppBannerContent(null)).toBeNull()
    expect(smartAppBannerContent('')).toBeNull()
    expect(smartAppBannerContent('123", app-argument="veyq://link#k=x')).toBeNull()
    expect(smartAppBannerContent('1234567890')).toBe('app-id=1234567890')
  })

  it('the tag never names an app-argument', () => {
    expect(smartAppBannerContent('1234567890')).not.toContain('app-argument')
  })

  it('Safari on an iPhone or an iPad draws it once an id is set; other browsers and no id do not', () => {
    expect(safariDrawsItsBanner(null, IPHONE_SAFARI, 5)).toBe(false)
    expect(safariDrawsItsBanner('1234567890', IPHONE_SAFARI, 5)).toBe(true)
    expect(safariDrawsItsBanner('1234567890', IPAD_DESKTOP, 5)).toBe(true)
    expect(safariDrawsItsBanner('1234567890', IPAD_DESKTOP, 0)).toBe(false) // a Mac's Safari
    expect(safariDrawsItsBanner('1234567890', IPHONE_CHROME, 5)).toBe(false)
    expect(safariDrawsItsBanner('1234567890', ANDROID_CHROME, 5)).toBe(false)
  })
})
