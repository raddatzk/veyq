import { describe, it, expect } from 'vitest'
import { coverFileProblem, coverMediaType } from './pickImage'
import { MAX_COVER_SOURCE_BYTES } from './coverWrite'

// What the cover dialog accepts as a source (inbox #411): an image, not empty, within the limit.
describe('coverFileProblem', () => {
  const file = (type: string, size: number) => ({ type, size })

  it('accepts an image within the limit', () => {
    expect(coverFileProblem(file('image/jpeg', 1000))).toBeNull()
    expect(coverFileProblem(file('image/webp', MAX_COVER_SOURCE_BYTES))).toBeNull() // the limit itself is in
  })

  it('refuses a non-image, an empty file and an image over the limit - each with its message', () => {
    expect(coverFileProblem(file('application/pdf', 1000))).toBe('node.cover.notImage')
    expect(coverFileProblem(file('', 1000))).toBe('node.cover.notImage')
    expect(coverFileProblem(file('image/png', 0))).toBe('node.cover.notImage')
    expect(coverFileProblem(file('image/png', MAX_COVER_SOURCE_BYTES + 1))).toBe('node.cover.tooLarge')
  })

  it('takes an untyped HEIC, HEIF or SVG by its extension - as the system hands a Photos export to Chrome', () => {
    expect(coverFileProblem({ type: '', size: 1000, name: 'IMG_0001.HEIC' })).toBeNull()
    expect(coverFileProblem({ type: '', size: 1000, name: 'photo.heif' })).toBeNull()
    expect(coverFileProblem({ type: '', size: 1000, name: 'logo.svg' })).toBeNull()
    expect(coverFileProblem({ type: '', size: 1000, name: 'notes.txt' }), 'any other untyped file is still no image').toBe('node.cover.notImage')
    expect(coverFileProblem({ type: '', size: 1000, name: 'heic' }), 'a bare name is no extension').toBe('node.cover.notImage')
  })
})

describe('coverMediaType', () => {
  it('is the file\'s own type, else the one its extension names', () => {
    expect(coverMediaType({ type: 'image/png', name: 'x.heic' }), 'a type the system gave wins').toBe('image/png')
    expect(coverMediaType({ type: '', name: 'IMG_0001.HEIC' })).toBe('image/heic')
    expect(coverMediaType({ type: '', name: 'logo.SVG' })).toBe('image/svg+xml')
    expect(coverMediaType({ type: '', name: 'a.bin' })).toBe('')
  })
})
