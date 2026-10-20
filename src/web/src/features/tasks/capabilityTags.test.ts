import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { GRANT_DELIVERY, SCOPE_ROTATION } from './capabilityTags'

// Conformance pin (cr-0076): the opaque tag the web client ships must equal the public registry
// (contracts/tasks/tags/v1.json), so a worker and the server never silently stop matching after a re-derive.
describe('capability-tag registry conformance', () => {
  const registry = JSON.parse(
    readFileSync(fileURLToPath(new URL('../../../../../contracts/tasks/tags/v1.json', import.meta.url)), 'utf8'),
  )

  it('grant-delivery matches the registry', () => {
    expect(GRANT_DELIVERY).toBe(registry.tags['grant-delivery'].tag)
  })

  it('scope-rotation matches the registry', () => {
    expect(SCOPE_ROTATION).toBe(registry.tags['scope-rotation'].tag)
  })
})
