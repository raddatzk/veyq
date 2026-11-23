// @vitest-environment happy-dom
// Memories' drop page — the page of a WRITE_ONLY link, the one guest-only page Memories keeps (inbox #401 S5) — with the
// REAL `openSharedMemory`: it opens the drop PUBLIC key from the shell's head and nothing else, reads no media (an
// uploader is served none, ADR 0031) and calls no authenticated client (`test/guestMode.ts`). Mounted bare — the
// password/terms gates and the role dispatch are the shell's (views/ShareView*.test.ts). Its picker's E3 window is
// `views/ShareView.picker.test.ts`'s.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
// The real read of what the link tells its guest (ADR 0093 § 7): asked only once a test opens the guest page.
vi.unmock('../../../core/sharing/guestNoticeRead')
// What reaches the upload queue: a spy; the picker's own read of the input stays real.
// The uploader's identity as the name makes it: a spy (its wire and crypto are `share.ts`' own tests'); the rest real.
vi.mock('../share', async (importOriginal) => ({ ...(await importOriginal<typeof import('../share')>()), createContributorIdentity: vi.fn() }))
vi.mock('../uploadQueue', async (importOriginal) => ({ ...(await importOriginal<typeof import('../uploadQueue')>()), enqueueUploads: vi.fn() }))

import MemoryShareBody from './MemoryShareBody.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { saveGuestSession, forgetGuestSession, loadGuestSession } from '../../../core/sharing/guestIdentity'
import type { GuestAccess } from '../../../core/access'
import { enqueueUploads } from '../uploadQueue'
import { MEMORY_KIND } from '../kinds'
import { createContributorIdentity, type ContributorSession } from '../share'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { publicShareMediaApi } from '../api'
import { publicSharesApi } from '../../../api/client'
import { stubApi } from '../../../test/apiMock'
import { closeGuestPage, openGuestPage } from '../../../core/sharing/guestNotice'
import { guestAccessFor } from '../../../test/models'
import { authenticatedCalls } from '../../../test/guestMode'
import { realI18n, t } from '../../../test/i18n'

const K_SHARE = v1.randomBytes(32)
const DROP_NODE = '5c09e000-0000-4000-8000-0000000000d0'
const sealedDropPublicKey = b64std(v1.seal({ purpose: 'drop-public-key' }, K_SHARE, v1.serializeHybridPublic(v1.dropKeyPublic(v1.generateDropKey()))))

function head(share: Partial<PublicShare>, role: ShareRole = ShareRole.WriteOnly): PublicShareHead {
  return {
    shareId: 's1', share: { role, scopeId: 'scope-1', rootKind: MEMORY_KIND, nodes: [], ...share } as PublicShare, kShare: K_SHARE,
    scopeId: 'scope-1', name: 'Rom 2026', noteByLocale: { en: 'Bitte hier hochladen' }, role, rootKind: MEMORY_KIND,
  }
}
const DROP = { nodeId: DROP_NODE, sealedDropPublicKey }

// The shell's one naming dialog, as the page reaches it (`GuestAccess.requestGuest`): each question recorded, answered by
// the test — a name (`make` runs under it, as the dialog's save does), or null (the guest cancelled).
type Make = Parameters<GuestAccess['requestGuest']>[0]
let asked: Make[]
let answer: (make: Make) => ReturnType<GuestAccess['requestGuest']>
const requestGuest: GuestAccess['requestGuest'] = (make) => {
  asked.push(make)
  return answer(make)
}

function mountPage(h: PublicShareHead) {
  return mount(MemoryShareBody, {
    props: { shareId: 's1', fragmentKey: 'AAAA', pwToken: undefined, head: h, reload: async () => {}, access: guestAccessFor(h, { requestGuest }) },
    global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true } },
  })
}

/** A photo picked in the page's file input (the picker's first read is at once). */
async function pickPhoto(w: ReturnType<typeof mountPage>) {
  const input = w.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'a.jpg', { type: 'image/jpeg' })], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

beforeEach(() => {
  asked = []
  answer = () => new Promise(() => {})
  vi.mocked(enqueueUploads).mockReset()
  vi.mocked(createContributorIdentity).mockReset()
  localStorage.clear()
  forgetGuestSession('s1')
  authenticatedCalls.length = 0
  closeGuestPage()
})

describe("Memories' drop page (a WRITE_ONLY link)", () => {
  it('a named uploader reads every statement of reasons its link told it, though the link lives (ADR 0093 § 7)', async () => {
    const uploader: ContributorSession = { identityId: 'i-1', contributorToken: 'tok', uploaderKey: b64std(v1.randomBytes(32)) }
    saveGuestSession('s1', uploader)
    const statement = {
      id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e', measureKind: 'CONTENT' as const, targetId: 'medium-1', covers: [],
      ground: 'TERMS_BREACH' as const, provision: 'Space terms, clause 4', basis: 'REPORT' as const, takenAt: new Date('2026-10-18T09:00:00Z'),
    }
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: { removals: [], statements: [statement], contactAddress: 'abuse@example.org' } })
    openGuestPage('s1')
    const w = mountPage(head(DROP))
    await flushPromises()
    expect(w.find('.share-add-button').exists()).toBe(true) // the link lives: the drop is offered
    expect(getShareGuestNotice).toHaveBeenCalledWith({ shareId: 's1', xContributorToken: 'tok' })
    expect(w.findAll('[data-testid="guest-statement"]')).toHaveLength(1)
    expect(w.find('[data-passage="provision"]').text()).toBe('It relies on Space terms, clause 4.')
    expect(authenticatedCalls).toEqual([])
  })

  it('an unnamed visitor asks nothing of what the link would tell a guest', async () => {
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: { removals: [], statements: [], contactAddress: 'abuse@example.org' } })
    openGuestPage('s1')
    const w = mountPage(head(DROP))
    await flushPromises()
    expect(w.find('.share-add-button').exists()).toBe(true)
    expect(getShareGuestNotice).not.toHaveBeenCalled()
    expect(w.find('[data-testid="guest-statement"]').exists()).toBe(false)
  })

  // Inbox #1090: the name is asked at the write (ADR 0154 decision 1), so the page holds no name form — the drop at once.
  it('an unnamed uploader sees the drop at once — the intro, the warning, the curator\'s note — no name form, no media read', async () => {
    const w = mountPage(head(DROP))
    await flushPromises()
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
    expect(w.find('form').exists(), 'no name form in the page').toBe(false)
    expect(w.find('.guest-naming-input').exists()).toBe(false)
    expect(w.find('.share-add-button').text()).toBe(t('share.addPhotos'))
    expect(w.find('.share-uploader-intro').text()).toBe(t('share.uploaderIntro'))
    expect(w.text()).toContain(t('share.uploaderEphemeralWarning'))
    expect(w.find('.share-note').text()).toContain('Bitte hier hochladen')
    expect(w.text(), 'no "Not you?" before a name').not.toContain(t('share.guest.notYou'))
    expect(asked, 'nothing asked before a pick').toEqual([])
    expect(publicShareMediaApi).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })

  it('the first pick asks the name through the shell\'s dialog; its answer makes the uploader\'s own identity, then the photo goes', async () => {
    const created = { identityId: 'i-new', contributorToken: 'tok-new', uploaderKey: b64std(v1.randomBytes(32)) }
    vi.mocked(createContributorIdentity).mockResolvedValue(created)
    answer = async (make) => (make ? make('Ada') : null)
    const w = mountPage(head(DROP))
    await flushPromises()
    await pickPhoto(w)
    expect(asked).toHaveLength(1)
    expect(vi.mocked(createContributorIdentity).mock.calls).toEqual([
      [expect.objectContaining({ shareId: 's1' }), 'scope-1', expect.objectContaining({ kind: 'drop' }), 'Ada', 'AAAA', undefined],
    ])
    const kept = loadGuestSession<ContributorSession>('s1')
    expect(kept?.displayName).toBe('Ada')
    expect(kept?.contributorToken).toBe('tok-new')
    expect(kept?.uploaderKey, 'its own K_up kept on the device').toBeTruthy()
    expect(enqueueUploads).toHaveBeenCalledOnce()
    expect(w.text()).toContain(t('share.guest.as', { name: 'Ada' }))
    await pickPhoto(w)
    expect(asked, 'named, a later pick asks nothing').toHaveLength(1)
    expect(enqueueUploads).toHaveBeenCalledTimes(2)
    w.unmount()
  })

  it('declined at the pick: nothing made, nothing sent and nothing said', async () => {
    answer = async () => null
    const w = mountPage(head(DROP))
    await flushPromises()
    const before = w.text()
    await pickPhoto(w)
    expect(asked).toHaveLength(1)
    expect(createContributorIdentity).not.toHaveBeenCalled()
    expect(enqueueUploads).not.toHaveBeenCalled()
    expect(loadGuestSession('s1')).toBeNull()
    expect(w.text(), 'nothing said').toBe(before)
    w.unmount()
  })

  it('named, the drop: the badge, "Not you?", add photos and the empty session — no gallery of the member\'s', async () => {
    const uploader: ContributorSession = { identityId: 'i-1', contributorToken: 'tok', uploaderKey: b64std(v1.randomBytes(32)) }
    saveGuestSession('s1', uploader)
    const w = mountPage(head(DROP))
    await flushPromises()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
    expect(w.find('.share-view-badge').text()).toBe(t('share.uploaderBadge'))
    expect(w.text()).toContain(t('share.guest.notYou'))
    expect(w.find('.share-add-button').text()).toBe(t('share.addPhotos'))
    expect(w.find('.veyq-empty').text()).toBe(t('share.uploaderEmpty'))
    expect(w.find('.media-lightbox').exists()).toBe(false)
    expect(publicShareMediaApi).not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })

  it('a session saved without its own key names itself again at the pick rather than seal under a key it does not hold', async () => {
    saveGuestSession('s1', { identityId: 'i-1', contributorToken: 'tok' })
    answer = async () => null
    const w = mountPage(head(DROP))
    await flushPromises()
    await pickPhoto(w)
    expect(asked).toHaveLength(1)
    expect(asked[0], 'asked with the uploader\'s own identity as what the name makes').toBeTypeOf('function')
    expect(enqueueUploads).not.toHaveBeenCalled()
    w.unmount()
  })

  it('reports an uploader response without the drop key it needs unusable', async () => {
    const w = mountPage(head({ nodeId: DROP_NODE, sealedDropPublicKey: undefined }))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })

  it('…and a drop key sealed under another key', async () => {
    const other = b64std(v1.seal({ purpose: 'drop-public-key' }, v1.randomBytes(32), v1.serializeHybridPublic(v1.dropKeyPublic(v1.generateDropKey()))))
    const w = mountPage(head({ nodeId: DROP_NODE, sealedDropPublicKey: other }))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })

  it('is a drop only: a viewer\'s or contributor\'s head is unusable here (the shell gives those the member\'s views)', async () => {
    for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
      const wrap = b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, v1.randomBytes(32)))
      const w = mountPage(head({ wrap, nodes: [{ id: 'root', parentId: null, kind: MEMORY_KIND, epoch: 0 }] as PublicShare['nodes'] }, role))
      await flushPromises()
      expect(w.emitted('unavailable'), role).toHaveLength(1)
    }
  })
})
