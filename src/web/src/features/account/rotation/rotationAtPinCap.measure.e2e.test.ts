// Inbox #538 live MEASUREMENT (VEYQ_E2E=1 and VEYQ_E2E_MEASURE=1): does an account rotation still fit the request body
// limit when the identity-pin store holds the contract's cap of 25,000 entries? REAL backend + MariaDB, REAL crypto, the
// REAL `rotateKeys` — the request measured is the one the client builds, not a body assembled here. ADR 0104
// § Consequences holds the decision the readings bought: the cap and the one request stay. The readings, against the
// server's default body limit of 10,485,760 bytes (`quarkus.http.limits.max-body-size` unset; `RequestTooLargeRoutes`
// answers it alike on every path):
//   - a rotation costs 384 bytes a pin and about 2,700 beside them, so at the cap it sends 9,602,700 bytes (91.6 %) and
//     is answered 200 — a margin of 883 KB;
//   - with every other field at its contract maximum (the identity bundle and its wrapped private key, both cold wraps,
//     the signature, the device register, the head) it would send about 9.70 MB (92.5 %), ≈ 27,000 pins' worth;
//   - securing the account adds fixed fields of 2,424 bytes and 39 bytes per scope this device cannot open (at most
//     10,000), measured with the real encoders: about 9,605,224 bytes at the cap with nothing unreadable;
//   - a body past the limit is `413 request_too_large`, answered on its `Content-Length` before authentication, with
//     nothing written; headless Chrome over HTTP/1.1 reads that answer while still sending (bodies of 1.1×, 2× and 10×
//     the limit, direct to the jar and through Traefik), so the client maps it to a refusal naming the Server Owner
//     (uc.account.rotate-keys E10).
// The worst case the contract admits is computed, not measured: `scripts/validate_rotation_shape.py` derives it from
// the contract's own caps and fails above the limit.
//
// **Opt-in, not in the default web-e2e run** (the `.measure.e2e.test.ts` suffix; `scripts/run_web_e2e.py` leaves it out
// unless the variable is set, and refuses to run it named without it). From a worktree, beside the owner's stack:
//   (cd src/backend && ./gradlew quarkusBuild)
//   VEYQ_E2E_MEASURE=1 VEYQ_PIN_ROTATION_POINTS=999,10000,20000,25000 VEYQ_PIN_ROTATION_OUT=<file> \
//   VEYQ_INFRA_DIR=<main checkout>/infra/docker VEYQ_JAR=$PWD/src/backend/build/quarkus-app/quarkus-run.jar \
//   VEYQ_PORT=<free> VEYQ_ADMIN_PORT=<free> \
//   python3 scripts/run_web_e2e.py src/web/src/features/account/rotation/rotationAtPinCap.measure.e2e.test.ts
// It answers once the body limit, the cap or a field's bound moves; CI holds the arithmetic in `IdentityPinsSliceTest`.
//
// The store is seeded through the store's own write, sealed in the documented format (`src/test/pinStore.ts`, the
// independent writer the unit tests use) under the account's current Master Key: one `replaceAll` of N entries for N
// made-up contacts. A pin is a fingerprint (ADR 0104 decision 3), so nobody needs to exist for it — and admitting
// 25,000 members to get there would take hours (the heavy stage's 999 took 48 minutes). Every entry seals to the same
// 296 base64 characters whatever it says, so the bytes are a real store's.
//
// The rotation is then taken at each point of `VEYQ_PIN_ROTATION_POINTS` (comma-separated, ascending; default the cap
// alone), re-seeding before each: the request body's exact length, its answer, the time of the request and of the whole
// rotation, and what the body's other fields cost. One line per point, `pin-rotation {json}`, on stdout (which the
// orchestrator's vitest shows only for a failed test) and appended to `VEYQ_PIN_ROTATION_OUT` when set. The suite
// asserts what ADR 0104 claims: a rotation at the cap is answered 200 and leaves every entry re-keyed under the new
// Master Key. Its bite is the server's own limit, passed to the jar through the orchestrator's environment:
// `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=9601500` lets the seed of 25,000 entries land and refuses the cap's rotation with
// `413 request_too_large` (measured 2026-10-26, inbox #538). A lower limit refuses the seed already, which an undici
// client still sending sees as `ECONNRESET`, not as the 413 — a browser reads the 413 (above, inbox #553).
import { beforeAll, describe, it, expect } from 'vitest'
import { fromB64std } from '@veyq/crypto/base64'
import { createHash } from 'node:crypto'
import { appendFileSync } from 'node:fs'
import { accountIdentityPinsApi } from '../../../api/client'
import { masterKey } from '../../../app/vault'
import { mintSecretKey, rotateKeys } from './rotateKeys'
import { getPin } from '../identityPins'
import { birthInstance } from '../../../test/e2eBootstrap'
import { pinIdOf, sealPinEntry, sealPinHead } from '../../../test/pinStore'
import { recordCanaries } from '../../../test/canaries'
import { registry } from '../../../core/applications/registry'
import { registerMemoriesLifecycle } from '../../../test/memoriesLifecycle'
import { MEMORY_KIND } from '../../memories/kinds'
import { rekeyMemoryContent } from '../../memories/rekey'
import { testKind } from '../../../test/testApplication'

/** The contract's cap (`IdentityPinsWrite.put.maxItems`). */
const CAP = 25_000

const POINTS = (process.env.VEYQ_PIN_ROTATION_POINTS ?? String(CAP)).split(',').map((p) => Number(p.trim()))

// As scopeRotation.e2e: `rotateKeys` fails closed without a registered `onMasterKeyRotated`, and the owner's sole-member
// tenant is re-keyed after the publish, which needs the Memories kind's `rekeyContent`.
beforeAll(() => {
  registerMemoriesLifecycle()
  if (!registry.kind(MEMORY_KIND).supported) testKind(registry, { kind: MEMORY_KIND, icon: 'images', rekeyContent: rekeyMemoryContent })
})

/** One reading of a rotation request, taken off the wire by [watchRotation]. */
type Sent = { bytes: number; status: number; ms: number; body: string; answer: string }

/** Wraps the global fetch (the generated client resolves it at call time, `wireRecorder.ts` says why that holds in node)
 *  and keeps the rotation's request as sent; every other call passes through untouched. */
function watchRotation(): { sent: Sent[]; restore: () => void } {
  const sent: Sent[] = []
  const inner = globalThis.fetch
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (!(init?.method === 'POST' && new URL(url).pathname === '/api/account/rotation')) return inner(input, init)
    const body = typeof init.body === 'string' ? init.body : ''
    const started = performance.now()
    const res = await inner(input, init)
    const answer = await res.clone().text()
    sent.push({ bytes: Buffer.byteLength(body, 'utf8'), status: res.status, ms: Math.round(performance.now() - started), body, answer })
    return res
  }
  return { sent, restore: () => { globalThis.fetch = inner } }
}

/** What each part of the rotation's body costs, in bytes of its JSON. */
function breakdown(body: string): Record<string, number> {
  const parsed = JSON.parse(body) as Record<string, unknown> & { identityPins: { put: unknown[]; head: string } }
  const size = (v: unknown) => Buffer.byteLength(JSON.stringify(v), 'utf8')
  const parts: Record<string, number> = {}
  for (const [k, v] of Object.entries(parsed)) parts[k] = size(v)
  const put = parsed.identityPins.put
  parts['identityPins.put'] = size(put)
  parts['identityPins.put per entry'] = put.length ? (size(put) - 2 - (put.length - 1)) / put.length + 1 : 0
  parts['identityPins.head'] = size(parsed.identityPins.head)
  parts['identityPins without put'] = parts.identityPins - parts['identityPins.put']
  parts['everything but put'] = Buffer.byteLength(body, 'utf8') - parts['identityPins.put']
  return parts
}

/** Replaces the caller's store with `userIds`' pins, sealed under the vault's current Master Key. */
async function seed(userIds: string[]): Promise<void> {
  const mk = masterKey()!
  const { revision } = await accountIdentityPinsApi().getIdentityPins()
  const entries = new Map<string, string>()
  const put = userIds.map((userId, i) => {
    const pinId = pinIdOf(mk, userId)
    const pin = { fingerprint: fingerprintOf(i), head: fingerprintOf(i), index: i, firstSeenAt: 1_760_000_000_000 + i, replaced: i % 2 === 1 }
    const entry = sealPinEntry(mk, userId, pin, pinId)
    entries.set(pinId, entry)
    return { pinId, entry }
  })
  await accountIdentityPinsApi().writeIdentityPins({
    identityPinsWrite: { revision, head: sealPinHead(mk, entries, revision + 1), put, remove: [], replaceAll: true },
  })
}

/** The `i`-th contact's fingerprint: 32 bytes as a real `fingerprintDigestB64` is, distinct per contact. */
const fingerprintOf = (i: number) => createHash('sha256').update(`veyq #538 contact ${i}`).digest('base64')

// The measurement's own gate (the header says why it is opt-in), beside the live suites' `VEYQ_E2E`.
describe.skipIf(!process.env.VEYQ_E2E || process.env.VEYQ_E2E_MEASURE !== '1')('#538 — a rotation of a full identity-pin store (live, real crypto, a measurement)', () => {
  it('rotates a store of 25,000 pins in one request the server accepts', async () => {
    expect(POINTS.every((p, i) => Number.isInteger(p) && p > 0 && p <= CAP && (i === 0 || p > POINTS[i - 1])), `points ${POINTS}`).toBe(true)
    const { owner } = await birthInstance('E2E Pin Cap')
    const userIds = Array.from({ length: CAP }, () => crypto.randomUUID())
    // Each rotation installs a new Secret Key (ADR 0060 Decision 2); the next one proves with it.
    let secretKey = owner.built.emergencyKit.secretKey

    for (const n of POINTS) {
      await seed(userIds.slice(0, n))
      const watch = watchRotation()
      const started = performance.now()
      const next = mintSecretKey()
      const outcome = await rotateKeys(owner.password, secretKey, { secretKeyText: next }).then(() => {
        secretKey = next
        return 'ok'
      }, (e: unknown) => String(e))
      const wholeMs = Math.round(performance.now() - started)
      watch.restore()
      const sent = watch.sent.at(-1)
      const reading = JSON.stringify({
        at: new Date().toISOString(), pins: n, bytes: sent?.bytes, status: sent?.status,
        answer: sent?.status === 200 ? undefined : sent?.answer, requestMs: sent?.ms, rotationMs: wholeMs,
        attempts: watch.sent.length, outcome, parts: sent ? breakdown(sent.body) : undefined,
      })
      console.log(`pin-rotation ${reading}`)
      if (process.env.VEYQ_PIN_ROTATION_OUT) appendFileSync(process.env.VEYQ_PIN_ROTATION_OUT, `${reading}\n`)
      expect(sent, `${n} pins: the rotation sent its request`).toBeTruthy()
      expect({ status: sent!.status, outcome }, `${n} pins: the rotation landed`).toEqual({ status: 200, outcome: 'ok' })
    }

    // What the last rotation stored opens under the NEW Master Key, every entry at the id its contact derives — read
    // back from the server by the module itself (`getPin` lists and verifies the whole set against the new head).
    const last = POINTS.at(-1)!
    const sample = [0, 1, Math.floor(last / 2), last - 1]
    const read = await Promise.all(sample.map((i) => getPin(userIds[i])))
    expect(read.map((p) => p?.fingerprint), 'the re-keyed store opens under the new key').toEqual(sample.map(fingerprintOf))
    expect(fromB64std(read[0]!.fingerprint).length).toBe(32)
    recordCanaries(read.map((p) => p!.fingerprint))
  }, 30 * 60_000)
})
