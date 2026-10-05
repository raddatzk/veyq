// Test kit for the public share page, which is a shell (views/ShareView.vue) over an application body: the shell
// resolves the kind-independent head (core/sharing/resolvePublicShare), the Memories body opens the rest
// (features/memory/share `openSharedMemory`). A share test that stubs both writes the ONE resolved shape it cares about
// and lets these two helpers split it the way the production code does.
import { NodeKind } from '../api/generated/content'
import { ShareRole, type PublicShare } from '../api/generated/sharing'
import type { PublicShareHead } from '../core/sharing/resolvePublicShare'
import type { OpenedMemory, ResolvedShare } from '../features/memory/share'

/** The platform half of a resolved share. A WRITE_ONLY share has no root kind (an unlocked response serves no nodes). */
export function headOf(r: ResolvedShare, shareId = 's1'): PublicShareHead {
  return {
    shareId,
    share: { role: r.role, scopeId: r.scopeId, nodes: [], media: [] } as unknown as PublicShare,
    kShare: new Uint8Array(32),
    scopeId: r.scopeId,
    name: r.memoryName,
    noteByLocale: r.noteByLocale,
    role: r.role,
    rootKind: r.role === ShareRole.WriteOnly ? null : NodeKind.Memory,
  }
}

/** The Memories half of the same resolved share. */
export function openedOf(r: ResolvedShare): OpenedMemory {
  return { access: r.access, items: r.items }
}
