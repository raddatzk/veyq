// A short text shown below a control on hover and keyboard focus (ADR 0107): `<VeyqTooltip :text="…"><button …/></VeyqTooltip>`.
// It wraps exactly one element and renders no element of its own, so it changes no layout. The text is a hint, never
// the control's only name: an icon-only control still carries its `aria-label`.
import { defineComponent, withDirectives, type PropType, type VNode } from 'vue'
import Tooltip from 'primevue/tooltip'

export default defineComponent({
  name: 'VeyqTooltip',
  props: { text: { type: String as PropType<string | undefined>, default: undefined } },
  setup(props, { slots }) {
    return () => {
      const children = (slots.default?.() ?? []) as VNode[]
      const child = children.find((c) => typeof c.type !== 'symbol') ?? children[0]
      if (!child) return null
      return withDirectives(child, [[Tooltip, props.text, '', { bottom: true }]])
    }
  },
})
