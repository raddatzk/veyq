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
import { tagAdmission } from './grant'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

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

beforeEach(() => {
  push.mockClear()
  vi.mocked(trustStates).mockResolvedValue({})
})

describe('useMemberDirectory', () => {
  it('loads rows + ownership on mount and refreshes trust', async () => {
    vi.mocked(trustStates).mockResolvedValueOnce({ a: { state: 'changed', pinVerified: false } })
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [member('a'), member('b')], isOwner: true })))
    expect(api.loading.value).toBe(true)
    await flushPromises()
    expect(api.loading.value).toBe(false)
    expect(api.members.value.map((m) => m.userId)).toEqual(['a', 'b'])
    expect(api.isOwner.value).toBe(true)
    expect(api.trust.value).toEqual({ a: { state: 'changed', pinVerified: false } })
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

describe('useMemberDirectory — admission (ADR 0020 move 6)', () => {
  // The directory surfaces what the grant-delivery worker declines as `admission_unverified`: a pending member whose
  // admission does not verify under this scope's current epoch key, beside the changed-key badge.
  const S = '5c09e000-0000-4000-8000-0000000000d1'
  const U = 'a0000000-0000-4000-8000-0000000000d2'
  const V = 'b0000000-0000-4000-8000-0000000000d3'
  const W = 'c0000000-0000-4000-8000-0000000000d4'
  const key = v1.randomBytes(32)
  const pending = (userId: string, admission: object): ScopeMember => ({ ...member(userId), hasGrant: false, ...admission }) as ScopeMember

  it('flags a pending member whose tag does not verify, and only that one', async () => {
    const good = pending(U, tagAdmission(key, { scopeId: S, kind: 'GROUP' }, 1, U))
    const forged = pending(V, { ...tagAdmission(key, { scopeId: S, kind: 'GROUP' }, 1, V), admissionTag: b64std(v1.randomBytes(32)) })
    const release = vi.fn()
    const { api } = harness(() =>
      useMemberDirectory(
        async () => ({ rows: [member(W), good, forged], isOwner: true }),
        async () => ({ scopeId: S, kind: 'GROUP' as const, key, epoch: 1, release }),
      ),
    )
    await flushPromises()
    expect(api.unadmitted.value).toEqual({ [V]: true })
    expect(release).toHaveBeenCalledTimes(1) // a key opened for this read is zeroed after it
  })

  it('flags nothing when the key cannot be had — never a broken directory', async () => {
    const forged = pending(V, { admissionId: null })
    const { api } = harness(() => useMemberDirectory(async () => ({ rows: [forged], isOwner: true }), async () => null))
    await flushPromises()
    expect(api.unadmitted.value).toEqual({})
    expect(api.loadError.value).toBe('')
  })
})
