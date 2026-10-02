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
import { listMyTenants } from '../../spaces/scopes'
import { forgetGroupNamesSeen, rememberGroupName } from '../../spaces/groupNamesSeen'
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

// inbox #280: a queued group's name is sealed under a key the rotator cannot open until re-admitted. The row shows
// the name this device last opened (held in memory only — nothing new is persisted), marked as possibly stale, or
// a neutral label; never the raw scope id.
describe('KeyRotationPanel — a queued group is labelled, never shown by id', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    forgetGroupNamesSeen()
  })

  it('shows a neutral label for a group this device never opened', async () => {
    const w = await mountPanel()
    const r = row(w, scopeOf('waiting'))
    expect(r.text()).not.toContain(scopeOf('waiting'))
    expect(r.text()).toContain(t('rotation.groupNameUnavailable'))
  })

  it('shows the name this device last opened, marked as possibly out of date', async () => {
    rememberGroupName(scopeOf('stalled'), 'Familie')
    const w = await mountPanel()
    const r = row(w, scopeOf('stalled'))
    expect(r.text()).toContain('Familie')
    expect(r.text()).toContain(t('rotation.groupNameLastSeen'))
    expect(r.text()).not.toContain(scopeOf('stalled'))
    expect(r.text()).not.toContain(t('rotation.groupNameUnavailable'))
    // The control on the same render: a row the device never opened still reads neutral, not 'Familie'.
    expect(row(w, scopeOf('waiting')).text()).not.toContain('Familie')
  })

  it('a tenant root keeps its plaintext name, unmarked', async () => {
    vi.mocked(listMyTenants).mockResolvedValueOnce([{ tenantId: scopeOf('secured'), name: 'Raddatz' }] as never)
    const w = await mountPanel()
    const r = row(w, scopeOf('secured'))
    expect(r.text()).toContain('Raddatz')
    expect(r.text()).not.toContain(t('rotation.groupNameLastSeen'))
    expect(r.text()).not.toContain(t('rotation.groupNameUnavailable'))
  })
})
