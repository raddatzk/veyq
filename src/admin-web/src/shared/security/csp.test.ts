import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ADMIN_CSP, adminCsp, PWNED_ORIGIN, REKA_STYLE_HASHES } from './csp'

// Guard the CSP the admin console ships with. The policy is a security backstop, not incidental config, so a
// loosened directive or a new third-party origin must fail here rather than land quietly.

const directives = new Map(
  ADMIN_CSP.split(';').map((d) => {
    const [name, ...sources] = d.trim().split(/\s+/)
    return [name, sources] as const
  }),
)

/** The CSP hash of every fixed <style> text a reka-ui component renders that a `.vue`/`.ts` file under `srcDir` imports,
 *  following reka's own relative imports from that component's module. Reka emits such a style as a `Primitive` with
 *  `as: "style"` around one `createTextVNode("…")` (SelectViewport, ComboboxViewport, ScrollAreaViewport, Viewport). */
function rekaStyleHashes(srcDir: string): string[] {
  const dist = dirname(createRequire(import.meta.url).resolve('reka-ui'))
  const index = readFileSync(join(dist, 'index.js'), 'utf8')
  const moduleOf = new Map<string, string>()
  for (const m of index.matchAll(/import \{([^}]*)\} from "(\.\/[^"]+)"/g))
    for (const local of m[1].split(',')) moduleOf.set(local.trim(), join(dist, m[2]))
  const localOf = new Map<string, string>()
  for (const m of index.matchAll(/(\w+) as (\w+)/g)) localOf.set(m[2], m[1])
  const sources = (dir: string): string[] =>
    readdirSync(dir).flatMap((f) => {
      const path = join(dir, f)
      if (statSync(path).isDirectory()) return f === 'node_modules' ? [] : sources(path)
      return /\.(vue|ts)$/.test(f) && !/\.test\.ts$/.test(f) ? [path] : []
    })
  const imported = new Set<string>()
  for (const file of sources(srcDir))
    for (const m of readFileSync(file, 'utf8').matchAll(/import \{([^}]*)\} from 'reka-ui'/g))
      for (const name of m[1].split(',')) if (name.trim()) imported.add(name.trim().replace(/^type\s+/, '').split(/\s+as\s+/)[0])
  const seen = new Set<string>()
  const hashes = new Set<string>()
  const visit = (file: string) => {
    if (seen.has(file)) return
    seen.add(file)
    const code = readFileSync(file, 'utf8')
    for (const m of code.matchAll(/as: "style",[\s\S]*?createTextVNode\("((?:[^"\\]|\\.)*)"/g))
      hashes.add(`'sha256-${createHash('sha256').update(JSON.parse(`"${m[1]}"`)).digest('base64')}'`)
    for (const m of code.matchAll(/from "(\.{1,2}\/[^"]+\.js)"/g)) visit(join(dirname(file), m[1]))
  }
  for (const name of imported) {
    const module = moduleOf.get(localOf.get(name) ?? name)
    if (!module) throw new Error(`reka-ui export ${name} not found in its index.js — the walk would miss it`)
    visit(module)
  }
  return [...hashes].sort()
}

describe('ADMIN_CSP', () => {
  it('keeps scripts strictly same-origin, with no unsafe-* escape', () => {
    expect(directives.get('script-src')).toEqual(["'self'"])
    expect(directives.get('default-src')).toEqual(["'self'"])
  })

  it('runs workers from its own origin only — the Argon2 worker; never a blob: one (inbox #527)', () => {
    expect(directives.get('worker-src')).toEqual(["'self'"])
  })

  it("allows styles from its own files and reka's fixed <style> hashes, never 'unsafe-inline' (inbox #523)", () => {
    expect(directives.get('style-src')).toEqual(["'self'", ...REKA_STYLE_HASHES])
    expect(ADMIN_CSP).not.toContain('unsafe-inline')
  })

  it('names a style nonce only on the dev server, where Vite injects every stylesheet as a <style>', () => {
    expect(adminCsp('abc123')).toContain(`style-src 'self' ${REKA_STYLE_HASHES.join(' ')} 'nonce-abc123'`)
    expect(ADMIN_CSP).not.toContain("'nonce-")
  })

  it('hashes exactly the <style> elements the reka-ui components this client imports render', () => {
    expect(rekaStyleHashes(fileURLToPath(new URL('../..', import.meta.url)))).toEqual([...REKA_STYLE_HASHES].sort())
  })

  it('embeds nothing and lets nothing re-base or re-target the page', () => {
    expect(directives.get('object-src')).toEqual(["'none'"])
    expect(directives.get('base-uri')).toEqual(["'self'"])
    expect(directives.get('form-action')).toEqual(["'self'"])
  })

  it('talks to its own origin and the pwned-password range API — nothing else', () => {
    expect(directives.get('connect-src')).toEqual(["'self'", PWNED_ORIGIN])
  })

  it('names no other third-party origin in any directive', () => {
    const origins = [...directives.values()].flat().filter((s) => /^https?:/.test(s))
    expect(origins).toEqual([PWNED_ORIGIN])
  })

  it('forbids framing: the policy is a header, which carries frame-ancestors (a <meta> would ignore it)', () => {
    expect(directives.get('frame-ancestors')).toEqual(["'none'"])
  })
})

// The console's nginx is the policy's delivery (one image for Compose and Helm, rendered by the same entrypoint), so the
// template is read as nginx reads it: every server-level `add_header`, comments stripped.
const template = readFileSync(fileURLToPath(new URL('../../../nginx.conf.template', import.meta.url)), 'utf8')
const code = template
  .split('\n')
  .map((line) => line.replace(/#.*$/, ''))
  .join('\n')
const headers = new Map(
  [...code.matchAll(/^\s*add_header\s+(\S+)\s+("[^"]*"|\S+)\s+always;/gm)].map((m) => [m[1], m[2].replace(/^"|"$/g, '')] as const),
)

describe('the headers nginx sends with the console', () => {
  it('sends ADMIN_CSP as its Content-Security-Policy, the same policy byte for byte', () => {
    expect(headers.get('Content-Security-Policy')).toBe(ADMIN_CSP)
  })

  it('ships no CSP <meta> in the page: the header is the one delivery', () => {
    const index = readFileSync(fileURLToPath(new URL('../../../index.html', import.meta.url)), 'utf8')
    expect(index).not.toMatch(/http-equiv\s*=\s*["']?content-security-policy/i)
    const viteConfig = readFileSync(fileURLToPath(new URL('../../../vite.config.ts', import.meta.url)), 'utf8')
    expect(viteConfig).not.toMatch(/'http-equiv'/)
  })

  it('isolates the console from other origins: no opener handle either way, no no-cors read, no framing', () => {
    expect(headers.get('Cross-Origin-Opener-Policy')).toBe('same-origin')
    expect(headers.get('Cross-Origin-Resource-Policy')).toBe('same-origin')
    expect(headers.get('X-Frame-Options')).toBe('DENY')
  })

  it('grants the one browser feature the console uses, clipboard-write, to itself and switches the rest off', () => {
    const features = new Map(headers.get('Permissions-Policy')!.split(', ').map((f) => f.split('=') as [string, string]))
    for (const off of ['camera', 'microphone', 'geolocation', 'fullscreen', 'payment', 'usb', 'display-capture'])
      expect(features.get(off), off).toBe('()')
    expect([...features.values()].every((v) => v === '()' || v === '(self)')).toBe(true)
    expect([...features.entries()].filter(([, v]) => v === '(self)').map(([k]) => k)).toEqual(['clipboard-write'])
  })
})
