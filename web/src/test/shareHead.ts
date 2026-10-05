// Test kit for the public share page, which is a shell (views/ShareView.vue) over an application body: the shell
// resolves the kind-independent head (core/sharing/resolvePublicShare), the Memories body opens the rest
// (features/memory/share `openSharedMemory`). A share test that stubs both writes the ONE resolved shape it cares about
// and lets these two helpers split it the way the production code does.
import { MEMORY_KIND } from '../features/memory/kinds'
import type { PublicShare } from '../api/generated/sharing'
import type { PublicShareHead } from '../core/sharing/resolvePublicShare'
import type { OpenedMemory, ResolvedShare } from '../features/memory/share'

/** The platform half of a resolved share. Every Memory share is rooted at a MEMORY node, a WRITE_ONLY one too (the
 *  server names the root kind for every unlocked role). */
export function headOf(r: ResolvedShare, shareId = 's1'): PublicShareHead {
  return {
    shareId,
    share: { role: r.role, scopeId: r.scopeId, rootKind: MEMORY_KIND, nodes: [], media: [] } as unknown as PublicShare,
    kShare: new Uint8Array(32),
    scopeId: r.scopeId,
    name: r.memoryName,
    noteByLocale: r.noteByLocale,
    role: r.role,
    rootKind: MEMORY_KIND,
  }
}

/** The Memories half of the same resolved share. */
export function openedOf(r: ResolvedShare): OpenedMemory {
  return { access: r.access, items: r.items }
}
