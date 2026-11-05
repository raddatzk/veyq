// @vitest-environment happy-dom
// The Trusted Types door (ADR 0117). happy-dom has no Trusted Types, so the enforcing browser is stood in for by a fake
// factory with the spec's two observable rules - a policy is a function from string to an opaque value, `isHTML` knows its
// own values - over a fresh copy of the module; the browser's own refusal is lane L1's (tests/e2e/src/cspViolations.ts).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { buildCsp, DEFAULT_POLICY, VEYQ_POLICY } from './csp'
import { renderMarkdown } from '../markdown'
import type { DirectiveBinding } from 'vue'
import type { SafeHtml } from './trustedTypes'

type Rules = Record<string, (input: string, ...args: unknown[]) => unknown>

class FakeTrusted {
  constructor(readonly value: string) {}
  toString() {
    return this.value
  }
}

/** A Trusted Types factory as the browser has one: what a rule returns is wrapped, null is refused. */
function fakeFactory() {
  const policies = new Map<string, Rules>()
  const call = (rules: Rules, kind: string, input: string, args: unknown[]) => {
    const rule = rules[kind]
    if (!rule) throw new TypeError(`policy has no ${kind}`)
    const out = rule(input, ...args)
    if (out == null) throw new TypeError(`${kind} refused`)
    return new FakeTrusted(String(out))
  }
  return {
    policies,
    createPolicy(name: string, rules: Rules) {
      if (policies.has(name)) throw new TypeError(`duplicate policy ${name}`)
      policies.set(name, rules)
      return {
        createHTML: (input: string, ...args: unknown[]) => call(rules, 'createHTML', input, args),
        createScriptURL: (input: string, ...args: unknown[]) => call(rules, 'createScriptURL', input, args),
      }
    },
    isHTML: (value: unknown) => value instanceof FakeTrusted,
  }
}

/** A directive hook as Vue calls it: the element and a binding of which these hooks read `value` and `oldValue`. */
type Hook = (el: Element, binding: DirectiveBinding<SafeHtml | null | undefined>) => void
const run = (hook: unknown, el: Element, value: unknown, oldValue?: unknown) =>
  (hook as Hook)(el, { value, oldValue } as unknown as DirectiveBinding<SafeHtml | null | undefined>)

const HOSTILE = '<img src=x onerror="alert(1)"> **bold** [x](javascript:alert(1))'

/** A fresh copy of the module, evaluated with [factory] as `window.trustedTypes` (or none). */
async function freshModule(factory?: ReturnType<typeof fakeFactory>) {
  vi.resetModules()
  ;(window as { trustedTypes?: unknown }).trustedTypes = factory
  return import('./trustedTypes')
}

afterEach(() => {
  delete (window as { trustedTypes?: unknown }).trustedTypes
})

describe('in a browser with Trusted Types', () => {
  let factory: ReturnType<typeof fakeFactory>
  let tt: Awaited<ReturnType<typeof freshModule>>
  beforeEach(async () => {
    factory = fakeFactory()
    tt = await freshModule(factory)
  })

  it('creates exactly the two policies the built CSP names, when the module loads', () => {
    expect([...factory.policies.keys()]).toEqual([VEYQ_POLICY, DEFAULT_POLICY])
    const allowed = buildCsp({ tileOrigin: null, geocoderOrigin: null, breachOrigin: null, ratesOrigin: null })
      .split(';')
      .map((d) => d.trim().split(/\s+/))
      .find(([name]) => name === 'trusted-types')
      ?.slice(1)
    expect(allowed).toEqual([...factory.policies.keys()])
  })

  it("markdownHtml is the veyq policy's TrustedHTML of the sanitizer's output", () => {
    const html = tt.markdownHtml(HOSTILE) as unknown
    expect(html).toBeInstanceOf(FakeTrusted)
    expect(String(html)).toBe(renderMarkdown(HOSTILE))
    expect(String(html)).not.toContain('<img')
  })

  it('the veyq policy makes nothing for a caller that does not hold one of its purposes', () => {
    const { createHTML } = factory.policies.get(VEYQ_POLICY)!
    expect(() => createHTML(HOSTILE)).toThrow(/only through markdownHtml/)
    expect(() => createHTML(HOSTILE, 'markdown')).toThrow(/only through markdownHtml/)
  })

  it('v-safe-html writes what markdownHtml made, and refuses a string', () => {
    const el = document.createElement('div')
    run(tt.vSafeHtml.mounted, el, tt.markdownHtml('# Hi\n\n<b>x</b>'))
    expect(el.innerHTML).toBe('<h1>Hi</h1><p>&lt;b&gt;x&lt;/b&gt;</p>')
    const raw = document.createElement('div')
    expect(() => run(tt.vSafeHtml.mounted, raw, '<b>x</b>')).toThrow(/never a string/)
    expect(raw.innerHTML).toBe('')
  })

  it('v-safe-html empties the element for no markup, and writes nothing when the value did not change', () => {
    const el = document.createElement('div')
    const html = tt.markdownHtml('a')
    run(tt.vSafeHtml.mounted, el, html)
    el.append('touched')
    run(tt.vSafeHtml.updated, el, html, html)
    expect(el.textContent).toBe('atouched')
    run(tt.vSafeHtml.updated, el, null, html)
    expect(el.childNodes).toHaveLength(0)
  })

  it('the default policy passes exactly the listed library markup, and no other string', () => {
    const { createHTML } = factory.policies.get(DEFAULT_POLICY)!
    for (const markup of tt.LIBRARY_MARKUP) expect(createHTML(markup)).toBe(markup)
    for (const other of ['', '<svg/> ', '<svg onload="alert(1)"/>', '<span aria-hidden="true">+</span><img src=x>', HOSTILE])
      expect(createHTML(other), other).toBeNull()
  })

  it("the default policy passes this client's own scripts as script URLs, and no other", () => {
    const { createScriptURL } = factory.policies.get(DEFAULT_POLICY)!
    const own = [`${location.origin}/assets/argon.worker-C_QM2tLb.js`, '/assets/pdf.worker.min-Dkey6ZUl.js', '/sw.js']
    for (const url of own) expect(createScriptURL(url), url).toBe(url)
    const foreign = [
      'https://evil.example/assets/x.js',
      '/api/files/x.js',
      '/blobs/bucket/x.js',
      '/assets/x.css',
      '/assets/../api/x.js',
      '/sw.js.map',
      'data:text/javascript,alert(1)',
      'blob:' + location.origin + '/0b5c7a8e',
      'javascript:alert(1)',
    ]
    for (const url of foreign) expect(createScriptURL(url), url).toBeNull()
  })

  it('parseInertXml parses through the policy and returns a document of its own', () => {
    const doc = tt.parseInertXml('<x:xmpmeta xmlns:x="adobe:ns:meta/"><a>1</a></x:xmpmeta>', 'application/xml')
    expect(doc).not.toBe(document)
    expect(doc.getElementsByTagName('a')[0].textContent).toBe('1')
  })
})

describe('in a browser without Trusted Types', () => {
  it('runs the same sanitizer, and v-safe-html still refuses a string', async () => {
    const tt = await freshModule(undefined)
    const el = document.createElement('div')
    run(tt.vSafeHtml.mounted, el, tt.markdownHtml(HOSTILE))
    expect(el.querySelector('strong')?.textContent).toBe('bold') // control: the renderer's markup is there
    expect(el.querySelector('img')).toBeNull()
    expect(el.textContent).toBe('<img src=x onerror="alert(1)"> bold [x](javascript:alert(1))')
    expect(() => run(tt.vSafeHtml.mounted, el, '<b>x</b>')).toThrow(/never a string/)
  })
})

describe('SafeHtml', () => {
  it('is no string, so a template cannot bind one to v-safe-html (a typecheck case)', () => {
    // @ts-expect-error a string is no SafeHtml: `v-safe-html="someString"` does not compile
    const notSafe: SafeHtml = '<b>x</b>'
    expect(typeof notSafe).toBe('string')
  })
})
