// Whether a bookmark's page may show its share in place (ADR 0121 decision 4): decided by what the share's instance says
// of itself — the first use of capability discovery across instances (ADR 0032, ADR 0088). The page reads that instance's
// `/api/setup` (this origin's own needs no CORS; another's answers any origin while it lets its links be embedded,
// decision 3) and embeds only where
//  - this instance's own `core.share-embedding` is effective for the member's tenant (off: the CSP admits no other origin,
//    and every bookmark opens as in ADR 0120 — decision 2),
//  - the share's instance answered as a veyq instance does (a version, a capability list),
//  - its major is one this client's embed speaks ({@link EMBED_MAJORS}: its own, plus every older one of ADR 0085's
//    window it carries a guest adapter for — decision 6's one exception — never a newer one), and
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
import { EXPECTED_MAJOR } from '../../shared/state/capabilities'

/** The majors this client's embed speaks: its own, plus each older major it carries a guest adapter for — the guest
 *  client generated from that major's contracts and that major's write formats (ADR 0085 decisions 5, 6 and 8). None is
 *  carried before a major 2 is released (ADR 0085 § Consequences), so today the list is this client's own major alone.
 *  A major goes in here ONLY WITH its adapter, never as a bare number: `shareInstance.test.ts`' tripwire is red from the
 *  moment VERSION moves to 2 until major 1's adapter is here (the train's step 1, infra/README § Cutting a release). */
export const EMBED_MAJORS: readonly number[] = [EXPECTED_MAJOR]

/** The older majors ADR 0085 decision 5's PLANNED window holds for a client of `major`: the two before it, released
 *  ones only (major 0 is pre-release and never gets an adapter). An unscheduled major widens the window past this
 *  (decision 4); these two are its floor, which is all the tripwire can know from VERSION. */
export function olderMajorsInWindow(major: number): number[] {
  return [major - 1, major - 2].filter((m) => m >= 1)
}

/** Why a share is not shown in place — each its own line on the bookmark's page (`node.bookmark.fallback.*`). */
export type EmbedRefusal =
  /** This instance switched `core.share-embedding` off (for the member's tenant). */
  | 'switchedOff'
  /** The share's instance gave no answer a veyq instance gives (the network, no CORS, not a veyq server). */
  | 'unreachable'
  /** It runs a major this client's embed does not speak (newer, or older than the window — {@link EMBED_MAJORS}). */
  | 'otherVersion'
  /** It serves no public links (`core.public-shares` off). */
  | 'noLinks'
  /** It does not let its links be shown on other instances (`core.public-shares.embeddable` off). */
  | 'notEmbeddable'

export type Embeddability = { embeds: true } | { embeds: false; reason: EmbedRefusal }

/** What the decision reads: this instance's switch, the share's instance's setup status as read (null: no answer), and
 *  the majors this client's embed speaks ({@link EMBED_MAJORS}). */
export interface EmbedInputs {
  embedding: boolean
  setup: SetupStatus | null
  majors: readonly number[]
}

/** Whether the share may be shown in place (see the header). Pure; fails closed on every input it cannot read. */
export function embeddability({ embedding, setup, majors }: EmbedInputs): Embeddability {
  if (!embedding) return { embeds: false, reason: 'switchedOff' }
  const served = setup && Array.isArray(setup.capabilities) ? setup.capabilities : null
  const theirs = setup?.version?.major
  if (!served || typeof theirs !== 'number') return { embeds: false, reason: 'unreachable' }
  if (!majors.includes(theirs)) return { embeds: false, reason: 'otherVersion' }
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
