// An icon as a detached DOM element, for code that builds markup by hand outside a Vue template (a map library's
// marker). It is `VeyqIcon` rendered, so it draws whatever `VeyqIcon` draws.
import { h, render } from 'vue'
import VeyqIcon from './VeyqIcon.vue'
import type { VeyqIconName } from './icons'

export function createIconElement(name: VeyqIconName): HTMLElement {
  const host = document.createElement('span')
  render(h(VeyqIcon, { name }), host)
  const el = host.firstElementChild as HTMLElement
  render(null, host)
  return el
}
