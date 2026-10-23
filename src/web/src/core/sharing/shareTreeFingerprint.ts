// A string that differs whenever a resolved share's subtree differs in a way a view can show (inbox #408): the same idea
// as `core/node/tree.ts` `treeFingerprint` for the guest's rows, the operator's block included (a block changes no
// ciphertext, inbox #503). Ids, ciphertext and that flag only, compared for equality — the
// live refresh uses it to swap `head` (which rebuilds the guest's tree and every section's data) only on a real change.
//
// Two things beside the tree's shape are in it too, because the page reads them from the head it holds (inbox #506):
// the guest-write record's N (`guestWriteRecordDays`, the notice before a first write — the switch or N changing must
// reach an open page, which is why the server puts it in the body the tag digests), and a node's drop key
// (`sealedDropPrivateKey`, set once, by the node's first WRITE_ONLY link) with an identity folder's wrapped `K_up`
// (`wrappedIdentityKey`). The guest's media poll opens a WRITE_ONLY uploader's media through the drop key of the head
// it holds (`openSharedMemory`), and that listing moves without any row: a head kept without the key never opens it.
// After a re-key (ADR 0109) the root's epoch (`rootEpoch`, what a guest's write states) and a WRITE_ONLY link's drop public
// key (`sealedDropPublicKey`, replaced with the node's drop key) move too: a head kept with the old ones writes stale.
import type { PublicShare } from '../../api/generated/sharing'

export function shareTreeFingerprint(share: PublicShare): string {
  const nodes = (share.nodes ?? [])
    .map((n) =>
      [n.id, n.parentId ?? '', n.kind, n.orderIndex ?? '', n.epoch, n.placementRevision ?? '', n.encryptedName ?? '', n.wrappedContentKey ?? '', n.wrappedMeta ?? '', n.shareIdentityId ?? '', n.wrappedIdentityKey ?? '', n.sealedDropPrivateKey ?? '', n.blockedByOperator].join('|'),
    )
    .sort()
    .join('\n')
  return [
    share.role, share.rootKind ?? '', share.sealedName ?? '', share.sealedNote ?? '', share.wrap ?? '', share.guestWriteRecordDays ?? '',
    share.rootEpoch ?? '', share.sealedDropPublicKey ?? '', nodes,
  ].join('\n#\n')
}
