// Assembling a share's **copyable link** — the one part of sharing that knows the deployment's origin.
//
// Split out of the share data layer deliberately (`./manageShares.ts`, and the Memories application's
// `features/memories/share.ts`, which re-exports this file from its old path so its dialogs and their mocks stay put). That
// layer is the sharing **data layer**: crypto plus the generated
// client, and the surface a headless suite drives end to end. A link is neither — `${origin}/s/{id}#k=…` is a
// presentation of a share, assembled where a human is about to copy it, and the origin is a property of the
// browser the curator happens to be sitting in, not of the share.
//
// The split has a measured cost behind it, not a taste argument. While `createShare` built the link eagerly,
// `share.ts` could not be imported outside a DOM at all — and every `*.e2e.test.ts` runs under Vitest's default
// **node** environment (there is no `test.environment` in `src/web/vite.config.ts`; the DOM-needing unit suites opt
// in per file with `// @vitest-environment happy-dom`). So a sharing e2e suite died on
// `window is not defined` before its first assertion, and the fix was mistaken for a test affordance —
// a shim in the bootstrap — when it was a layering fault the whole time.
//
// Which is the rule this file exists to keep: **the share data layer (`manageShares.ts`, `features/memories/share.ts`)
// stays importable without a DOM.** If something here ever needs to move back, that is the signal a presentation
// concern is leaking into the data path again. (The terms-acceptance helpers in `resolvePublicShare.ts` are the
// deliberate exception — a `localStorage` flag genuinely is browser state, not a layering fault, and
// `uc.sharing.share-accept` is browser-only by design.)
import { b64url } from '@veyq/crypto/base64'
import { recoverShareKey, type RecoveryCopy, type ShareAddress } from './manageShares'
import type { ScopeKey } from '../../features/spaces/epochs'

/** Build the copyable link `/s/{shareId}#k=<base64url(K_share)>` — the fragment carries `K_share`, never sent. */
export function buildShareLink(shareId: string, kShare: Uint8Array): string {
  return `${window.location.origin}/s/${shareId}#k=${b64url(kShare)}`
}

/** Reconstruct a managed share's full copyable link by recovering `K_share` through the scope's epoch keys (ADR 0036
 *  point 4) — so a scope member can re-copy **any** existing link, not just the freshly-minted one. Null when the
 *  share carries no recoverable key or the recovery fails. */
export function recoverShareLink(share: ShareAddress, epochs: ScopeKey, copy: RecoveryCopy): string | null {
  if (!copy.sealedShareKey) return null
  try {
    return buildShareLink(share.shareId, recoverShareKey(epochs, share, copy))
  } catch {
    return null
  }
}
