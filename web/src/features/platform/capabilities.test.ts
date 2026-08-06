import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  SHARING_PUBLIC_LINKS,
  SHARING_UPLOADER_LINKS,
  SPACES_GROUPS,
  ACCOUNT_DEVICE_TRUST,
} from './capabilities'

// Conformance pin (cr-0103, ADR 0032): every capability id the web client ships must be a real entry in the
// append-only catalog (contracts/platform/capabilities.json), so client soft-gates never key off an id the
// backend does not know — the same drift-proofing the task tags + crypto vectors get.
describe('platform-capability registry conformance', () => {
  const catalog = JSON.parse(
    readFileSync(fileURLToPath(new URL('../../../../contracts/platform/capabilities.json', import.meta.url)), 'utf8'),
  )
  const ids = Object.keys(catalog.capabilities)

  it.each([SHARING_PUBLIC_LINKS, SHARING_UPLOADER_LINKS, SPACES_GROUPS, ACCOUNT_DEVICE_TRUST])(
    'pins %s as a real catalog entry',
    (id) => {
      expect(ids).toContain(id)
    },
  )
})
