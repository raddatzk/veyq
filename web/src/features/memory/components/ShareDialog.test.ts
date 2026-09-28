// @vitest-environment happy-dom
// cr-share-link-expiry-ui — the manage list shows a share's expiry when set (next to "created"), and nothing extra
// when there's none. `expiresAt` is already parsed onto ManagedShare; this covers the display half (the set half
// lives in ShareCreateDialog.test.ts). The data layer is stubbed so the list mounts crypto/network-free.
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const listSharesFor = vi.fn()
vi.mock('../shareLink', () => ({
  recoverShareLink: vi.fn(() => 'https://x/s/1#k=abc'),
}))
vi.mock('../share', () => ({
  listSharesFor: (...a: unknown[]) => listSharesFor(...a),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  readShareName: vi.fn(() => 'A share'),
  readShareNoteMap: vi.fn(() => ({})),
  hasLocaleText: vi.fn(() => false),
}))

import ShareDialog from './ShareDialog.vue'
import { ShareRole } from '../../../api/generated/sharing'
import { realI18n, t } from '../../../test/i18n'

const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000cd'
const EPOCHS = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) }

const share = (over: Record<string, unknown>) => ({
  shareId: 's', scopeId: SCOPE_ID, nodeId: 'n1', role: ShareRole.Viewer, enabled: true, mine: true,
  createdAt: new Date('2026-01-01T00:00:00Z'), expiresAt: null,
  sealedNote: null, sealedShareName: null, sealedShareKey: 'k', sealedShareKeyEpoch: 0, passwordRequired: false, sealedPassword: null,
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
