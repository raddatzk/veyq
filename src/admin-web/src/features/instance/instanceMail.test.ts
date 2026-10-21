// ADR 0093 § 7 (admin-web) — whether a Server Owner's statement of reasons can be mailed, as the console warns of it
// before a step over content: only settings that are not set up at all count (unconfirmed ones still send), and a read
// that fails says nothing.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceApi: vi.fn() }))

import { statementMailNotSetUp } from './instanceMail'
import { instanceApi } from '../../api/client'
import type { InstanceMailSettings } from '../../api/generated/instance'
import { fakeApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'

const read = (confirmation: InstanceMailSettings['confirmation']): InstanceMailSettings => ({
  tls: true, passwordSet: false, configSecretState: 'OK', address: 'owner@example.com', confirmation,
})

beforeEach(() => vi.clearAllMocks())

describe('statementMailNotSetUp', () => {
  it('is true only while no instance mail is set up', async () => {
    fakeApi(instanceApi, { getInstanceMailSettings: async () => read('NOT_CONFIGURED') })
    expect(await statementMailNotSetUp()).toBe(true)
  })

  it.each(['CODE_SENT', 'SEND_FAILED', 'UNCONFIRMED', 'CONFIRMED'] as const)('is false for settings that are set up (%s) — they still send', async (c) => {
    fakeApi(instanceApi, { getInstanceMailSettings: async () => read(c) })
    expect(await statementMailNotSetUp()).toBe(false)
  })

  it('is false when the read fails — the console warns only of what it read', async () => {
    fakeApi(instanceApi, { getInstanceMailSettings: async () => { throw httpError(500) } })
    expect(await statementMailNotSetUp()).toBe(false)
  })
})
