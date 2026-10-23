// The crypto micro-reading (cr-profile-where-the-time-goes, decision 3): AES-256-GCM over 4 MB and 64 MB through
// `@noble/ciphers` — the pure-JS AEAD the v1 suite seals content with (`src/packages/crypto/src/primitives.ts`) — against
// WebCrypto's in the same browser, with the same key, nonce and AAD, each sealed whole and as the chunk frames a blob is
// stored as (256 KiB of plaintext per frame, its own nonce, AAD = u32be(index) ‖ u32be(total); ADR 0013 §3). Both
// implementations are first held to the v1 conformance vectors (`primitives.aes256gcm`, `chunk_frame`) and to each
// other's ciphertext, so a fast wrong answer is no reading. A READING only: switching the suite's AEAD would change ADR
// 0017 and is an ADR and a CR of its own.
//
// It runs on a page of its own, not the app's: the tenant's origin (a secure context, which WebCrypto needs) answered by
// three routes — a blank document and noble's own module files from the checkout's node_modules — so no app code runs
// beside the reading and nothing reaches the instance. The routes serve before the window opens and nothing is fetched
// inside it. The numbers go to `<stem>.crypto.json`; the window around them is `profiled`'s, so the CPU profile shows
// where noble's time goes.
import { test, expect } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname } from 'node:path'
import { OUT, instance, profiled, spec } from './profiling'

const NOBLE = dirname(createRequire(import.meta.url).resolve('@noble/ciphers/aes.js'))
const VECTORS = JSON.parse(readFileSync(new URL('../../../contracts/crypto/vectors/v1.json', import.meta.url), 'utf8')) as {
  primitives: { aes256gcm: { key: string; nonce: string; aad: string; plaintext: string; ciphertext_and_tag: string } }
  chunk_frame: { contentKey: string; nonce: string; plaintext: string; index: number; total: number; frame: string }
  chunk_frame_size: { plaintextBytes: number }
}
const SIZES = [4 * 1024 * 1024, 64 * 1024 * 1024]

interface Timing {
  bytes: number
  layout: 'whole' | 'framed'
  impl: 'noble' | 'webcrypto'
  sealMs: number
  openMs: number
  sealMiBps: number
}

test('crypto: AES-256-GCM through @noble/ciphers against WebCrypto, 4 MB and 64 MB', async ({ browser }) => {
  const { tenantDomain } = instance()
  const origin = `https://${tenantDomain}`
  const context = await browser.newContext()
  await context.route(`${origin}/__profile/index.html`, (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset="utf-8"><title>AEAD reading</title>' }))
  await context.route(`${origin}/__profile/noble/*.js`, (route) =>
    route.fulfill({ contentType: 'text/javascript', path: `${NOBLE}/${new URL(route.request().url()).pathname.split('/').pop()}` }))
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await page.goto(`${origin}/__profile/index.html`)
  // Loaded before the window: the module fetches are the routes', and a first call compiles what the reading then runs.
  // A module script of the page's own, not an `import()` inside an evaluated function, which the test's transpiler may
  // rewrite.
  await page.addScriptTag({ type: 'module', content: "import { gcm } from '/__profile/noble/aes.js'; window.__noble = { gcm }" })
  await page.waitForFunction(() => (window as unknown as { __noble?: unknown }).__noble !== undefined)

  let timings: Timing[] = []
  let conformance: Record<string, boolean> = {}
  await profiled(browser, page, cdp, async () => {
    const result = await page.evaluate(async ({ vectors, sizes }) => {
      // WebCrypto takes an ArrayBuffer-backed view; every buffer here is one.
      type Bytes = Uint8Array<ArrayBuffer>
      const { gcm } = (window as unknown as { __noble: { gcm: (k: Bytes, n: Bytes, a: Bytes) => {
        encrypt(p: Bytes): Bytes; decrypt(c: Bytes): Bytes } } }).__noble
      const hex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (b) => parseInt(b, 16))
      const toHex = (b: Bytes) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
      const same = (a: Bytes, b: Bytes) => a.length === b.length && a.every((x, i) => x === b[i])
      const subtleKey = (raw: Bytes) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt'])
      const subtleSeal = async (key: CryptoKey, iv: Bytes, aad: Bytes, pt: Bytes) =>
        new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, key, pt))
      const subtleOpen = async (key: CryptoKey, iv: Bytes, aad: Bytes, ct: Bytes) =>
        new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: aad, tagLength: 128 }, key, ct))
      const u32be = (n: number) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n >>> 0, false); return b }
      const frameAad = (index: number, total: number) => { const a = new Uint8Array(8); a.set(u32be(index)); a.set(u32be(total), 4); return a }

      // The conformance vectors, through both.
      const v = vectors.primitives.aes256gcm
      const f = vectors.chunk_frame
      const fNonce = hex(f.nonce)
      const conformance = {
        nobleAead: toHex(gcm(hex(v.key), hex(v.nonce), hex(v.aad)).encrypt(hex(v.plaintext))) === v.ciphertext_and_tag,
        webcryptoAead: toHex(await subtleSeal(await subtleKey(hex(v.key)), hex(v.nonce), hex(v.aad), hex(v.plaintext))) === v.ciphertext_and_tag,
        nobleFrame: f.nonce + toHex(gcm(hex(f.contentKey), fNonce, frameAad(f.index, f.total)).encrypt(hex(f.plaintext))) === f.frame,
        webcryptoFrame: f.nonce + toHex(await subtleSeal(await subtleKey(hex(f.contentKey)), fNonce, frameAad(f.index, f.total), hex(f.plaintext))) === f.frame,
      }

      const random = (n: number) => {
        const b: Bytes = new Uint8Array(n)
        for (let o = 0; o < n; o += 65536) crypto.getRandomValues(b.subarray(o, Math.min(n, o + 65536)))
        return b
      }
      const key = random(32)
      const nonce = random(12)
      const aad = random(16)
      const sKey = await subtleKey(key)
      const frameBytes = vectors.chunk_frame_size.plaintextBytes
      // A frame's nonce: the run's nonce with the frame index in its last four bytes — distinct per frame, as the suite's
      // random nonces are; how a nonce is drawn costs nothing here.
      const frameNonce = (i: number) => { const n = nonce.slice(); n.set(u32be(i), 8); return n }

      type Sealed = Bytes[]
      const impls = {
        noble: {
          whole: async (pt: Bytes) => [gcm(key, nonce, aad).encrypt(pt)],
          wholeOpen: async (ct: Sealed) => gcm(key, nonce, aad).decrypt(ct[0]),
          framed: async (pt: Bytes) => {
            const total = Math.ceil(pt.length / frameBytes)
            const out: Sealed = []
            for (let i = 0; i < total; i++) out.push(gcm(key, frameNonce(i), frameAad(i, total)).encrypt(pt.subarray(i * frameBytes, (i + 1) * frameBytes)))
            return out
          },
          framedOpen: async (ct: Sealed) => { for (let i = 0; i < ct.length; i++) gcm(key, frameNonce(i), frameAad(i, ct.length)).decrypt(ct[i]) },
        },
        webcrypto: {
          whole: async (pt: Bytes) => [await subtleSeal(sKey, nonce, aad, pt)],
          wholeOpen: async (ct: Sealed) => subtleOpen(sKey, nonce, aad, ct[0]),
          framed: async (pt: Bytes) => {
            const total = Math.ceil(pt.length / frameBytes)
            const out: Sealed = []
            for (let i = 0; i < total; i++) out.push(await subtleSeal(sKey, frameNonce(i), frameAad(i, total), pt.subarray(i * frameBytes, (i + 1) * frameBytes)))
            return out
          },
          framedOpen: async (ct: Sealed) => { for (let i = 0; i < ct.length; i++) await subtleOpen(sKey, frameNonce(i), frameAad(i, ct.length), ct[i]) },
        },
      }

      // One warm-up of each path on a small buffer, so the reading is of compiled code.
      const warm = random(frameBytes * 2)
      for (const impl of Object.values(impls)) {
        await impl.wholeOpen(await impl.whole(warm))
        await impl.framedOpen(await impl.framed(warm))
      }

      const timings = []
      const agree: Record<string, boolean> = {}
      for (const bytes of sizes) {
        const pt = random(bytes)
        for (const layout of ['whole', 'framed'] as const) {
          const sealed: Record<string, Sealed> = {}
          for (const name of ['noble', 'webcrypto'] as const) {
            const impl = impls[name]
            const t0 = performance.now()
            const ct = await (layout === 'whole' ? impl.whole(pt) : impl.framed(pt))
            const t1 = performance.now()
            await (layout === 'whole' ? impl.wholeOpen(ct) : impl.framedOpen(ct))
            const t2 = performance.now()
            sealed[name] = ct
            timings.push({ bytes, layout, impl: name, sealMs: t1 - t0, openMs: t2 - t1, sealMiBps: bytes / 1048576 / ((t1 - t0) / 1000) })
          }
          agree[`${bytes}-${layout}`] = sealed.noble.length === sealed.webcrypto.length
            && sealed.noble.every((c, i) => same(c, sealed.webcrypto[i]))
        }
      }
      return { conformance: { ...conformance, ...Object.fromEntries(Object.entries(agree).map(([k, ok]) => [`agree-${k}`, ok])) }, timings }
    }, { vectors: VECTORS, sizes: SIZES })
    timings = result.timings
    conformance = result.conformance
  })

  const { stem, throttle, run } = spec()
  writeFileSync(`${OUT}/${stem}.crypto.json`, JSON.stringify({ run, throttle, conformance, timings }, null, 2))
  for (const [check, ok] of Object.entries(conformance)) expect(ok, `${check}: both implementations give the vector's bytes`).toBe(true)
  await context.close()
})
