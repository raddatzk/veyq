import { describe, it, expect } from 'vitest'
import { coverFileProblem } from './pickImage'
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
})
