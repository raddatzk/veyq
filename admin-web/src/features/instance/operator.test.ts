// uc.admin.operator-details — the facade's rules every surface shares: *private* only with the notice accepted, the
// preset's changes from the server's preset, and the two warnings, only in the organisation mode.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceOperatorApi: vi.fn() }))

import {
  NoticeNotAccepted, operatingModeRequest, operatorWarnings, parseRecipient, presetChanges, setOperatingMode, suggestionRow,
  suggestionTaken, operatorUpdateOf, type InstanceOperator,
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
    providerAddress: undefined, providerEmail: undefined, providerRepresentative: undefined, supervisoryAuthority: undefined,
    recipients: [], recipientSuggestions: [], ...over,
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
    expect(operatorWarnings(op(), true)).toEqual({ contact: true, identification: true, privacy: true })
    expect(operatorWarnings(op(), false)).toEqual({ contact: false, identification: true, privacy: true })
    expect(operatorWarnings(op({ contactEmail: 'c@example.org' }), true).contact).toBe(false)
    expect(
      operatorWarnings(op({ providerName: 'n', providerAddress: 'a', providerEmail: 'e@example.org' }), true).identification,
    ).toBe(false)
    expect(operatorWarnings(op({ operatingMode: 'PRIVATE' }), true)).toEqual({ contact: false, identification: false, privacy: false })
  })
})

describe('operator facade — the privacy notice’s values (ADR 0035 decision 8)', () => {
  const hosting = { name: 'Example Hosting GmbH', purpose: 'hosts the server', country: 'Germany' }

  it('warns, in the organisation mode only, while no recipient or no supervisory authority is named', () => {
    expect(operatorWarnings(op({ recipients: [hosting] }), true).privacy).toBe(true) // no authority yet
    expect(operatorWarnings(op({ supervisoryAuthority: 'LfD' }), true).privacy).toBe(true) // no recipient yet
    expect(operatorWarnings(op({ recipients: [hosting], supervisoryAuthority: 'LfD' }), true).privacy).toBe(false)
    expect(operatorWarnings(op({ operatingMode: 'PRIVATE' }), true).privacy).toBe(false)
  })

  it('offers a suggestion as a row to complete, and no more once a row names its host', () => {
    const store = { kind: 'OBJECT_STORE' as const, host: 's3.example.com' }
    expect(suggestionRow(store, (k) => `purpose of ${k}`)).toEqual({ name: 's3.example.com', purpose: 'purpose of OBJECT_STORE', country: null })
    expect(suggestionTaken(store, [hosting])).toBe(false)
    expect(suggestionTaken(store, [{ name: 'S3.Example.com', purpose: 'stores files', country: null }])).toBe(true)
    expect(suggestionTaken(store, [{ name: 'Example AG', purpose: 'object store at s3.example.com', country: null }])).toBe(true)
  })

  it('reads a CLI row as <name>|<what they do>|<country>, the country optional, and refuses anything else', () => {
    expect(parseRecipient(' Example AG | hosts the server | Germany ')).toEqual({ name: 'Example AG', purpose: 'hosts the server', country: 'Germany' })
    expect(parseRecipient('Mail AG|relays the mail')).toEqual({ name: 'Mail AG', purpose: 'relays the mail', country: null })
    expect(parseRecipient('Mail AG')).toBeNull()
    expect(parseRecipient('|relays')).toBeNull()
    expect(parseRecipient('a|b|c|d')).toBeNull()
  })

  it('keeps the recipients and the officer when a partial change is laid over the values', () => {
    const u = operatorUpdateOf(op({ recipients: [hosting], dataProtectionOfficer: 'DSB GmbH' }))
    expect(u.recipients).toEqual([hosting])
    expect(u.dataProtectionOfficer).toBe('DSB GmbH')
    expect(operatorUpdateOf(op()).dataProtectionOfficer).toBeNull()
  })
})
