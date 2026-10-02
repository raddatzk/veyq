// uc.memory.concurrent-curation (web) — the one write loop behind every sealed-meta writer (ADR 0077): a node's
// `wrappedMeta` (a Memory's date, place, cover) and a media's listed tier (corrected time, place name, media type).
// Any member writes these blobs WHOLE, and the server cannot merge what it cannot read, so the merge is ours:
// every write states the `metaVersion` it was built from; a `409 stale_version` hands back the current blob, and
// the writer re-runs ONLY its own change on it — the identity-pin loop (`account/identityPins.ts`).
//
// Two rules sit underneath, and each was a live defect (inbox #271, #273):
//  - a writer that re-sealed from its own cached copy erased what a peer wrote meanwhile (a member who merely OPENED
//    a Memory wiped a peer's place through the auto-date self-heal) — hence the version;
//  - a writer that treated a blob it could not open as empty re-sealed one field and dropped every sibling — hence
//    `MetaUnreadableError`: only an ABSENT blob is a fresh one.
import { HttpStatus, httpStatus } from '../../shared/httpError'

/** A sealed meta blob as listed: its ciphertext (null when none) and the version it is at (ADR 0077). */
export type SealedMetaState = { wrappedMeta: string | null; metaVersion: number }

/** Bounds the re-base/retry loop on a `409 stale_version` — the identity-pin store's bound. */
export const MAX_META_WRITE_ATTEMPTS = 5

/** A present blob the writer could not open or parse. Never rebuilt from empty — refused (ADR 0077 point 4). */
export class MetaUnreadableError extends Error {
  constructor() {
    super('meta_unreadable')
    this.name = 'MetaUnreadableError'
  }
}

/** An editor's OWN field changed underneath it (ADR 0077 point 3): no retry — the editor re-opens on `current`. */
export class MetaConflictError extends Error {
  constructor(readonly current: SealedMetaState) {
    super('meta_conflict')
    this.name = 'MetaConflictError'
  }
}

/** What a writer's edit sees: whether this is a re-run on a conflict body, and the state it is re-running on. */
export type MetaEditContext = { retry: boolean; state: SealedMetaState }

/**
 * The current state a `409 stale_version` carries (the `StaleSealedMeta` body), or null for any other failure —
 * a 409 with another code (`scope_deleting`, `slug_taken`) included. The body is read for exactly these fields.
 */
export async function staleMetaOf(e: unknown): Promise<SealedMetaState | null> {
  if (httpStatus(e) !== HttpStatus.conflict) return null
  try {
    const body = (await (e as { response: Response }).response.clone().json()) as {
      code?: unknown; wrappedMeta?: unknown; metaVersion?: unknown
    }
    if (body?.code !== 'stale_version' || typeof body.metaVersion !== 'number') return null
    return { wrappedMeta: typeof body.wrappedMeta === 'string' ? body.wrappedMeta : null, metaVersion: body.metaVersion }
  } catch {
    return null
  }
}

/** One writer's plumbing: how to open, seal and send its blob. The edit is the writer's own change. */
export type SealedMetaOps<M> = {
  /** Open the blob for a write: an absent one is fresh; a present one that does not open THROWS (never `{}`). */
  open: (wrappedMeta: string | null) => M
  /** The sealed blob to send, or null to clear it (a media tier with nothing left in it). */
  seal: (meta: M) => string | null
  send: (wrappedMeta: string | null, metaVersion: number) => Promise<void>
}

/** The outcome of a write: the state the server last confirmed or handed back, and whether this call wrote. */
export type SealedMetaResult<M> = { state: SealedMetaState; meta: M; wrote: boolean }

/**
 * Apply `edit` to the blob at `base` and store it, version-checked. On `409 stale_version` re-open the conflict
 * body's blob, re-run `edit` on it and retry — at most [MAX_META_WRITE_ATTEMPTS] times. `edit` returns null for
 * "no write" (a self-heal whose decision says so on the fresh blob, a backfill the fresh blob no longer needs) and
 * may throw [MetaConflictError] (an editor whose own field moved). The returned `state` is always the newest the
 * server confirmed or handed back, so the caller can patch its copy whether or not a write landed.
 */
export async function writeSealedMeta<M>(
  base: SealedMetaState,
  ops: SealedMetaOps<M>,
  edit: (meta: M, ctx: MetaEditContext) => M | null | Promise<M | null>,
): Promise<SealedMetaResult<M>> {
  let state = base
  for (let attempt = 0; ; attempt++) {
    const opened = ops.open(state.wrappedMeta)
    const next = await edit(opened, { retry: attempt > 0, state })
    if (next === null) return { state, meta: opened, wrote: false }
    const wrappedMeta = ops.seal(next)
    try {
      await ops.send(wrappedMeta, state.metaVersion)
      return { state: { wrappedMeta, metaVersion: state.metaVersion + 1 }, meta: next, wrote: true }
    } catch (e) {
      const fresh = attempt + 1 < MAX_META_WRITE_ATTEMPTS ? await staleMetaOf(e) : null
      if (!fresh) throw e
      state = fresh // re-base on what the server holds now, and re-run only our own change on it
    }
  }
}

/** JSON with sorted keys, so two writers' copies of the same sub-object compare equal whatever their key order. */
export function stableJson(value: unknown): string {
  if (value === undefined) return 'undefined'
  return JSON.stringify(value, (_k, v: unknown) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
      : v,
  )
}
