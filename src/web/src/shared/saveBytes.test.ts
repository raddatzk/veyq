// @vitest-environment happy-dom
import { describe, it, expect, vi, afterEach } from 'vitest'
import { saveBytes } from './saveBytes'

const bytes = (...b: number[]) => new Uint8Array(b)

describe('saveBytes', () => {
  afterEach(() => vi.restoreAllMocks())

  it('saves via a Blob object URL + a synthetic <a download>, then revokes the URL', () => {
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    saveBytes(bytes(1, 2, 3), 'Rom 1.jpg')

    expect(create).toHaveBeenCalledOnce()
    expect(click).toHaveBeenCalledOnce()
    expect(revoke).toHaveBeenCalledWith('blob:fake')
    // the anchor is removed after the click (no lingering element)
    expect(document.querySelector('a[download]')).toBeNull()
  })
})
