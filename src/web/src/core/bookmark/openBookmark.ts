// Opening a bookmark, and handing its share's report to where the share lives (ADR 0120 decisions 5 and 6). A link of this
// instance's own origin opens in this tab, through the router (its password handed to the share page in memory); any other
// origin opens in a new tab with `noopener` and `noreferrer`, and where the bookmark keeps a password the member is first
// shown it to copy ({@link bookmarkOpenRequest}, the one `BookmarkOpenDialog` in `App.vue`) - a password never travels in a
// URL and no other origin can be handed anything. A report is the same open with the share page asked to open its own
// report dialog (`reportLinkOf`): the report goes to the share's own operator, never to this instance's moderators.
import { getCurrentInstance, shallowRef } from 'vue'
import type { Router } from 'vue-router'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from '../node/tree'
import { unsealNodeMeta } from '../node/nodeMeta'
import { handSharePassword } from '../sharing/handedPassword'
import { hostOf, reportLinkOf } from './bookmarkLink'
import { bookmarkOf, type Bookmark } from './bookmarkMeta'

/** A foreign link with a password, waiting for the member to copy the password and open it. */
export interface BookmarkOpenRequest {
  /** The URL the dialog's link opens (the bookmark's link, or its report hand-off). */
  url: string
  host: string
  password: string
  report: boolean
}

/** The open request, or null - read by the one dialog. */
export const bookmarkOpenRequest = shallowRef<BookmarkOpenRequest | null>(null)

/** What an open needs from the page: this document's origin, the router's navigation, and a new tab. */
export interface OpenDoors {
  origin: string
  navigate: (to: { path: string; hash: string }) => void
  openAway: (url: string) => void
}

/** Open [bookmark] - or, with [report], hand its share's report to the share page - through [doors]. */
export function openBookmark(bookmark: Bookmark, report: boolean, doors: OpenDoors): void {
  const { link, password } = bookmark
  const url = report ? reportLinkOf(link) : link.link
  if (link.origin === doors.origin) {
    if (password) handSharePassword(link.shareId, password)
    doors.navigate({ path: `/s/${link.shareId}`, hash: url.slice(url.indexOf('#')) })
    return
  }
  if (password) {
    bookmarkOpenRequest.value = { url, host: hostOf(link), password, report }
    return
  }
  doors.openAway(url)
}

/** The bookmark [node] keeps, as [access] reads it through [tree]; null where its key is not held or its meta does not read. */
export function bookmarkOfNode(access: Access, tree: TreeSource, node: Pick<TreeNode, 'id' | 'wrappedMeta'>): Bookmark | null {
  let key: Uint8Array | null
  try {
    key = tree.keyOf(node.id)
  } catch {
    key = null
  }
  if (!key) return null
  const scopeId = access.mode === 'guest' ? access.head.scopeId : access.scopeId
  return bookmarkOf(unsealNodeMeta(key, { scopeId, nodeId: node.id }, node.wrappedMeta))
}

/** A new tab on another origin that learns nothing of this one: no opener, no referrer. */
export function openAway(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}

/** The page's doors for {@link openBookmark}; called in a component's (or a row factory's) setup. */
export function useBookmarkDoors(): OpenDoors {
  // The app's router as the component's app holds it, not `useRouter()`: a view mounted without one (a unit test of a level)
  // gets doors that still navigate, the whole document, rather than a warning on every mount.
  const router = getCurrentInstance()?.appContext.config.globalProperties.$router as Router | undefined
  return {
    origin: window.location.origin,
    navigate: (to) => (router ? void router.push(to) : window.location.assign(`${to.path}${to.hash}`)),
    openAway,
  }
}
