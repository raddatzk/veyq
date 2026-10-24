// The shell's sticky offset (`--app-sticky-top`, where a page's sticky row stops: a Memory's tab row, uc.memory.banner)
// must equal the height of the shell's own sticky chrome at every breakpoint - the top bar when wide, the compact header's
// two rows and its border below 1020px. Both are CSS, which a unit test cannot render, so the cascade of `layout.css` is
// resolved here per width (its `@media (max-width)` blocks in source order, `var()` and `calc()` sums of px/rem) and the
// two compared. L5 (heavy stage, 2026-10-24): the offset stayed 72px + 3rem + 1px while the phone's tenant row was 64px,
// so the tab row stuck 8px below the header. The L1 spec `memory-banner.spec.ts` measures the real boxes; this stops a
// breakpoint's row and the offset drifting apart unseen, without a browser.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const layout = readFileSync(new URL('../../styles/layout.css', import.meta.url), 'utf8')
const base = readFileSync(new URL('../../styles/base.css', import.meta.url), 'utf8')
const REM = 16

type Rule = { selectors: string[]; decls: Map<string, string>; maxWidth: number }

/** Every style rule of `css`, in source order, with the `max-width` of the `@media` block it sits in (Infinity outside). */
function parseRules(css: string): Rule[] {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const rules: Rule[] = []
  const walk = (from: number, to: number, maxWidth: number) => {
    let i = from
    while (i < to) {
      const open = src.indexOf('{', i)
      if (open < 0 || open >= to) return
      const prelude = src.slice(i, open).trim()
      let depth = 0
      let close = open
      for (; close < to; close++) {
        if (src[close] === '{') depth++
        else if (src[close] === '}' && --depth === 0) break
      }
      if (prelude.startsWith('@media')) {
        const m = /^@media \(max-width: (\d+)px\)$/.exec(prelude)
        if (!m) throw new Error(`a query this resolver does not model: ${prelude}`)
        walk(open + 1, close, Math.min(maxWidth, Number(m[1])))
      } else if (!prelude.startsWith('@')) {
        const decls = new Map<string, string>()
        for (const d of src.slice(open + 1, close).split(';')) {
          const colon = d.indexOf(':')
          if (colon > 0) decls.set(d.slice(0, colon).trim(), d.slice(colon + 1).trim())
        }
        rules.push({ selectors: prelude.split(',').map((s) => s.trim()), decls, maxWidth })
      }
      i = close + 1
    }
  }
  walk(0, src.length, Infinity)
  return rules
}

const rules = parseRules(layout)

/** The declarations `selector` ends with at viewport `width`: every rule naming it exactly, later ones winning. */
function at(selector: string, width: number): Map<string, string> {
  const out = new Map<string, string>()
  for (const r of rules) if (r.maxWidth >= width && r.selectors.includes(selector)) for (const [k, v] of r.decls) out.set(k, v)
  return out
}

/** The root token the wide offset reads (`base.css`). */
function rootTokens(): Map<string, string> {
  const all = [...base.matchAll(/(--app-topbar-height):\s*([^;]+);/g)]
  expect(all, 'one definition of --app-topbar-height').toHaveLength(1)
  return new Map([[all[0][1], all[0][2].trim()]])
}

/** `value` in px: `var()` resolved against `tokens`, then a px/rem term or a `calc()` sum of them - anything else throws. */
function px(value: string | undefined, tokens: Map<string, string>, what: string): number {
  if (value === undefined) throw new Error(`${what}: not set`)
  let v = value
  for (let guard = 0; /var\(/.test(v); guard++) {
    if (guard > 20) throw new Error(`${what}: var() cycle in ${value}`)
    v = v.replace(/var\((--[\w-]+)(?:,\s*([^()]*))?\)/g, (_, name: string, fallback?: string) => {
      const t = tokens.get(name) ?? fallback
      if (t === undefined) throw new Error(`${what}: ${name} is not defined`)
      return t
    })
  }
  const body = /^calc\((.*)\)$/.exec(v)?.[1] ?? v
  return body.split('+').reduce((sum, term) => {
    const m = /^\s*(\d+(?:\.\d+)?)(px|rem)\s*$/.exec(term)
    if (!m) throw new Error(`${what}: "${value}" is not a fixed px/rem length (got "${term.trim()}")`)
    return sum + Number(m[1]) * (m[2] === 'rem' ? REM : 1)
  }, 0)
}

/** The custom properties the header and its rows see at `width`: the root's, then `.app-shell`'s. */
function tokensAt(width: number): Map<string, string> {
  const tokens = rootTokens()
  for (const [k, v] of at('.app-shell', width)) if (k.startsWith('--')) tokens.set(k, v)
  return tokens
}

/** The width of a `border-bottom` shorthand: its first term. */
function borderWidth(shorthand: string | undefined, tokens: Map<string, string>, what: string): number {
  return px(shorthand?.split(/\s+/)[0], tokens, what)
}

describe('the shell sticky offset equals its sticky chrome at every breakpoint (L5)', () => {
  it('wide (1400px): the top bar is the sticky chrome and the offset is its minimum height; the compact header draws no box', () => {
    const tokens = tokensAt(1400)
    expect(at('.app-compact-header', 1400).get('display')).toBe('contents')
    const topbar = at('.workspace-topbar', 1400)
    expect(topbar.get('position')).toBe('sticky')
    expect(px(topbar.get('min-height'), tokens, 'top bar min-height')).toBe(px(tokens.get('--app-sticky-top'), tokens, 'offset'))
    expect(px(tokens.get('--app-sticky-top'), tokens, 'offset')).toBe(72)
  })

  // 900: the compact header on a tablet; 390: the phone of the L1 spec, whose trace measured the header at 113px.
  for (const [width, expected] of [[900, 121], [390, 113]] as const) {
    it(`compact (${width}px): the offset is the header's two fixed rows plus its border, ${expected}px`, () => {
      const tokens = tokensAt(width)
      const header = at('.app-compact-header', width)
      expect(header.get('position')).toBe('sticky')
      const rows = (header.get('grid-template-rows') ?? '').split(/\s+(?![^(]*\))/)
      expect(rows, 'two rows: the tenant row and the top bar').toHaveLength(2)
      const [tenantRow, topbarRow] = rows.map((r, i) => px(r, tokens, `header row ${i + 1}`))
      const headerHeight = tenantRow + topbarRow + borderWidth(header.get('border-bottom'), tokens, 'header border')

      // Each row's content is held to its row, so the row heights are the boxes' heights.
      const tenant = at('.tenant-sidebar', width)
      expect(px(tenant.get('height'), tokens, 'tenant row height')).toBe(tenantRow)
      expect(px(tenant.get('min-height'), tokens, 'tenant row min-height')).toBe(tenantRow)
      const topbar = at('.workspace-topbar', width)
      expect(px(topbar.get('height'), tokens, 'top bar height')).toBe(topbarRow)
      expect(px(topbar.get('max-height'), tokens, 'top bar max-height')).toBe(topbarRow)

      expect(px(tokens.get('--app-sticky-top'), tokens, 'offset')).toBe(headerHeight)
      expect(headerHeight).toBe(expected)
    })
  }
})
