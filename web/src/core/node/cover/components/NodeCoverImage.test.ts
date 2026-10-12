// @vitest-environment happy-dom
// The cover as every core surface draws it (inbox #411): ONE stored crop frames the same image the same way in a row, a tile, a
// header and a pin; a cover without a crop is a centred fit. (Parity with the Memory's own tile: `MemoryCoverImage.test.ts`.)
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import NodeCoverImage from './NodeCoverImage.vue'

describe('NodeCoverImage', () => {
  it('draws a stored crop with the display formula: inverse scale, offset to the crop\'s corner', () => {
    const w = mount(NodeCoverImage, { props: { src: 'blob:x', crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 } } })
    const style = w.find('img').attributes('style')!
    expect(style).toContain('width: 200%')
    expect(style).toContain(`height: ${100 / 0.28125}%`)
    expect(style).toContain('left: -20%')
    expect(style).toContain(`top: ${(-0.2 / 0.28125) * 100}%`)
    expect(style).toContain('object-fit: fill')
  })

  it.each([
    ['no crop at all', null],
    ['the full frame', { x: 0, y: 0, width: 1, height: 1 }],
  ])('%s is a centred cover fit, never a distortion', (_what, crop) => {
    const style = mount(NodeCoverImage, { props: { src: 'blob:x', crop } }).find('img').attributes('style')!
    expect(style).toContain('width: 100%')
    expect(style).toContain('height: 100%')
    expect(style).toContain('object-fit: cover')
    expect(style).not.toContain('fill')
  })

  it('without an image it draws the kind\'s icon, or nothing when none is given', () => {
    const icon = mount(NodeCoverImage, { props: { src: null, icon: 'pi pi-folder' } })
    expect(icon.find('img').exists()).toBe(false)
    expect(icon.find('.pi-folder').exists()).toBe(true)
    expect(mount(NodeCoverImage, { props: { src: null } }).find('.veyq-node-cover-icon').exists()).toBe(false)
  })
})
