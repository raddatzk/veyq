// A Pot write refused `node_rekey_pending` (ADR 0111 decision 11): a MEMBER's session pulls the Pot's path forward on
// this device and writes again at once; a GUEST's session never pulls — it holds no key above its root — and waits. The
// pull itself is core's (`pullRekeyPath`, its own tests in `core/node/rekey/rekeyScope.test.ts`); here only who asks it.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), appendPotWrites: vi.fn(), readGuestPot: vi.fn() }))
vi.mock('../../core/node/rekey/rekeyScope', () => ({ pullRekeyPath: vi.fn() }))
vi.mock('../../core/node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../../core/node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))

import * as v1 from '@veyq/crypto/v1'
import { usePotSession } from './potSession'
import { appendPotWrites, readGuestPot } from './domain/potApi'
import { pullRekeyPath } from '../../core/node/rekey/rekeyScope'
import { nodeKeyForWrite } from '../../core/node/rekey/floor'
import { guestAccessOf, memberAccessOf, memberTreeOf } from '../../test/models'
import { ANNA, POT_ID, PotServer, SCOPE, person, potNode } from './potTestUtil'

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const pending = () => Object.assign(new Error('node_rekey_pending'), {
  response: new Response(JSON.stringify({ code: 'node_rekey_pending' }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

let pot: PotServer
const pull = vi.fn(async () => true)
beforeEach(() => {
  vi.clearAllMocks()
  pot = new PotServer(v1.randomBytes(32))
  vi.mocked(pullRekeyPath).mockReturnValue(pull)
  vi.mocked(nodeKeyForWrite).mockImplementation(() => ({ key: pot.potKey, epoch: 1 }))
  vi.mocked(appendPotWrites).mockRejectedValueOnce(pending()).mockResolvedValue([])
  vi.mocked(readGuestPot).mockResolvedValue(null)
})

function session(guest: boolean) {
  const node = potNode(pot.potKey)
  const access = guest
    ? guestAccessOf('READ_WRITE', { requestGuest: async () => ({ identityId: 'g', contributorToken: 't' }) })
    : memberAccessOf({ scopeId: SCOPE })
  return effectScope().run(() =>
    usePotSession({ access: () => access, scopeId: () => SCOPE, node: () => node, potKey: () => pot.potKey, epochs: () => ({ current: 1, keys: new Map() }), tree: () => memberTreeOf(() => [node], { scopeId: SCOPE }), live: false }),
  )!
}

describe('a Pot write meeting node_rekey_pending', () => {
  it('a member\'s write pulls the Pot\'s path forward once and lands on the next attempt', async () => {
    await session(false).write([{ entryId: ANNA, content: person('Anna', 1) }])
    expect(pullRekeyPath).toHaveBeenCalledWith(SCOPE, [POT_ID])
    expect(pull).toHaveBeenCalledTimes(1)
    expect(appendPotWrites).toHaveBeenCalledTimes(2)
  })

  it('a guest\'s write never pulls: it waits, and lands once the re-key reached it (control on the same refusal)', async () => {
    vi.useFakeTimers()
    try {
      const run = session(true).write([{ entryId: ANNA, content: person('Anna', 1) }])
      await vi.advanceTimersByTimeAsync(20_000) // the waits
      await run
    } finally {
      vi.useRealTimers()
    }
    expect(pull).not.toHaveBeenCalled()
    expect(appendPotWrites).toHaveBeenCalledTimes(2)
  })
})
