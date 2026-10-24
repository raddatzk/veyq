// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import { useCapabilities, setCapabilities, setTenantCapabilities } from './useCapabilities'
import type { SetupStatus } from '../../api/generated'

function status(over: Partial<SetupStatus> = {}): SetupStatus {
  return {
    setupRequired: false,
    suites: ['v1'],
    instanceSalt: 'AAAA',
    version: { major: EXPECTED_MAJOR, build: 1 },
    capabilities: [{ id: 'core.public-shares' }],
    ...over,
  } as SetupStatus
}

beforeEach(() => {
  // reset the module singleton to the pre-discovery state
  capabilityState.ids = null
  capabilityState.tenantIds = null
  capabilityState.major = null
  capabilityState.build = null
})

describe('useCapabilities', () => {
  it('hasCapability reflects the discovered manifest; false before discovery and for absent ids', () => {
    const { hasCapability } = useCapabilities()
    expect(hasCapability('core.public-shares')).toBe(false) // not discovered yet ⇒ hide
    setCapabilities(status({ capabilities: [{ id: 'core.public-shares' }, { id: 'core.group-space' }] }))
    expect(hasCapability('core.public-shares')).toBe(true)
    expect(hasCapability('core.group-space')).toBe(true)
    expect(hasCapability('core.public-shares.drop-box')).toBe(false) // absent from the manifest ⇒ hidden
  })

  it('the open tenant\'s set replaces the instance manifest while it is loaded, and forgetting it brings the manifest back', () => {
    // ADR 0088 decision 3: the server computes the tenant's set (a subset); the client reads it, never ANDs.
    const { hasCapability } = useCapabilities()
    setCapabilities(status({ capabilities: [{ id: 'core.public-shares' }, { id: 'core.group-space' }] }))
    setTenantCapabilities([{ id: 'core.group-space' }])
    expect(hasCapability('core.public-shares')).toBe(false) // the tenant switched it off
    expect(hasCapability('core.group-space')).toBe(true)
    setTenantCapabilities(null) // sign-out, or the set could not be read
    expect(hasCapability('core.public-shares')).toBe(true)
  })

  it('isSwitchedOff is true only for an id a read set lacks — not before discovery (uc.platform.switch-application-off)', () => {
    const { isSwitchedOff } = useCapabilities()
    expect(isSwitchedOff('memories')).toBe(false) // nothing read yet: not off, no banner says so
    setCapabilities(status({ capabilities: [{ id: 'memories' }, { id: 'depot' }] }))
    expect(isSwitchedOff('memories')).toBe(false)
    setTenantCapabilities([{ id: 'depot' }])
    expect(isSwitchedOff('memories')).toBe(true) // the tenant switched it off
    expect(isSwitchedOff('depot')).toBe(false)
  })

  it('incompatibility is null when the major matches, and before discovery; build never gates', () => {
    const { incompatibility } = useCapabilities()
    expect(incompatibility()).toBeNull() // not discovered
    setCapabilities(status({ version: { major: EXPECTED_MAJOR, build: 9999 } }))
    expect(incompatibility()).toBeNull()
  })

  it('incompatibility is "update-app" when the server major is newer', () => {
    const { incompatibility } = useCapabilities()
    setCapabilities(status({ version: { major: EXPECTED_MAJOR + 1, build: 1 } }))
    expect(incompatibility()).toBe('update-app')
  })

  it('incompatibility is "update-server" when the server major is older', () => {
    const { incompatibility } = useCapabilities()
    setCapabilities(status({ version: { major: EXPECTED_MAJOR - 1, build: 1 } }))
    expect(incompatibility()).toBe('update-server')
  })
})
