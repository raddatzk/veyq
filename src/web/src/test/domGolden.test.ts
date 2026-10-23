// @vitest-environment happy-dom
// The golden serialiser's own contract: what it keeps (tag, sorted classes, the kept attributes, collapsed text, DOM
// order) and what it must ignore (style, ids, scope and library markers, comments, blank text) — each ignored case
// paired with a kept one on the same element, so a serialiser that drops everything cannot pass.
import { describe, it, expect } from 'vitest'
import { serializeDom } from './domGolden'

function dom(html: string): Element {
  const host = document.createElement('div')
  host.innerHTML = html.trim()
  return host.firstElementChild!
}

describe('serializeDom', () => {
  it('prints one indented line per element and per text node, children in DOM order', () => {
    expect(serializeDom(dom('<section><h2>Title</h2><p>One</p><p>Two</p></section>'))).toBe(
      ['section', '  h2', '    "Title"', '  p', '    "One"', '  p', '    "Two"', ''].join('\n'),
    )
  })

  it('sorts the classes, so the order of a class binding is not a diff — but a renamed class is', () => {
    const a = serializeDom(dom('<div class="zeta alpha  mid"></div>'))
    expect(a).toBe('div.alpha.mid.zeta\n')
    expect(serializeDom(dom('<div class="mid zeta alpha"></div>'))).toBe(a)
    expect(serializeDom(dom('<div class="alpha mid zeta-renamed"></div>'))).not.toBe(a)
  })

  it('keeps role, aria-label, aria-pressed and disabled; drops style, id, data-v-*, library markers', () => {
    const line = serializeDom(
      dom(
        '<button id="reka-tabs-v-0-trigger-a" style="color: red" data-v-1a2b3c="" data-state="active" data-reka-collection-item="" ' +
          'type="button" role="switch" aria-label="Say &quot;hi&quot;" aria-pressed="true" disabled="disabled"></button>',
      ),
    )
    expect(line).toBe('button [role="switch"] [aria-label="Say \\"hi\\""] [aria-pressed="true"] [disabled]\n')
  })

  it('trims and collapses whitespace in text, and skips whitespace-only text and comments', () => {
    const out = serializeDom(dom('<div>\n   <!--v-if-->\n  <span>  Two\n\t words  </span>   \n</div>'))
    expect(out).toBe(['div', '  span', '    "Two words"', ''].join('\n'))
  })

  it('is deterministic: the same DOM serialises identically twice, a reordered sibling does not', () => {
    const html = '<ul><li class="b a">x</li><li>y</li></ul>'
    expect(serializeDom(dom(html))).toBe(serializeDom(dom(html)))
    expect(serializeDom(dom('<ul><li>y</li><li class="b a">x</li></ul>'))).not.toBe(serializeDom(dom(html)))
  })
})
