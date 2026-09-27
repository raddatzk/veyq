// @vitest-environment happy-dom
// uc.sharing.share-link-storage-limit (web) — a link visitor's upload refused `quota_exceeded` reads as the link being
// full and offers no retry, because none can help (ADR 0073 §6); every other failure keeps the generic failed tile
// WITH its retry, and so does a member's tile, which is not a link upload.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import OptimisticTile from './OptimisticTile.vue'
import type { OptimisticTileVM } from '../useOptimisticTiles'
import { i18n } from '../../../i18n'

function failedTile(error: string): OptimisticTileVM {
  return {
    layoutId: 'optimistic:f-1', fileId: 'f-1', name: 'a.jpg', url: null, aspectRatio: 1,
    status: 'failed', error, progress: 0, isVideo: false,
  }
}

const mountTile = (error: string, link: boolean) =>
  mount(OptimisticTile, { props: { tile: failedTile(error), link }, global: { plugins: [i18n] } })

describe('OptimisticTile — a refused link upload', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('says the link takes no more uploads and shows no retry on quota_exceeded', () => {
    const w = mountTile('quota_exceeded', true)
    expect(w.text()).toContain(i18n.global.t('media.uploadTileLinkFull'))
    expect(w.text()).not.toContain(i18n.global.t('media.uploadTileFailed'))
    expect(w.find('.upload-tile-retry').exists()).toBe(false)
  })

  it('keeps the generic failed tile with its retry for any other error (control)', () => {
    const w = mountTile('upload_failed', true)
    expect(w.text()).toContain(i18n.global.t('media.uploadTileFailed'))
    expect(w.find('.upload-tile-retry').exists()).toBe(true)
    expect(w.emitted('retry')).toBeUndefined()
    return w.find('.upload-tile-retry').trigger('click').then(() => expect(w.emitted('retry')).toEqual([['f-1']]))
  })

  it('never names the link on a tile that is not a link upload', () => {
    const w = mountTile('quota_exceeded', false)
    expect(w.text()).not.toContain(i18n.global.t('media.uploadTileLinkFull'))
  })
})
