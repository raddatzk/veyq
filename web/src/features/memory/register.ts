// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
//
// The slots filled here (the level view, the create/rename dialog, the share dialog, the upload tray in the top bar,
// the pinned rail in the sidebar) are single-claim and Memories-shaped today: the next application's change request re-cuts them per kind (the registry's header).
import { NodeKind } from '../../api/generated/content'
import type { Registry } from '../../core/applications/registry'
import MemoryDetailHost from './components/MemoryDetailHost.vue'
import MemoryLevelHost from './components/MemoryLevelHost.vue'
import NodeMutationDialog from './components/NodeMutationDialog.vue'
import ShareDialog from './components/ShareDialog.vue'
import UploadStatusMenu from './components/UploadStatusMenu.vue'
import PinnedMemories from './components/PinnedMemories.vue'
import { memoriesLifecycle } from './lifecycle'

export function registerMemories(registry: Registry): void {
  // A Memory is shareable (cr-0104); a chapter is a Memory of its own that opens the same detail but is not
  // shareable (the backend rejects a non-MEMORY share root). Both open to the gallery (cr-0060).
  registry.registerKind({ kind: NodeKind.Memory, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true })
  registry.registerKind({ kind: NodeKind.Chapter, detail: MemoryDetailHost, icon: 'pi pi-images', shareable: false })
  registry.claimSlot('levelView', MemoryLevelHost)
  registry.claimSlot('nodeMutationDialog', NodeMutationDialog)
  registry.claimSlot('shareDialog', ShareDialog)
  registry.claimSlot('topbarTray', UploadStatusMenu)
  registry.claimSlot('sidebarPins', PinnedMemories)
  registry.registerLifecycle('memories', memoriesLifecycle)
}
