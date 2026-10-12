// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MemoryCard from './MemoryCard.vue'
import { MEMORY_KIND } from '../kinds'
import type { TreeNode } from '../../../core/node/tree'
import type { MemoryDate } from '../memoryDate'
import { realI18n, t } from '../../../test/i18n'
import { treeNode } from '../../../test/models'


const memory: TreeNode = treeNode({ id: 'm1', kind: MEMORY_KIND, name: 'Rom', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })

const mountCard = () => mount(MemoryCard, { props: { memory }, global: { plugins: [realI18n()] } })

describe('MemoryCard', () => {
  it('carries no favorite pin: the heart is core\'s action (inbox #409), a chapter\'s lives in its header', () => {
    expect(mountCard().find('.memory-card-pin').exists()).toBe(false)
    expect(mountCard().find('.pi-heart').exists()).toBe(false)
  })

  it('opens the Memory on the card body click', async () => {
    const w = mountCard()
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
