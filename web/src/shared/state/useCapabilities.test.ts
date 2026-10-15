// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import { useCapabilities, setCapabilities } from './useCapabilities'
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
