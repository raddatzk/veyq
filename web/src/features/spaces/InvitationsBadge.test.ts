// @vitest-environment happy-dom
// uc.spaces.consent-group-add · criterion `invitee-attention-badge` (cr-invitations-topbar-badge). The top-bar
// badge is appears-on-demand (hidden at zero), shows the open-proposal count, routes to /invitations, is
// self-clearing once the count falls, and fail-quiet on a fetch error — all driven by the module-singleton
// useMyInvitations() over the existing listMyGroupInvitations (mocked here — no new server signal to exercise).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const { listMock } = vi.hoisted(() => ({ listMock: vi.fn() }))
vi.mock('./members', () => ({ listMyGroupInvitations: listMock }))

import InvitationsBadge from './InvitationsBadge.vue'
import { refreshMyInvitations, clearMyInvitations, useMyInvitations } from './invitations'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: { shell: { invitations: 'Group invitations' } } },
})

const mountBadge = () => mount(InvitationsBadge, { global: { plugins: [i18n] } })
const row = (scopeId: string) => ({ scopeId, addedBy: 'x', sealedNameKey: '', encryptedName: '' })

describe('InvitationsBadge (cr-invitations-topbar-badge)', () => {
  beforeEach(() => {
    push.mockClear()
    listMock.mockReset()
    clearMyInvitations()
  })

  it('renders nothing when there are no open proposals (appears-on-demand)', async () => {
    listMock.mockResolvedValue([])
    await refreshMyInvitations()
    const w = mountBadge()
    expect(w.find('.invitations-trigger').exists()).toBe(false)
  })

  it('shows the open-proposal count and routes to /invitations on click', async () => {
    listMock.mockResolvedValue([row('a'), row('b'), row('c')])
    await refreshMyInvitations()
    const w = mountBadge()
    const btn = w.find('.invitations-trigger')
    expect(btn.exists()).toBe(true)
    expect(w.find('.invitations-badge').text()).toBe('3')
    await btn.trigger('click')
    expect(push).toHaveBeenCalledWith('/invitations')
  })

  it('is self-clearing — the badge disappears once the count falls to zero', async () => {
    listMock.mockResolvedValue([row('a')])
    await refreshMyInvitations()
    const w = mountBadge()
    expect(w.find('.invitations-trigger').exists()).toBe(true)

    listMock.mockResolvedValue([])
    await refreshMyInvitations()
    await w.vm.$nextTick()
    expect(w.find('.invitations-trigger').exists()).toBe(false)
  })

  it('fail-quiet — a fetch error resets the count to zero (no phantom badge)', async () => {
    listMock.mockResolvedValue([row('a')])
    await refreshMyInvitations()
    expect(useMyInvitations().count.value).toBe(1)

    listMock.mockRejectedValue(new Error('boom'))
    await refreshMyInvitations()
    expect(useMyInvitations().count.value).toBe(0)
  })
})
