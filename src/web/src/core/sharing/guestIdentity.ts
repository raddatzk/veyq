// A guest's named identity on a READ_WRITE link (ADR 0076; uc.sharing.share-contributor:sealed-name-anonymous-identity)
// — the platform's half, whatever the shared root's kind: the display name sealed under the root's key, the anonymous
// identity it creates and the session the guest's device keeps. Core's, because a folder link's guest names itself
// exactly as a Memory link's does; an application adds only what is its own (Memories: the WRITE_ONLY uploader, whose
// name is sealed under a key of its own wrapped to the drop key — ADR 0031 point 7 — and which therefore stays in
// `features/memories/share.ts`).
import { computed, ref, type ComputedRef } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { i18n } from '../../i18n'
import { resolvePublicShare, shareRootEpoch, type PublicShareHead } from './resolvePublicShare'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import { openShareRoot } from './shareTree'

/** A guest's display name, sealed bound to (scope, share, identity) (cr-0101, ADR 0072): under the shared root's key for
 *  a READ_WRITE guest, under its own `K_up` for an uploader. Who reads it opens it with the same context. */
export const identityNameContext = (scopeId: string, shareId: string, identityId: string) =>
  ({ purpose: 'share-identity-name', scopeId, shareId, identityId }) as const

/**
 * A guest's display name as a MEMBER reads it (inbox #431): `sealedName` (standard base64, as a member's listing serves
 * it) opened under the first of `keys` it opens under, or null when none does. A READ_WRITE guest named itself under its
 * link's ROOT key, which need not be the node the member looks at — a folder link's guest who uploads below the folder —
 * so the caller passes the node's own key and its ancestors', NEAREST FIRST, as the session holds them through the chain
 * (`contentKeyChain`); a WRITE_ONLY guest's name opens only under its own `K_up`. Every application reads a guest's name
 * here: Memories' contributor buckets (`features/memories/mediaBuckets.ts`) and Depot's uploader (`features/depot/uploader.ts`).
 */
export function openIdentityName(
  scopeId: string, shareId: string, identityId: string, sealedName: string, keys: Iterable<Uint8Array>,
): string | null {
  let sealed: Uint8Array
  try {
    sealed = fromB64std(sealedName)
  } catch {
    return null // not base64: nothing to open
  }
  const context = identityNameContext(scopeId, shareId, identityId)
  for (const key of keys) {
    try {
      return new TextDecoder().decode(v1.openField(context, key, sealed))
    } catch {
      /* not under this key — the next one up */
    }
  }
  return null
}

/**
 * A guest's self-chosen `name` as a MEMBER is shown it: marked as a guest's - "Ben (guest)", "Ben (Gast)" (inbox #433).
 * A guest names itself freely, so a bare name could pass for a member's ("Kevin Raddatz"); every place a member reads a
 * guest's name - Depot's uploader line, Memories' lightbox uploader and contributor buckets - goes through here, so the
 * mark cannot be left out in one of them. Never for a guest's own view ("You are here as Ben" stays), and never for a
 * fallback that already says "a guest". Reactive to the language where it is read inside a computed or a template.
 */
export function guestDisplayName(name: string): string {
  return i18n.global.t('node.guestName', { name })
}

/** What creating an identity yields: the client-minted id and the capability token every guest write presents — and,
 *  once saved, the name the guest gave (`displayName`, kept on its device only: the server holds it sealed, and the
 *  page's "Not you?" line says whose session it is). A session saved before names were kept carries none. */
export type GuestIdentity = { identityId: string; contributorToken: string; displayName?: string }

/**
 * Name yourself on a READ_WRITE link (operator-blind): the display name sealed under the shared root's key `rootKey`,
 * bound to a client-minted identity id, and an anonymous identity created with it. The request proves the guest holds the
 * whole link, not only its id (ADR 0076): `X-Share-Write` carries the token derived from `fragmentKey` (the link's
 * `#k=`), whose hash the share stores; `pwToken` is the password gate's guest token (cr-0164). `rootEpoch` is the epoch
 * `rootKey` was minted at (`PublicShare.rootEpoch`), which the request states (ADR 0109 point 6). The server never sees
 * the name, the key or the person.
 */
export async function createGuestIdentity(
  head: Pick<PublicShareHead, 'shareId' | 'scopeId'>,
  rootKey: Uint8Array,
  { name, fragmentKey, pwToken, rootEpoch }: { name: string; fragmentKey: string; pwToken?: string; rootEpoch: number },
): Promise<GuestIdentity> {
  const { shareId, scopeId } = head
  const identityId = crypto.randomUUID() // client-minted: the name is sealed bound to it (ADR 0072)
  const sealedDisplayName = b64std(v1.sealField(identityNameContext(scopeId, shareId, identityId), rootKey, v1.utf8(name)))
  const { contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    createShareIdentityRequest: { identityId, sealedDisplayName, nodeEpoch: rootEpoch },
    xSharePassword: pwToken, // cr-0164: the password gate fronts the write path too
    xShareWrite: b64std(v1.shareWriteToken(fromB64url(fragmentKey), shareId)),
  })
  return { identityId, contributorToken }
}

/**
 * Name the guest of a READ_WRITE link once for every body of it
 * (uc.sharing.share-contributor:rights-inherit-below-a-folder): {@link createGuestIdentity} under the SHARE ROOT's key
 * — whichever body asks first, so a folder link's guest is named under the folder's key even when its first write is in
 * a Memory below — and saved as the link's one session ({@link saveGuestSession}). A member opens the name through the
 * chain (the node's key or one of its ancestors', `features/memories/mediaBuckets.ts`). The two epoch refusals (the
 * root re-keyed since `head` was read, or not yet re-keyed) resolve the link again and name the guest under the root's
 * key as it then is (`writeAtNodeEpoch`, ADR 0109 point 6).
 */
export async function nameShareGuest(
  head: PublicShareHead, { name, fragmentKey, pwToken }: { name: string; fragmentKey: string; pwToken?: string },
): Promise<GuestIdentity> {
  const created = await writeAtNodeEpoch(async (reread) => {
    const at = reread ? await resolvePublicShare(head.shareId, fragmentKey, pwToken) : head
    return createGuestIdentity(at, openShareRoot(at).key, { name, fragmentKey, pwToken, rootEpoch: shareRootEpoch(at.share) })
  })
  const identity: GuestIdentity = { ...created, displayName: name }
  saveGuestSession(head.shareId, identity)
  return identity
}

// --- the guest's session on its device -------------------------------------------------------------------------------

const sessionKey = (shareId: string) => `veyq:share-contributor:${shareId}`
// Bumped on every save and forget, so {@link useGuestSession} re-reads the store: whichever body or section of the page
// named the guest, or forgot it ("Not you?"), every other one sees it at once — no copy of the session to fall stale.
const sessionRevision = ref(0)

/** The guest's saved session for `shareId` — its identity and token, plus whatever an application keeps beside them
 *  (`S`; Memories' uploader keeps its `K_up`) — or null when there is none or the store is unreadable. One per link,
 *  whichever body named the guest; it never leaves the device (localStorage, so a reload keeps the name). */
export function loadGuestSession<S extends GuestIdentity = GuestIdentity>(shareId: string): S | null {
  try {
    const raw = localStorage.getItem(sessionKey(shareId))
    return raw ? (JSON.parse(raw) as S) : null
  } catch {
    return null
  }
}

/** Save the guest's session for `shareId`. Throws when the store refuses the write, as the caller's naming step reports. */
export function saveGuestSession(shareId: string, session: GuestIdentity): void {
  localStorage.setItem(sessionKey(shareId), JSON.stringify(session))
  sessionRevision.value++
}

/** The guest's saved session for `shareId`, reactive: it changes when any part of the page saves or forgets it
 *  ({@link saveGuestSession}, {@link forgetGuestSession}). Read-only — write through those two. */
export function useGuestSession<S extends GuestIdentity = GuestIdentity>(shareId: string): ComputedRef<S | null> {
  return computed(() => {
    void sessionRevision.value
    return loadGuestSession<S>(shareId)
  })
}

/** Forget the guest's session for `shareId` on this device (core's "Not you?", `GuestNaming.vue`): the next visitor
 *  names itself afresh. The identity, and whatever it left on the server, stay as they are. A store that refuses is
 *  ignored — every reader drops the session either way. */
export function forgetGuestSession(shareId: string): void {
  try {
    localStorage.removeItem(sessionKey(shareId))
  } catch {
    /* unreadable store: nothing was kept there either */
  }
  sessionRevision.value++
}
