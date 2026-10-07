// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
//
// Memories' section of the folder level view (`MemoriesSection`) rides on its two kinds' descriptors: core's level view
// renders it once, between the folder's overview card and the sub-folders (cr-files-application P4b). The single-claim
// slots filled here (the create/rename dialog, the share dialog) are Memories-shaped today: P4c re-cuts them per kind
// (the registry's header). The upload tray in the top bar and the pinned rail in the sidebar are list slots: Memories adds one item to
// each, under an id namespaced by the application.
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import type { Registry } from '../../core/applications/registry'
import MemoryDetailHost from './components/MemoryDetailHost.vue'
import MemoriesSection from './components/MemoriesSection.vue'
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
  // Both name the one section (rendered once, handed the children of both; a CHAPTER sits only inside a MEMORY, so at a
  // folder level that is the Memories). `order` 10 leaves room on either side for another application's section.
  const section = { order: 10, component: MemoriesSection }
  registry.registerKind({ kind: MEMORY_KIND, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true, section })
  registry.registerKind({ kind: CHAPTER_KIND, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true, section })
  registry.claimSlot('nodeMutationDialog', NodeMutationDialog)
  registry.claimSlot('shareDialog', ShareDialog)
  registry.addSlotItem('topbarTray', 'memories.upload-status', UploadStatusMenu)
  registry.addSlotItem('sidebarPins', 'memories.pinned', PinnedMemories)
  // The public share page: every Memories share — viewer, contributor and the WRITE_ONLY drop — is rooted at a MEMORY
  // or a CHAPTER node (`PublicShare.rootKind`); the one body renders either root and picks its screens by the role.
  registry.claimShareBody(MEMORY_KIND, MemoryShareBody)
  registry.claimShareBody(CHAPTER_KIND, MemoryShareBody)
  registry.registerLifecycle('memories', memoriesLifecycle)
}
