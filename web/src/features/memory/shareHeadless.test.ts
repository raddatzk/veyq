// @vitest-environment node
//
// The standing guard behind `shareLink.ts`'s split: **`share.ts` is usable with no DOM at all.**
//
// The environment docblock is deliberate rather than redundant. `node` is Vitest's current default, but that
// default is exactly what the claim leans on — pinning it here keeps this file honest if a global
// `test.environment` is ever set, instead of quietly turning the guard vacuous.
//
// **Why this calls `createShare` instead of just importing the module.** The defect that bought this file was
// never an import-time failure: `import './share'` under node always worked. `window.location.origin` sat inside
// `buildShareLink`'s *body*, reached only when `createShare` assembled the returned link — so a sharing e2e suite
// imported the module fine and then died on `window is not defined` at its first curator call. An import-only
// assertion would have passed against the broken code, which is the one thing a guard must not do. So the guard
// exercises the path: a `typeof window` check first (so it cannot pass vacuously in a DOM environment), then a
// real `createShare` round-trip with only the network mocked.
//
// Its limit: it covers the *mint* path, which is where the fault was and where a headless suite starts. It does
// not sweep `share.ts` for other browser globals — `acceptedTermsVersion` / `recordTermsAccepted` still use
// `localStorage`, deliberately and documented (uc.sharing.share-accept is browser-only by design), so a future
// headless caller of those is out of this guard's reach and needs its own decision.
import { describe, it, expect, vi } from 'vitest'

vi.mock('../../api/client', () => ({
  sharesApi: vi.fn(),
  publicSharesApi: vi.fn(),
  tokenApi: vi.fn(),
}))

import { createShare } from './share'
import { sharesApi } from '../../api/client'
import * as v1 from '@veyq/crypto/v1'

const SCOPE_ID = '7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c'
const NODE_ID = '4a6e8b2c-1f2d-3e4a-5b6c-7c3f0e2a9b1d'

describe('share.ts is headless — the data layer never touches the DOM (see shareLink.ts)', () => {
  it('mints a share with no `window` in scope, returning K_share for the caller to build a link from', async () => {
    // Guard the guard: in a DOM environment the assertion below would prove nothing.
    expect(typeof window, 'this suite must run without a DOM or it asserts nothing').toBe('undefined')

    const create = vi.fn().mockResolvedValue({ shareId: 'share-headless' })
    vi.mocked(sharesApi).mockReturnValue({ createShare: create } as unknown as ReturnType<typeof sharesApi>)

    const kMem = v1.randomBytes(32)
    const epochs = { current: 0, keys: new Map([[0, v1.randomBytes(32)]]) }
    const minted = await createShare(SCOPE_ID, NODE_ID, kMem, epochs, 'Rom 2026')

    // the id is minted client-side (ADR 0072) — the one the request carried
    expect(minted.shareId).toBe(create.mock.calls[0][0].createShareRequest.shareId)
    expect(minted.kShare, 'the key comes back raw — assembling a URL is the UI edge, not this layer').toHaveLength(32)
    // and the server still saw only ciphertext on the way out
    expect(create.mock.calls[0][0].createShareRequest.sealedName).not.toContain('Rom')
  })
})
