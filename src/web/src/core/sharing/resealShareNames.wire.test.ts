// A root rename's share-name re-seal on the wire (uc.sharing.share-public-viewer:root-rename-reseals-share-names, ADR
// 0080 decision 14): recorded at `fetch` — the JSON the real generated client serializes, no mock of the API client —
// the list is read for the node, and each share gets one `PUT /api/shares/{shareId}/sealed-name` whose body is exactly
// `{sealedName}`: no note, label, role, expiry, password or key rides along (the operation changes the name only, and
// must not look like an `updateShare`), and the name is the new one under that share's `K_share`.
import { describe, it, expect, vi, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { mintShare, resealShareNames } from './manageShares'
import { SHARE_MEMORY_NAME } from './resolvePublicShare'
import { ShareRole } from '../../api/generated/sharing'
import type { ScopeKey } from '../../features/spaces/epochs'

const SCOPE_ID = '22222222-2222-4222-8222-222222222222'
const NODE_ID = '5c09e000-0000-4000-8000-0000000000b1'
const EPOCHS: ScopeKey = { epoch: 0, key: v1.randomBytes(32) }

afterEach(() => vi.unstubAllGlobals())

type Recorded = { url: string; method: string; body: unknown }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('resealShareNames on the wire', () => {
  it('lists the node\'s shares, then PUTs exactly {sealedName} to each share\'s sealed-name', async () => {
    // A real share row: mint through the real client and keep what it posted (its recovery copy).
    const calls: Recorded[] = []
    let row: Record<string, unknown> = {}
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
      calls.push({ url: String(input), method: init?.method ?? 'GET', body })
      if (init?.method === 'POST') {
        row = {
          shareId: body!.shareId, nodeId: NODE_ID, scopeId: SCOPE_ID, role: 'READ', sealedName: body!.sealedName,
          sealedShareKey: body!.sealedShareKey, wrappedRecoveryValueKey: body!.wrappedRecoveryValueKey,
          sealedShareKeyEpoch: body!.sealedShareKeyEpoch, enabled: true, mine: false,
          reissueRequired: false, deletable: false, newGuestsRefused: false, writeVerifierMissing: false, guestStops: [],
          createdAt: '2026-10-01T10:00:00Z',
        }
        return json({ shareId: body!.shareId }, 201)
      }
      if ((init?.method ?? 'GET') === 'GET') return json([row])
      return new Response(null, { status: 204 })
    }))
    const minted = await mintShare(SCOPE_ID, NODE_ID, { key: v1.randomBytes(32), epoch: 0 }, EPOCHS, 'Alt', ShareRole.Read)
    // the mint's body carries the copy's value-key wrap beside the copy (ADR 0115): what the listing serves back
    expect(typeof (calls[0].body as Record<string, unknown>).wrappedRecoveryValueKey).toBe('string')
    calls.length = 0

    const report = await resealShareNames(NODE_ID, 'Neu', EPOCHS)

    expect(report.resealed).toEqual([minted.shareId])
    expect(calls.map((c) => c.method)).toEqual(['GET', 'PUT'])
    expect(calls[0].url).toContain(`/api/shares?nodeId=${NODE_ID}`)
    expect(calls[1].url).toMatch(new RegExp(`/api/shares/${minted.shareId}/sealed-name$`))
    const body = calls[1].body as Record<string, unknown>
    expect(Object.keys(body)).toEqual(['sealedName'])
    expect(new TextDecoder().decode(v1.openField(SHARE_MEMORY_NAME, minted.kShare, fromB64std(body.sealedName as string)))).toBe('Neu')
  })
})
