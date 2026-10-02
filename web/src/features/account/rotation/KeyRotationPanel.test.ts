// @vitest-environment happy-dom
// uc.account.rotate-keys §9 (web): the state of every rotation an account rotation queued is the SERVER's, read on
// every visit — never a `waiting` the client synthesised once from the rotation's own response (ADR 0060
// Decision 6). The derivation is the backend's (RotationSliceTest); here we pin that the panel reads it, renders each
// state with its own hint, and offers the re-queue only where it can do anything: on `failed`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./rotateKeys', () => ({
  rotateKeys: vi.fn(),
  RotationRefused: class extends Error {},
  listQueuedRotations: vi.fn(),
  requeueQueuedRotation: vi.fn(),
}))
vi.mock('../../../app/vault', () => ({ unlockOrigin: () => 'cold' }))
vi.mock('../../spaces/scopes', () => ({ listMyTenants: vi.fn().mockResolvedValue([]) }))

import KeyRotationPanel from './KeyRotationPanel.vue'
import { listQueuedRotations, requeueQueuedRotation } from './rotateKeys'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Button: { props: ['label', 'icon'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  Dialog: true,
  Password: true,
  SecretKeyField: true,
  EmailAddressSection: true,
  TrustResetLink: true,
}

const STATES = ['waiting', 'stalled', 'secured', 'failed'] as const
const scopeOf = (state: string) => `s-${state}`

async function mountPanel() {
  vi.mocked(listQueuedRotations).mockResolvedValue(STATES.map((state) => ({ scopeId: scopeOf(state), state })) as never)
  const wrapper = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const row = (w: ReturnType<typeof mount>, scopeId: string) => w.find(`[data-queued-scope="${scopeId}"]`)
const requeueIn = (w: ReturnType<typeof mount>, scopeId: string) =>
  row(w, scopeId).findAll('button').find((b) => b.text() === t('rotation.requeue'))

describe('KeyRotationPanel — the queued rotations, as the server reads them', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reads the states on mount and shows each with its own badge and hint', async () => {
    const w = await mountPanel()
    expect(listQueuedRotations).toHaveBeenCalledTimes(1)
    for (const state of STATES) {
      const r = row(w, scopeOf(state))
      expect(r.exists(), state).toBe(true)
      expect(r.text()).toContain(t(`rotation.status.${state}`))
      expect(r.text()).toContain(t(`rotation.queuedHint.${state}`))
    }
  })

  it('offers the re-queue on the failed row only, and shows the answered state', async () => {
    const w = await mountPanel()
    for (const state of STATES.filter((s) => s !== 'failed')) expect(requeueIn(w, scopeOf(state)), state).toBeUndefined()
    const control = requeueIn(w, scopeOf('failed'))
    expect(control).toBeTruthy()

    vi.mocked(requeueQueuedRotation).mockResolvedValue({ scopeId: scopeOf('failed'), state: 'waiting' } as never)
    await control!.trigger('click')
    await flushPromises()

    expect(requeueQueuedRotation).toHaveBeenCalledWith(scopeOf('failed'))
    expect(row(w, scopeOf('failed')).text()).toContain(t('rotation.status.waiting'))
    expect(requeueIn(w, scopeOf('failed'))).toBeUndefined()
  })
})
