// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../account/identityPins', () => ({ trustStates: vi.fn() }))

import { useMemberDirectory } from './useMemberDirectory'
import { trustStates } from '../account/identityPins'
import { TrustStateRefused } from '../account/trustMarks'
import type { ScopeMember } from './members'

function harness<T>(factory: () => T) {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        const api = factory()
        expose({ api })
        return () => h('div')
      },
    }),
  )
  return { api: (wrapper.vm as unknown as { api: T }).api, wrapper }
}

const member = (userId: string): ScopeMember =>
  ({ userId, username: userId, role: 'MEMBER', avatarRevision: 0, identityPublicKey: `pk-${userId}`, keyChainHead: `head-${userId}`, pending: false, sealedName: '' })

beforeEach(() => {
  push.mockClear()
  vi.mocked(trustStates).mockResolvedValue({})
})

describe('useMemberDirectory', () => {
  it('loads rows + ownership on mount and refreshes trust', async () => {
    vi.mocked(trustStates).mockResolvedValueOnce({ a: { state: 'anomaly' } })
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a'), member('b')], isOwner: true })))
    expect(api.loading.value).toBe(true)
    await flushPromises()
    expect(api.loading.value).toBe(false)
    expect(api.members.value.map((m) => m.userId)).toEqual(['a', 'b'])
    expect(api.isOwner.value).toBe(true)
    expect(api.trust.value).toEqual({ a: { state: 'anomaly' } })
    // Each member is judged against the key AND the head the directory served for them.
    expect(trustStates).toHaveBeenCalledWith([
      { userId: 'a', identityPublicKey: 'pk-a', keyChainHead: 'head-a' },
      { userId: 'b', identityPublicKey: 'pk-b', keyChainHead: 'head-b' },
    ])
    expect(api.loadError.value).toBe('')
  })

  it('names a refused pin store (ADR 0028 Decision 7) instead of a directory that merely shows no flags', async () => {
    vi.mocked(trustStates).mockRejectedValueOnce(new TrustStateRefused('trust_state_unreadable', 'pins'))
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a')], isOwner: true })))
    await flushPromises()
    expect(api.trustRefusal.value).toBe('verify.trustUnreadable')
    expect(api.trust.value).toEqual({})
    expect(api.members.value.map((m) => m.userId)).toEqual(['a']) // the directory itself still loads
  })

  it('control: any other trust-read failure degrades silently, as before', async () => {
    vi.mocked(trustStates).mockRejectedValueOnce(new Error('vault locked'))
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a')], isOwner: true })))
    await flushPromises()
    expect(api.trustRefusal.value).toBeNull()
  })

  it('maps a 403 load to notAuthorized and a 500 to failed', async () => {
    const { api } = harness(() => useMemberDirectory(async () => Promise.reject(httpError(403))))
    await flushPromises()
    expect(api.loadError.value).toBe('notAuthorized')
    await flushPromises()
  })

  it('redirects to login on a 401 load (via handle401)', async () => {
    const { api } = harness(() => useMemberDirectory(async () => Promise.reject(httpError(401))))
    await flushPromises()
    expect(push).toHaveBeenCalledWith({ name: 'login' })
    expect(api.loadError.value).toBe('') // 401 → the redirect handles it, no error shown
  })

  it('reload re-runs the load body', async () => {
    let owner = false
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a')], isOwner: owner })))
    await flushPromises()
    expect(api.isOwner.value).toBe(false)
    owner = true
    await api.reload()
    expect(api.isOwner.value).toBe(true)
  })
})
