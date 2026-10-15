// The one builder the console and `veyq-admin store set` share: what a typed form becomes on the wire.
import { describe, it, expect } from 'vitest'
import { BUNDLED_STORE_UPDATE, InstanceStoreType, ownStoreUpdate, storeReady } from './store'
import type { OwnStoreFields } from './store'

const fields: OwnStoreFields = {
  type: InstanceStoreType.S3, endpoint: ' https://e ', publicEndpoint: '', bucket: 'b', region: 'r', accessKey: 'AK',
  secretKey: '', garageAdminEndpoint: 'http://ignored', garageAdminToken: 'ignored',
}

describe('ownStoreUpdate', () => {
  it('omits an empty secret (keep the stored one) rather than sending an empty value to be sealed', () => {
    expect(ownStoreUpdate(fields)).not.toHaveProperty('secretKey')
    expect(ownStoreUpdate({ ...fields, secretKey: 's' })).toHaveProperty('secretKey', 's')
  })
  it('sends an empty public endpoint as null so clearing the field clears it on the server', () => {
    expect(ownStoreUpdate(fields).publicEndpoint).toBeNull()
  })
  it('leaves the Garage fields out of an S3 store and puts them in a Garage one', () => {
    const s3 = ownStoreUpdate(fields)
    expect(s3).not.toHaveProperty('garageAdminEndpoint')
    expect(s3).not.toHaveProperty('garageAdminToken')
    const garage = ownStoreUpdate({ ...fields, type: InstanceStoreType.Garage })
    expect(garage.garageAdminEndpoint).toBe('http://ignored')
    expect(garage.garageAdminToken).toBe('ignored')
  })
  it('trims what it sends', () => {
    expect(ownStoreUpdate(fields).endpoint).toBe('https://e')
  })
})

describe('the bundled choice and the readiness courtesy', () => {
  it('chooses the bundled store with no other field', () => {
    expect(BUNDLED_STORE_UPDATE).toEqual({ bundled: true })
  })
  it('is ready only after a passed check', () => {
    const base = { bundledAvailable: false, blobsStored: false, setting: null }
    expect(storeReady({ ...base, lastCheck: null })).toBe(false)
    expect(storeReady({ ...base, lastCheck: { passed: false, checkedAt: new Date(), steps: [] } })).toBe(false)
    expect(storeReady({ ...base, lastCheck: { passed: true, checkedAt: new Date(), steps: [] } })).toBe(true)
    expect(storeReady(null)).toBe(false)
  })
})
