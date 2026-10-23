// @vitest-environment happy-dom
// uc.admin.capability-requirements (web) — the drop box's share dialog builds no password and no end date of its own;
// where the space requires them (ADR 0088 decision 8) it asks for both, waits for them, and sends them with the link.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../dropBox', () => ({ mintDropBoxLink: vi.fn() }))
vi.mock('../../../core/sharing/manageShares', () => ({
  deleteShare: vi.fn(), isReissueRequired: vi.fn(), listSharesFor: vi.fn(), reactivateShare: vi.fn(), revokeShare: vi.fn(),
  updateShare: vi.fn(), readShareName: vi.fn(() => ''), readShareNote: vi.fn(() => ({ texts: {}, mustAccept: false })),
}))
vi.mock('../../../core/sharing/shareLink', () => ({ buildShareLink: vi.fn(() => 'https://x/s/new#k=a'), recoverShareLink: vi.fn() }))

import DropBoxShareDialog from './DropBoxShareDialog.vue'
import { mintDropBoxLink } from '../dropBox'
import { listSharesFor, updateShare } from '../../../core/sharing/manageShares'
import { setTenantCapabilities } from '../../../shared/state/useCapabilities'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqDialog: { template: '<div><slot /></div>' },
  VeyqButton: { props: ['label', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { template: '<div><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input class="input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqDateField: { props: ['modelValue'], template: '<input class="datepicker" />' },
}

function mountDialog() {
  return mount(DropBoxShareDialog, {
    props: { scopeId: 's', nodeId: 'n', nodeKey: new Uint8Array(32), epochs: { current: 0, keys: new Map() }, nodeEpoch: 0, nodeName: 'Box', nodeKind: 'DEPOT_DROPBOX' },
    global: { plugins: [realI18n()], stubs },
  })
}
const createButton = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('depot.dropBox.shareCreate'))!

describe('DropBoxShareDialog — the space\'s link requirements', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listSharesFor).mockResolvedValue([])
    vi.mocked(mintDropBoxLink).mockResolvedValue({ shareId: 'new', kShare: new Uint8Array(32) })
  })
  afterEach(() => setTenantCapabilities(null))

  it('asks for a password and an end date when required, waits for them, and sends both', async () => {
    setTenantCapabilities([{ id: 'core.public-shares', linkPassword: true, linkMaxDays: 7 }])
    const w = mountDialog()
    await flushPromises()
    expect(w.text()).toContain(t('share.passwordRequiredByRule'))
    expect(w.text()).toContain(t('share.expiryRequiredByRule', { days: 7 }))
    expect(createButton(w).attributes('disabled')).toBeDefined() // no password yet
    await w.find('#dropbox-share-password').setValue('a-long-password')
    expect(createButton(w).attributes('disabled')).toBeUndefined()
    await createButton(w).trigger('click')
    await flushPromises()
    const call = vi.mocked(mintDropBoxLink).mock.calls[0]!
    expect(call[6]).toBe('a-long-password')
    expect((call[7] as Date).getTime()).toBeLessThanOrEqual(Date.now() + 7 * 24 * 3600 * 1000)
  })

  it('control: without requirements it asks for neither and mints at once', async () => {
    const w = mountDialog()
    await flushPromises()
    expect(w.find('#dropbox-share-password').exists()).toBe(false)
    await createButton(w).trigger('click')
    await flushPromises()
    const call = vi.mocked(mintDropBoxLink).mock.calls[0]!
    expect(call[6]).toBe('')
    expect(call[7]).toBeNull()
  })
})

// inbox #457: the drop box's rows say why a link serves no guest too — and offer no fix, since this dialog builds no
// edit and no renew (a link that misses a requirement is switched off and replaced by a new one).
describe('DropBoxShareDialog — a link that serves no guest says why (#457)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('names the reason on the row, offers no fix on a link it cannot re-open, and a served link says nothing', async () => {
    const base = {
      scopeId: 's', nodeId: 'n', role: 'WRITE_ONLY', enabled: true, mine: true, createdAt: new Date(), expiresAt: null,
      sealedNote: null, sealedShareName: null, sealedShareKey: null, sealedShareKeyEpoch: null, passwordRequired: false,
      sealedPassword: null, reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false,
    } as const
    vi.mocked(listSharesFor).mockResolvedValue([
      { ...base, shareId: 'stopped', guestStops: [{ reason: 'tenantSwitch', capabilityId: 'core.public-shares.drop-box' }, { reason: 'linkPassword' }] },
      { ...base, shareId: 'served', guestStops: [] },
    ])
    const w = mountDialog()
    await flushPromises()
    const rows = w.findAll('.veyq-list-row')
    expect(rows[0]!.findAll('.share-guest-stop').map((s) => s.text())).toEqual([
      t('share.guestStops.tenantSwitch', { what: t('share.guestStops.entry.dropBox') }),
      t('share.guestStops.linkPassword'),
    ])
    expect(rows[0]!.find('.share-guest-stops-fix').exists()).toBe(false)
    expect(rows[1]!.find('.share-guest-stops').exists()).toBe(false)
  })
})

// inbox #457 (owner, 2026-10-16): the owner gives a stopped drop-box link what it misses, on the same link.
describe('DropBoxShareDialog — a stopped link is fixed in place (#457)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(updateShare).mockResolvedValue(undefined)
  })
  const base = {
    scopeId: 's', nodeId: 'n', role: 'WRITE_ONLY', enabled: true, mine: true, expiresAt: null, sealedNote: null,
    sealedShareName: null, sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
    reissueRequired: false, deletable: true, newGuestsRefused: false, writeVerifierMissing: false,
  } as const
  const save = (w: ReturnType<typeof mountDialog>) => w.find('.dropbox-fix-save')

  it('"Add a password" asks for one, waits for it, and sets it on that link — no new link', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([
      { ...base, shareId: 'stopped', createdAt: new Date(), guestStops: [{ reason: 'linkPassword' }] },
    ])
    const w = mountDialog()
    await flushPromises()
    expect(w.find('.dropbox-fix').exists()).toBe(false)
    await w.find('.share-guest-stops-edit').trigger('click')
    expect(w.find('.dropbox-fix').text()).toContain(t('share.guestStops.fixTitle'))
    expect(save(w).attributes('disabled'), 'no save without a password').toBeDefined()
    await w.find('#dropbox-fix-password').setValue('a long new password')
    await save(w).trigger('click')
    await flushPromises()
    const [address, , copy, , , expiry, role, passwordChange] = vi.mocked(updateShare).mock.calls[0]!
    expect(address).toEqual({ scopeId: 's', nodeId: 'n', shareId: 'stopped' })
    expect(copy).toMatchObject({ shareId: 'stopped' })
    expect([expiry, role]).toEqual([null, undefined]) // nothing else changed
    expect(passwordChange).toEqual({ set: 'a long new password' })
    expect(mintDropBoxLink).not.toHaveBeenCalled()
    expect(listSharesFor).toHaveBeenCalledTimes(2) // the list is read again, with the server's new answer
    expect(w.find('.dropbox-fix').exists()).toBe(false)
  })

  it('a link without an end date gets the last day its lifetime allows, counted from when it was made', async () => {
    const made = new Date(Date.now() - 2 * 24 * 3600 * 1000)
    vi.mocked(listSharesFor).mockResolvedValue([
      { ...base, shareId: 'stopped', createdAt: made, guestStops: [{ reason: 'linkMaxDays', maxDays: 7 }] },
    ])
    const w = mountDialog()
    await flushPromises()
    await w.find('.share-guest-stops-edit').trigger('click')
    expect(w.find('#dropbox-fix-password').exists(), 'no password asked for').toBe(false)
    await save(w).trigger('click')
    await flushPromises()
    const [address, , , , , expiry, , passwordChange] = vi.mocked(updateShare).mock.calls[0]!
    expect(address.shareId).toBe('stopped')
    expect(passwordChange).toBeUndefined()
    expect((expiry as Date).getTime()).toBeLessThanOrEqual(made.getTime() + 7 * 24 * 3600 * 1000)
    expect((expiry as Date).getTime()).toBeGreaterThan(Date.now())
  })

  it('a link older than its lifetime offers no fix: no end date fits it, and a new link is made below', async () => {
    vi.mocked(listSharesFor).mockResolvedValue([
      { ...base, shareId: 'old', createdAt: new Date(Date.now() - 30 * 24 * 3600 * 1000), guestStops: [{ reason: 'linkMaxDays', maxDays: 7 }] },
    ])
    const w = mountDialog()
    await flushPromises()
    expect(w.find('.share-guest-stop').text()).toBe(t('share.guestStops.linkMaxDaysOld', { days: 7 }))
    expect(w.find('.share-guest-stops-fix').exists()).toBe(false)
  })
})
