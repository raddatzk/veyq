// @vitest-environment happy-dom
// uc.account.onboard-device — the receiving half BOTH cards share (`JoinDeviceCard`, `SignInWithCodeCard`). What
// is pinned here is pinned once for both: the cards' own suites keep what differs between the two directions.
//
// Real crypto, the real poll loop; only the wire and the tail are mocked.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./pairingChannel', () => ({ collectPairingChannel: vi.fn() }))
vi.mock('./onboardTail', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./onboardTail')>()),
  runOnboardTail: vi.fn(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ isRememberSupported: () => true }))
vi.mock('../../../shared/state/useCapabilities', () => ({ useCapabilities: () => ({ hasCapability: () => true }) }))

import { useGrantReceiver } from './useGrantReceiver'
import { collectPairingChannel } from './pairingChannel'
import { runOnboardTail } from './onboardTail'
import { channelKeys, mintPairingSecret, sealGrant, type PairingGrant } from './pairing'
import { realI18n, t } from '../../../test/i18n'

const collect = vi.mocked(collectPairingChannel)
const tail = vi.mocked(runOnboardTail)

const CHANNEL = channelKeys(mintPairingSecret())
const GRANT: PairingGrant = {
  email: 'kevin@example.com',
  masterKey: new Uint8Array(32).fill(9),
  refreshToken: 'the-grant-token',
  label: 'Kevin’s laptop',
}

/** A host with no UI of its own: the composable, waiting for the grant from the moment it mounts. */
const mountReceiver = () => {
  const done = vi.fn()
  let receiver!: ReturnType<typeof useGrantReceiver<'answering'>>
  const Host = defineComponent({
    setup() {
      receiver = useGrantReceiver('answering')
      receiver.awaitGrant(CHANNEL, done)
      return () => h('div')
    },
  })
  const wrapper = mount(Host, { global: { plugins: [realI18n()] } })
  return { wrapper, receiver: () => receiver, done }
}

const grantArrives = async (envelope: string) => {
  collect.mockResolvedValue({ envelope })
  await vi.advanceTimersByTimeAsync(2_000)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  collect.mockResolvedValue({})
  tail.mockResolvedValue(undefined)
})

afterEach(() => vi.useRealTimers())

describe('useGrantReceiver — the half both receiving cards share (uc.account.onboard-device)', () => {
  // Formerly pinned by the join card alone; the sign-in card reached the same line unpinned. One test here now
  // pins it for both, because both cards run THIS function. The completed grant is the live control.
  it('reports an unclassified tail failure as its own message, and still does not retry — against a completed one', async () => {
    tail.mockRejectedValue(new Error('offline'))
    const failing = mountReceiver()
    await flushPromises()
    await grantArrives(sealGrant(CHANNEL.key, failing.receiver().transportKey.pub, GRANT))

    // Distinct from both adoption failures (`gone`, `occupied`): a bare "an error was shown" is satisfied by all.
    expect(failing.receiver().step.value).toBe('failed')
    expect(failing.receiver().error.value).toBe(t('pairing.joinFailed'))
    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()
    expect(tail).toHaveBeenCalledTimes(1)
    expect(failing.done).not.toHaveBeenCalled()
    failing.wrapper.unmount()

    vi.clearAllMocks()
    collect.mockResolvedValue({})
    tail.mockResolvedValue(undefined)
    const completing = mountReceiver()
    await flushPromises()
    await grantArrives(sealGrant(CHANNEL.key, completing.receiver().transportKey.pub, GRANT))

    expect(tail).toHaveBeenCalledExactlyOnceWith(GRANT, false)
    expect(completing.done).toHaveBeenCalledTimes(1)
    expect(completing.receiver().error.value).toBe('')
  })

  // ADR 0059 §11 at this altitude: a grant sealed to this device's key but MACed under a channel key it does not
  // share takes the unreadable-envelope path — no tail, no session adopted.
  it('an envelope MACed under another channel key reaches no tail — the completed grant above is the control', async () => {
    const r = mountReceiver()
    await flushPromises()
    await grantArrives(sealGrant(channelKeys(mintPairingSecret()).key, r.receiver().transportKey.pub, GRANT))

    expect(tail).not.toHaveBeenCalled()
    expect(r.receiver().error.value).toBe(t('pairing.joinFailed'))
    expect(r.done).not.toHaveBeenCalled()
  })
})
