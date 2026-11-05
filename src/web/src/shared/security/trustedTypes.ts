// Trusted Types (ADR 0117): the one door markup takes into the page. An XSS in this client is key theft - the page holds
// every unlocked key - so the built policy enforces it (`require-trusted-types-for 'script'`, csp.ts): a browser with
// Trusted Types refuses a plain string at every HTML and script-URL sink, ours and every bundled library's alike. This
// module creates the two policies the policy names, and nothing else may (`trusted-types veyq default`, no duplicates):
//
// - `veyq`, held in this module and never handed out. Its `createHTML` IS the sanitizer: it runs `renderMarkdown` (escape
//   first, a small safe subset) over what it is given, so the only markup a page shows comes out of that renderer.
//   `markdownHtml` is its door, and `vSafeHtml` the one directive that writes it into an element. The policy has one
//   other purpose, behind a token only this module holds: `parseInertXml`, an XML parse into a document that is never
//   part of the page (the XMP block of a photo, read by the EXIF library) - a DOMParser sink too under Trusted Types.
// - `default`, which the browser consults for a plain string that reaches a sink from code that cannot be pointed at
//   `veyq`: the libraries. It passes an EXACT list of fixed markup (Leaflet's own literals, `LIBRARY_MARKUP`) and the
//   app's own same-origin scripts as worker and service-worker URLs (`isOwnScript`) - each worker the build starts
//   (`new Worker(new URL(…, import.meta.url))`, the one spelling Vite bundles a worker from), pdf.js' and the media
//   service worker. Everything else it refuses, so a string that is not on its list fails at the sink as without it.
//
// Where the browser has no Trusted Types (`window.trustedTypes` absent), the same sanitizer runs and `vSafeHtml` still
// takes only what `markdownHtml` made: the guarantee that a raw string never becomes markup holds in every browser, the
// browser's enforcement is the second layer where it exists. The dev server sends no Trusted Types directive (csp.ts).
import type { ObjectDirective } from 'vue'
import { renderMarkdown } from '../markdown'
import { DEFAULT_POLICY, VEYQ_POLICY } from './csp'

/** Markup from `markdownHtml`: a TrustedHTML where the browser has Trusted Types, else this module's own wrapper.
 *  Opaque on purpose - no string is one, so `v-safe-html="someString"` does not compile. */
declare const safeHtml: unique symbol
export type SafeHtml = { readonly [safeHtml]: true }

/** The fixed markup bundled libraries write as a string, which the `default` policy passes as it is. Exact strings, never
 *  a pattern: each entry is a literal in the library's own source, carrying no data. A library upgrade that changes one
 *  is refused at the sink, and lane L1's violation fixture (tests/e2e/src/cspViolations.ts) names it. */
export const LIBRARY_MARKUP: ReadonlySet<string> = new Set([
  // Leaflet's inline-SVG probe, run once when the library loads (leaflet/src/core/Browser.js, `inlineSvg`).
  '<svg/>',
  // Leaflet's zoom buttons, its `zoomInText` / `zoomOutText` defaults (leaflet/src/control/Control.Zoom.js).
  '<span aria-hidden="true">+</span>',
  '<span aria-hidden="true">&#x2212;</span>',
])

/** True for a URL of one of this client's own built scripts: same origin, and either a file the build put under
 *  `assets/` or the media service worker at `sw.js` (ADR 0047). The dev server is not held to it: it sends no Trusted
 *  Types directive, so the browser never asks (its workers are served from `src/` and `node_modules/`). */
export function isOwnScript(input: string, origin: string, base: string): boolean {
  let url: URL
  try {
    url = new URL(input, origin)
  } catch {
    return false
  }
  if (url.origin !== origin || url.username || url.password) return false
  return url.pathname === `${base}sw.js` || (url.pathname.startsWith(`${base}assets/`) && url.pathname.endsWith('.js') && !url.pathname.includes('..'))
}

// The purposes of `veyq`'s `createHTML`, as tokens only this module holds: what it is given decides nothing.
const MARKDOWN = Symbol('markdown')
const INERT_XML = Symbol('inert-xml')
type Purpose = typeof MARKDOWN | typeof INERT_XML

interface HtmlPolicy {
  createHTML(input: string, purpose: Purpose): unknown
}
interface TrustedTypesFactory {
  createPolicy(name: string, rules: Record<string, (input: string, ...args: unknown[]) => unknown>): HtmlPolicy
  isHTML(value: unknown): boolean
}

/** The browser's Trusted Types factory, or undefined where there is none (Firefox and Safari without it, node). */
export function trustedTypesFactory(): TrustedTypesFactory | undefined {
  return typeof window === 'undefined' ? undefined : (window as { trustedTypes?: TrustedTypesFactory }).trustedTypes
}

/** Markup the sanitizer made, where the browser has no Trusted Types. The class never leaves this module. */
class SanitizedMarkup {
  constructor(readonly html: string) {
    Object.freeze(this)
  }
  toString(): string {
    return this.html
  }
}

let veyq: HtmlPolicy | null = null
let factory: TrustedTypesFactory | undefined

/**
 * Create both policies. Runs once, when `main.ts` imports this module FIRST: Leaflet probes inline SVG with `innerHTML`
 * the moment it loads, so `default` must exist before any library module evaluates. A policy the page's CSP refuses
 * (another name list, a second copy of this module) leaves `veyq` null: `markdownHtml` then hands back markup the
 * enforcing browser refuses at the sink, which is the failure the L1 violation fixture turns red - never markup it lets by.
 */
export function installTrustedTypes(found = trustedTypesFactory()): void {
  factory = found
  if (!found || veyq) return
  try {
    veyq = found.createPolicy(VEYQ_POLICY, {
      createHTML: (input, purpose) => {
        if (purpose === MARKDOWN) return renderMarkdown(input)
        if (purpose === INERT_XML) return input
        throw new TypeError('the veyq policy makes markup only through markdownHtml() and parseInertXml()')
      },
    })
    found.createPolicy(DEFAULT_POLICY, {
      createHTML: (input) => (LIBRARY_MARKUP.has(input) ? input : null),
      createScriptURL: (input) => (isOwnScript(input, location.origin, import.meta.env.BASE_URL) ? input : null),
    })
  } catch (e) {
    console.error('veyq: the Trusted Types policies could not be created; markup will be refused', e)
  }
}

/** A Markdown text as safe markup: `renderMarkdown`'s output, as the `veyq` policy's TrustedHTML where there is one. */
export function markdownHtml(markdown: string): SafeHtml {
  if (veyq) return veyq.createHTML(markdown, MARKDOWN) as SafeHtml
  return new SanitizedMarkup(renderMarkdown(markdown)) as unknown as SafeHtml
}

/** XML parsed into a document of its own. The document is never adopted into the page: a caller reads values from it. */
export function parseInertXml(xml: string, type: 'application/xml' | 'text/xml'): Document {
  const source = veyq ? veyq.createHTML(xml, INERT_XML) : xml
  return new DOMParser().parseFromString(source as string, type)
}

function write(el: Element, value: SafeHtml | null | undefined): void {
  if (value == null) {
    el.replaceChildren()
    return
  }
  if (value instanceof SanitizedMarkup) el.innerHTML = value.html
  else if (factory?.isHTML(value)) el.innerHTML = value as unknown as string
  else throw new TypeError('v-safe-html takes markup from markdownHtml() only, never a string')
}

/**
 * `v-safe-html`: the one way a template shows markup (`vue/no-v-html` stays on). It takes only what `markdownHtml` made -
 * the type refuses a string at compile time, and `write` refuses one at run time in every browser.
 */
export const vSafeHtml: ObjectDirective<Element, SafeHtml | null | undefined> = {
  mounted: (el, { value }) => write(el, value),
  updated: (el, { value, oldValue }) => {
    if (value !== oldValue) write(el, value)
  },
}

installTrustedTypes()
