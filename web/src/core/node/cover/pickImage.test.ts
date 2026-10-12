// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { coverFileProblem, pickImageFile } from './pickImage'
import { MAX_COVER_SOURCE_BYTES } from './coverWrite'

// Core's image picker for a cover (inbox #411): a programmatic input that accepts images only, resolved on change and on cancel.
afterEach(() => vi.restoreAllMocks())

describe('pickImageFile', () => {
  function fakePicker(act: (input: HTMLInputElement) => void) {
    const made: HTMLInputElement[] = []
    vi.spyOn(HTMLInputElement.prototype, 'click').mockImplementation(function (this: HTMLInputElement) {
      made.push(this)
      act(this)
    })
    return made
  }

  it('opens a detached file input that accepts images and resolves the chosen file', async () => {
    const file = new File([new Uint8Array([1])], 'a.png', { type: 'image/png' })
    const made = fakePicker((input) => {
      Object.defineProperty(input, 'files', { value: [file] })
      input.dispatchEvent(new Event('change'))
    })
    await expect(pickImageFile()).resolves.toBe(file)
    expect(made).toHaveLength(1)
    expect(made[0].type).toBe('file')
    expect(made[0].accept).toBe('image/*')
    expect(made[0].multiple, 'ONE image').toBe(false)
    expect(made[0].isConnected, 'there is no markup to mount or unmount').toBe(false)
  })

  it('resolves null when the dialog is cancelled', async () => {
    fakePicker((input) => input.dispatchEvent(new Event('cancel')))
    await expect(pickImageFile()).resolves.toBeNull()
  })
})

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
