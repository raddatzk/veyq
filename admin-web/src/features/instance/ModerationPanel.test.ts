// @vitest-environment happy-dom
// uc.moderation.appoint-moderators (admin-web, ADR 0099) — Server → Moderation. Each case is one observable:
//   - with no epoch, the panel says reporting is off, and turning moderation on asks first;
//   - a due rotation is said, and its button rotates;
//   - the roster shows each moderator's state and whether their address is confirmed;
//   - a removal asks first, and a refused rotation behind it is SHOWN (the CR's bite, below);
//   - an appointment of an address no account has is refused in words.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./moderation', async () => {
  const actual = await vi.importActual<typeof import('./moderation')>('./moderation')
  return {
    ...actual,
    getModeration: vi.fn(),
    appointModerator: vi.fn(),
    removeModerator: vi.fn(),
    rotateModerationKey: vi.fn(),
  }
})
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import ModerationPanel from './ModerationPanel.vue'
import {
  appointModerator, getModeration, removeModerator, rotateModerationKey,
  type InstanceModeration, type InstanceModerator,
} from './moderation'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Select: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
  },
  Button: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})

function moderator(over: Partial<InstanceModerator>): InstanceModerator {
  return {
    accountId: 'owner', address: 'owner@example.com', state: 'ACCEPTED', appointedAt: new Date('2026-10-01T00:00:00Z'),
    acceptedAt: new Date('2026-10-01T00:00:00Z'), identityPublicKey: 'AAAA', holdsCurrentEpoch: true, addressConfirmed: true,
    ...over,
  }
}

function on(over: Partial<InstanceModeration> = {}): InstanceModeration {
  return {
    enabled: true,
    currentEpoch: 2,
    rotationDueSince: null,
    serverOwner: { accountId: 'owner', identityPublicKey: 'AAAA' },
    moderators: [
      moderator({}),
      moderator({ accountId: 'deputy', address: 'deputy@example.com', addressConfirmed: false }),
      moderator({ accountId: 'new', address: 'new@example.com', state: 'PENDING', acceptedAt: null, identityPublicKey: null, holdsCurrentEpoch: false, addressConfirmed: false }),
    ],
    ...over,
  }
}

const off: InstanceModeration = { ...on(), enabled: false, currentEpoch: null, moderators: [] }

async function mountPanel(initial: InstanceModeration) {
  vi.mocked(getModeration).mockResolvedValue(initial)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const w = mount(ModerationPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return w
}
const row = (w: Awaited<ReturnType<typeof mountPanel>>, address: string) => w.find(`[data-address="${address}"]`)

describe('ModerationPanel (uc.moderation.appoint-moderators, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('says reporting is off while no key exists, and offers no way to make one here (setup makes epoch 1)', async () => {
    const w = await mountPanel(off)
    expect(w.find('.mod-off').text()).toBe(t('moderation.off'))
    expect(w.find('.mod-roster').exists()).toBe(false)
    expect(w.find('.mod-enable').exists()).toBe(false)
  })

  it('shows each moderator’s state and whether the address is confirmed', async () => {
    const w = await mountPanel(on({ moderators: [
      moderator({}),
      moderator({ accountId: 'deputy', address: 'deputy@example.com', holdsCurrentEpoch: false, addressConfirmed: false }),
      moderator({ accountId: 'new', address: 'new@example.com', state: 'PENDING', acceptedAt: null, identityPublicKey: null, holdsCurrentEpoch: false }),
    ] }))
    expect(row(w, 'owner@example.com').text()).toContain(t('moderation.stateHolds'))
    expect(row(w, 'owner@example.com').text()).toContain(t('moderation.addressConfirmed'))
    expect(row(w, 'deputy@example.com').text()).toContain(t('moderation.stateWaiting'))
    expect(row(w, 'deputy@example.com').text()).toContain(t('moderation.addressUnconfirmed'))
    expect(row(w, 'new@example.com').text()).toContain(t('moderation.statePending'))
    expect(w.find('.mod-epoch').text()).toBe(t('moderation.epoch', { epoch: 2 }))
  })

  it('warns of a due rotation, and its button rotates with the removal reason it starts on', async () => {
    vi.mocked(rotateModerationKey).mockResolvedValue(on({ currentEpoch: 3 }))
    const w = await mountPanel(on({ rotationDueSince: new Date('2026-10-17T09:00:00Z') }))
    expect(w.find('.mod-due').text()).toContain('Rotation due since')
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-due__rotate').trigger('click')
    await flushPromises()
    expect(rotateModerationKey).toHaveBeenCalledWith('REMOVAL', expect.objectContaining({ currentEpoch: 2 }))
    expect(w.find('.mod-due').exists()).toBe(false)
  })

  it('says nothing is due when nothing is — control', async () => {
    const w = await mountPanel(on())
    expect(w.find('.mod-due').exists()).toBe(false)
  })

  it('rotates by hand with the chosen reason, after asking', async () => {
    vi.mocked(rotateModerationKey).mockResolvedValue(on({ currentEpoch: 3 }))
    const w = await mountPanel(on())
    await w.find('.mod-reason').setValue('COMPROMISE')
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-rotate__submit').trigger('click')
    await flushPromises()
    expect(rotateModerationKey).toHaveBeenCalledWith('COMPROMISE', expect.objectContaining({ currentEpoch: 2 }))
  })

  // THE CR's bite (phase 5): a removal whose rotation the server refuses — the grants no longer name exactly the
  // accepted moderators — must reach the Server Owner as the panel's error. This case goes red if the panel swallows
  // the refusal (measured: a `catch` that drops the error leaves `.mod-error` absent and the case fails).
  it('shows a refused rotation after a removal as the panel’s error, and reads the roster again', async () => {
    vi.mocked(removeModerator).mockRejectedValue(httpError(409, 'moderation_grants_incomplete'))
    const w = await mountPanel(on())
    vi.mocked(getModeration).mockResolvedValue(on({ rotationDueSince: new Date('2026-10-17T10:00:00Z') }))
    confirmAsk.mockResolvedValueOnce(true)
    await row(w, 'deputy@example.com').find('.mod-remove').trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('moderation.removeBody') }))
    expect(removeModerator).toHaveBeenCalledWith('deputy')
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.grantsIncomplete'))
    // the removal stood: the roster read again now says a rotation is due
    expect(w.find('.mod-due').exists()).toBe(true)
  })

  it('shows a refused rotation by hand as the panel’s error', async () => {
    vi.mocked(rotateModerationKey).mockRejectedValue(httpError(409, 'moderation_grants_incomplete'))
    const w = await mountPanel(on())
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-rotate__submit').trigger('click')
    await flushPromises()
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.grantsIncomplete'))
  })

  it('removes nothing when the removal is not confirmed', async () => {
    const w = await mountPanel(on())
    confirmAsk.mockResolvedValueOnce(false)
    await row(w, 'deputy@example.com').find('.mod-remove').trigger('click')
    await flushPromises()
    expect(removeModerator).not.toHaveBeenCalled()
  })

  it('appoints by address, and names an address no account has', async () => {
    vi.mocked(appointModerator).mockRejectedValueOnce(httpError(404, 'account_not_found'))
    const w = await mountPanel(on())
    await w.find('.mod-address').setValue('nobody@example.com')
    await w.find('.mod-appoint').trigger('submit')
    await flushPromises()
    expect(appointModerator).toHaveBeenCalledWith('nobody@example.com')
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.accountNotFound'))
    expect((w.find('.mod-address').element as HTMLInputElement).value).toBe('nobody@example.com') // kept to correct

    vi.mocked(appointModerator).mockResolvedValueOnce(on())
    await w.find('.mod-address').setValue('deputy@example.com')
    await w.find('.mod-appoint').trigger('submit')
    await flushPromises()
    expect(w.find('.mod-error').exists()).toBe(false)
    expect((w.find('.mod-address').element as HTMLInputElement).value).toBe('')
  })
})
