// @vitest-environment happy-dom
// The layer's tooltip wraps exactly one element and renders none of its own, so it changes no layout and the wrapped
// control keeps its own name.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import VeyqTooltip from './VeyqTooltip.vue'

describe('VeyqTooltip', () => {
  it('renders the wrapped element itself, nothing around it', () => {
    const w = mount(defineComponent({
      render: () => h('div', { class: 'row' }, [h(VeyqTooltip, { text: 'Delete' }, () => [h('button', { 'aria-label': 'Delete', class: 'x' })])]),
    }))
    const row = w.get('.row').element
    expect(row.children).toHaveLength(1)
    expect(row.firstElementChild?.tagName).toBe('BUTTON')
    expect(row.firstElementChild?.getAttribute('aria-label')).toBe('Delete')
  })
})
