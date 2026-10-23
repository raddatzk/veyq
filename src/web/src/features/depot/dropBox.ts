// The drop box (cr-files-application F3, ADR 0080 decision 11, docs/apps/depot.md § The drop box): a `DEPOT_DROPBOX`
// node a member shares WRITE_ONLY. Its guests hold no key of it — each names itself once with an identity key `K_up`
// of its own (handed over wrapped to the drop box's drop public key, `core/sharing/dropKey.ts`), gets ONE identity
// folder in the drop box (`ensureShareDropFolder`, sealed under `K_up`), and uploads its files there with the same
// guest job a folder contributor runs (`shareUpload.ts`), its folder being the only key of its context. A member opens
// every folder through the drop box's key (core's tree, `childEntryKey`).
//
//   member: createDropBox → mintDropBoxLink (the drop key is set on the node with the first link)
//   guest:  nameDropGuest (K_up) → [first file] ensureDropFolder (its folder, K_F under K_up) → dropGuestContext →
//           guestFileUploadJob
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { contentApi, publicSharesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import type { EpochKeys } from '../spaces/epochs'
import { createContentNode, openContentKey, sealName, slugTokenFor, wrapContentKey, type CreateNodeOptions, type NodeParent } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { mintShare, NO_NOTE, type MintedShare, type ShareRootKey } from '../../core/sharing/manageShares'
import { dropKeysForShare, mintIdentityKey, openDropPublicKey } from '../../core/sharing/dropKey'
import { forgetGuestSession, identityNameContext, loadGuestSession, saveGuestSession, type GuestIdentity } from '../../core/sharing/guestIdentity'
import { shareRootEpoch, type PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { GuestTreeError, type GuestTreeContext } from '../../core/sharing/guestTree'
import type { UploadJob } from '../../core/blob/queue'
import { decodeHttpError, HttpStatus, isCodedError } from '../../shared/httpError'
import { ShareRole, type ShareNode } from '../../api/generated/sharing'
import { publicShareFilesApi } from './api'
import { DROPBOX_KIND } from './kinds'

// --- the member ------------------------------------------------------------------------------------------------------

/** Create a drop box named `name` under `parent` (a folder, or the scope root): a node like any other, its key under
 *  its parent's. It takes guests only once a WRITE_ONLY link exists ({@link mintDropBoxLink}). */
export async function createDropBox(
  scopeId: string, epochs: EpochKeys, parent: NodeParent, name: string, opts: CreateNodeOptions = {},
): Promise<string> {
  return (await createContentNode(scopeId, epochs, parent, DROPBOX_KIND, name, undefined, opts)).nodeId
}

/** The drop box's sealed drop private key as the server holds it now — null until its first link. */
async function currentSealedDropKey(scopeId: string, nodeId: string): Promise<string | null> {
  const nodes = await withRefresh(() => contentApi().listNodes({ scopeId }))
  return nodes.find((n) => n.id === nodeId)?.sealedDropPrivateKey ?? null
}

/**
 * Mint a WRITE_ONLY link on the drop box (core's `mintShare`): no wrap — the link carries the drop box's drop PUBLIC key
 * under `K_share`, the node's existing drop key when it has one (every guest folder so far is sealed to it), else a
 * fresh one whose private half the server sets on the node in the same transaction. Two members minting the first
 * link at once: the server keeps one and refuses the other `drop_key_exists`, which re-reads the node and retries once.
 */
export function mintDropBoxLink(
  scopeId: string, nodeId: string, node: ShareRootKey, epochs: EpochKeys, nodeName: string, name = '', password = '',
  expiresAt: Date | null = null,
): Promise<MintedShare> {
  return mintShare(scopeId, nodeId, node, epochs, nodeName, ShareRole.WriteOnly, expiresAt, NO_NOTE, name, password, {
    dropFields: async (kShare, nodeKey) => dropKeysForShare(scopeId, nodeId, nodeKey, kShare, await currentSealedDropKey(scopeId, nodeId)),
    retryOnce: (e) => isCodedError(e, HttpStatus.conflict, 'drop_key_exists'),
  })
}

// --- the guest -------------------------------------------------------------------------------------------------------

/** A drop-box guest's session on its device: the platform's identity and token plus its own `K_up` (standard base64)
 *  and the name it gave — kept on the device only, so the page can say whose session it is and name the guest's folder
 *  when its first file goes out (the folder is made then, not when the guest names itself, inbox #385). A session saved
 *  before the name was kept has none. */
export type DropGuestSession = GuestIdentity & { identityKey: string; displayName?: string }

/** The saved session of `shareId`, when it is a drop-box guest's (it carries a `K_up`). */
export function loadDropGuest(shareId: string): DropGuestSession | null {
  const s = loadGuestSession<DropGuestSession>(shareId)
  return s?.identityKey ? s : null
}

/** Keep a drop-box guest's session on its device (localStorage, the platform's one session per link). Throws when the
 *  store refuses it. */
export function saveDropGuest(shareId: string, session: DropGuestSession): void {
  saveGuestSession(shareId, session)
}

/** "Not you?": forget this device's session of `shareId`, so the next visitor names itself afresh under a new identity.
 *  The old identity's folder and files stay in the drop box, untouched. */
export function forgetDropGuest(shareId: string): void {
  forgetGuestSession(shareId)
}

/**
 * Name yourself on a drop-box link (operator-blind): a fresh identity key `K_up` wrapped to the drop box's drop public
 * key (opened from the resolve with the link's `K_share`), the display name sealed under `K_up` bound to a client-minted
 * identity id, and an anonymous identity created with both. The caller keeps it as the link's one session
 * ({@link saveDropGuest}; the page does, a script need not). The write token proves the
 * guest holds the whole link (ADR 0076).
 */
export async function nameDropGuest(
  head: PublicShareHead, { name, pwToken }: { name: string; pwToken?: string },
): Promise<DropGuestSession> {
  const { shareId, scopeId } = head
  const sealedDropPublicKey = head.share.sealedDropPublicKey
  const dropBoxId = head.share.nodeId
  if (!sealedDropPublicKey || !dropBoxId) throw new v1.OpenRefused('drop-public-key')
  const kShare = head.kShare
  const identityId = crypto.randomUUID() // client-minted: the name and the key wrap are sealed bound to it (ADR 0072)
  const minted = mintIdentityKey(scopeId, dropBoxId, identityId, openDropPublicKey(kShare, sealedDropPublicKey))
  const sealedDisplayName = b64std(v1.sealField(identityNameContext(scopeId, shareId, identityId), minted.identityKey, v1.utf8(name)))
  const { contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    // `K_up` is sealed to the drop box's drop public key, which a re-key replaces: the root's epoch as read (ADR 0109 point 6)
    createShareIdentityRequest: { nodeEpoch: shareRootEpoch(head.share), identityId, sealedDisplayName, wrappedIdentityKey: minted.wrappedIdentityKey },
    xSharePassword: pwToken,
    xShareWrite: b64std(v1.shareWriteToken(kShare, shareId)),
  })
  const session: DropGuestSession = { identityId, contributorToken, identityKey: b64std(minted.identityKey), displayName: name }
  return session
}

/** A guest's identity folder as it opened: its id, its key `K_F` and the epoch its entry is sealed at. */
export type DropFolder = { nodeId: string; key: Uint8Array; epoch: number; placementRevision: number; created: boolean }

/**
 * The guest's own folder in the drop box: created on the first call — a fresh id and key `K_F`, the folder's name (the
 * guest's display name) and slug sealed under `K_up`, `K_F` wrapped under `K_up`, at the drop box's epoch as the link
 * serves it (`PublicShare.rootEpoch`: a create states the scope's current epoch, which a guest does not know — the root's
 * equals it unless the root is being re-keyed, which the server answers stale or pending, ADR 0109 point 6) — and the same
 * folder on every later call, whose stored wrap opens under `K_up` here.
 */
export async function ensureDropFolder(
  head: PublicShareHead, session: DropGuestSession, displayName: string, pwToken?: string,
): Promise<DropFolder> {
  const { shareId, scopeId } = head
  const dropBoxId = head.share.nodeId!
  const identityKey = fromB64std(session.identityKey)
  const address = { scopeId, nodeId: crypto.randomUUID(), epoch: shareRootEpoch(head.share), placementRevision: 0 }
  const folderKey = v1.randomBytes(32)
  const res = await publicShareFilesApi().ensureShareDropFolder({
    shareId,
    xSharePassword: pwToken,
    dropFolderRequest: {
      contributorToken: session.contributorToken,
      nodeId: address.nodeId,
      encryptedName: sealName(identityKey, address, displayName),
      slugToken: slugTokenFor(identityKey, dropBoxId, FOLDER_KIND, displayName),
      epoch: address.epoch,
      wrappedContentKey: wrapContentKey(identityKey, address, folderKey),
    },
  })
  const key = openContentKey(scopeId, identityKey, {
    id: res.nodeId, epoch: res.epoch, placementRevision: res.placementRevision, wrappedContentKey: res.wrappedContentKey,
  })
  return { nodeId: res.nodeId, key, epoch: res.epoch, placementRevision: res.placementRevision, created: res.created }
}

/**
 * The guest tree context a drop-box guest's uploads run in: its own folder is the one key it holds (core's guest create
 * seals a new file under it and the server bounds the create to that folder). No sibling is readable, so no name is
 * pre-checked: a taken one is the server's `slug_taken`. Nothing is ever re-resolved (the guest places nothing).
 */
export function dropGuestContext(head: PublicShareHead, session: DropGuestSession, folder: DropFolder, pwToken?: string): GuestTreeContext {
  const row: ShareNode = { id: folder.nodeId, kind: FOLDER_KIND, parentId: head.share.nodeId, orderIndex: 0, epoch: folder.epoch, placementRevision: folder.placementRevision, blockedByOperator: false }
  const parts = {
    head,
    rootId: folder.nodeId,
    keys: new Map([[folder.nodeId, folder.key]]),
    names: new Map<string, string>(),
    nodes: new Map<string, ShareNode>([[folder.nodeId, row]]),
  }
  return {
    ...parts,
    shareId: head.shareId,
    scopeId: head.scopeId,
    pwToken,
    contributorToken: session.contributorToken,
    reresolve: async () => parts,
  }
}

/**
 * The failure code a drop-box guest's upload ends on once the link no longer takes files from this guest (inbox #381):
 * revoked or deleted (`404`), switched to another password, or this guest's folder trashed (`403
 * share_not_contributable`). The guest page shows it as such — not as a generic failure, and never not at all.
 */
export const DROP_LINK_GONE = 'link_gone'

/** The server codes that mean the link is gone for this guest, at any of its doors (create, ensure, start, window,
 *  complete): a revoke between start and completion answers `upload_not_found`. */
const GONE_CODES: ReadonlySet<string> = new Set([
  'share_not_found', 'node_not_found', 'upload_not_found', 'share_not_contributable', 'share_password_incorrect',
])

/** Whether a failed drop-box call means the link is gone for this guest: one of {@link GONE_CODES} (as a guest tree
 *  refusal or a response body), or any `404`. A store's own refusal (`chunk_put_failed:403`) carries no response and
 *  is not that: the link may be fine, the upload failed. */
export async function isDropLinkGone(e: unknown): Promise<boolean> {
  if (e instanceof Error && e.message === DROP_LINK_GONE) return true
  if (e instanceof GuestTreeError) return GONE_CODES.has(e.code)
  const { status, code } = await decodeHttpError(e)
  return status === HttpStatus.notFound || (code !== null && GONE_CODES.has(code))
}

/**
 * A drop-box guest's queue job: `job`, ending on {@link DROP_LINK_GONE} when the link is gone, and remembering that in
 * `link` — every later run (the queue's automatic retry, the guest's other files) fails at once, with no request.
 * An abort stays an abort.
 */
export function dropGuestJob<R, X extends object>(job: UploadJob<R, X>, link: { gone: boolean }): UploadJob<R, X> {
  return {
    ...job,
    async run(ctx) {
      if (link.gone) throw new Error(DROP_LINK_GONE)
      try {
        return await job.run(ctx)
      } catch (e) {
        if (!ctx.signal.aborted && (await isDropLinkGone(e))) {
          link.gone = true
          throw new Error(DROP_LINK_GONE)
        }
        throw e
      }
    },
  }
}
