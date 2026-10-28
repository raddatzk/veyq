// @vitest-environment happy-dom
// uc.account.rotate-keys (web), the ceremony — cr-secure-the-account decisions 7, 11, 13 and § Phase 5. The mechanism
// (`rotateKeys`, `finishRotation`) is its own suite; here the screen around it: the entry from the plan, the gate with
// "Also choose a new password" pre-selected, the new Emergency Kit gating the publish (the REAL `EmergencyKitSave`, so
// "ready" is its own downloaded AND confirmed), every error arm's copy, the per-space states, and the links to renew
// (the real `linksToRenew`, over a faked share list) opening the node's own share dialog.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, ref, computed } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./rotateKeys', () => ({
  rotateKeys: vi.fn(),
  mintSecretKey: vi.fn(),
  RotationRefused: class extends Error {
    constructor(public readonly reason: string) {
      super(`rotation refused: ${reason}`)
    }
  },
  RotationOutcomeUnknown: class extends Error {},
}))
vi.mock('../coldCredential', () => ({
  proveColdCredential: vi.fn(),
  ColdCredentialRefused: class extends Error {
    constructor(public readonly reason: string) {
      super(`cold credential refused: ${reason}`)
    }
  },
}))
vi.mock('./finishRotation', () => ({ listRotationStates: vi.fn() }))
vi.mock('./rotationEntry', () => ({ readRotationEntry: vi.fn() }))
vi.mock('../../../app/vault', () => ({ unlockOrigin: () => 'cold' }))
vi.mock('../../spaces/scopes', () => ({ listMyTenants: vi.fn(), listMyScopes: vi.fn(), openScopeById: vi.fn() }))
vi.mock('../../../api/client', () => ({ sharesApi: vi.fn() }))
vi.mock('../../../core/node/tree', () => ({ listTree: vi.fn(), nodeContentKey: vi.fn() }))
vi.mock('../emergencyKit/emergencyKit', () => ({ renderKitPdf: vi.fn(), saveKitPdf: vi.fn() }))
vi.mock('../passwordWarning', () => ({ useBreachCheck: vi.fn() }))

import KeyRotationPanel from './KeyRotationPanel.vue'
import { mintSecretKey, rotateKeys, RotationOutcomeUnknown, RotationRefused } from './rotateKeys'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { listRotationStates } from './finishRotation'
import { readRotationEntry, type RotationEntry } from './rotationEntry'
import { listMyScopes, listMyTenants, openScopeById } from '../../spaces/scopes'
import { sharesApi } from '../../../api/client'
import { listTree, nodeContentKey } from '../../../core/node/tree'
import { registry } from '../../../core/applications/registry'
import { forgetGroupNamesSeen, rememberGroupName } from '../../spaces/groupNamesSeen'
import type { RotationPlanScope, RotationScopeState } from '../../../api/generated/account'
import { ShareRole, type SharingApi, type ShareSummary } from '../../../api/generated/sharing'
import type { ScopeSummary } from '../../../api/generated/scopes'
import { realI18n, t } from '../../../test/i18n'
import { fakeApi } from '../../../test/apiMock'
import { useBreachCheck } from '../passwordWarning'
import { renderKitPdf } from '../emergencyKit/emergencyKit'
import { i18n } from '../../../i18n'
import { myTenant } from '../../../test/models'

const field = {
  props: ['modelValue', 'inputId'],
  emits: ['update:modelValue'],
  template: '<input :id="inputId" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
}
const stubs = {
  VeyqButton: {
    props: ['label', 'icon', 'disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqDialog: { props: ['visible'], template: '<div v-if="visible" class="dialog"><slot /></div>' },
  VeyqPassword: field,
  SecretKeyField: field,
  EmailAddressSection: true,
  TrustResetLink: true,
  PasswordBreachNotice: true,
}

const MINTED = 'bmV3LXNlY3JldC1rZXktYmFzZTY0LXRoaXJ0eS10d28='
const NEW_PASSWORD = 'a brand new passphrase'

const planRow = (o: Partial<RotationPlanScope> & Pick<RotationPlanScope, 'scopeId'>): RotationPlanScope => ({
  kind: 'GROUP', tenantId: 't1', currentEpoch: 0, wrappedGrant: 'AA==', grantedEpoch: 0, ...o,
})
const noEntry = (): RotationEntry => ({ plan: [], blocked: [], signedOpenInvites: false })
const share = (o: Partial<ShareSummary> & Pick<ShareSummary, 'shareId'>): ShareSummary => ({
  nodeId: `node-${o.shareId}`, scopeId: 's-here', role: ShareRole.Read, sealedName: 'AA==', enabled: true, mine: true,
  reissueRequired: true, deletable: true, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [], createdAt: new Date(0), ...o,
})
const scopeSummary = (id: string): ScopeSummary => ({
  id, kind: 'GROUP', role: 'OWNER', tenantId: 't1', nameEpoch: 0, iconEpoch: 0, currentEpoch: 0, grantedEpoch: 0, wrappedGrant: 'AA==',
})
const listShares = vi.fn<SharingApi['listShares']>()

beforeEach(() => {
  vi.clearAllMocks()
  forgetGroupNamesSeen()
  vi.mocked(mintSecretKey).mockReturnValue(MINTED)
  vi.mocked(listRotationStates).mockResolvedValue([])
  vi.mocked(readRotationEntry).mockResolvedValue(noEntry())
  vi.mocked(listMyTenants).mockResolvedValue([])
  vi.mocked(listMyScopes).mockResolvedValue([scopeSummary('s-here')])
  vi.mocked(openScopeById).mockResolvedValue(null)
  vi.mocked(proveColdCredential).mockResolvedValue(new Uint8Array(32))
  listShares.mockResolvedValue([])
  fakeApi(sharesApi, { listShares })
  vi.mocked(renderKitPdf).mockResolvedValue(new Uint8Array([1]))
  vi.mocked(useBreachCheck).mockReturnValue({
    state: ref({ kind: 'idle' }), useAnyway: computed({ get: () => false, set: () => undefined }), checkNow: vi.fn(), proceed: vi.fn(async () => true),
  })
})

async function mountPanel(): Promise<VueWrapper> {
  const w = mount(KeyRotationPanel, { global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}

const buttons = (w: VueWrapper, label: string) => w.findAll('button').filter((b) => b.text() === label)
const row = (w: VueWrapper, attr: string, id: string) => w.find(`[${attr}="${id}"]`)

/** Fill the gate: current password + Secret Key, and (pre-selected) the new password twice. */
async function fillGate(w: VueWrapper, { newPassword = true } = {}) {
  await w.find('#rotation-password').setValue('pw')
  await w.find('#rotation-secret-key').setValue('secret')
  if (newPassword) {
    await w.find('#rotation-new-password').setValue(NEW_PASSWORD)
    await w.find('#rotation-new-password-confirm').setValue(NEW_PASSWORD)
  }
}
async function toKit(w: VueWrapper) {
  await buttons(w, t('rotation.next'))[0].trigger('click')
  await flushPromises()
}
/** The real kit step: download, then tick "I have saved it". */
async function saveKit(w: VueWrapper, { confirm = true } = {}) {
  await buttons(w, t('kit.download'))[0].trigger('click')
  await flushPromises()
  if (confirm) {
    await w.find('.kit-confirm [role="checkbox"]').trigger('click')
    await flushPromises()
  }
}
const publishButton = (w: VueWrapper) => buttons(w, t('rotation.action'))[0]
async function publish(w: VueWrapper) {
  await publishButton(w).trigger('click')
  await buttons(w, t('rotation.action')).at(-1)!.trigger('click')
  await flushPromises()
}
async function throughTheCeremony(w: VueWrapper) {
  await fillGate(w)
  await toKit(w)
  await saveKit(w)
  await publish(w)
}

describe('the entry: plain words before any field, from the plan (decision 11)', () => {
  it('lists the spaces that cannot get their new lock yet, each with the plan’s reason or the device’s', async () => {
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 's-paused', name: 'Raddatz' })])
    rememberGroupName('s-going', 'Urlaub')
    vi.mocked(readRotationEntry).mockResolvedValue({
      plan: [planRow({ scopeId: 's-paused', kind: 'TENANT' }), planRow({ scopeId: 's-going' }), planRow({ scopeId: 's-closed' })],
      blocked: [
        { scopeId: 's-paused', kind: 'TENANT', tenantId: 's-paused', reason: 'tenant_suspended' },
        { scopeId: 's-going', kind: 'GROUP', tenantId: 't1', reason: 'scope_deleting' },
        { scopeId: 's-closed', kind: 'GROUP', tenantId: 't1', reason: 'unreadable' },
      ],
      signedOpenInvites: false,
    })
    const w = await mountPanel()
    expect(w.find('[data-testid="rotation-blocked"]').text()).toContain(t('rotation.blockedTitle'))
    expect(row(w, 'data-blocked-scope', 's-paused').text()).toContain('Raddatz')
    expect(row(w, 'data-blocked-scope', 's-paused').text()).toContain(t('rotation.blocked.tenant_suspended'))
    expect(row(w, 'data-blocked-scope', 's-going').text()).toContain('Urlaub')
    expect(row(w, 'data-blocked-scope', 's-going').text()).toContain(t('rotation.blocked.scope_deleting'))
    expect(row(w, 'data-blocked-scope', 's-closed').text()).toContain(t('rotation.blocked.unreadable'))
    expect(row(w, 'data-blocked-scope', 's-closed').text()).not.toContain('s-closed') // never the raw id
    // The entry stands before the fields.
    const html = w.html()
    expect(html.indexOf('data-testid="rotation-blocked"')).toBeLessThan(html.indexOf('id="rotation-password"'))
  })

  it('control: nothing blocked, no list — and the facts still render', async () => {
    const w = await mountPanel()
    expect(w.find('[data-testid="rotation-blocked"]').exists()).toBe(false)
    expect(w.text()).toContain(t('rotation.factSharedSpaces'))
    expect(w.text()).toContain(t('rotation.factNewKit'))
  })

  it('says invitations stop working only when this account signed open ones', async () => {
    vi.mocked(readRotationEntry).mockResolvedValue({ ...noEntry(), signedOpenInvites: true })
    expect((await mountPanel()).text()).toContain(t('rotation.factInvites'))
    vi.mocked(readRotationEntry).mockResolvedValue(noEntry())
    expect((await mountPanel()).text()).not.toContain(t('rotation.factInvites'))
  })
})

describe('the gate and the new password (decision 7)', () => {
  it('“Also choose a new password” is pre-selected, and the new password is what the publish installs', async () => {
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'published', states: [], unreadableScopeIds: [] })
    const w = await mountPanel()
    expect(w.find('#rotation-new-password-choice').attributes('data-state')).toBe('checked')
    await throughTheCeremony(w)
    expect(rotateKeys).toHaveBeenCalledWith('pw', 'secret', { secretKeyB64: MINTED, password: NEW_PASSWORD })
  })

  it('control: unticked, the password is kept — the publish names none', async () => {
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'published', states: [], unreadableScopeIds: [] })
    const w = await mountPanel()
    await w.find('#rotation-new-password-choice').trigger('click')
    expect(w.find('#rotation-new-password').exists()).toBe(false)
    await fillGate(w, { newPassword: false })
    await toKit(w)
    await saveKit(w)
    await publish(w)
    expect(rotateKeys).toHaveBeenCalledWith('pw', 'secret', { secretKeyB64: MINTED, password: undefined })
  })

  it('the gate proves the current credential before a kit is printed; a refusal stays on the gate', async () => {
    vi.mocked(proveColdCredential).mockRejectedValue(new (ColdCredentialRefused as unknown as new (r: string) => Error)('credentialRejected'))
    const w = await mountPanel()
    await fillGate(w)
    await toKit(w)
    expect(proveColdCredential).toHaveBeenCalledWith('pw', 'secret')
    expect(w.text()).toContain(t('rotation.errorCredential'))
    expect(buttons(w, t('kit.download'))).toHaveLength(0)
  })

  it('a different new password and confirmation stay on the gate', async () => {
    const w = await mountPanel()
    await fillGate(w)
    await w.find('#rotation-new-password-confirm').setValue('something else entirely')
    await toKit(w)
    expect(w.text()).toContain(t('changePassword.mismatch'))
    expect(proveColdCredential).not.toHaveBeenCalled()
  })

  it('a floor that opens the account’s NEWER key (an earlier publish committed) finishes it without a kit', async () => {
    vi.mocked(proveColdCredential).mockRejectedValue(new (ColdCredentialRefused as unknown as new (r: string) => Error)('vaultStale'))
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'resumed', states: [{ scopeId: 's-1', state: 'secured' }], unreadableScopeIds: [] })
    const w = await mountPanel()
    await fillGate(w)
    await toKit(w)
    expect(rotateKeys).toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="rotation-done"]').text()).toContain(t('rotation.doneOldKit'))
  })
})

describe('the new Emergency Kit gates the publish (decision 7)', () => {
  // Bite: `EmergencyKitSave` ready on the download alone (`ready = downloaded`) → the first case is red.
  it('the publish stays disabled after the download until the kit is confirmed saved', async () => {
    const w = await mountPanel()
    await fillGate(w)
    await toKit(w)
    expect(publishButton(w).attributes('disabled')).toBeDefined()
    await saveKit(w, { confirm: false })
    expect(publishButton(w).attributes('disabled')).toBeDefined()
    await w.find('.kit-confirm [role="checkbox"]').trigger('click')
    await flushPromises()
    expect(publishButton(w).attributes('disabled')).toBeUndefined()
  })

  it('the kit is printed from the very Secret Key the publish installs, and says to keep the old one until the end', async () => {
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'published', states: [], unreadableScopeIds: [] })
    const w = await mountPanel()
    await fillGate(w)
    await toKit(w)
    expect(w.text()).toContain(t('rotation.kitKeepOld'))
    await saveKit(w)
    expect(vi.mocked(renderKitPdf).mock.calls[0][0]).toMatchObject({ secretKey: MINTED })
    await publish(w)
    expect(vi.mocked(rotateKeys).mock.calls[0][2].secretKeyB64).toBe(MINTED)
  })

  it('the result says the old kit no longer works, and the minted key leaves the panel', async () => {
    vi.mocked(rotateKeys).mockResolvedValue({ outcome: 'published', states: [{ scopeId: 's-1', state: 'waiting' }], unreadableScopeIds: [] })
    const w = await mountPanel()
    await throughTheCeremony(w)
    expect(w.find('[data-testid="rotation-done"]').text()).toContain(t('rotation.doneOldKit'))
    expect(w.find('.kit-save').exists()).toBe(false)
    expect(row(w, 'data-queued-scope', 's-1').text()).toContain(t('rotation.status.waiting'))
  })
})

describe('each failure says what it knows (decision 12)', () => {
  const Refused = RotationRefused as unknown as new (r: string) => Error
  const cases: [string, () => unknown, string][] = [
    ['credentialRejected', () => new Refused('credentialRejected'), 'rotation.errorCredential'],
    ['noFloorWrap', () => new Refused('noFloorWrap'), 'rotation.errorNoFloor'],
    ['vaultLocked', () => new Refused('vaultLocked'), 'rotation.errorLocked'],
    ['trustStateRefused', () => new Refused('trustStateRefused'), 'rotation.errorTrustState'],
    ['requestTooLarge', () => new Refused('requestTooLarge'), 'rotation.errorTooLarge'],
    ['an unknown outcome', () => new (RotationOutcomeUnknown as unknown as new () => Error)(), 'rotation.errorUnknownOutcome'],
    ['a declared refusal of the publish', () => new Error('409 stale_revision'), 'rotation.errorFailed'],
  ]
  it.each(cases)('%s → its own copy', async (_name, error, key) => {
    vi.mocked(rotateKeys).mockRejectedValue(error())
    const w = await mountPanel()
    await throughTheCeremony(w)
    expect(w.text()).toContain(t(key))
    for (const [, , other] of cases) if (other !== key) expect(w.text()).not.toContain(t(other))
  })

  it('neither the unknown outcome nor a refused credential says nothing was changed — they cannot know it', () => {
    const say = (key: string, locale: 'en' | 'de') => i18n.global.t(key, {}, { locale })
    for (const locale of ['en', 'de'] as const) {
      const nothing = locale === 'en' ? /Nothing was changed/ : /Es wurde nichts geändert/
      expect(say('rotation.errorFailed', locale)).toMatch(nothing) // control: the copy for a declared refusal does say it
      expect(say('rotation.errorCredential', locale)).not.toMatch(nothing)
      expect(say('rotation.errorCredential', locale)).toMatch(/new Emergency Kit|neuen Notfall-Kit/)
      expect(say('rotation.errorUnknownOutcome', locale)).toMatch(/new Emergency Kit|neuen Notfall-Kit/)
    }
  })
})

describe('the spaces of the last rotation, as the server reads them (decision 8)', () => {
  it('each state with its badge and hint; a blocked one says why; each named, never by id', async () => {
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 's-root', name: 'Raddatz' })])
    rememberGroupName('s-group', 'Familie')
    vi.mocked(readRotationEntry).mockResolvedValue({
      ...noEntry(), plan: [planRow({ scopeId: 's-personal', kind: 'PERSONAL', tenantId: 't2' })],
    })
    const states: RotationScopeState[] = [
      { scopeId: 's-personal', state: 'secured' },
      { scopeId: 's-root', state: 'rekeying' },
      { scopeId: 's-group', state: 'waiting' },
      { scopeId: 's-unseen', state: 'blocked', reason: 'tenant_suspended' },
    ]
    vi.mocked(listRotationStates).mockResolvedValue(states)
    const w = await mountPanel()
    expect(row(w, 'data-queued-scope', 's-personal').text()).toContain(t('rotation.personalSpace')) // another tenant’s, from the plan
    expect(row(w, 'data-queued-scope', 's-root').text()).toContain('Raddatz')
    expect(row(w, 'data-queued-scope', 's-group').text()).toContain('Familie')
    expect(row(w, 'data-queued-scope', 's-group').text()).toContain(t('rotation.groupNameLastSeen'))
    expect(row(w, 'data-queued-scope', 's-unseen').text()).toContain(t('rotation.scopeNameUnavailable'))
    for (const s of states.slice(0, 3)) {
      expect(row(w, 'data-queued-scope', s.scopeId).text()).toContain(t(`rotation.status.${s.state}`))
      expect(row(w, 'data-queued-scope', s.scopeId).text()).toContain(t(`rotation.queuedHint.${s.state}`))
    }
    expect(row(w, 'data-queued-scope', 's-unseen').text()).toContain(t('rotation.status.blocked'))
    expect(row(w, 'data-queued-scope', 's-unseen').text()).toContain(t('rotation.blocked.tenant_suspended'))
    for (const s of states) expect(row(w, 'data-queued-scope', s.scopeId).text()).not.toContain(s.scopeId)
  })
})

describe('the links to renew (decision 13)', () => {
  const ShareDialogStub = defineComponent({
    props: ['scopeId', 'nodeId', 'nodeKey', 'nodeEpoch', 'epochs', 'nodeName', 'nodeKind', 'uploader', 'contribute'],
    emits: ['close'],
    setup: (props, { emit }) => () => h('div', { class: 'share-dialog-stub', 'data-node': props.nodeId, onClick: () => emit('close') }),
  })

  async function afterRotation(shares: ShareSummary[]) {
    listShares.mockResolvedValue(shares)
    vi.mocked(listRotationStates).mockResolvedValue([{ scopeId: 's-here', state: 'secured' }])
    return mountPanel()
  }

  it('a link the server marks reissueRequired renders Renew; one without the flag, or someone else’s, does not', async () => {
    const w = await afterRotation([
      share({ shareId: 'flagged' }),
      share({ shareId: 'fresh', reissueRequired: false }),
      share({ shareId: 'theirs', mine: false }),
    ])
    expect(listShares).toHaveBeenCalledWith() // account-wide: no nodeId
    expect(row(w, 'data-renew-share', 'flagged').find('button').text()).toBe(t('rotation.renew'))
    expect(row(w, 'data-renew-share', 'fresh').exists()).toBe(false)
    expect(row(w, 'data-renew-share', 'theirs').exists()).toBe(false)
  })

  it('control: no flagged link, no list', async () => {
    const w = await afterRotation([share({ shareId: 'fresh', reissueRequired: false })])
    expect(w.find('[data-testid="rotation-links"]').exists()).toBe(false)
  })

  it('a link in another tenant’s space says to open that tenant, with no button; an upload link says to make a new one', async () => {
    vi.mocked(listMyTenants).mockResolvedValue([myTenant({ tenantId: 't2', name: 'Verein' })])
    vi.mocked(readRotationEntry).mockResolvedValue({ ...noEntry(), plan: [planRow({ scopeId: 's-there', tenantId: 't2' })] })
    const w = await afterRotation([share({ shareId: 'there', scopeId: 's-there' }), share({ shareId: 'drop', role: ShareRole.WriteOnly })])
    expect(row(w, 'data-renew-share', 'there').text()).toContain(t('rotation.linkElsewhere', { tenant: 'Verein' }))
    expect(row(w, 'data-renew-share', 'there').find('button').exists()).toBe(false)
    expect(row(w, 'data-renew-share', 'drop').text()).toContain(t('rotation.linkUpload'))
    expect(row(w, 'data-renew-share', 'drop').find('button').text()).toBe(t('rotation.linkOpen'))
  })

  it('Renew opens that node’s share dialog through the registry, with the props its page hands it; closing zeroes its keys', async () => {
    const nodeKey = new Uint8Array(32).fill(5)
    const epochKey = new Uint8Array(32).fill(6)
    vi.mocked(openScopeById).mockResolvedValue({
      scopeId: 's-here', tenantId: 't1', kind: 'GROUP', key: new Uint8Array(32).fill(4), epochs: { current: 0, keys: new Map([[0, epochKey]]) },
      nameEpoch: 0, deletedAt: null, purgeAfter: null,
    } as unknown as Awaited<ReturnType<typeof openScopeById>>)
    vi.mocked(listTree).mockResolvedValue([{ id: 'node-flagged', kind: 'FOLDER', name: 'Reisen', epoch: 0 } as unknown as Awaited<ReturnType<typeof listTree>>[number]])
    vi.mocked(nodeContentKey).mockReturnValue(nodeKey)
    const kindOf = vi.spyOn(registry, 'kind').mockReturnValue({ supported: true, shareDialog: ShareDialogStub } as unknown as ReturnType<typeof registry.kind>)
    const w = await afterRotation([share({ shareId: 'flagged' })])
    expect(row(w, 'data-renew-share', 'flagged').text()).toContain('Reisen') // the node’s name as this device opens it
    await row(w, 'data-renew-share', 'flagged').find('button').trigger('click')
    await flushPromises()
    const dialog = w.findComponent(ShareDialogStub)
    expect(dialog.exists()).toBe(true)
    expect(kindOf).toHaveBeenCalledWith('FOLDER')
    expect(dialog.props()).toMatchObject({ scopeId: 's-here', nodeId: 'node-flagged', nodeName: 'Reisen', nodeKind: 'FOLDER', uploader: false, contribute: false })
    await dialog.trigger('click') // the dialog's close
    await flushPromises()
    expect(w.findComponent(ShareDialogStub).exists()).toBe(false)
    expect(nodeKey.every((b) => b === 0)).toBe(true)
    expect(epochKey.every((b) => b === 0)).toBe(true)
    kindOf.mockRestore()
  })

  it('a node that does not open here says so instead of a dialog', async () => {
    const w = await afterRotation([share({ shareId: 'flagged' })])
    await row(w, 'data-renew-share', 'flagged').find('button').trigger('click')
    await flushPromises()
    expect(w.text()).toContain(t('rotation.renewFailed'))
  })
})
