import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as capabilities from './capabilities'

// Conformance pin (cr-0103, ADR 0032): every capability id the web client ships must be a real entry in the
// append-only catalog (contracts/platform/capabilities.json), so client soft-gates never key off an id the
// backend does not know — the same drift-proofing the task tags + crypto vectors get. The ids are read from the
// module's exports, not listed here, so a new constant is pinned the moment it exists. One-directional (code ⊆
// catalog): a catalog id may be reserved ahead of any UI (ADR 0032 §6).
describe('platform-capability registry conformance', () => {
  const catalog = JSON.parse(
    readFileSync(fileURLToPath(new URL('../../../../../contracts/platform/capabilities.json', import.meta.url)), 'utf8'),
  )
  const ids = Object.keys(catalog.capabilities)
  const shipped = Object.entries(capabilities)

  it('ships at least the seven ids of the register re-cut', () => {
    expect(shipped.length).toBeGreaterThanOrEqual(7)
  })

  it.each(shipped)('pins %s as a real catalog entry', (_name, id) => {
    expect(ids).toContain(id)
  })
})
