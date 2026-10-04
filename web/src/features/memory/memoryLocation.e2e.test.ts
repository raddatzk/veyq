import { describe, it, expect } from 'vitest'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { unsealNodeMeta } from './nodeMeta'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { listTree, openContentKey } from '../../core/node/tree'
import { createMemory } from './memoryNodes'
import { setMemoryDate, unsealMemoryDate } from './memoryDate'
import { setMemoryLocation, unsealMemoryLocation, computeMedoid } from './memoryLocation'
import { reverseGeocode, defaultLevel, type GeoLevel } from './geocode'
import { fromB64std } from '@veyq/crypto/base64'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Opt-in web location e2e (cr-0069) — the live path the mocked tests can't cover: the sealed `location`
 * round-trips through the **real backend + DB** operator-blind (no plaintext name/coord crosses), a date sealed
 * on the same node **survives** a location write (read-modify-write), a location removal keeps the date, a
 * pre-armed `auto` location is born at creation, and reverse geocoding hits a **real third-party provider**.
 *
 * Gated on `VEYQ_E2E=1`; default `npm run check` skips it. The instance is cold-birthed over the real API by
 * `src/test/e2eBootstrap.ts`, so this needs a FRESH instance and no seeder:
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d && (start backend)
 *   VEYQ_E2E=1 npx vitest run memoryLocation.e2e
 * Or `python3 scripts/run_web_e2e.py`, which is what CI runs.
 *
 * The geocode leg is OPT-IN via `VEYQ_GEOCODER` (e.g. https://nominatim.openstreetmap.org) and the orchestrator
 * leaves it unset on purpose: it makes a real third-party network call, which is a flake source in CI and reaches
 * the public internet from a harness that is supposed to run self-hosted. Unset, exactly one assertion drops (that
 * the geocoder returned levels) and every assertion about veyq still runs, with `nameSource: 'manual'`.
 */
const TENERIFE_A = { lat: 28.2916, lng: -16.6291 }
const TENERIFE_B = { lat: 28.4682, lng: -16.2546 }

describe.skipIf(!process.env.VEYQ_E2E)('memory location e2e (live, operator-blind seal)', () => {
  it('seals a location operator-blind, preserves the date (RMW), removes it, pre-arms at creation', async () => {
    await birthInstance('E2E Location')
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)!
    const scopeId = personal.id
    const scopeKey = (await openPersonalScope())!

    // --- a Memory with a date, then a location set on it ---
    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `E2E place ${Date.now()}`)
    const date = { mode: 'range' as const, from: '2026-05-01', to: '2026-05-09' }
    let meta = await setMemoryDate(scopeId, nodeId, memoryKey, date, { wrappedMeta: null, metaVersion: 0 })

    const medoid = computeMedoid([TENERIFE_A, TENERIFE_B])!
    // real reverse geocode (optional leg) — returns the address hierarchy; we pick the city level
    let name = 'Teneriffa'
    let level: GeoLevel['key'] = 'city'
    const geocoder = process.env.VEYQ_GEOCODER
    if (geocoder) {
      // A browser supplies its own User-Agent; in node we inject a descriptive one per the Nominatim usage policy.
      const uaFetch: typeof fetch = (input, init) =>
        fetch(input, { ...init, headers: { ...(init?.headers as Record<string, string>), 'User-Agent': 'veyq-dev-smoke/1.0 (dev@veyq.test)' } })
      const levels = await reverseGeocode(geocoder, medoid.lat, medoid.lng, 'de', uaFetch)
      expect(levels && levels.length).toBeTruthy()
      const picked = defaultLevel(levels!)!
      name = picked.name
      level = picked.key
      // the veyq backend was never in the loop — a third-party call only
      console.log(`[e2e] reverse-geocoded medoid → "${name}" (${level}); levels: ${levels!.map((l) => l.name).join(' › ')}`)
    }
    meta = await setMemoryLocation(scopeId, nodeId, memoryKey, {
      name, nameSource: geocoder ? 'geocoded' : 'manual', derived: medoid, derivedMode: 'auto',
      nameAnchor: { lat: medoid.lat, lng: medoid.lng, level },
    }, meta)

    // re-fetch from the server: the location AND the date both unseal (RMW kept the sibling)
    const fetched = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === nodeId)!
    const key = openContentKey(scopeId, scopeKey, fetched)!
    expect(unsealMemoryLocation(scopeId, key, fetched)?.name).toBe(name)
    expect(unsealMemoryDate(scopeId, key, fetched)).toEqual(date)

    // operator-blind: the stored ciphertext (echoed verbatim by listNodes) carries no plaintext name/coord
    const plain = new TextDecoder('utf-8', { fatal: false }).decode(fromB64std(fetched.wrappedMeta!))
    expect(plain).not.toContain(name)
    expect(plain).not.toContain('28.') // no latitude fragment
    expect(plain).not.toContain('2026-05-01') // no date either

    // --- remove the location: place gone, date intact ---
    const { wrappedMeta: removed } = await setMemoryLocation(scopeId, nodeId, memoryKey, null, meta)
    expect(unsealMemoryLocation(scopeId, memoryKey, { id: nodeId, wrappedMeta: removed })).toBeNull()
    expect(unsealMemoryDate(scopeId, memoryKey, { id: nodeId, wrappedMeta: removed })).toEqual(date)

    // --- pre-arm an auto location at creation (no derived yet) ---
    const born = await createMemory(scopeId, epochsOf(scopeKey), `E2E prearm ${Date.now()}`, null, {
      name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto',
    })
    const bornNode = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === born.nodeId)!
    const bornLoc = unsealMemoryLocation(scopeId, born.memoryKey, bornNode)
    expect(bornLoc).toEqual({ name: 'Boulder gym', nameSource: 'manual', derivedMode: 'auto', derived: undefined, nameAnchor: undefined })

    // The wire assertion's derivation (ADR 0053). The assertions above already check the ONE thing each call
    // returns; the canaries are the whole envelope of every node this suite sealed — both metas plus every node
    // name in the tree, which is where the geocoded/manual place name and the Memory titles live. Coordinates
    // are numbers and drop by type, which is why the suite still keeps its own `28.` substring assertion.
    const tree = await listTree(scopeId, epochsOf(scopeKey))
    recordCanaries(
      tree.map((n) => n.name),
      unsealNodeMeta(key, { scopeId, nodeId: fetched.id }, fetched.wrappedMeta),
      unsealNodeMeta(born.memoryKey, { scopeId, nodeId: bornNode.id }, bornNode.wrappedMeta),
    )
  })
})
