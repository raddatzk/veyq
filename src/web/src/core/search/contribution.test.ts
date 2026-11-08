// @vitest-environment happy-dom
import { describe, it, expect } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { contributeSearchDocuments, provideNoSearchContributions, provideSearchContributions, searchContribution } from './contribution'

const photo = { kind: 'memories.photo', id: 'm1', revision: '1', name: 'IMG_1.jpg', facets: {}, open: () => {} }

const Detail = defineComponent({
  setup() {
    contributeSearchDocuments(() => ({ scopeId: 's1', nodeId: 'n1', documents: [photo] }))
    return () => h('div')
  },
})
const host = (provideIt: () => void) =>
  defineComponent({
    props: { show: { type: Boolean, default: true } },
    setup(props, { slots }) {
      provideIt()
      return () => (props.show ? slots.default?.() : null)
    },
  })
const MemberPage = host(provideSearchContributions)
const Blocked = host(provideNoSearchContributions)

describe('the contribution door', () => {
  it('holds what the detail contributes while it is mounted below the member page, and withdraws it on unmount', async () => {
    const show = ref(true)
    const w = mount(defineComponent({ setup: () => () => h(MemberPage, { show: show.value }, () => h(Detail)) }))
    expect(searchContribution()?.documents.map((d) => d.id)).toEqual(['m1'])
    show.value = false
    await w.vm.$nextTick()
    expect(searchContribution()).toBeNull()
  })

  it('takes nothing from a detail mounted without the member page (a guest\'s page, a detail alone)', () => {
    mount(Detail)
    expect(searchContribution()).toBeNull()
  })

  it('takes nothing from a region that blocks it below the member page (a bookmark\'s share shown in place)', () => {
    mount(defineComponent({ setup: () => () => h(MemberPage, null, () => h(Blocked, null, () => h(Detail))) }))
    expect(searchContribution()).toBeNull()
  })
})
