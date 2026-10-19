// uc.admin.operator-details — the facade's rules every surface shares: *private* only with the notice accepted, the
// preset's changes from the server's preset, and the two warnings, only in the organisation mode.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceOperatorApi: vi.fn() }))

import {
  NoticeNotAccepted, operatingModeRequest, operatorWarnings, presetChanges, setOperatingMode, type InstanceOperator,
} from './operator'
import { instanceOperatorApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'

const entry = (id: string, onInPrivate: boolean, onInOrganisation: boolean, off: boolean) => ({
  id, title: { en: id, de: id }, onInPrivate, onInOrganisation, off,
})

function op(over: Partial<InstanceOperator> = {}): InstanceOperator {
  return {
    operatingMode: 'ORGANISATION', contactEmail: undefined, contactLanguages: [], statementEmail: undefined, notificationEmail: undefined,
    notificationConfirmation: 'NOT_SET', notificationConfirmedAt: undefined, instanceMailReady: false, providerName: undefined,
    providerAddress: undefined, providerEmail: undefined, providerRepresentative: undefined, supervisoryAuthority: undefined, ...over,
  }
}

describe('operator facade (uc.admin.operator-details)', () => {
  let api: ReturnType<typeof stubSet>
  const stubSet = () => stubApi(instanceOperatorApi, { setInstanceOperatingMode: { mode: 'PRIVATE', chosen: true, preset: [] } })
  beforeEach(() => {
    api = stubSet()
  })

  it('refuses private without the notice accepted and sends nothing; organisation needs no tick', async () => {
    expect(() => operatingModeRequest('PRIVATE', false)).toThrow(NoticeNotAccepted)
    expect(() => setOperatingMode('PRIVATE', false)).toThrow(NoticeNotAccepted)
    expect(api.setInstanceOperatingMode).not.toHaveBeenCalled()
    expect(operatingModeRequest('PRIVATE', true)).toBe('PRIVATE')
    expect(operatingModeRequest('ORGANISATION', false)).toBe('ORGANISATION')
    await setOperatingMode('PRIVATE', true)
    expect(api.setInstanceOperatingMode).toHaveBeenCalledWith({ instanceOperatingModeUpdate: { mode: 'PRIVATE' } })
  })

  it('lists only the preset entries whose own switch the mode would change, with the state it would get', () => {
    const preset = [entry('core.a', false, true, false), entry('core.b', false, true, true), entry('core.c', true, true, false)]
    expect(presetChanges(preset, 'PRIVATE')).toEqual([{ entry: preset[0], on: false }])
    expect(presetChanges(preset, 'ORGANISATION')).toEqual([{ entry: preset[1], on: true }])
  })

  it('warns in the organisation mode only: the contact while public links are on, the identification on its own', () => {
    expect(operatorWarnings(op(), true)).toEqual({ contact: true, identification: true })
    expect(operatorWarnings(op(), false)).toEqual({ contact: false, identification: true })
    expect(operatorWarnings(op({ contactEmail: 'c@example.org' }), true).contact).toBe(false)
    expect(
      operatorWarnings(op({ providerName: 'n', providerAddress: 'a', providerEmail: 'e@example.org' }), true).identification,
    ).toBe(false)
    expect(operatorWarnings(op({ operatingMode: 'PRIVATE' }), true)).toEqual({ contact: false, identification: false })
  })
})
