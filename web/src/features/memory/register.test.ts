// @vitest-environment happy-dom
// Memories' registration (ADR 0079), the part core's move dialog reads (inbox #358): where each kind may be placed — the
// mirror of the backend's `MemoryNodeKindRules` (`NodeKindMatrixSliceTest`): a Memory at the root or in a folder, a
// chapter only directly in a Memory. A hint, the server deciding; a wrong one offers a place the server refuses.
import { describe, it, expect } from 'vitest'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { registerMemories } from './register'
import { CHAPTER_KIND, MEMORY_KIND } from './kinds'

describe('registerMemories: placement', () => {
  const registry = createRegistry()
  registerMemories(registry)

  it.each([
    [MEMORY_KIND, null, true], [MEMORY_KIND, FOLDER_KIND, true], [MEMORY_KIND, MEMORY_KIND, false], [MEMORY_KIND, CHAPTER_KIND, false],
    [CHAPTER_KIND, null, false], [CHAPTER_KIND, FOLDER_KIND, false], [CHAPTER_KIND, MEMORY_KIND, true], [CHAPTER_KIND, CHAPTER_KIND, false],
    [MEMORY_KIND, 'DEPOT_DROPBOX', false], [CHAPTER_KIND, 'FILE', false],
  ])('%s in %s: %s', (kind, parent, allowed) => {
    expect(registry.placementAllows(kind, parent)).toBe(allowed)
  })
})
