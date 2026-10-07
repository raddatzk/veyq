// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
//
// Memories' section of the folder level view (`MemoriesSection`) rides on its two kinds' descriptors: core's level view
// renders it once, between the folder's overview card and the sub-folders (cr-files-application P4b). So do its dialogs
// (P4c): `MemoryNodeDialog` creates a Memory or a chapter (the kind picks which), `ShareDialog` manages a Memory's or a
// chapter's links (its uploader variant included); a rename of either is core's `FolderNameDialog`. The pinned rail in the sidebar is a list slot: Memories adds one item
// to it, under an id namespaced by the application. The top bar's upload status is core's (`core/blob/UploadStatusMenu.vue`,
// cr-files-application B3) and lists Memories' batches with every other application's, so Memories adds nothing to the
// `topbarTray` list.
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import type { Registry } from '../../core/applications/registry'
import MemoryDetailHost from './components/MemoryDetailHost.vue'
import MemoriesSection from './components/MemoriesSection.vue'
import MemoryNodeDialog from './components/MemoryNodeDialog.vue'
import ShareDialog from './components/ShareDialog.vue'
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
  // One create dialog and one share dialog serve both kinds: the create's `kind` prop picks Memory or chapter, the
  // share dialog reads `nodeKind` for its wording.
  const memories = { detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true, section, createDialog: MemoryNodeDialog, shareDialog: ShareDialog }
  registry.registerKind({ kind: MEMORY_KIND, ...memories })
  registry.registerKind({ kind: CHAPTER_KIND, ...memories })
  registry.addSlotItem('sidebarPins', 'memories.pinned', PinnedMemories)
  // The public share page: every Memories share — viewer, contributor and the WRITE_ONLY drop — is rooted at a MEMORY
  // or a CHAPTER node (`PublicShare.rootKind`); the one body renders either root and picks its screens by the role.
  registry.claimShareBody(MEMORY_KIND, MemoryShareBody)
  registry.claimShareBody(CHAPTER_KIND, MemoryShareBody)
  registry.registerLifecycle('memories', memoriesLifecycle)
}
