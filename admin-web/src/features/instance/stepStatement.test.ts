// uc.moderation.object-to-a-measure A7 (admin-web) — a Server Owner's step's statement of reasons, with the REAL crypto
// suite: the test opens what the facade sealed (the facade itself cannot — `moderation.noOpen.test.ts`).
//   - the facts go in the clear for the mail AND sealed to the current epoch's public half, bound to the statement id
//     the request carries — the same id the server stores them under;
//   - no facts: nothing sealed, nothing read; moderation off: refused with nothing built.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceModerationApi: vi.fn() }))

import { stepStatement, StepStatementInvalid } from './stepStatement'
import { instanceModerationApi } from '../../api/client'
import type { InstanceModeration } from '../../api/generated/instance'
import { fakeApi } from '../../test/apiMock'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const m = v1.generateModerationKey()

function api(over: Partial<InstanceModeration> = {}) {
  return fakeApi(instanceModerationApi, {
    getInstanceModeration: async () => ({
      enabled: true, currentEpoch: 3, currentKemPublicKey: b64std(v1.moderationBundle(m).kem), rotationDueSince: null,
      serverOwner: { accountId: 'owner', identityPublicKey: 'aWRlbnRpdHk=' }, moderators: [], ...over,
    }),
  })
}

beforeEach(() => vi.clearAllMocks())

describe('stepStatement', () => {
  it('carries the facts in the clear and sealed for the log to the epoch, bound to the statement id it mints', async () => {
    api()
    const s = await stepStatement({ ground: 'TERMS_BREACH', provision: '  Space terms, clause 4 ', facts: ' what was found ' }, () => ID)
    expect(s).toMatchObject({ statementId: ID, ground: 'TERMS_BREACH', provision: 'Space terms, clause 4', facts: 'what was found' })
    expect(s.factsForLog!.epoch).toBe(3)
    const sealed = fromB64std(s.factsForLog!.sealedFacts)
    expect(new TextDecoder().decode(sealed)).not.toContain('what was found')
    expect(v1.openStepFacts(3, ID, v1.moderationKemSecret(m), sealed)).toBe('what was found')
    // Bound to its statement: under another id it does not open.
    expect(() => v1.openStepFacts(3, ID.replace(/c$/, 'd'), v1.moderationKemSecret(m), sealed)).toThrow(v1.OpenRefused)
  })

  it('without facts reads nothing and seals nothing; a blank provision is none', async () => {
    const mocks = api()
    expect(await stepStatement({ ground: 'ILLEGAL_CONTENT', provision: ' ', facts: '  ' }, () => ID))
      .toEqual({ statementId: ID, ground: 'ILLEGAL_CONTENT', provision: null, facts: null, factsForLog: null })
    expect(mocks.getInstanceModeration).not.toHaveBeenCalled()
  })

  it('refuses with moderation off, so nothing unsealed is sent beside facts it cannot log', async () => {
    api({ currentEpoch: null, currentKemPublicKey: null })
    await expect(stepStatement({ ground: 'TERMS_BREACH', provision: '', facts: 'x' })).rejects.toEqual(new StepStatementInvalid('moderationOff'))
  })
})
