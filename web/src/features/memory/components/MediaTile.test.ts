// @vitest-environment happy-dom
// A gallery tile of a medium a moderator blocked (uc.moderation.take-a-measure; ADR 0093 § 6): the placeholder "Blocked
// by the operator", with no reason and no image — even where a thumbnail URL was already decrypted for it.
import { describe, it, expect } from 'vitest'
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
})
