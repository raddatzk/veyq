// One open Pot as its page holds it (ADR 0108, docs/apps/expenses.md): every revision read and opened, the Pot's settings
// from its sealed meta, the ledger and its check computed from them, the live poll, and the writes — for a member and a
// share guest alike, through the one session the page was opened with (inbox #401: the same page, functions removed).
//
// - **Reads.** A member's is core's revisioned list (`readMemberPot`, conditional, a delta merged by `applyPotChange`); a
//   guest's is the link's tagged read, replaced whole when its tag moved (`readGuestPot`). The client keeps no copy across
//   sessions (offline is out of the first cut).
// - **Writes.** One door, {@link PotSession.write}: the key a write seals under is a member's through core's floor
//   (`nodeKeyForWrite`) or the guest's from its opened link, and a guest names itself first (core's one naming flow,
//   `requestGuest`). An epoch refusal re-reads and rebuilds once (`writeAtNodeEpoch`, ADR 0109 point 6); a
//   `409 stale_revision` re-reads the Pot and is re-thrown, so the editor can show the other writer's version
//   (`expenses.entry.stale`) and the writer can save on top.
// - **Settings** (the Pot's `pot` meta) are a member's, written whole with the revision read (`writeNodeMeta`, ADR 0077).
import { computed, onScopeDispose, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { Access, TreeSource } from '../../core/access'
import type { TreeNode } from '../../core/node/tree'
import type { EpochKeys } from '../spaces/epochs'
import { nodeKeyForWrite } from '../../core/node/rekey/floor'
import { loadTree, treeStore } from '../../core/node/treeStore'
import { nodeMetaState, unsealNodeMeta } from '../../core/node/nodeMeta'
import { writeNodeMeta } from '../../core/node/nodeMetaWrite'
import { memberNodeMetaOptions } from '../../core/node/rereadNode'
import { createLivePoller } from '../../shared/state/livePoller'
import { ITEMS_POLL } from '../../core/items/itemsLive'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import { guestDisplayName, openIdentityName } from '../../core/sharing/guestIdentity'
import type { Person } from '../../core/people/directory'
import type { PotGuestWriter } from '../../api/generated/expenses'
import { applyPotChange, appendPotWrites, eraseParticipant, readGuestPot, readMemberPot, StaleRevisionError, type PotTarget, type PotWrite, type PotWriteKey } from './domain/potApi'
import { buildLedger, type PotLedger } from './domain/ledger'
import { checkLedger, type LedgerCheck } from './domain/heads'
import type { HeldRevision } from './domain/revisions'
import { POT_META_KEY, potMetaObject, potMetaOf, type PotMeta } from './domain/potMeta'

export interface PotSessionInput {
  access: () => Access
  scopeId: () => string
  node: () => TreeNode
  /** The Pot's key as the session's key chain opens it (null while the keys are not ready). */
  potKey: () => Uint8Array | null
  epochs: () => EpochKeys | null
  tree: () => TreeSource<TreeNode>
  /** Start the live poll (default true; a test turns it off). */
  live?: boolean
}

/** The visitor declined to name itself, so a guest's write was not sent. */
export class GuestDeclinedError extends Error {
  constructor() {
    super('guest_declined')
    this.name = 'GuestDeclinedError'
  }
}

/** The key a write needs is not open here (the scope is locked, the chain does not reach the Pot). */
export class PotKeyUnavailableError extends Error {
  constructor() {
    super('pot_key_unavailable')
    this.name = 'PotKeyUnavailableError'
  }
}

export interface PotSession {
  held: Readonly<Ref<readonly HeldRevision[]>>
  loaded: Readonly<Ref<boolean>>
  failed: Readonly<Ref<boolean>>
  /** The Pot's settings from its sealed meta, or null when it carries none that read. */
  meta: ComputedRef<PotMeta | null>
  /** The ledger over every held revision; null without settings (no base currency to count in). */
  ledger: ComputedRef<PotLedger | null>
  check: ComputedRef<LedgerCheck>
  /** Re-read (a delta for a member, the whole Pot for a guest when its tag moved). */
  refresh(): Promise<void>
  /** Append a batch (see the header). Throws `PotWriteRefused`, `StaleRevisionError` (after the re-read),
   *  {@link GuestDeclinedError}, {@link PotKeyUnavailableError}, or the server's refusal. */
  write(writes: readonly PotWrite[]): Promise<void>
  /** A member's erasure of a participant (`eraseParticipant`), then a re-read. */
  erase(participantId: string): Promise<void>
  /** A member's change of the Pot's settings: [change] runs on the settings the server holds now (re-run on a conflict). */
  writeMeta(change: (current: PotMeta | null) => PotMeta): Promise<void>
  /** Who wrote a revision, for a member's history: a member's name, a guest's marked as a guest's, or null (unknown). */
  writerName(writer: string | undefined, people: ReadonlyMap<string, Person> | null): string | null
  stop(): void
}

export function usePotSession(input: PotSessionInput): PotSession {
  const held = shallowRef<readonly HeldRevision[]>([])
  const loaded = ref(false)
  const failed = ref(false)
  const guestWriters = shallowRef<ReadonlyMap<string, PotGuestWriter>>(new Map())
  const nodeId = () => input.node().id
  // What the next member poll echoes, and the guest's last tag.
  let cursor: { revision: number; deleteRevision: number; since?: string } | null = null
  let guestTag: string | null = null
  let generation = 0

  const meta = computed<PotMeta | null>(() => {
    const key = input.potKey()
    if (!key) return null
    return potMetaOf(unsealNodeMeta(key, { scopeId: input.scopeId(), nodeId: nodeId() }, input.node().wrappedMeta))
  })
  const ledger = computed(() => (meta.value ? buildLedger(held.value, meta.value.base) : null))
  const check = computed(() => checkLedger(held.value))

  const pot = () => {
    const potKey = input.potKey()
    if (!potKey) throw new PotKeyUnavailableError()
    return { scopeId: input.scopeId(), nodeId: nodeId(), potKey }
  }

  async function readMember(full: boolean): Promise<void> {
    const mine = generation
    const scopeId = input.scopeId()
    const from = full ? null : cursor
    const read = await readMemberPot(scopeId, nodeId(), from ? { ifNoneMatch: String(from.revision), since: from.since, sinceDelete: from.deleteRevision } : {})
    if (mine !== generation || read.notModified) return
    held.value = applyPotChange(full ? [] : held.value, read, pot())
    const writers = new Map(full ? [] : guestWriters.value)
    for (const w of read.guestWriters) writers.set(w.identityId, w)
    guestWriters.value = writers
    cursor = { revision: read.revision, deleteRevision: read.deleteRevision, since: read.cursor ?? from?.since }
  }

  async function readGuest(): Promise<void> {
    const access = input.access()
    if (access.mode !== 'guest') return
    const mine = generation
    const read = await readGuestPot(access.shareId, nodeId(), { pwToken: access.pwToken, ifNoneMatch: guestTag ?? undefined })
    if (mine !== generation || read === null) return
    held.value = applyPotChange([], { items: read.revisions, ids: null }, pot())
    guestTag = read.etag
  }

  async function refresh(): Promise<void> {
    try {
      if (input.access().mode === 'member') await readMember(cursor === null)
      else await readGuest()
      failed.value = false
    } catch (e) {
      if (!loaded.value) failed.value = true
      throw e
    } finally {
      loaded.value = true
    }
  }

  // --- the live poll ------------------------------------------------------------------------------------------------
  const poller = createLivePoller(ITEMS_POLL)
  if (input.live !== false) {
    // One tick, the read the session holds (a member's conditional delta, a guest's tagged read): a `304` is no work, a
    // failure rejects so the poller backs off (core's `ITEMS_POLL`, inbox #410).
    poller.start(async () => {
      if (!loaded.value || !input.potKey()) return 'skip'
      if (input.access().mode === 'member') await readMember(cursor === null)
      else await readGuest()
    })
  }
  const stop = () => {
    generation++
    poller.stop()
  }
  onScopeDispose(stop)

  // --- writes -------------------------------------------------------------------------------------------------------
  async function memberKey(reread: boolean): Promise<PotWriteKey> {
    if (reread) await loadTree()
    const epochs = input.epochs()
    const at = epochs ? nodeKeyForWrite(input.scopeId(), epochs, treeStore.nodes, nodeId()) : null
    if (!at) throw new PotKeyUnavailableError()
    return at
  }

  async function target(): Promise<{ target: PotTarget; key: (reread: boolean) => Promise<PotWriteKey> }> {
    const access = input.access()
    const scopeId = input.scopeId()
    if (access.mode === 'member') return { target: { kind: 'member', scopeId, nodeId: nodeId() }, key: memberKey }
    const guest = await access.requestGuest()
    if (!guest) throw new GuestDeclinedError()
    return {
      target: { kind: 'guest', scopeId, nodeId: nodeId(), shareId: access.shareId, contributorToken: guest.contributorToken, pwToken: access.pwToken },
      key: async (reread) => {
        if (reread) await access.reload()
        const key = input.potKey()
        if (!key) throw new PotKeyUnavailableError()
        return { key, epoch: input.node().epoch }
      },
    }
  }

  async function staleReread<T>(run: () => Promise<T>): Promise<T> {
    try {
      return await run()
    } catch (e) {
      if (e instanceof StaleRevisionError) await refresh().catch(() => {})
      throw e
    }
  }

  async function write(writes: readonly PotWrite[]): Promise<void> {
    const base = meta.value?.base
    if (!base) throw new PotKeyUnavailableError()
    const { target: to, key } = await target()
    await staleReread(() => writeAtNodeEpoch(async (again) => appendPotWrites(to, await key(again), held.value, writes, base)))
    await refresh().catch(() => {})
  }

  async function erase(participantId: string): Promise<void> {
    const base = meta.value?.base
    if (!base) throw new PotKeyUnavailableError()
    const scopeId = input.scopeId()
    await staleReread(() =>
      writeAtNodeEpoch(async (again) => eraseParticipant({ kind: 'member', scopeId, nodeId: nodeId() }, await memberKey(again), held.value, participantId, base)),
    )
    await refresh().catch(() => {})
  }

  async function writeMeta(change: (current: PotMeta | null) => PotMeta): Promise<void> {
    const at = await memberKey(false)
    const scopeId = input.scopeId()
    await writeNodeMeta(scopeId, nodeId(), at.key, nodeMetaState(input.node()), (envelope) => {
      envelope[POT_META_KEY] = potMetaObject(change(potMetaOf(envelope)))
      return envelope
    }, undefined, memberNodeMetaOptions(scopeId, nodeId()))
    await loadTree()
  }

  /** The Pot's key and its ancestors', nearest first: a READ_WRITE guest named itself under its link's root. */
  function* nameKeys(): Generator<Uint8Array> {
    const tree = input.tree()
    const seen = new Set<string>()
    for (let n: TreeNode | null = tree.node(nodeId()); n && !seen.has(n.id); n = n.parentId ? tree.node(n.parentId) : null) {
      seen.add(n.id)
      let key: Uint8Array | null = null
      try {
        key = tree.keyOf(n.id)
      } catch {
        key = null
      }
      if (key) yield key
    }
  }

  function writerName(writer: string | undefined, people: ReadonlyMap<string, Person> | null): string | null {
    if (!writer) return null
    if (writer.startsWith('user:')) return people?.get(writer.slice(5))?.displayName ?? null
    if (!writer.startsWith('share:')) return null
    const identityId = writer.slice(6)
    const sealed = guestWriters.value.get(identityId)
    const name = sealed ? openIdentityName(input.scopeId(), sealed.shareId, identityId, sealed.sealedName, nameKeys()) : null
    return name ? guestDisplayName(name) : null
  }

  return { held, loaded, failed, meta, ledger, check, refresh, write, erase, writeMeta, writerName, stop }
}
