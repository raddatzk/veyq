import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { buildCsp, DEFAULT_POLICY, REKA_STYLE_HASHES, VEYQ_POLICY } from './csp'
import { DEV_BREACH_ORIGIN, DEV_GEOCODER_ORIGIN, DEV_RATES_ORIGIN, DEV_TILE_ORIGIN } from '../state/mapDefaults'

// Guard the CSP the app ships with (cr-0133). These pins exist so a directive can't be silently loosened or a
// third-party origin baked in without a failing test — the policy is a security backstop, not incidental config.

// The built policy names no outside service: the backend appends the configured ones per response (ADR 0088 decision
// 7). The dev server passes the reference providers' origins, as `vite.config.ts` does.
const BUILT = { tileOrigin: null, geocoderOrigin: null, breachOrigin: null, ratesOrigin: null }
const DEV = { tileOrigin: DEV_TILE_ORIGIN, geocoderOrigin: DEV_GEOCODER_ORIGIN, breachOrigin: DEV_BREACH_ORIGIN, ratesOrigin: DEV_RATES_ORIGIN, dev: true }

const directive = (policy: string, name: string) =>
  policy.split(';').map((d) => d.trim().split(/\s+/)).find(([n]) => n === name)?.slice(1)

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

describe('buildCsp', () => {
  const csp = buildCsp(BUILT)

  it('locks scripts/objects/frames down — the key-exfil + XSS backstop', () => {
    // Exactly these two: our own module scripts, and compiling the WebP encoder's WebAssembly (ADR 0127).
    expect(directive(csp, 'script-src')).toEqual(["'self'", "'wasm-unsafe-eval'"])
    expect(directive(buildCsp(DEV), 'script-src')).toEqual(["'self'", "'wasm-unsafe-eval'"])
    expect(csp).toContain("default-src 'self'")
    expect(csp).toContain("object-src 'none'")
    expect(csp).toContain("frame-src 'none'")
    expect(csp).toContain("base-uri 'self'")
    expect(csp).toContain("form-action 'self'")
    expect(csp).toContain("worker-src 'self'")
    // no unsafe script execution anywhere: 'wasm-unsafe-eval' compiles WebAssembly, 'unsafe-eval' would evaluate strings
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-(eval|inline|hashes)'/)
    expect(buildCsp(DEV)).not.toMatch(/script-src[^;]*'unsafe-(eval|inline|hashes)'/)
  })

  it('allows only what the app genuinely needs (data:/blob:)', () => {
    expect(csp).toContain("img-src 'self' data: blob:")
    expect(csp).toContain("media-src 'self' blob:")
  })

  it("allows styles from its own files and reka's fixed <style> hashes, never 'unsafe-inline' (inbox #523)", () => {
    expect(directive(csp, 'style-src')).toEqual(["'self'", ...REKA_STYLE_HASHES])
    expect(csp).not.toContain('unsafe-inline')
    expect(buildCsp(DEV)).not.toContain('unsafe-inline')
  })

  it('names a style nonce only on the dev server, where Vite injects every stylesheet as a <style>', () => {
    expect(directive(buildCsp({ ...DEV, devStyleNonce: 'abc123' }), 'style-src')).toEqual(["'self'", ...REKA_STYLE_HASHES, "'nonce-abc123'"])
    expect(csp).not.toContain("'nonce-")
  })

  it("hashes exactly the <style> elements the reka-ui components this client imports render", () => {
    expect(rekaStyleHashes(fileURLToPath(new URL('../..', import.meta.url)))).toEqual([...REKA_STYLE_HASHES].sort())
    // A synchronous read of every source file (1720 files, 19 MB with the generated clients): 183 ms alone, but past
    // vitest's 5 s default when the suite's other workers contend for the disk (four times on 2026-11-05, load 8-17).
  }, 30_000)

  it('bakes NO third-party origin into the build: the backend appends the services in use (ADR 0088 decision 7)', () => {
    expect(csp).toContain("img-src 'self' data: blob:;")
    expect(csp).toContain("connect-src 'self';")
    expect(csp).not.toMatch(/openstreetmap|pwnedpasswords/)
  })

  it('the dev server allows the reference providers a new instance is seeded with', () => {
    const dev = buildCsp(DEV)
    expect(dev).toMatch(/img-src[^;]*https:\/\/tile\.openstreetmap\.org/)
    expect(dev).toMatch(/connect-src[^;]*https:\/\/nominatim\.openstreetmap\.org/)
    expect(dev).toMatch(/connect-src[^;]*https:\/\/api\.pwnedpasswords\.com/)
    expect(dev).toMatch(/connect-src[^;]*https:\/\/api\.frankfurter\.dev/)
  })

  it("forbids framing in dev and production alike (the policy is always a header, never a <meta>)", () => {
    expect(csp).toContain("frame-ancestors 'none'")
    expect(buildCsp(DEV)).toContain("frame-ancestors 'none'")
  })

  it('enforces Trusted Types in the build, with exactly the two policies the client creates (ADR 0117)', () => {
    expect(directive(csp, 'require-trusted-types-for')).toEqual(["'script'"])
    expect(directive(csp, 'trusted-types')).toEqual([VEYQ_POLICY, DEFAULT_POLICY])
    // No Vue pass-through policy, no second copy of a policy: a name or a keyword more is a door a string walks through.
    expect(csp).not.toMatch(/trusted-types[^;]*\b(vue|'allow-duplicates'|\*)/)
  })

  it('sends no Trusted Types directive from the dev server, whose Vite client writes its overlay with innerHTML', () => {
    expect(directive(buildCsp(DEV), 'require-trusted-types-for')).toBeUndefined()
    expect(directive(buildCsp(DEV), 'trusted-types')).toBeUndefined()
  })

  it('lets the dev server reach another instance, and leaves that to the backend in the build (ADR 0121)', () => {
    expect(directive(buildCsp(DEV), 'connect-src')).toEqual(expect.arrayContaining(['https:', 'http://*.localhost:*']))
    // The built policy: the backend appends `https:` per tenant while `core.share-embedding` is on (AppShellRoutes).
    expect(directive(csp, 'connect-src')).not.toContain('https:')
    expect((directive(csp, 'connect-src') ?? []).some((source) => source.includes('*'))).toBe(false)
  })

  it('adds the Vite HMR websocket only in dev', () => {
    expect(buildCsp(DEV)).toMatch(/connect-src[^;]*\bws:/)
    expect(csp).not.toMatch(/connect-src[^;]*\bws:/)
  })
})
