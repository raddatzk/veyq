// @vitest-environment happy-dom
// The layer's drop-down (ADR 0107): the closed field says the placeholder while nothing is picked and the picked
// option's label once one is.
import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import VeyqSelect from './VeyqSelect.vue'

const options = [
  { label: 'Terrorist content', value: 'TERROR' },
  { label: 'Something else', value: 'OTHER' },
]

describe('VeyqSelect', () => {
  it('shows the placeholder until an option is picked, then its label', async () => {
    const w = mount(VeyqSelect, { props: { options, modelValue: null, placeholder: 'Choose what fits best', inputId: 'category' } })
    await flushPromises()
    expect(w.get('#category').text()).toBe('Choose what fits best')
    await w.setProps({ modelValue: 'OTHER' })
    expect(w.get('#category').text()).toBe('Something else')
  })
})
