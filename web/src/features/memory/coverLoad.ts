// How Memories reads the one cover source core cannot: a Memory's cover chosen from its OWN PHOTOS (`{ kind: 'media',
// mediaId }`, uc.memory.cover first source). Registered as the Memory and Chapter kinds' `cover.load`
// (`register.ts`); core's cover store (`core/node/cover/coverStore.ts`) opens an uploaded cover itself and asks this only
// for the rest, for a member and a share guest alike - the sidebar, the rows, the map and the header all go through it.
//
// A member opens the chosen medium's THUMBNAIL tier (`listMedia` -> `openGrid`); a share guest reads it through the link's
// own gated reads, as its gallery does (`openSharedMemory` keys the listing - a drop-box guest's upload through its identity;
// `openShareGrid` presigns), the link's media listing read ONCE per guest page and only when a cover needs it (memoised on
// the access object). Best-effort: a medium that is gone (E2), a refused read or a key that does not open is null - the node
// then shows its icon, never a broken tile.
import type { CoverLoadContext } from '../../core/applications/registry'
import type { GuestAccess } from '../../core/access'
import type { ShareMediaListing } from '../../api/generated/share-media'
import type { DecryptedCover } from '../../core/node/cover/coverModel'
import { hasDerivatives, listMedia, openGrid } from './media'
import { normalizeMemoryCover } from './memoryCover'
import { listShareMedia, openShareGrid, openSharedMemory } from './share'

// One listing per guest page: the access object is the page's, and it is dropped with the page.
const listings = new WeakMap<GuestAccess, Promise<ShareMediaListing>>()
const listingOf = (access: GuestAccess): Promise<ShareMediaListing> => {
  let listing = listings.get(access)
  if (!listing) {
    listing = listShareMedia(access.shareId, access.role, access.pwToken)
    listings.set(access, listing)
  }
  return listing
}

export async function loadMemoryPhotoCover(ctx: CoverLoadContext): Promise<DecryptedCover | null> {
  const cover = normalizeMemoryCover(ctx.cover, ctx.node.id)
  if (!cover || cover.source.kind !== 'media') return null
  const mediaId = cover.source.mediaId
  try {
    let bytes: Uint8Array | null
    if (ctx.guest) {
      const opened = openSharedMemory(ctx.guest.head, await listingOf(ctx.guest))
      const pick = opened.items.find((si) => si.nodeId === ctx.node.id && si.item.mediaId === mediaId && hasDerivatives(si.item))
      bytes = pick ? await openShareGrid(ctx.guest.shareId, pick, ctx.guest.pwToken) : null
    } else {
      const media = await listMedia(ctx.scopeId, ctx.node.id)
      const pick = media.find((m) => m.mediaId === mediaId && hasDerivatives(m))
      bytes = pick ? await openGrid(ctx.scopeId, ctx.nodeKey, pick) : null
    }
    return bytes ? { blob: new Blob([bytes as BlobPart]), crop: cover.crop } : null
  } catch {
    return null
  }
}
