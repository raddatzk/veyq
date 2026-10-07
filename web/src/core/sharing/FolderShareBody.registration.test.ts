// @vitest-environment happy-dom
// The FOLDER share body is pre-registered by the registry itself (cr-node-key-hierarchy 5b.4b), and the body reads the
// registry: a static import between the two is a cycle. This file imports the BODY FIRST — the order in which a static
// registration answered null for FOLDER on the app's registry (measured) — and asserts both registries answer with a body
// that loads `FolderShareBody`. Imported in the other order (every other suite), the cycle does not show.
import { it, expect } from 'vitest'
import FolderShareBody from './FolderShareBody.vue'
import { createRegistry, registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'

type AsyncWrapper = { __asyncLoader?: () => Promise<unknown> }

it('the app registry and a fresh one both hold the folder body, whichever module is evaluated first', async () => {
  for (const r of [registry, createRegistry()]) {
    const body = r.shareBody(FOLDER_KIND) as AsyncWrapper | null
    expect(body, 'a body for FOLDER').not.toBeNull()
    expect(await body!.__asyncLoader!(), 'it loads the folder body').toBe(FolderShareBody)
  }
})

it('an application cannot claim FOLDER: the platform holds it', () => {
  const r = createRegistry()
  expect(() => r.claimShareBody(FOLDER_KIND, {})).toThrow(/already claimed/)
})
