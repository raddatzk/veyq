// A real-DOM check that nothing makes the text of a viewer unselectable (inbox #412): `user-select: none` anywhere in the
// app's stylesheets or components' <style> blocks is found, and a mounted element is tested against each of those rules'
// selectors with `matches` - on itself and on every ancestor, since `user-select` is inherited by the text inside. happy-dom
// does not apply our stylesheets, so reading the rules and matching them against the real DOM is the part that can be
// proved here; that the browser then selects is the platform's (and a real-browser check's).
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const SRC = join(process.cwd(), 'src')

function files(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) files(path, out)
    else if (/\.(css|vue)$/.test(name)) out.push(path)
  }
  return out
}

/** The CSS text of a file: the whole of a stylesheet, the <style> blocks of a component. */
function cssOf(path: string): string {
  const text = readFileSync(path, 'utf8')
  if (path.endsWith('.css')) return text
  return [...text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n')
}

/** A selector list split at its top-level commas (a comma inside `:is(a, b)` stays). */
function splitSelectors(list: string): string[] {
  const out: string[] = []
  let depth = 0
  let start = 0
  for (let i = 0; i < list.length; i++) {
    if (list[i] === '(') depth++
    else if (list[i] === ')') depth--
    else if (list[i] === ',' && depth === 0) {
      out.push(list.slice(start, i).trim())
      start = i + 1
    }
  }
  out.push(list.slice(start).trim())
  return out.filter(Boolean)
}

/** Every selector of a rule that declares `user-select: none` (any prefix), with the file it lives in. */
export function userSelectNoneRules(): { selector: string; file: string }[] {
  const found: { selector: string; file: string }[] = []
  for (const file of files(SRC)) {
    const css = cssOf(file).replace(/\/\*[\s\S]*?\*\//g, '')
    for (const rule of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      if (!/(^|[;\s])(-webkit-|-moz-)?user-select\s*:\s*none/.test(rule[2])) continue
      for (const selector of splitSelectors(rule[1])) found.push({ selector, file: file.slice(SRC.length + 1) })
    }
  }
  return found
}

/** The `user-select: none` rules that apply to `el` or to an ancestor of it: a selection cannot start in text they cover. */
export function unselectableBy(el: Element): { selector: string; file: string }[] {
  const rules = userSelectNoneRules()
  const hits: { selector: string; file: string }[] = []
  for (let node: Element | null = el; node; node = node.parentElement) {
    for (const rule of rules) {
      const plain = rule.selector.replace(/::[a-z-]+|:(hover|focus|focus-visible|focus-within|active|disabled|checked)\b/gi, '') // pseudo-elements and states: the state is not the question
      try {
        if (plain && node.matches(plain)) hits.push(rule)
      } catch {
        // a selector the DOM cannot parse matches nothing
      }
    }
  }
  return hits
}
