// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryCard from './MemoryCard.vue'
import { NodeKind } from '../../../api/generated/content'
import type { TreeNode } from '../tree'
import type { MemoryDate } from '../memoryDate'
import { realI18n, t } from '../../../test/i18n'


const memory: TreeNode = { id: 'm1', kind: NodeKind.Memory, name: 'Rom', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0 }

const mountCard = (favorite: boolean) =>
  mount(MemoryCard, { props: { memory, favorite }, global: { plugins: [realI18n()] } })

describe('MemoryCard favorite pin', () => {
  it('shows an outline heart when not favorited', () => {
    const w = mountCard(false)
    expect(w.find('.memory-card-pin').exists()).toBe(true)
    expect(w.find('.memory-card-pin.is-favorite').exists()).toBe(false)
    expect(w.find('.memory-card-pin .pi-heart').exists()).toBe(true)
  })

  it('shows a filled accent heart when favorited', () => {
    const w = mountCard(true)
    expect(w.find('.memory-card-pin.is-favorite').exists()).toBe(true)
    expect(w.find('.memory-card-pin .pi-heart-fill').exists()).toBe(true)
  })

  it('toggles favorite on pin click WITHOUT opening the Memory', async () => {
    const w = mountCard(false)
    await w.find('.memory-card-pin').trigger('click')
    expect(w.emitted('toggleFavorite')?.[0]?.[0]).toMatchObject({ id: 'm1' })
    expect(w.emitted('open')).toBeFalsy()
  })

  it('opens the Memory on the card body click', async () => {
    const w = mountCard(false)
    await w.find('.memory-card-main').trigger('click')
    expect(w.emitted('open')?.[0]?.[0]).toMatchObject({ id: 'm1' })
  })
})

describe('MemoryCard cover (cr-0072)', () => {
  it('shows the placeholder images tile when no cover is set', () => {
    const w = mount(MemoryCard, { props: { memory }, global: { plugins: [realI18n()] } })
    expect(w.find('.memory-card-cover .memory-card-icon.pi-images').exists()).toBe(true)
    expect(w.find('.memory-card-cover img').exists()).toBe(false)
  })

  it('renders the decrypted cover with the crop-offset styles when set', () => {
    const w = mount(MemoryCard, {
      props: { memory, coverSrc: 'blob:cover', coverCrop: { x: 0.1, y: 0.2, width: 0.8, height: 0.6 } },
      global: { plugins: [realI18n()] },
    })
    const img = w.find('.memory-card-cover img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('blob:cover')
    expect(img.attributes('style') ?? '').toContain(`width: ${100 / 0.8}%`)
    expect(w.find('.memory-card-cover .memory-card-icon.pi-images').exists()).toBe(false)
  })
})

describe('MemoryCard sealed date (cr-0067)', () => {
  const mountWithDate = (date: MemoryDate | null) =>
    mount(MemoryCard, { props: { memory, date }, global: { plugins: [realI18n()] } })

  it('shows no date line when the node carries none', () => {
    expect(mountWithDate(null).find('.memory-card-date').exists()).toBe(false)
  })

  it('renders a single day', () => {
    const w = mountWithDate({ mode: 'single', from: '2026-06-15', to: '2026-06-15' })
    expect(w.find('.memory-card-date').text()).toBe('06/15/2026')
  })

  it('renders a range', () => {
    const w = mountWithDate({ mode: 'range', from: '2026-06-01', to: '2026-06-30' })
    expect(w.find('.memory-card-date').text()).toBe('06/01/2026 – 06/30/2026')
  })

  it('renders an ongoing “since …”', () => {
    const w = mountWithDate({ mode: 'ongoing', from: '2026-01-01' })
    expect(w.find('.memory-card-date').text()).toBe(t('memory.date.since', { day: '01/01/2026' }))
  })
})
