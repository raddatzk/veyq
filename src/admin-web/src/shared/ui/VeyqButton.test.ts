// @vitest-environment happy-dom
// The layer's button (ADR 0107): its props are ours, and what a sheet or a test reaches it by is the layer's classes,
// whatever renders it underneath. These pin that contract, so the swap of its inside cannot move a hook unseen.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import VeyqButton from './VeyqButton.vue'

describe('VeyqButton', () => {
  it('says its variant and appearance in the layer classes, and is a plain button by default', () => {
    const w = mount(VeyqButton, { props: { label: 'Delete', variant: 'danger', appearance: 'outlined', size: 'small' } })
    const el = w.get('button')
    expect(el.classes()).toEqual(expect.arrayContaining(['veyq-button', 'veyq-button--danger', 'veyq-button--outlined', 'veyq-button--small']))
    expect(el.attributes('type')).toBe('button')
    expect(w.get('.veyq-button__label').text()).toBe('Delete')
  })

  it('defaults to the filled primary, and an icon-only button says so', () => {
    const w = mount(VeyqButton, { props: { icon: 'trash' }, attrs: { 'aria-label': 'Delete' } })
    const el = w.get('button')
    expect(el.classes()).toEqual(expect.arrayContaining(['veyq-button--primary', 'veyq-button--filled', 'veyq-button--icon-only']))
    expect(el.attributes('aria-label')).toBe('Delete')
    expect(w.find('.veyq-button__icon').exists()).toBe(true)
  })

  it('submits a form only when asked to', () => {
    expect(mount(VeyqButton, { props: { label: 'Save', type: 'submit' } }).get('button').attributes('type')).toBe('submit')
  })

  it('is a real link with an href', () => {
    const w = mount(VeyqButton, { props: { label: 'Open', href: 'https://example.org/x', target: '_blank', rel: 'noopener' } })
    const a = w.get('a')
    expect(a.attributes('href')).toBe('https://example.org/x')
    expect(a.attributes('target')).toBe('_blank')
    expect(a.attributes('type')).toBeUndefined()
  })

  it('emits one click per click, and none while disabled', async () => {
    const w = mount(VeyqButton, { props: { label: 'Go' } })
    await w.get('button').trigger('click')
    expect(w.emitted('click')).toHaveLength(1)
    const off = mount(VeyqButton, { props: { label: 'Go', disabled: true } })
    expect(off.get('button').attributes('disabled')).toBeDefined()
  })
})
