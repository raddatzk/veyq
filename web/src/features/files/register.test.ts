import { describe, it, expect } from 'vitest'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { fileNameSlug, slugify } from '../../shared/naming'
import { registerFiles } from './register'
import { FILE_KIND } from './kinds'

describe('registerFiles', () => {
  it('registers the FILE kind: not shareable, a file icon, no detail yet', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.kind(FILE_KIND)).toEqual({
      supported: true,
      kind: FILE_KIND,
      icon: 'pi pi-file',
      shareable: false,
      slug: fileNameSlug,
    })
  })

  it('folds a file name case-insensitively and keeps its punctuation', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.slugFor(FILE_KIND, 'Foto.JPG')).toBe(registry.slugFor(FILE_KIND, 'foto.jpg'))
    expect(registry.slugFor(FILE_KIND, 'Day1.md')).not.toBe(registry.slugFor(FILE_KIND, 'Day1 md'))
  })

  it('leaves the folder folding alone', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).toBe(slugify('Foto.JPG'))
    expect(registry.slugFor(FOLDER_KIND, 'Foto.JPG')).not.toBe(fileNameSlug('Foto.JPG'))
  })

  it('claims no slot, no level section, no share body and no lifecycle', () => {
    const registry = createRegistry()
    registerFiles(registry)
    for (const slot of ['nodeMutationDialog', 'shareDialog'] as const) expect(registry.slot(slot)).toBeNull()
    expect(registry.sections()).toEqual([]) // a FILE child shows in core's fallback section until F1
    for (const slot of ['topbarTray', 'sidebarPins'] as const) expect(registry.slotItems(slot)).toEqual([])
    expect(registry.shareBody(FILE_KIND)).toBeNull()
  })

  it('is write-once: a second registration throws', () => {
    const registry = createRegistry()
    registerFiles(registry)
    expect(() => registerFiles(registry)).toThrow(/already registered/)
  })
})
