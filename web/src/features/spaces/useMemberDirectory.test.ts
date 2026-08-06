// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('../account/identityPins', () => ({ trustStates: vi.fn() }))

import { useMemberDirectory } from './useMemberDirectory'
import { trustStates } from '../account/identityPins'
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
  ({ userId, username: userId, role: 'MEMBER', hasGrant: true, identityPublicKey: `pk-${userId}`, sealedName: '' }) as ScopeMember
const httpError = (status: number) => ({ response: { status } })

beforeEach(() => {
  push.mockClear()
  vi.mocked(trustStates).mockResolvedValue({})
})

describe('useMemberDirectory', () => {
  it('loads rows + ownership on mount and refreshes trust', async () => {
    vi.mocked(trustStates).mockResolvedValueOnce({ a: 'changed' })
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a'), member('b')], isOwner: true })))
    expect(api.loading.value).toBe(true)
    await flushPromises()
    expect(api.loading.value).toBe(false)
    expect(api.members.value.map((m) => m.userId)).toEqual(['a', 'b'])
    expect(api.isOwner.value).toBe(true)
    expect(api.trust.value).toEqual({ a: 'changed' })
    expect(api.loadError.value).toBe('')
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

  it('openReview targets a member and opens the dialog', async () => {
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a')], isOwner: false })))
    await flushPromises()
    api.openReview(api.members.value[0])
    expect(api.reviewTarget.value?.userId).toBe('a')
    expect(api.reviewOpen.value).toBe(true)
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
