// @vitest-environment happy-dom
// uc.account.rotate-keys E9 (inbox #233): accepting a contact's changed key first runs one pass of the scope-rotation
// worker, then moves the pin. A grant is verified under the key its granter signed it with, and the pin must still
// hold that key for it to open — so an owner whose grant the rotator signed covers the queued rotation BEFORE their
// pin moves away from the key that signed it. The ordering, and that a failing pass never blocks the accept, are the
// two things pinned here; the crypto that makes the order matter is scopes.test.ts' "#233" block.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../account/identityPins', () => ({
  safetyNumber: vi.fn((k: string) => `sn-${k}`),
  getPin: vi.fn().mockResolvedValue({ key: 'old-key', state: 'pinned', firstSeenAt: 0 }),
  acceptKeyChange: vi.fn(),
}))
vi.mock('../tasks/scopeRotationWorker', () => ({ runScopeRotationWorker: vi.fn() }))

import ChangedKeyReviewDialog from './ChangedKeyReviewDialog.vue'
import { acceptKeyChange } from '../account/identityPins'
import { runScopeRotationWorker } from '../tasks/scopeRotationWorker'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  Dialog: { props: ['visible'], template: '<div v-if="visible"><slot /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  TrustResetLink: true,
}

const member = { userId: 'u-r', username: 'rotator', identityPublicKey: 'new-key' }
const mountDialog = () =>
  mount(ChangedKeyReviewDialog, { props: { member, visible: true }, global: { plugins: [realI18n()], stubs } })
const accept = (w: ReturnType<typeof mountDialog>) => w.findAll('button').find((b) => b.text() === t('verify.accept'))!

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
