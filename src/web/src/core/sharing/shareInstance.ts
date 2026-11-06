// Whether a bookmark's page may show its share in place (ADR 0121 decision 4): decided by what the share's instance says
// of itself — the first use of capability discovery across instances (ADR 0032, ADR 0088). The page reads that instance's
// `/api/setup` (this origin's own needs no CORS; another's answers any origin while it lets its links be embedded,
// decision 3) and embeds only where
//  - this instance's own `core.share-embedding` is effective for the member's tenant (off: the CSP admits no other origin,
//    and every bookmark opens as in ADR 0120 — decision 2),
//  - the share's instance answered as a veyq instance does (a version, a capability list),
//  - its major equals this client's (ADR 0085: the one rule the web client has for a server), and
//  - it serves `core.public-shares` and `core.public-shares.embeddable` (a child is absent where its parent is off,
//    ADR 0088 decision 2, so the leaf alone would do; both are read, so a server that sent a leaf without its parent
//    is refused too).
// Anything else falls back to ADR 0120's open, with one line saying why ({@link EmbedRefusal}). A write's own capability
// (`.contribute`) is not asked: the share's server refuses a write it does not allow, as on the share's own page.
//
// The decision is ONE pure function ({@link embeddability}) over what was read; the read ({@link readShareInstance}) only
// fetches, through the generated client, and turns every failure into "no answer".
import type { SetupStatus } from '../../api/generated'
import { setupApi } from '../../api/client'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_EMBEDDABLE } from '../../features/platform/capabilities'

/** Why a share is not shown in place — each its own line on the bookmark's page (`node.bookmark.fallback.*`). */
export type EmbedRefusal =
  /** This instance switched `core.share-embedding` off (for the member's tenant). */
  | 'switchedOff'
  /** The share's instance gave no answer a veyq instance gives (the network, no CORS, not a veyq server). */
  | 'unreachable'
  /** It runs another major of veyq than this client. */
  | 'otherVersion'
  /** It serves no public links (`core.public-shares` off). */
  | 'noLinks'
  /** It does not let its links be shown on other instances (`core.public-shares.embeddable` off). */
  | 'notEmbeddable'

export type Embeddability = { embeds: true } | { embeds: false; reason: EmbedRefusal }

/** What the decision reads: this instance's switch, the share's instance's setup status as read (null: no answer), and
 *  the major this client speaks. */
export interface EmbedInputs {
  embedding: boolean
  setup: SetupStatus | null
  major: number
}

/** Whether the share may be shown in place (see the header). Pure; fails closed on every input it cannot read. */
export function embeddability({ embedding, setup, major }: EmbedInputs): Embeddability {
  if (!embedding) return { embeds: false, reason: 'switchedOff' }
  const served = setup && Array.isArray(setup.capabilities) ? setup.capabilities : null
  const theirs = setup?.version?.major
  if (!served || typeof theirs !== 'number') return { embeds: false, reason: 'unreachable' }
  if (theirs !== major) return { embeds: false, reason: 'otherVersion' }
  const ids = new Set(served.map((c) => c?.id))
  if (!ids.has(CORE_PUBLIC_SHARES)) return { embeds: false, reason: 'noLinks' }
  if (!ids.has(CORE_PUBLIC_SHARES_EMBEDDABLE)) return { embeds: false, reason: 'notEmbeddable' }
  return { embeds: true }
}

/** The setup status of the instance at `apiBase` (`''`: this origin), or null for any failure — a refusal, a network
 *  error, an answer the browser withheld for want of CORS. */
export async function readShareInstance(apiBase: string): Promise<SetupStatus | null> {
  try {
    return await setupApi(apiBase).getSetupStatus()
  } catch {
    return null
  }
}
