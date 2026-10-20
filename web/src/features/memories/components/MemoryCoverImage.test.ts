// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryCoverImage from './MemoryCoverImage.vue'
import NodeCoverImage from '../../../core/node/cover/components/NodeCoverImage.vue'
import { FULL_FRAME, coverImageStyle } from '../memoryCover'

describe('MemoryCoverImage', () => {
  it('renders the source image with the crop-offset styles', () => {
    const w = mount(MemoryCoverImage, {
      props: { src: 'blob:abc', alt: 'Rom', crop: { x: 0.1, y: 0.2, width: 0.8, height: 0.6 } },
    })
    const img = w.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('blob:abc')
    const style = img.attributes('style') ?? ''
    // the inverse-scale + offset formula (a 0.8×0.6 crop at (0.1, 0.2))
    expect(style).toContain(`width: ${100 / 0.8}%`)
    expect(style).toContain(`height: ${100 / 0.6}%`)
    expect(style).toContain(`left: ${(-0.1 / 0.8) * 100}%`)
    expect(style).toContain(`top: ${(-0.2 / 0.6) * 100}%`)
    expect(style).toContain('object-fit: fill')
    expect(w.find('.memory-cover-image-ph').exists()).toBe(false)
  })

  it('full-frame crop ⇒ object-fit cover (centre-crop, no distortion — e.g. a one-click gallery cover)', () => {
    const w = mount(MemoryCoverImage, { props: { src: 'blob:abc', crop: FULL_FRAME } })
    const style = w.find('img').attributes('style') ?? ''
    expect(style).toContain('width: 100%')
    expect(style).toContain('object-fit: cover')
  })

  it('no src ⇒ the default images-tile placeholder, no <img>', () => {
    const w = mount(MemoryCoverImage, { props: { src: null } })
    expect(w.find('img').exists()).toBe(false)
    const ph = w.find('.memory-cover-image-ph')
    expect(ph.exists()).toBe(true)
    expect(ph.classes()).toContain('pi-images')
  })

  it('a host-supplied placeholder slot overrides the default tile', () => {
    const w = mount(MemoryCoverImage, {
      props: { src: undefined },
      slots: { placeholder: '<span class="custom-ph">nope</span>' },
    })
    expect(w.find('.memory-cover-image-ph').exists()).toBe(false)
    expect(w.find('.custom-ph').exists()).toBe(true)
  })
})

// The cover as every surface draws it (inbox #411): a folder framed in core's dialog must look the same in a core row, tile, header
// and pin (`NodeCoverImage`) as a Memory does in its page header and map pin (this component). Both read the one formula.
describe('MemoryCoverImage and core\'s NodeCoverImage frame a cover identically', () => {
  const styleOf = (component: typeof NodeCoverImage | typeof MemoryCoverImage, crop: Parameters<typeof coverImageStyle>[0]) =>
    mount(component, { props: { src: 'blob:x', crop } }).find('img').attributes('style')

  it.each([
    ['a framed crop', { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }],
    ['a corner crop', { x: 0.5, y: 0.5, width: 0.5, height: 0.5 }],
    ['the full frame', { x: 0, y: 0, width: 1, height: 1 }],
    ['no crop (a centred 16:9 fit)', null],
  ])('%s', (_what, crop) => {
    expect(styleOf(NodeCoverImage, crop)).toBe(styleOf(MemoryCoverImage, crop))
    expect(styleOf(NodeCoverImage, crop)).toContain(`object-fit: ${coverImageStyle(crop).objectFit}`)
  })
})
