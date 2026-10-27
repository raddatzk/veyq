// The two refusals a write that states a node's epoch can meet (ADR 0109 point 6), decoded once.
//
// Every write that seals under a node's key states that node's epoch as it read it (`nodeEpoch`, or `parentEpoch` for an
// entry under a parent's key). The server compares it with the node's stored one:
//  - `409 stale_epoch` — the node was re-keyed since the caller read it: what it sealed is under a key the node no longer
//    has. The remedy is to RE-READ (the tree, a guest's share) and rebuild the write under the node's key now — once.
//  - `409 node_rekey_pending` — the scope was rotated and this node is not re-keyed yet: only it waits. A MEMBER's write
//    pulls its path forward first (ADR 0111 decision 11): its `pull` re-keys the node and its lagging ancestors on this
//    device and the write is retried at once, re-reading; a guest's write (no `pull`) — and a member's whose pull cannot
//    run, a drop box only an owner re-keys — waits and tries again, by when the re-key has usually reached it.
// The device's own floor (layer 2, `core/node/rekey/floor.ts`) refuses a member write under a node whose epoch is below
// the scope's current one before anything is sent (`NodeKeyBelowFloorError`): the same situation as `node_rekey_pending`
// seen from the device, with the same remedy — wait, re-read, and the re-key has brought the node to the current epoch.
// A retry with the SAME key and epoch can never succeed once the re-key landed (stated < stored is `stale_epoch`), which
// is why a call site that can re-derive passes a builder that re-reads on every attempt after the first.
import { HttpStatus, isCodedError } from './httpError'

export const NODE_REKEY_PENDING = 'node_rekey_pending'
export const STALE_NODE_EPOCH = 'stale_epoch'

/** `node_rekey_pending` answered inside a 200 rather than as a `409` — one item of a batch (`completeMedia`'s per-item
 *  `error`): thrown by the transport that reads it, so the retry below treats it as the HTTP refusal. */
export class NodeRekeyPendingError extends Error {
  constructor() {
    super(NODE_REKEY_PENDING)
    this.name = 'NodeRekeyPendingError'
  }
}

/** The node a write named is not re-keyed since the scope's last rotation: pull or wait, and retry. */
export async function isRekeyPending(e: unknown): Promise<boolean> {
  return (e instanceof Error && e.name === 'NodeRekeyPendingError') || isCodedError(e, HttpStatus.conflict, NODE_REKEY_PENDING)
}

/** The node's epoch moved since the caller read it: re-read and rebuild. */
export function isStaleNodeEpoch(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, STALE_NODE_EPOCH)
}

/** The device floor refused the write (`core/node/rekey/floor.ts` `NodeKeyBelowFloorError`), recognised by its name so
 *  this helper does not import the node tree it sits below. */
export function isBelowNodeKeyFloor(e: unknown): boolean {
  return e instanceof Error && e.name === 'NodeKeyBelowFloorError'
}

/** A refusal that waiting cures: the server's `node_rekey_pending`, or the device floor's own refusal. */
export async function isRekeyPendingOrBelowFloor(e: unknown): Promise<boolean> {
  return isBelowNodeKeyFloor(e) || (await isRekeyPending(e))
}

/** The waits before each retry on `node_rekey_pending`: three retries, then the refusal is the caller's. */
export const REKEY_RETRY_DELAYS_MS: readonly number[] = [2_000, 5_000, 10_000]

export type RekeyRetryOptions = {
  /** The wait before each retry; its length is how many retries there are. */
  delaysMs?: readonly number[]
  signal?: AbortSignal
  /**
   * A MEMBER's write only: bring the written node's path to the scope's current epoch on this device (core's
   * `pullRekeyPath`), true when it did. Run once, on the first refusal; the write is then retried at once, re-reading,
   * and no wait is spent. False (a drop box only an owner re-keys, a device that cannot) falls back to the waits. A
   * guest's write never passes one: it holds no key above its root and waits for a member's device.
   */
  pull?: () => Promise<boolean>
}

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason)
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(timer)
      reject(signal!.reason)
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Run `attempt` and, while it fails with `409 node_rekey_pending` (or the device floor's `NodeKeyBelowFloorError`), pull
 * once when the options carry a `pull` — a member's write re-keys its own path and retries at once — and otherwise wait
 * (see {@link REKEY_RETRY_DELAYS_MS}) and run it again; when the waits are used up the last refusal is thrown as it came.
 * `attempt` gets its number (0 first): from 1 on it should re-read what it seals under, since the re-key has most likely
 * moved the node's key and epoch. Every other failure — `stale_epoch` included — is thrown at once.
 */
export async function retryWhileRekeying<T>(attempt: (n: number) => Promise<T>, opts: RekeyRetryOptions = {}): Promise<T> {
  const delays = opts.delaysMs ?? REKEY_RETRY_DELAYS_MS
  let pull = opts.pull
  let waited = 0
  for (let n = 0; ; n++) {
    try {
      return await attempt(n)
    } catch (e) {
      if (!(await isRekeyPendingOrBelowFloor(e))) throw e
      if (pull) {
        const once = pull
        pull = undefined
        if (await once().catch(() => false)) continue
      }
      if (waited >= delays.length) throw e
      await wait(delays[waited++], opts.signal)
    }
  }
}

/**
 * A write at a node's epoch, both refusals handled: `node_rekey_pending` as {@link retryWhileRekeying} (its `pull`
 * included, at most once per write), `stale_epoch` by one more attempt. `attempt(reread)` is told whether to re-read
 * first — false on the first run, true after either refusal — and builds the write from what it then holds. A second
 * `stale_epoch` is thrown.
 */
export async function writeAtNodeEpoch<T>(attempt: (reread: boolean) => Promise<T>, opts: RekeyRetryOptions = {}): Promise<T> {
  let staleRetried = false
  // the pull runs at most once per write, across the stale re-run too
  let pull = opts.pull
  const pullOnce = pull && (() => { const p = pull!; pull = undefined; return p() })
  for (;;) {
    try {
      return await retryWhileRekeying((n) => attempt(n > 0 || staleRetried), { ...opts, pull: pull && pullOnce })
    } catch (e) {
      if (staleRetried || !(await isStaleNodeEpoch(e))) throw e
      staleRetried = true
    }
  }
}
