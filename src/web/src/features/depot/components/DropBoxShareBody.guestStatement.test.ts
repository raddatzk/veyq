// @vitest-environment happy-dom
// The drop box's guest page and a moderator's statement of reasons (ADR 0093 § 7, inbox #813): a drop page has no
// placeholder to hang one on, so every statement the link tells this device's guest identity is shown on the page —
// while the link lives (a content block on one of its uploads), not only once it went away. Its control on the same
// page: a device that holds no session for the link asks nothing and shows nothing. Guest mode: no authenticated
// client is called.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../dropBox', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../dropBox')>()),
  loadDropGuest: vi.fn(),
}))
// The real read: this file is about what the link tells the guest (src/test/setup.ts stands in for it elsewhere).
vi.unmock('../../../core/sharing/guestNoticeRead')

import DropBoxShareBody from './DropBoxShareBody.vue'
import { loadDropGuest, type DropGuestSession } from '../dropBox'
import { publicSharesApi } from '../../../api/client'
import { saveGuestSession } from '../../../core/sharing/guestIdentity'
import { closeGuestPage, openGuestPage } from '../../../core/sharing/guestNotice'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare, type ShareGuestNotice } from '../../../api/generated/sharing'
import { guestAccessFor } from '../../../test/models'
import { stubApi } from '../../../test/apiMock'
import { authenticatedCalls } from '../../../test/guestMode'
import { realI18n } from '../../../test/i18n'
import { TEMPLATE } from '../../moderation/statement/template'

const SHARE = 'Dr0pIdDr0pIdDr0pIdDr0pIdDr0pIdDr0pIdDr0p-_s'
const HEAD = {
  shareId: SHARE, scopeId: 'scope', name: 'Steuer 2026',
  share: { role: ShareRole.WriteOnly, passwordRequired: false, nodes: [], guestWriteRecordDays: 7 } as PublicShare,
} as PublicShareHead
const SESSION: DropGuestSession = { identityId: 'id-1', contributorToken: 'tok-1', identityKey: 'a2V5' }
const TOLD: ShareGuestNotice = {
  removals: [],
  statements: [
    { id: 'a1', measureKind: 'CONTENT', targetId: 'file-1', covers: [], ground: 'TERMS_BREACH', takenAt: new Date('2026-10-18T09:00:00Z') },
    { id: 'a2', measureKind: 'CONTENT', targetId: 'file-2', covers: [], ground: 'ILLEGAL_CONTENT', takenAt: new Date('2026-10-17T09:00:00Z') },
  ],
  contactAddress: undefined,
}

function mountPage() {
  return mount(DropBoxShareBody, {
    props: { shareId: SHARE, fragmentKey: 'frag', pwToken: undefined, head: HEAD, reload: async () => {}, access: guestAccessFor(HEAD) },
    global: { plugins: [realI18n()] },
  })
}

beforeEach(() => {
  localStorage.clear()
  authenticatedCalls.length = 0
  closeGuestPage()
  openGuestPage(SHARE)
})

describe('the drop box guest page and its statements of reasons (ADR 0093 § 7)', () => {
  it('shows every statement the link told this guest while the link lives, asked with its own token', async () => {
    saveGuestSession(SHARE, SESSION)
    vi.mocked(loadDropGuest).mockReturnValue(SESSION)
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: TOLD })
    const w = mountPage()
    await flushPromises()
    expect(w.find('input.dropbox-picker').exists(), 'the link lives: the picker is offered').toBe(true)
    expect(getShareGuestNotice).toHaveBeenCalledWith({ shareId: SHARE, xContributorToken: 'tok-1' })
    const shown = w.findAll('[data-testid="guest-statement"]')
    expect(shown).toHaveLength(2)
    // No instance contact: the guest is told so in a sentence of its own (the template's guest block), no form.
    expect(shown[0].find('[data-passage="facts"]').text()).toBe(TEMPLATE.guest.factsNoContact.en)
    expect(shown[0].find('[data-passage="objectionByContact"]').text()).toBe(TEMPLATE.guest.objectionNoContact.en)
    expect(w.find('.statement-objection').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })

  it('asks nothing and shows nothing on a device that holds no session for the link', async () => {
    vi.mocked(loadDropGuest).mockReturnValue(null)
    const { getShareGuestNotice } = stubApi(publicSharesApi, { getShareGuestNotice: TOLD })
    const w = mountPage()
    await flushPromises()
    expect(getShareGuestNotice).not.toHaveBeenCalled()
    expect(w.find('[data-testid="guest-statement"]').exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })
})
