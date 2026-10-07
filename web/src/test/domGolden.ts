// A rendered DOM as a stable, readable text tree — the serialiser behind the golden (file-snapshot) tests.
//
// **What it keeps.** One line per element: its tag, its classes SORTED (so a re-ordered `:class` binding is not a
// diff, a renamed or dropped class is), and the attributes in `KEPT_ATTRIBUTES` — the ones a user or assistive tech
// meets. One line per non-empty text node, trimmed and whitespace-collapsed. Children stay in DOM order, because
// that order IS what the user sees.
//
// **What it drops, and why it is an allowlist.** Everything else: inline `style`, `id`, Vue's `data-v-*` scope
// attributes, PrimeVue's generated ids and `data-pc-*`/`data-p-*` markers, comments (`<!--v-if-->`), whitespace-only
// text. An allowlist rather than a blocklist, because the next library upgrade adds attributes nobody listed — a
// blocklist would turn each of them into a snapshot diff nobody can judge, an allowlist ignores them by construction.
//
// **Its limit.** It pins the DOM, not the screen: no layout, no computed CSS, no media query. A rule that hides an
// element at some width is invisible here (the 721–1120 px hiding of the folder card's actions was CSS-only, found in
// a real browser) — the class that carries such a rule is what a golden can pin.

/** The attributes a golden keeps; every other attribute is ignored. */
export const KEPT_ATTRIBUTES: readonly string[] = ['role', 'aria-label', 'aria-pressed', 'disabled']

const INDENT = '  '

const collapse = (text: string): string => text.replace(/\s+/g, ' ').trim()

function elementLine(el: Element): string {
  const classes = [...el.classList].filter((c) => c.length > 0).sort()
  const attrs = KEPT_ATTRIBUTES.filter((name) => el.hasAttribute(name)).map((name) => {
    const value = el.getAttribute(name) ?? ''
    // A boolean attribute is present or not; its value (`""`, `"disabled"`) is markup noise.
    return name === 'disabled' ? '[disabled]' : `[${name}=${JSON.stringify(value)}]`
  })
  return `${el.localName}${classes.map((c) => `.${c}`).join('')}${attrs.length ? ` ${attrs.join(' ')}` : ''}`
}

function walk(node: Node, depth: number, out: string[]): void {
  if (node.nodeType === 3 /* TEXT_NODE */) {
    const text = collapse(node.textContent ?? '')
    if (text) out.push(`${INDENT.repeat(depth)}${JSON.stringify(text)}`)
    return
  }
  if (node.nodeType !== 1 /* ELEMENT_NODE */) return // comments, processing instructions
  const el = node as Element
  out.push(`${INDENT.repeat(depth)}${elementLine(el)}`)
  for (const child of el.childNodes) walk(child, depth + 1, out)
}

/** Serialise `root` and its subtree into the golden text form (one trailing newline). */
export function serializeDom(root: Element): string {
  const out: string[] = []
  walk(root, 0, out)
  return `${out.join('\n')}\n`
}
