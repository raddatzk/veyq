// @vitest-environment happy-dom
// cr-share-link-expiry-ui — the manage list shows a share's expiry when set (next to "created"), and nothing extra
// when there's none. `expiresAt` is already parsed onto ManagedShare; this covers the display half (the set half
// lives in ShareCreateDialog.test.ts). The data layer is stubbed so the list mounts crypto/network-free.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const listSharesFor = vi.fn()
const deleteShare = vi.fn()
vi.mock('../shareLink', () => ({
  recoverShareLink: vi.fn(() => 'https://x/s/1#k=abc'),
}))
vi.mock('../share', () => ({
  isReissueRequired: vi.fn(),
  listSharesFor: (...a: unknown[]) => listSharesFor(...a),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  deleteShare: (...a: unknown[]) => deleteShare(...a),
  readShareName: vi.fn(() => 'A share'),
  readShareNoteMap: vi.fn(() => ({})),
  hasLocaleText: vi.fn(() => false),
}))

import ShareDialog from './ShareDialog.vue'
import { confirmRequest, settleConfirm } from '../../../shared/state/useConfirm'
import { ShareRole } from '../../../api/generated/sharing'
import { realI18n, t } from '../../../test/i18n'

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000cd'
const EPOCHS = { current: 1, keys: new Map([[0, new Uint8Array(32)], [1, new Uint8Array(32)]]) }

const share = (over: Record<string, unknown>) => ({
  shareId: 's', scopeId: SCOPE_ID, nodeId: 'n1', role: ShareRole.Viewer, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null,
  sealedNote: null, sealedShareName: null, sealedShareKey: 'k', sealedShareKeyEpoch: 1, passwordRequired: false, sealedPassword: null,
  reissueRequired: false,
  deletable: true,
  newGuestsRefused: false,
  ...over,
})

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  ShareCreateDialog: true,
}

const mountList = () =>
  mount(ShareDialog, {
    props: { scopeId: SCOPE_ID, nodeId: 'n1', memoryKey: new Uint8Array([1, 2, 3]), epochs: EPOCHS, memoryName: 'Rom' },
    global: { plugins: [realI18n()], stubs },
  })

describe('ShareDialog — expiry display (cr-share-link-expiry-ui)', () => {
  it('shows the expiry line for a share with expiresAt, and only for that one', async () => {
    listSharesFor.mockResolvedValue([
      share({ shareId: 'withExpiry', expiresAt: new Date('2026-08-01T21:59:59Z') }),
      share({ shareId: 'noExpiry', expiresAt: null }),
    ])
    const w = mountList()
    await flushPromises()

    expect(w.findAll('.share-list-expires')).toHaveLength(1)
    expect(w.text()).toContain(t('share.expires', { date: '' }).trim())
  })

  it('shows no expiry line when no share has one', async () => {
    listSharesFor.mockResolvedValue([share({ expiresAt: null })])
    const w = mountList()
    await flushPromises()

    expect(w.findAll('.share-list-expires')).toHaveLength(0)
    expect(w.text()).not.toContain(t('share.expires', { date: '' }).trim())
  })
})

// W1 (ADR 0075 point 4): the panel follows the server's re-activation verdict, served as `reissueRequired`. The
// client cannot derive it — the removal count is not on the wire — so the marked fixture sits at the CURRENT epoch,
// where a client-side epoch compare would answer wrong.
const button = (w: ReturnType<typeof mountList>, label: string) => w.find('button[aria-label="' + label + '"]')
/** Unfold the switched-off links — they sit under a count until opened. */
async function openOff(w: ReturnType<typeof mountList>) {
  await w.find('.share-fold-toggle').trigger('click')
}

// #270 (ADR 0076 point 5): a guest link minted before the write verifier takes no new guest until renewed. The panel
// follows the server's `newGuestsRefused` and marks the row, beside the renew its owner already has.
describe('ShareDialog — a link that takes no new guest until renewed (#270)', () => {
  it('marks a live link the server says refuses new guests, and its owner can renew it', async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'old', role: ShareRole.Contributor, newGuestsRefused: true })])
    const w = mountList()
    await flushPromises()

    expect(w.find('.share-new-guests-refused').text()).toBe(t('share.newGuestsRefusedBadge'))
    expect(w.find('.share-new-guests-refused').attributes('title')).toBe(t('share.newGuestsRefusedHint'))
    expect(button(w, t('share.reissue')).exists()).toBe(true)
  })

  it('control: a link the server does not mark carries no such badge', async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'new', role: ShareRole.Contributor, newGuestsRefused: false })])
    const w = mountList()
    await flushPromises()

    expect(w.find('.share-list-item').exists()).toBe(true)
    expect(w.find('.share-new-guests-refused').exists()).toBe(false)
  })
})

describe('ShareDialog — renew vs re-activate (ADR 0075, W1)', () => {
  it('marks a disabled share the server says needs renewing and offers no re-activate — even at the current epoch', async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'counted', enabled: false, sealedShareKeyEpoch: 1, reissueRequired: true })])
    const w = mountList()
    await flushPromises()
    await openOff(w)

    expect(w.text()).toContain(t('share.reissueRequiredBadge'))
    expect(button(w, t('share.reactivate')).exists()).toBe(false)
    expect(button(w, t('share.reissue')).exists()).toBe(true)
  })

  it('control: a disabled share the server does not mark carries no badge and keeps its re-activate', async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'cur', enabled: false, sealedShareKeyEpoch: 1, reissueRequired: false })])
    const w = mountList()
    await flushPromises()
    await openOff(w)

    expect(w.text()).not.toContain(t('share.reissueRequiredBadge'))
    expect(button(w, t('share.reactivate')).exists()).toBe(true)
  })

  it("offers no renew on a co-member's enabled share — only its owner may replace a live link", async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'theirs', mine: false, enabled: true })])
    const w = mountList()
    await flushPromises()
    expect(button(w, t('share.reissue')).exists()).toBe(false)

    // control: the same share once it is off is anyone's to renew
    listSharesFor.mockResolvedValue([share({ shareId: 'theirs', mine: false, enabled: false })])
    const w2 = mountList()
    await flushPromises()
    await openOff(w2)
    expect(button(w2, t('share.reissue')).exists()).toBe(true)
  })
})

// W1 (ADR 0029 §7): delete is the trash can now, so switching off no longer wears it; delete is offered on a
// switched-off link only, where the server's `deletable` says the gate would let it through, and behind a confirm.
describe('ShareDialog — delete a link (ADR 0029 §7, W1)', () => {
  it('folds switched-off links under a count, offers delete only there, and switching off wears no trash can', async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'on', enabled: true }), share({ shareId: 'off', enabled: false })])
    const w = mountList()
    await flushPromises()

    expect(w.findAll('.share-list-item')).toHaveLength(1) // only the live link until the fold opens
    expect(w.find('.share-fold-toggle').text()).toBe(realI18n().global.t('share.offCount', { count: 1 }, 1))
    expect(button(w, t('share.revoke')).attributes('icon')).toBe('pi pi-ban')
    expect(button(w, t('share.delete')).exists()).toBe(false) // the enabled row, though deletable, offers none

    await openOff(w)
    expect(w.findAll('.share-list-item')).toHaveLength(2)
    expect(w.findAll('button[aria-label="' + t('share.delete') + '"]')).toHaveLength(1)
    expect(button(w, t('share.delete')).attributes('icon')).toBe('pi pi-trash')
  })

  it('deletes only after the confirm, and the row leaves the list', async () => {
    deleteShare.mockReset().mockResolvedValue(undefined)
    listSharesFor.mockResolvedValue([share({ shareId: 'on', enabled: true }), share({ shareId: 'off', enabled: false })])
    const w = mountList()
    await flushPromises()
    await openOff(w)

    await button(w, t('share.delete')).trigger('click')
    expect(deleteShare).not.toHaveBeenCalled() // the click asks, nothing more
    expect(confirmRequest.value?.message).toBe(t('share.deleteConfirm'))
    settleConfirm(true)
    await flushPromises()

    expect(deleteShare).toHaveBeenCalledWith('off')
    expect(w.findAll('.share-list-item')).toHaveLength(1)
    expect(w.find('.share-fold-toggle').exists()).toBe(false) // nothing switched off is left to fold
  })

  it('a declined confirm deletes nothing', async () => {
    deleteShare.mockReset().mockResolvedValue(undefined)
    listSharesFor.mockResolvedValue([share({ shareId: 'off', enabled: false })])
    const w = mountList()
    await flushPromises()
    await openOff(w)

    await button(w, t('share.delete')).trigger('click')
    settleConfirm(false)
    await flushPromises()

    expect(deleteShare).not.toHaveBeenCalled()
    expect(w.findAll('.share-list-item')).toHaveLength(1)
  })

  it("control: a co-member's switched-off link whose owner is still here offers no delete", async () => {
    listSharesFor.mockResolvedValue([share({ shareId: 'theirs', mine: false, enabled: false, deletable: false })])
    const w = mountList()
    await flushPromises()
    await openOff(w)

    expect(w.findAll('.share-list-item')).toHaveLength(1) // the row is there, so the absence below is real
    expect(button(w, t('share.delete')).exists()).toBe(false)
  })
})
