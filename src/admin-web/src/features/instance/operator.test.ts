// uc.admin.operator-details — the facade's rules every surface shares: the recipients' rows and the merge base of a
// partial change. No operating mode and no warning (ADR 0093 § 14).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceOperatorApi: vi.fn() }))

import {
  parseRecipient, recipientFits, RECIPIENT_KINDS, suggestionRow, suggestionTaken, operatorUpdateOf, type InstanceOperator,
} from './operator'
import { instanceOperatorApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'

function op(over: Partial<InstanceOperator> = {}): InstanceOperator {
  return {
    contactEmail: undefined, contactLanguages: [], statementEmail: undefined, notificationEmail: undefined,
    notificationConfirmation: 'NOT_SET', notificationConfirmedAt: undefined, instanceMailReady: false, providerName: undefined,
    providerAddress: undefined, providerEmail: undefined, providerRepresentative: undefined, supervisoryAuthority: undefined,
    recipients: [], recipientSuggestions: [], ...over,
  }
}

describe('operator facade (uc.admin.operator-details)', () => {
  beforeEach(() => {
    stubApi(instanceOperatorApi, {})
  })

  it('offers no mode and no warning: the operating mode is retired, and nothing is required (ADR 0093 § 14)', async () => {
    const facade = await import('./operator')
    for (const gone of ['operatingModeRequest', 'setOperatingMode', 'getOperatingMode', 'presetChanges', 'operatorWarnings', 'needsAcceptance']) {
      expect(facade).not.toHaveProperty(gone)
    }
    expect(op()).not.toHaveProperty('operatingMode')
  })
})

describe('operator facade — the privacy notice’s values (ADR 0035 decision 8)', () => {
  const hosting = { name: 'Example Hosting GmbH', kind: 'HOSTING' as const, description: null, country: 'DE' }

  it('offers a suggestion as a row of its own kind to complete, and no more once a row names its host', () => {
    const store = { kind: 'OBJECT_STORAGE' as const, host: 's3.example.com' }
    const relay = { kind: 'MAIL_RELAY' as const, host: 'smtp.example.org' }
    expect(suggestionRow(store)).toEqual({ name: 's3.example.com', kind: 'OBJECT_STORAGE', description: null, country: null })
    expect(suggestionRow(relay)).toEqual({ name: 'smtp.example.org', kind: 'MAIL_RELAY', description: null, country: null })
    expect(suggestionTaken(store, [hosting])).toBe(false)
    expect(suggestionTaken(store, [{ name: 'S3.Example.com', description: null }])).toBe(true)
    expect(suggestionTaken(store, [{ name: 'Example AG', description: 'mirror at s3.example.com' }])).toBe(true)
  })

  it('holds a description to OTHER alone, as the server does', () => {
    expect(RECIPIENT_KINDS).toEqual(['HOSTING', 'OBJECT_STORAGE', 'MAIL_RELAY', 'BACKUP', 'OTHER'])
    expect(recipientFits({ kind: 'OTHER', description: 'sends sign-in texts' })).toBe(true)
    expect(recipientFits({ kind: 'OTHER', description: ' ' })).toBe(false)
    expect(recipientFits({ kind: 'OTHER', description: null })).toBe(false)
    expect(recipientFits({ kind: 'HOSTING', description: null })).toBe(true)
    expect(recipientFits({ kind: 'HOSTING', description: 'hosts the server' })).toBe(false)
  })

  it('reads a CLI row as <name>|<kind>|<country code>, other with its words, the country optional, and refuses anything else', () => {
    expect(parseRecipient(' Example AG | hosting | DE ')).toEqual({ name: 'Example AG', kind: 'HOSTING', description: null, country: 'DE' })
    expect(parseRecipient('Example AG|hosting|fi')).toMatchObject({ country: 'FI' }) // either case, sent upper
    expect(parseRecipient('Example AG|hosting|Germany')).toBeNull() // a name is no code
    expect(parseRecipient('Example AG|hosting|XX')).toBeNull() // a code the backend does not know
    expect(parseRecipient('Mail AG|Mail-Relay')).toEqual({ name: 'Mail AG', kind: 'MAIL_RELAY', description: null, country: null })
    expect(parseRecipient('S3 AG|object-storage')).toMatchObject({ kind: 'OBJECT_STORAGE' })
    expect(parseRecipient('SMS AG|other: sends sign-in texts: by SMS|DE'))
      .toEqual({ name: 'SMS AG', kind: 'OTHER', description: 'sends sign-in texts: by SMS', country: 'DE' })
    expect(parseRecipient('SMS AG|other')).toBeNull() // OTHER needs its words
    expect(parseRecipient('SMS AG|other: |DE')).toBeNull()
    expect(parseRecipient('Example AG|hosting:hosts the server')).toBeNull() // words only for OTHER
    expect(parseRecipient('Example AG|hosts the server')).toBeNull() // free text is no kind any more
    expect(parseRecipient('Mail AG')).toBeNull()
    expect(parseRecipient('|backup')).toBeNull()
    expect(parseRecipient('a|backup|c|d')).toBeNull()
  })

  it('keeps the recipients and the officer when a partial change is laid over the values', () => {
    const u = operatorUpdateOf(op({ recipients: [hosting], dataProtectionOfficer: 'DSB GmbH' }))
    expect(u.recipients).toEqual([hosting])
    expect(u.dataProtectionOfficer).toBe('DSB GmbH')
    expect(operatorUpdateOf(op()).dataProtectionOfficer).toBeNull()
  })
})
