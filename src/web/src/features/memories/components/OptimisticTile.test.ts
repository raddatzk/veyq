// @vitest-environment happy-dom
// uc.sharing.share-link-storage-limit (web) — a link visitor's upload refused `quota_exceeded` reads as the link being
// full and offers no retry, because none can help (ADR 0073 §6); every other failure keeps the generic failed tile
// WITH its retry, and so does a member's tile, which is not a link upload.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import OptimisticTile from './OptimisticTile.vue'
import type { OptimisticTileVM } from '../useOptimisticTiles'
import { realI18n, t } from '../../../test/i18n'

function failedTile(error: string): OptimisticTileVM {
  return {
    layoutId: 'optimistic:f-1', fileId: 'f-1', name: 'a.jpg', url: null, aspectRatio: 1, isVideo: false,
    upload: { status: 'failed', error, progress: 0 },
  }
}

const mountTile = (error: string, link: boolean) =>
  mount(OptimisticTile, { props: { tile: failedTile(error), link }, global: { plugins: [realI18n()] } })

describe('OptimisticTile — a refused link upload', () => {
  it('says the link takes no more uploads and shows no retry on quota_exceeded', () => {
    const w = mountTile('quota_exceeded', true)
    expect(w.text()).toContain(t('media.uploadTileLinkFull'))
    expect(w.text()).not.toContain(t('media.uploadTileFailed'))
    expect(w.find('.upload-tile-retry').exists()).toBe(false)
  })

  it('keeps the generic failed tile with its retry for any other error (control)', () => {
    const w = mountTile('upload_failed', true)
    expect(w.text()).toContain(t('media.uploadTileFailed'))
    expect(w.find('.upload-tile-retry').exists()).toBe(true)
    expect(w.emitted('retry')).toBeUndefined()
    return w.find('.upload-tile-retry').trigger('click').then(() => expect(w.emitted('retry')).toEqual([['f-1']]))
  })

  // uc.platform.switch-application-off: the start was refused because Memories is off here — said, with no retry
  it('says Memories is switched off here and shows no retry on capability_disabled, member and guest alike', () => {
    for (const link of [true, false]) {
      const w = mountTile('capability_disabled', link)
      expect(w.text()).toContain(t('media.uploadTileSwitchedOff'))
      expect(w.text()).not.toContain(t('media.uploadTileFailed'))
      expect(w.find('.upload-tile-retry').exists()).toBe(false)
    }
  })

  it('never names the link on a tile that is not a link upload', () => {
    const w = mountTile('quota_exceeded', false)
    expect(w.text()).not.toContain(t('media.uploadTileLinkFull'))
  })
})
