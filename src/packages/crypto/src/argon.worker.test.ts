// @vitest-environment node
//
// The worker-side dispatcher, loaded for real: `self` is stubbed (its `message` listener captured, its `postMessage`
// recorded) and every op in ARGON_OPS is sent with TEST_ARGON params. The node cases in argonWorker.test.ts can never
// see a missing dispatcher branch — they have no Worker, so they always take the sync path — while a browser would
// get `unknown_op` back, which the front door REJECTS: every derivation through that op would fail in production
// with every other test green. This is the test that sees it.
import { describe, it, expect, vi, beforeAll, afterAll } from 'vitest'

import { ARGON_OPS, type ArgonOp } from './argonOps'
import * as v1 from './v1'
import { TEST_ARGON } from './testArgon'

const password = 'correct horse battery'
const salt = new Uint8Array(16).fill(5)
const secretKey = new Uint8Array(32).fill(9)
const deviceFactor = new Uint8Array(32).fill(3)

// Keyed by ArgonOp, so an op added to the list without a case here does not compile.
const CASES: Record<ArgonOp, { request: Record<string, unknown>; expected: () => unknown }> = {
  authKeypair: {
    request: { password, salt, params: TEST_ARGON },
    expected: () => v1.deriveAuthKeypair(password, salt, TEST_ARGON),
  },
  unlockKek: {
    request: { secretKey, password, salt, params: TEST_ARGON },
    expected: () => v1.kekPasswordSecret(secretKey, password, salt, TEST_ARGON),
  },
  deviceKek: {
    request: { deviceFactor, password, salt, params: TEST_ARGON },
    expected: () => v1.kekDevicePassword(deviceFactor, password, salt, TEST_ARGON),
  },
  recoveryKek: {
    request: { secretKey, salt, params: TEST_ARGON },
    expected: () => v1.kekRecovery(secretKey, salt, TEST_ARGON),
  },
  sharesPwToken: {
    request: { password, salt, params: TEST_ARGON },
    expected: () => v1.sharesPwToken(password, salt, TEST_ARGON),
  },
  inviteLinkKek: {
    request: { secret: secretKey, salt, params: TEST_ARGON },
    expected: () => v1.kekInviteLink(secretKey, salt, TEST_ARGON),
  },
}

type Reply = { id: number; ok: boolean; result?: unknown; error?: string }
let onMessage: ((event: { data: unknown }) => void) | undefined
const replies: Reply[] = []
let seq = 0

function send(message: Record<string, unknown>): Reply {
  const id = (seq += 1)
  onMessage?.({ data: { id, ...message } })
  const reply = replies.find((r) => r.id === id)
  if (!reply) throw new Error(`no reply to request ${id}`)
  return reply
}

beforeAll(async () => {
  vi.stubGlobal('self', {
    addEventListener: (type: string, cb: (event: { data: unknown }) => void) => {
      if (type === 'message') onMessage = cb
    },
    postMessage: (message: Reply) => replies.push(message),
  })
  await import('./argon.worker')
})
afterAll(() => vi.unstubAllGlobals())

describe('argon.worker answers every op the front door sends', () => {
  it.each(ARGON_OPS)('%s → ok, byte-identical to the sync path', (op) => {
    expect(send({ op, ...CASES[op].request })).toEqual({ id: seq, ok: true, result: CASES[op].expected() })
  })

  // The control: the harness does see a refusal, so a green above is an answer, not silence.
  it('refuses an op it has no branch for', () => {
    expect(send({ op: 'noSuchOp', password, salt, params: TEST_ARGON })).toEqual({
      id: seq,
      ok: false,
      error: 'Error: unknown_op',
    })
  })
})
