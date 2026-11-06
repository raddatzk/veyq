// @vitest-environment happy-dom
// What this device keeps of a share, keyed by where the share lives (ADR 0121 decision 5): this origin's keys are the very
// strings they were before - so a guest's identity, its accepted terms and notes and its own uploads still read after the
// change - and another instance's link of the same id is kept apart from them, and cannot be reached by any id this
// origin's route hands a share page.
import { describe, it, expect, beforeEach } from 'vitest'
import { apiBaseFor, deviceKey, originKey } from './shareAt'
import { forgetGuestSession, loadGuestSession, saveGuestSession } from './guestIdentity'
import { isNoteAccepted, recordNoteAccepted } from './shareNote'
import { acceptedTermsKeys, recordTermsAccepted } from './resolvePublicShare'

const ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const AWAY = 'https://photos.example.org'
const away = { shareId: ID, apiBase: AWAY }
const session = { identityId: 'i-1', contributorToken: 'tok-1', displayName: 'Ben' }

beforeEach(() => localStorage.clear())

describe('the device key', () => {
  it('is this origin\'s former key for a bare id and for an address on this origin', () => {
    expect(deviceKey('veyq:share-contributor', ID)).toBe(`veyq:share-contributor:${ID}`)
    expect(deviceKey('veyq:share-contributor', { shareId: ID, apiBase: '' })).toBe(`veyq:share-contributor:${ID}`)
    expect(originKey('veyq:terms-accepted', '', 'tenant-1')).toBe('veyq:terms-accepted:tenant-1')
  })

  it('carries another instance\'s origin, after a character no key of this origin has there', () => {
    expect(deviceKey('veyq:share-contributor', away)).toBe(`veyq:share-contributor@${AWAY}:${ID}`)
  })

  it('no id of this origin - whatever its route holds - spells another origin\'s key', () => {
    for (const crafted of [`@${AWAY}:${ID}`, `${AWAY}:${ID}`, `@${AWAY}`]) {
      expect(deviceKey('veyq:share-contributor', crafted)).not.toBe(deviceKey('veyq:share-contributor', away))
    }
  })

  it('a bookmark of this document\'s own origin reads through this origin, another\'s through its origin', () => {
    expect(apiBaseFor('https://veyq.example', 'https://veyq.example')).toBe('')
    expect(apiBaseFor(AWAY, 'https://veyq.example')).toBe(AWAY)
  })
})

describe('each store keeps this origin\'s key and keeps another instance\'s apart', () => {
  it('the guest\'s session', () => {
    saveGuestSession(ID, session)
    expect(JSON.parse(localStorage.getItem(`veyq:share-contributor:${ID}`)!)).toEqual(session)
    expect(loadGuestSession(away), 'another instance\'s link of the same id holds no session').toBeNull()
    saveGuestSession(away, { ...session, identityId: 'i-2' })
    expect(loadGuestSession(ID)?.identityId).toBe('i-1')
    forgetGuestSession(away)
    expect(loadGuestSession(ID)?.identityId, 'forgetting the foreign one leaves this origin\'s').toBe('i-1')
  })

  it('a note\'s acceptance', () => {
    recordNoteAccepted(ID, 'digest-1')
    expect(localStorage.getItem(`veyq:share-note-accepted:${ID}:digest-1`)).toBe('1')
    expect(isNoteAccepted(away, 'digest-1')).toBe(false)
    recordNoteAccepted(away, 'digest-1')
    expect(localStorage.getItem(`veyq:share-note-accepted@${AWAY}:${ID}:digest-1`)).toBe('1')
  })

  it('the accepted terms, per tenant', () => {
    recordTermsAccepted('tenant-1', { revision: 3, operatorRevision: 1 })
    expect(localStorage.getItem('veyq:terms-accepted:tenant-1')).not.toBeNull()
    expect(acceptedTermsKeys('tenant-1', AWAY), 'a tenant of the same id on another instance accepted nothing').toEqual([])
    recordTermsAccepted('tenant-1', { revision: 3, operatorRevision: 1 }, AWAY)
    expect(localStorage.getItem(`veyq:terms-accepted@${AWAY}:tenant-1`)).not.toBeNull()
  })
})
