// @vitest-environment happy-dom
// uc.account.rotate-keys §9 (web): the state of every scope of the account's last rotation is the SERVER's, read on
// every visit (cr-secure-the-account decision 8). The derivation is the backend's (RotationSliceTest); here we pin that
// the panel reads it, renders each state with its own hint, gates the publish on the new Emergency Kit, and maps every
// error the mechanism answers to its copy. The ceremony itself (steps, the entry screen, the links) is phase 5's.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./rotateKeys', () => ({
  rotateKeys: vi.fn(),
  mintSecretKey: vi.fn(),
  // Keep `reason`, so the panel's per-reason arms can be reached.
  RotationRefused: class extends Error {
    constructor(public readonly reason: string) {
      super(`rotation refused: ${reason}`)
    }
  },
  RotationOutcomeUnknown: class extends Error {},
}))
vi.mock('./finishRotation', () => ({ listRotationStates: vi.fn() }))
vi.mock('../../../app/vault', () => ({ unlockOrigin: () => 'cold' }))
vi.mock('../../spaces/scopes', () => ({ listMyTenants: vi.fn(), listMyScopes: vi.fn() }))
vi.mock('./soleOwnerSpaces', () => ({ soleOwnerSpaces: vi.fn() }))

import KeyRotationPanel from './KeyRotationPanel.vue'
import { mintSecretKey, rotateKeys, RotationOutcomeUnknown, RotationRefused } from './rotateKeys'
import { listRotationStates } from './finishRotation'
import { listMyScopes, listMyTenants } from '../../spaces/scopes'
import { soleOwnerSpaces } from './soleOwnerSpaces'
import { forgetGroupNamesSeen, rememberGroupName } from '../../spaces/groupNamesSeen'
import { ScopeKind, type ScopeSummary } from '../../../api/generated/scopes'
import type { RotationScopeState } from '../../../api/generated/account'
import { realI18n, t } from '../../../test/i18n'
import { myTenant } from '../../../test/models'

/** The new kit's save step, reporting ready at once unless a test says otherwise. */
const kit = vi.hoisted(() => ({ ready: true }))
const stubs = {
  VeyqButton: {
    props: ['label', 'icon', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqDialog: true,
  VeyqPassword: true,
  SecretKeyField: true,
  EmailAddressSection: true,
  TrustResetLink: true,
  EmergencyKitSave: {
    props: ['modelValue', 'secretKey', 'email'],
    emits: ['update:modelValue'],
    mounted(this: { $emit: (e: string, v: boolean) => void }) {
      this.$emit('update:modelValue', kit.ready)
    },
    template: '<div class="kit-save" :data-key="secretKey" />',
  },
}

const STATES = ['waiting', 'rekeying', 'secured'] as const
const scopeOf = (state: string) => `s-${state}`

async function mountPanel(states: RotationScopeState[] = STATES.map((state) => ({ scopeId: scopeOf(state), state }))) {
  vi.mocked(listRotationStates).mockResolvedValue(states)
  const wrapper = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const MINTED = 'bmV3LXNlY3JldC1rZXktYmFzZTY0LXRoaXJ0eS10d28='

beforeEach(() => {
  kit.ready = true
  vi.mocked(mintSecretKey).mockReturnValue(MINTED)
  vi.mocked(soleOwnerSpaces).mockResolvedValue([])
})

const row = (w: ReturnType<typeof mount>, scopeId: string) => w.find(`[data-queued-scope="${scopeId}"]`)

describe('KeyRotationPanel — the rotation’s spaces, as the server reads them', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listMyTenants).mockResolvedValue([])
    vi.mocked(listMyScopes).mockResolvedValue([])
  })

  it('reads the states on mount and shows each with its own badge and hint', async () => {
    const w = await mountPanel()
    expect(listRotationStates).toHaveBeenCalledTimes(1)
    for (const state of STATES) {
      const r = row(w, scopeOf(state))
      expect(r.exists(), state).toBe(true)
      expect(r.text()).toContain(t(`rotation.status.${state}`))
      expect(r.text()).toContain(t(`rotation.queuedHint.${state}`))
    }
  })

  it('a blocked space says why, by its reason', async () => {
    const w = await mountPanel([
      { scopeId: 's-paused', state: 'blocked', reason: 'tenant_suspended' },
      { scopeId: 's-going', state: 'blocked', reason: 'scope_deleting' },
    ])
    expect(row(w, 's-paused').text()).toContain(t('rotation.status.blocked'))
    expect(row(w, 's-paused').text()).toContain(t('rotation.blocked.tenant_suspended'))
    expect(row(w, 's-going').text()).toContain(t('rotation.blocked.scope_deleting'))
  })
})

// inbox #280/#281: a row carries a scope id only. The personal space is named as such, a tenant root by its plaintext
// name, a group by the name this device last opened (held in memory only), else a neutral label; never the raw id.
describe('KeyRotationPanel — a space is labelled, never shown by id', () => {
  const scopeSummary = (o: Partial<ScopeSummary> & Pick<ScopeSummary, 'id' | 'kind'>): ScopeSummary => ({
    role: 'OWNER', tenantId: 'tenant-1', nameEpoch: 0, iconEpoch: 0, currentEpoch: 0, grantedEpoch: 0, wrappedGrant: 'AA==', ...o,
  })

  beforeEach(() => {
    vi.clearAllMocks()
    forgetGroupNamesSeen()
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 's-root', name: 'Raddatz' })])
    vi.mocked(listMyScopes).mockResolvedValue([
      scopeSummary({ id: 's-personal', kind: ScopeKind.Personal }),
      scopeSummary({ id: 's-group', kind: ScopeKind.Group }),
      scopeSummary({ id: 's-unseen', kind: ScopeKind.Group }),
    ])
  })

  it('names each row by what this device knows, and shows no id', async () => {
    rememberGroupName('s-group', 'Familie')
    const ids = ['s-personal', 's-group', 's-root', 's-unseen', 's-elsewhere']
    const w = await mountPanel(ids.map((scopeId) => ({ scopeId, state: 'secured' })))
    expect(row(w, 's-personal').text()).toContain(t('rotation.personalSpace'))
    expect(row(w, 's-group').text()).toContain('Familie')
    expect(row(w, 's-group').text()).toContain(t('rotation.groupNameLastSeen'))
    expect(row(w, 's-root').text()).toContain('Raddatz')
    expect(row(w, 's-root').text()).not.toContain(t('rotation.groupNameLastSeen'))
    expect(row(w, 's-unseen').text()).toContain(t('rotation.scopeNameUnavailable'))
    expect(row(w, 's-elsewhere').text()).toContain(t('rotation.scopeNameUnavailable'))
    // The control on the same render: a row the device never opened still reads neutral, not 'Familie'.
    expect(row(w, 's-unseen').text()).not.toContain('Familie')
    for (const id of ids) expect(row(w, id).text(), id).not.toContain(id)
  })

  it('a failed read of the labels leaves every row neutral, still without its id', async () => {
    vi.mocked(listMyScopes).mockRejectedValue(new Error('offline'))
    vi.mocked(listMyTenants).mockRejectedValue(new Error('offline'))
    const w = await mountPanel([{ scopeId: 's-personal', state: 'secured' }])
    expect(row(w, 's-personal').text()).toContain(t('rotation.scopeNameUnavailable'))
    expect(row(w, 's-personal').text()).not.toContain('s-personal')
  })
})

describe('KeyRotationPanel — the spaces this account owns alone, before the button (uc.account.rotate-keys E4)', () => {
  // Phase 5 replaces this list with the entry screen from the plan; until then it renders as before.
  beforeEach(() => {
    vi.mocked(listMyTenants).mockResolvedValue([])
    vi.mocked(listRotationStates).mockResolvedValue([])
  })
  const line = (w: ReturnType<typeof mount>) => w.find('[data-testid="rotation-sole-owner"]')

  it('names each one under the warning, a space it cannot open by the neutral label', async () => {
    vi.mocked(soleOwnerSpaces).mockResolvedValue([{ scopeId: 'g1', name: 'Urlaub' }, { scopeId: 'g2', name: null }])
    const w = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(line(w).text()).toContain(t('rotation.soleOwner'))
    expect(line(w).findAll('li').map((li) => li.text())).toEqual(['Urlaub', t('rotation.scopeNameUnavailable')])
  })

  it('shows no warning when there is none', async () => {
    const w = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(line(w).exists()).toBe(false)
    expect(w.text()).toContain(t('rotation.factLinks')) // control: the facts did render
  })
})

describe('KeyRotationPanel — securing the account', () => {
  const field = { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">' }
  const rotatingStubs = { ...stubs, VeyqPassword: field, SecretKeyField: field, VeyqDialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' } }
  const actions = (w: ReturnType<typeof mount>) => w.findAll('button').filter((b) => b.text() === t('rotation.action'))

  async function filledIn() {
    vi.mocked(listRotationStates).mockResolvedValue([])
    const w = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs: rotatingStubs } })
    await flushPromises()
    const [pw, sk] = w.findAll('input')
    await pw.setValue('pw')
    await sk.setValue('secret')
    return w
  }

  async function rotateWith(outcome: { resolve: unknown } | { reject: unknown }) {
    if ('resolve' in outcome) vi.mocked(rotateKeys).mockResolvedValue(outcome.resolve as Awaited<ReturnType<typeof rotateKeys>>)
    else vi.mocked(rotateKeys).mockRejectedValue(outcome.reject)
    const w = await filledIn()
    await actions(w)[0].trigger('click')
    await actions(w).at(-1)!.trigger('click')
    await flushPromises()
    return w
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listMyTenants).mockResolvedValue([])
    vi.mocked(listMyScopes).mockResolvedValue([])
  })

  it('the publish stays disabled until the new Emergency Kit is saved and confirmed', async () => {
    // Bite: drop `kitReady` from the gate — the button is enabled with the kit unsaved.
    kit.ready = false
    const w = await filledIn()
    expect(actions(w)[0].attributes('disabled')).toBeDefined()
  })

  it('control: with the kit ready, it publishes the Secret Key the kit was printed from, and shows the server’s states', async () => {
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'published', states: [{ scopeId: 's-waiting', state: 'waiting' }], unreadableScopeIds: [] })
    const w = await filledIn()
    const printed = w.find('.kit-save').attributes('data-key')
    expect(printed).toBe(MINTED)
    await actions(w)[0].trigger('click')
    await actions(w).at(-1)!.trigger('click')
    await flushPromises()
    expect(rotateKeys).toHaveBeenCalledWith('pw', 'secret', { secretKeyB64: printed })
    expect(w.find('.kit-save').exists()).toBe(false) // the minted key leaves the panel once spent
    expect(row(w, 's-waiting').text()).toContain(t('rotation.status.waiting'))
    expect(w.text()).toContain(t('rotation.doneNotRepair'))
  })

  it('a refusal as too large shows the copy that names the Server Owner', async () => {
    const w = await rotateWith({ reject: new (RotationRefused as unknown as new (r: string) => Error)('requestTooLarge') })
    expect(w.text()).toContain(t('rotation.errorTooLarge'))
    expect(w.text()).not.toContain(t('rotation.errorFailed'))
  })

  it('an unknown outcome never says nothing was changed', async () => {
    const w = await rotateWith({ reject: new (RotationOutcomeUnknown as unknown as new () => Error)() })
    expect(w.text()).toContain(t('rotation.errorUnknownOutcome'))
    expect(w.text()).not.toContain(t('rotation.errorFailed'))
  })

  it('control: any other failure keeps the generic copy', async () => {
    const w = await rotateWith({ reject: new Error('offline') })
    expect(w.text()).toContain(t('rotation.errorFailed'))
    expect(w.text()).not.toContain(t('rotation.errorTooLarge'))
  })
})
