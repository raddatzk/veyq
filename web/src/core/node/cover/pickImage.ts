// Core's image picker for a cover (inbox #411): a programmatic `<input type=file accept="image/*">`, detached from the
// DOM, like Files' picker - there is no markup to mount and nothing to unmount. Resolves the picked file, or null when the
// dialog is cancelled (`cancel` event) - a browser that fires none leaves the promise pending, which holds nothing.
import { MAX_COVER_SOURCE_BYTES } from './coverWrite'

/** Open the system file dialog for ONE image. */
export function pickImageFile(): Promise<File | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/*'
    input.addEventListener('change', () => resolve(input.files?.[0] ?? null), { once: true })
    input.addEventListener('cancel', () => resolve(null), { once: true })
    input.click()
  })
}

/** The i18n key of why `file` cannot be a cover, or null when it can: a non-image, an empty file, or one over the limit. */
export function coverFileProblem(file: Pick<File, 'type' | 'size'>): string | null {
  if (!file.type.startsWith('image/') || file.size === 0) return 'node.cover.notImage'
  if (file.size > MAX_COVER_SOURCE_BYTES) return 'node.cover.tooLarge'
  return null
}
