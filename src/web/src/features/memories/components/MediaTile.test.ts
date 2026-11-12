// @vitest-environment happy-dom
// A gallery tile of a medium a moderator blocked (uc.moderation.take-a-measure; ADR 0093 § 6): the placeholder "Blocked
// by the operator", with no reason and no image — even where a thumbnail URL was already decrypted for it.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'
import MediaTile from './MediaTile.vue'
import { realI18n, t } from '../../../test/i18n'

const mountTile = (blocked: boolean) =>
  mount(MediaTile, {
    props: { src: 'blob:thumb', hasDerivatives: true, openLabel: 'Open item 1', blocked },
    global: { plugins: [realI18n()] },
  })

describe('MediaTile', () => {
  it('a blocked medium is the placeholder, never its image', () => {
    const w = mountTile(true)
    expect(w.find('.media-tile-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(w.find('img').exists()).toBe(false)
  })

  it('an unblocked one shows its thumbnail', () => {
    const w = mountTile(false)
    expect(w.find('.media-tile-blocked').exists()).toBe(false)
    expect(w.find('img.media-tile-img').attributes('src')).toBe('blob:thumb')
  })

  // inbox #488: a thumbnail whose read failed for good is an error tile, never a placeholder that shimmers forever
  it('a thumbnail that could not be read is the error tile, not the loading placeholder', () => {
    const w = mount(MediaTile, {
      props: { src: undefined, hasDerivatives: true, openLabel: 'Open item 1', failed: true },
      global: { plugins: [realI18n()] },
    })
    expect(w.find('.media-tile-failed').text()).toBe(t('media.thumbnailFailed'))
    expect(w.find('.media-grid-tile-placeholder').exists()).toBe(false)
  })

  it('a failed read under a thumbnail already shown (a donated upload preview) keeps the image', () => {
    const w = mount(MediaTile, {
      props: { src: 'blob:preview', hasDerivatives: true, openLabel: 'Open item 1', failed: true },
      global: { plugins: [realI18n()] },
    })
    expect(w.find('img.media-tile-img').attributes('src')).toBe('blob:preview')
    expect(w.find('.media-tile-failed').exists()).toBe(false)
  })
  // owner 2026-11-12, one heart everywhere: a photo's mark is drawn as a card's heart in the level is - the layer's small
  // text button, at the card's corner inset, the icon-button size and the card's veil. The look is CSS a unit test cannot
  // render, so the numbers are pinned against core's sheet at the source; a browser pass is the proof of the look.
  it('the mark is the level card\'s heart: the same button, corner, size and veil', () => {
    const w = mount(MediaTile, {
      props: { src: 'blob:thumb', hasDerivatives: true, openLabel: 'Open item 1', markable: true, markedByMe: true, markCount: 3, markLabel: 'Unmark' },
      global: { plugins: [realI18n()] },
    })
    const mark = w.find('button.media-tile-mark')
    expect(mark.classes()).toEqual(expect.arrayContaining(['veyq-button', 'veyq-button--secondary', 'veyq-button--text', 'veyq-button--small', 'is-marked', 'has-count']))
    expect(mark.attributes('aria-pressed')).toBe('true')
    expect(mark.attributes('aria-label')).toBe('Unmark')
    expect(mark.find('.veyq-button__label').text()).toBe('3')

    const decls = (css: string, selector: string): string => {
      const at = css.indexOf(`${selector} {`)
      expect(at, selector).toBeGreaterThan(-1)
      return css.slice(at, css.indexOf('}', at))
    }
    const value = (block: string, prop: string): string => new RegExp(`[\\s;{]${prop}:\\s*([^;]+);`).exec(block)?.[1].trim() ?? `no ${prop}`
    const core = readFileSync(resolve(__dirname, '../../../styles/ui.css'), 'utf8')
    const tile = readFileSync(resolve(__dirname, 'MediaTile.vue'), 'utf8')
    const ours = decls(tile, '.media-tile-mark.veyq-button')
    const corner = decls(core, '.veyq-level-item.is-grid:not(.is-compact) .veyq-level-item-actions')
    for (const prop of ['top', 'right']) expect(value(ours, prop), prop).toBe(value(corner, prop))
    expect(value(ours, 'height')).toBe(value(decls(core, '.veyq-prime-icon-button.veyq-button'), 'height'))
    expect(value(ours, 'background')).toBe(value(decls(core, '.veyq-level-item.is-grid .veyq-level-item-actions > .veyq-button'), 'background'))
  })
})
