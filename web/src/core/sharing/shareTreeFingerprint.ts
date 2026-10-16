// A string that differs whenever a resolved share's subtree differs in a way a view can show (inbox #408): the same idea
// as `core/node/tree.ts` `treeFingerprint` for the guest's rows. Ids and ciphertext only, compared for equality — the
// live refresh uses it to swap `head` (which rebuilds the guest's tree and every section's data) only on a real change.
import type { PublicShare } from '../../api/generated/sharing'

export function shareTreeFingerprint(share: PublicShare): string {
  const nodes = (share.nodes ?? [])
    .map((n) =>
      [n.id, n.parentId ?? '', n.kind, n.orderIndex ?? '', n.epoch, n.placementRevision ?? '', n.encryptedName ?? '', n.wrappedContentKey ?? '', n.wrappedMeta ?? '', n.shareIdentityId ?? ''].join('|'),
    )
    .sort()
    .join('\n')
  return [share.role, share.rootKind ?? '', share.sealedName ?? '', share.sealedNote ?? '', share.wrap ?? '', nodes].join('\n#\n')
}
