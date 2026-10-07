// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
//
// The slots filled here (the level view, the create/rename dialog, the share dialog, the upload tray in the top bar,
// the pinned rail in the sidebar, the public share body) are single-claim and Memories-shaped today: the next application's change request re-cuts them per kind (the registry's header).
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import type { Registry } from '../../core/applications/registry'
import MemoryDetailHost from './components/MemoryDetailHost.vue'
import MemoryLevelHost from './components/MemoryLevelHost.vue'
import NodeMutationDialog from './components/NodeMutationDialog.vue'
import ShareDialog from './components/ShareDialog.vue'
import UploadStatusMenu from './components/UploadStatusMenu.vue'
import PinnedMemories from './components/PinnedMemories.vue'
import MemoryShareBody from './components/MemoryShareBody.vue'
import { memoriesLifecycle } from './lifecycle'

export function registerMemories(registry: Registry): void {
  // A Memory (cr-0104) and a chapter (ADR 0080 Decision 13) are both share roots for every role — the backend's
  // `MemoryShareRoots` — and both open the same detail, to the gallery (cr-0060). A chapter's share wraps its own
  // `K_mem`: its guest holds nothing of the Memory above it.
  registry.registerKind({ kind: MEMORY_KIND, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true })
  registry.registerKind({ kind: CHAPTER_KIND, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true })
  registry.claimSlot('levelView', MemoryLevelHost)
  registry.claimSlot('nodeMutationDialog', NodeMutationDialog)
  registry.claimSlot('shareDialog', ShareDialog)
  registry.claimSlot('topbarTray', UploadStatusMenu)
  registry.claimSlot('sidebarPins', PinnedMemories)
  // The public share page: every Memories share — viewer, contributor and the WRITE_ONLY drop — is rooted at a MEMORY
  // or a CHAPTER node (`PublicShare.rootKind`); the one body renders either root and picks its screens by the role.
  registry.claimShareBody(MEMORY_KIND, MemoryShareBody)
  registry.claimShareBody(CHAPTER_KIND, MemoryShareBody)
  registry.registerLifecycle('memories', memoriesLifecycle)
}
