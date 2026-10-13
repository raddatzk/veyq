// uc.memory.participants (web) — who added a photo, for the lightbox's detail (ADR 0083 decision 7). INDEPENDENT of the
// participant list: a non-participant uploader is still named. A `user:<id>` owner key is a member - named by the display
// rule (real name else username) and, while they are still in the scope, a LINK to their profile; one who left is a greyed
// "Former member" with no link. A `share:<identity>` owner is a link's contributor: its name is the sealed display name in its
// bucket (the "Beiträge" source), shown WITHOUT a link - a guest has no profile. Members only, and only where the source
// carries the buckets door: a share guest's source has none, so a guest is shown no uploader at all - exactly what it
// saw before (the public resolve serves no names, inbox #190).
import { ref } from 'vue'
import { loadScopePeople, type Person } from '../../core/people/directory'
import { openBucketName } from './mediaBuckets'
import type { MediaItem } from './media'
import type { BucketsDoor } from './source'

/** What the lightbox shows of an uploader: the name, and the member's user id when - and only when - it may link. */
export interface UploaderLabel {
  name: string
  userId: string | null
}

export interface UploaderNamesContext {
  scopeId: () => string
  nodeId: () => string
  memoryKey: () => Uint8Array | null
  /** The source's buckets door; null where the session has none (a guest). */
  buckets: () => BucketsDoor | null
  formerLabel: () => string
  contributorLabel: () => string
}

export function useUploaderNames(ctx: UploaderNamesContext) {
  const labels = ref(new Map<string, UploaderLabel>())
  let loadedFor: string | null = null

  /** Read the owners once per node (call when the lightbox opens). Best-effort: a failure leaves the photos unattributed. */
  async function ensure(): Promise<void> {
    const door = ctx.buckets()
    const key = ctx.memoryKey()
    const nodeId = ctx.nodeId()
    if (!door || !key || loadedFor === nodeId) return
    loadedFor = nodeId
    try {
      const [buckets, people] = await Promise.all([door.list(nodeId), loadScopePeople(ctx.scopeId()).catch(() => [] as Person[])])
      const byId = new Map(people.map((p) => [p.userId, p]))
      const above = door.ancestorKeys(nodeId)
      const next = new Map<string, UploaderLabel>()
      for (const b of buckets) {
        if (!b.isPrimary) continue
        if (b.ownerKey.startsWith('user:')) {
          const person = byId.get(b.ownerKey.slice('user:'.length))
          next.set(b.ownerKey, person ? { name: person.displayName, userId: person.userId } : { name: ctx.formerLabel(), userId: null })
        } else if (b.ownerKey.startsWith('share:')) {
          next.set(b.ownerKey, { name: openBucketName(ctx.scopeId(), nodeId, key, b, above) || ctx.contributorLabel(), userId: null })
        }
      }
      labels.value = next
    } catch {
      loadedFor = null // try again at the next open
    }
  }

  /** The uploader of `item`, or null when it is not known (no owner key, not yet read, or a session without the door). */
  const uploaderOf = (item: MediaItem): UploaderLabel | null => (item.ownerKey ? labels.value.get(item.ownerKey) ?? null : null)

  return { uploaderOf, ensure }
}
