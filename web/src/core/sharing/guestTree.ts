// A READ_WRITE guest's tree operations on a share link (ADR 0080 decisions 2, 3, 6 and 10; cr-node-key-hierarchy 5b.4a):
// create a node inside the shared subtree, move or rename one, and take back an empty upload placeholder it gave up.
// Core's, like the member's (`core/node/tree.ts`): they carry no application semantics, and the server checks structure
// only (inside the subtree, the containers' owners, `NodeKindRules`) and opens nothing.
//
// **Keys come from the chain, never from `K_SC`.** A guest holds the root's key (the share's `wrap` under `K_share`) and
// every key below it (`walkSubtree`); it never holds the scope key, so it never writes at the scope root. Every write
// here seals under a key of the walk: a new node's key, name and slug under its PARENT's key; a placement's under the
// DESTINATION's key (the share root included — never the root as the source: its entry lives under a key the guest does
// not hold, ADR 0080 decision 9).
//
// **Epochs and versions (ADR 0080 decisions 3, 4 and 6).** A guest's create states the epoch it sealed with: the
// parent's served epoch (any epoch at or below the scope's current one is stored as sent, and the parent's is one the
// scope has had). A placement states none — the node keeps its own, so it is sealed at the node's served epoch — and
// binds the next placement revision, which the server compare-and-sets; a `409 stale_placement` re-resolves and rebuilds
// once ({@link PLACEMENT_RETRIES}), then {@link PlacementConflictError}.
//
// **Errors by code, not status.** One status carries several refusals here (409: `slug_taken`, `stale_epoch`, `id_taken`,
// `stale_placement`; 422: `illegal_structure`, `illegal_scope`; 403: `share_not_contributable`,
// `share_password_incorrect`), so every refusal is read for its `code` ({@link GuestTreeError},
// {@link guestTreeErrorMessageKey}). A name that collides with a sibling the guest can read is refused before sending,
// naming that sibling; the server's `409 slug_taken` stays the fallback (a sibling the guest cannot read).
import * as v1 from '@veyq/crypto/v1'
import { publicSharesApi } from '../../api/client'
import { sealName, slugTokenFor, wrapContentKey, PlacementConflictError, PLACEMENT_RETRIES } from '../node/tree'
import { expectMintedId } from '../node/mintedId'
import type { NodeKind } from '../node/kind'
import { registry } from '../applications/registry'
import { decodeHttpError, HttpStatus, isCodedError } from '../../shared/httpError'
import { resolvePublicShare, ShareUnavailableError, type PublicShareHead } from './resolvePublicShare'
import { openShareRoot, walkSubtree, type SharedSubtree } from './shareTree'

/** What a resolve of the link opens: the head, the root's id, and every reachable node's key, name and served row
 *  ({@link SharedSubtree}; the root has a key and a row, no name). */
export type GuestTreeParts = SharedSubtree & { head: PublicShareHead; rootId: string }

/** What a guest's tree operation needs: the opened subtree, the link's identity (`contributorToken`) and password token,
 *  and how to re-read the subtree when a placement lost a race ({@link placeShareNodeAsGuest}). */
export type GuestTreeContext = GuestTreeParts & {
  shareId: string
  scopeId: string
  pwToken?: string
  contributorToken: string
  reresolve(): Promise<GuestTreeParts>
}

/** Open a resolved READ_WRITE (or READ) link's subtree: the root's key from the share's `wrap`, every descendant's key and
 *  name through the chain. Throws {@link ShareUnavailableError} when the link carries no wrap or no root. */
export function openGuestTreeParts(head: PublicShareHead): GuestTreeParts {
  const root = openShareRoot(head)
  const rootId = head.share.nodes.find((n) => n.parentId == null)?.id
  if (!rootId) throw new ShareUnavailableError('not_found')
  return { head, rootId, ...walkSubtree(head.scopeId, head.share.nodes, root.key) }
}

/** The context for a guest's tree operations on the resolved link `head`; a re-resolve reads the link again with the
 *  same fragment key and password token. */
export function guestTreeContext(
  head: PublicShareHead, { contributorToken, fragmentKey, pwToken }: { contributorToken: string; fragmentKey: string; pwToken?: string },
): GuestTreeContext {
  return {
    ...openGuestTreeParts(head),
    shareId: head.shareId,
    scopeId: head.scopeId,
    pwToken,
    contributorToken,
    reresolve: async () => openGuestTreeParts(await resolvePublicShare(head.shareId, fragmentKey, pwToken)),
  }
}

/** The refusals a guest's tree operation answers, by the server's `code`. */
export const GUEST_TREE_CODES = [
  'slug_taken', 'stale_epoch', 'id_taken', 'illegal_structure', 'illegal_scope', 'share_not_contributable',
  'share_password_incorrect', 'share_not_found', 'node_not_found', 'stale_placement', 'rate_limited', 'not_a_placeholder',
] as const
export type GuestTreeCode = (typeof GUEST_TREE_CODES)[number]

/** A guest tree operation refused, by its code. For `slug_taken` found before sending, `siblingName` is the name of the
 *  sibling the new name collides with; null when the server found it (a sibling the guest cannot read). */
export class GuestTreeError extends Error {
  constructor(readonly code: GuestTreeCode, readonly siblingName: string | null = null) {
    super(siblingName === null ? code : `${code}: collides with '${siblingName}'`)
    this.name = 'GuestTreeError'
  }
}

/** A failed call as a {@link GuestTreeError} when the server named one of {@link GUEST_TREE_CODES} — read from the body's
 *  `code`, whatever the status; anything else (a network failure, another code) unchanged. */
export async function guestTreeErrorOf(e: unknown): Promise<unknown> {
  if (e instanceof GuestTreeError) return e
  const { code } = await decodeHttpError(e)
  return code !== null && (GUEST_TREE_CODES as readonly string[]).includes(code) ? new GuestTreeError(code as GuestTreeCode) : e
}

/** The i18n key (and its parameters) a guest is shown for a failed tree operation. */
export function guestTreeErrorMessageKey(err: unknown): { key: string; params?: Record<string, string> } {
  if (err instanceof PlacementConflictError) return { key: 'share.folder.placementConflict' }
  if (!(err instanceof GuestTreeError)) return { key: 'share.folder.createFailed' }
  switch (err.code) {
    case 'slug_taken':
      return err.siblingName === null ? { key: 'share.folder.nameTakenHere' } : { key: 'share.folder.nameTaken', params: { name: err.siblingName } }
    case 'illegal_structure':
    case 'illegal_scope':
      return { key: 'share.folder.notAllowed' }
    case 'share_not_contributable':
      return { key: 'share.folder.notContributable' }
    case 'rate_limited':
      return { key: 'share.folder.rateLimited' }
    case 'stale_placement':
      return { key: 'share.folder.placementConflict' }
    case 'node_not_found':
    case 'share_not_found':
    case 'share_password_incorrect':
      return { key: 'share.folder.gone' }
    case 'stale_epoch':
    case 'id_taken':
    case 'not_a_placeholder': // an abandon's, which no guest is shown (`abandonShareNodeAsGuest`)
      return { key: 'share.folder.createFailed' }
  }
}

/** Refuse `name` for a node of `kind` under `parentId` when a sibling the guest can read already slugs to the same slug —
 *  naming that sibling. `self` is the node being placed (its own entry never collides with itself). It compares what the
 *  server compares: siblings' tokens share the parent's key and slot, so equal tokens are equal slugs, each folded by
 *  ITS OWN kind's function (`registry.slugFor`, inbox #350) — whatever the two kinds are. */
function assertNameFree(parts: SharedSubtree, parentId: string, kind: NodeKind, name: string, self?: string): void {
  const slug = registry.slugFor(kind, name)
  for (const sibling of parts.nodes.values()) {
    if (sibling.parentId !== parentId || sibling.id === self) continue
    const siblingName = parts.names.get(sibling.id)
    if (siblingName !== undefined && registry.slugFor(sibling.kind, siblingName) === slug) throw new GuestTreeError('slug_taken', siblingName)
  }
}

/**
 * Create a node of `kind` named `name` under `parentId` (a node of the subtree, the root included) as a guest: a fresh
 * random content key wrapped under the PARENT's key, its name and slug token (slot: the parent's id) under that same key,
 * all bound to a client-minted id at the parent's served epoch and placement revision 0 (ADR 0080 decisions 1-3, 6).
 * Refuses a parent whose key the guest does not hold (`OpenRefused`) and a name a readable sibling already takes
 * ({@link GuestTreeError} `slug_taken`, naming it); a server refusal becomes a {@link GuestTreeError} by its code.
 */
export async function createShareNodeAsGuest(
  ctx: GuestTreeContext, { parentId, kind, name }: { parentId: string; kind: NodeKind; name: string },
): Promise<{ nodeId: string; contentKey: Uint8Array }> {
  const parentKey = ctx.keys.get(parentId)
  const parent = ctx.nodes.get(parentId)
  if (!parentKey || !parent) throw new v1.OpenRefused('content-key') // never K_SC: a guest writes only under keys it holds
  assertNameFree(ctx, parentId, kind, name)
  const contentKey = v1.randomBytes(32)
  const node = { scopeId: ctx.scopeId, nodeId: crypto.randomUUID(), epoch: parent.epoch, placementRevision: 0 }
  try {
    const res = await publicSharesApi().createShareNode({
      shareId: ctx.shareId,
      xContributorToken: ctx.contributorToken,
      xSharePassword: ctx.pwToken,
      createShareNodeRequest: {
        nodeId: node.nodeId,
        parentId,
        kind,
        encryptedName: sealName(parentKey, node, name),
        slugToken: slugTokenFor(parentKey, parentId, kind, name),
        epoch: node.epoch,
        wrappedContentKey: wrapContentKey(parentKey, node, contentKey),
      },
    })
    return { nodeId: expectMintedId(node.nodeId, res.nodeId), contentKey }
  } catch (e) {
    throw await guestTreeErrorOf(e)
  }
}

/**
 * Take back `nodeId` as a guest — the empty placeholder this guest created for an upload that was then refused (a full
 * link) or given up (`abandonShareNode`, ADR 0080 decision 6). The server erases it at once, but only this guest's own
 * — a node whose name its identity wrote last (ADR 0093 § 8) and on which no link is rooted — that holds nothing: no
 * child, no upload a member or another guest has open into it, and its kind's owner calls it empty (Depot: a file whose
 * slot never held a blob; core: a folder with no attachment and no sealed metadata); anything else is a
 * {@link GuestTreeError} `not_a_placeholder`, and nothing changes.
 */
export async function abandonShareNodeAsGuest(
  ctx: Pick<GuestTreeContext, 'shareId' | 'contributorToken' | 'pwToken'>, nodeId: string,
): Promise<void> {
  try {
    await publicSharesApi().abandonShareNode({
      shareId: ctx.shareId, nodeId, xContributorToken: ctx.contributorToken, xSharePassword: ctx.pwToken,
    })
  } catch (e) {
    throw await guestTreeErrorOf(e)
  }
}

/**
 * Move `nodeId` under `newParentId` as a guest, renaming it to `newName` when given — a rename is a placement to the
 * node's current parent (ADR 0080 decisions 3 and 10). The node's key is re-wrapped under the DESTINATION's key and its
 * name (the current one or `newName`) and slug token re-sealed under it, at the node's served epoch and the NEXT
 * placement revision; the request states the revision it was built on and no epoch. The share root is never the source.
 *
 * On `409 stale_placement` the link is re-resolved and the placement rebuilt on the node as it now is, at most
 * {@link PLACEMENT_RETRIES} time(s); then {@link PlacementConflictError}. Every other refusal is a {@link GuestTreeError}.
 */
export async function placeShareNodeAsGuest(
  ctx: GuestTreeContext, { nodeId, newParentId, newName }: { nodeId: string; newParentId: string; newName?: string },
): Promise<void> {
  let parts: GuestTreeParts = ctx
  for (let attempt = 0; ; attempt++) {
    try {
      await sendGuestPlacement(ctx, parts, nodeId, newParentId, newName)
      return
    } catch (e) {
      if (!(await isCodedError(e, HttpStatus.conflict, 'stale_placement'))) throw await guestTreeErrorOf(e)
      if (attempt >= PLACEMENT_RETRIES) throw new PlacementConflictError(nodeId)
      parts = await ctx.reresolve()
    }
  }
}

/** Build and send one guest placement against `parts` (see {@link placeShareNodeAsGuest}). */
async function sendGuestPlacement(
  ctx: GuestTreeContext, parts: GuestTreeParts, nodeId: string, newParentId: string, newName: string | undefined,
): Promise<void> {
  if (nodeId === parts.rootId) throw new GuestTreeError('illegal_structure') // its entry is under a key the guest lacks
  const node = parts.nodes.get(nodeId)
  const key = parts.keys.get(nodeId)
  const destinationKey = parts.keys.get(newParentId)
  const name = newName ?? parts.names.get(nodeId)
  if (!node || !key || !destinationKey || name === undefined || node.placementRevision == null) {
    throw new v1.OpenRefused('content-key') // nothing to re-wrap, or nothing to re-wrap it under
  }
  assertNameFree(parts, newParentId, node.kind, name, nodeId)
  const sealedAt = { scopeId: ctx.scopeId, nodeId, epoch: node.epoch, placementRevision: node.placementRevision + 1 }
  await publicSharesApi().updateShareNode({
    shareId: ctx.shareId,
    nodeId,
    xContributorToken: ctx.contributorToken,
    xSharePassword: ctx.pwToken,
    updateShareNodeRequest: {
      parentId: newParentId,
      encryptedName: sealName(destinationKey, sealedAt, name),
      slugToken: slugTokenFor(destinationKey, newParentId, node.kind, name),
      wrappedContentKey: wrapContentKey(destinationKey, sealedAt, key),
      placementRevision: node.placementRevision,
    },
  })
}
