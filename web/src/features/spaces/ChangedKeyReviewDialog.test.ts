// @vitest-environment happy-dom
// uc.account.rotate-keys E9 (inbox #233): accepting a contact's changed key first runs one pass of the scope-rotation
// worker, then moves the pin. A grant is verified under the key its granter signed it with, and the pin must still
// hold that key for it to open — so an owner whose grant the rotator signed covers the queued rotation BEFORE their
// pin moves away from the key that signed it. The ordering, and that a failing pass never blocks the accept, are the
// two things pinned here; the crypto that makes the order matter is scopes.test.ts' "#233" block.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../account/identityPins', () => ({
  safetyNumber: vi.fn(),
  getPin: vi.fn(),
  acceptKeyChange: vi.fn(),
}))
vi.mock('../tasks/scopeRotationWorker', () => ({ runScopeRotationWorker: vi.fn() }))

import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'
import { acceptKeyChange, getPin, safetyNumber } from '../account/identityPins'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Button: { props: ['label', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  TrustResetLink: true,
}

const member = { userId: 'u-r', username: 'rotator', identityPublicKey: 'new-key' }
const mountDialog = (m = member) =>
  mount(ChangedKeyReviewDialog, { props: { member: m, visible: true }, global: { plugins: [realI18n()], stubs } })
const accept = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('verify.accept'))!

beforeEach(() => {
  vi.mocked(safetyNumber).mockImplementation((k) => `sn-${k}`)
  vi.mocked(getPin).mockResolvedValue({ key: 'old-key', state: 'pinned', firstSeenAt: 0 })
})

describe('ChangedKeyReviewDialog — accept drains the scope-rotation worker before the pin moves (inbox #233)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('says, before the accept, that a space still awaiting its re-key may then wait for another member', async () => {
    const w = mountDialog()
    await flushPromises()
    expect(w.text()).toContain(t('verify.reviewPendingRekey'))
  })

  it('the worker pass resolves before the pin store is written', async () => {
    const events: string[] = []
    let finishPass!: (n: number) => void
    vi.mocked(runScopeRotationWorker).mockImplementation(() => {
      events.push('pass started')
      return new Promise<number>((resolve) => (finishPass = (n) => { events.push('pass done'); resolve(n) }))
    })
    vi.mocked(acceptKeyChange).mockImplementation(async () => { events.push('pin written') })
    const w = mountDialog()
    await flushPromises()

    await accept(w).trigger('click')
    await flushPromises()
    expect(events).toEqual(['pass started']) // the pin waits for the pass
    finishPass(1)
    await flushPromises()

    expect(events).toEqual(['pass started', 'pass done', 'pin written'])
    expect(acceptKeyChange).toHaveBeenCalledWith('u-r', 'new-key')
    expect(w.emitted('accepted')).toHaveLength(1)
  })

  it('a pass that fails does not stop the accept', async () => {
    vi.mocked(runScopeRotationWorker).mockRejectedValue(new Error('claim failed'))
    const w = mountDialog()
    await flushPromises()

    await accept(w).trigger('click')
    await flushPromises()

    expect(runScopeRotationWorker).toHaveBeenCalledTimes(1)
    expect(acceptKeyChange).toHaveBeenCalledWith('u-r', 'new-key')
    expect(w.emitted('accepted')).toHaveLength(1)
  })
})

// inbox #246: the served key is decoded on this device to draw its safety number. A key the decoder refuses (the
// identity codec rejects malformed fields) must leave a stated reason and no way to accept it — not an unhandled
// error under two blank boxes.
describe('ChangedKeyReviewDialog — a key that cannot be read (inbox #246)', () => {
  beforeEach(() => vi.clearAllMocks())
  const refuseBad = (k: string) => {
    if (k === 'bad-key') throw new Error('malformed identity public key')
    return `sn-${k}`
  }

  it('a served key the decoder refuses shows why, and the accept is not offered', async () => {
    vi.mocked(safetyNumber).mockImplementation(refuseBad)
    const w = mountDialog({ ...member, identityPublicKey: 'bad-key' })
    await flushPromises()

    expect(w.text()).toContain(t('verify.currentUnreadable'))
    expect(w.find('code.expected').text()).toBe('sn-old-key')
    expect(accept(w).attributes('disabled')).toBeDefined()
    await accept(w).trigger('click')
    await flushPromises()
    expect(acceptKeyChange).not.toHaveBeenCalled()
  })

  it('a pinned key that cannot be read says so, and the current number still shows', async () => {
    vi.mocked(safetyNumber).mockImplementation(refuseBad)
    vi.mocked(getPin).mockResolvedValueOnce({ key: 'bad-key', state: 'pinned', firstSeenAt: 0 })
    const w = mountDialog()
    await flushPromises()

    expect(w.text()).toContain(t('verify.expectedUnreadable'))
    expect(w.text()).not.toContain(t('verify.currentUnreadable'))
    expect(w.find('code.current').text()).toBe('sn-new-key')
    expect(accept(w).attributes('disabled')).toBeUndefined()
  })
})
