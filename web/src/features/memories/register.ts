// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
//
// Memories' rows of the folder level (`memoryRows`, inbox #407) ride on its two kinds' descriptors: core's level view calls
// the row factory once and draws every Memory as a row or a tile of the one list (cr-files-application P4b). So do its dialogs
// (P4c): `MemoryNodeDialog` creates a Memory or a chapter (the kind picks which), `ShareDialog` manages a Memory's or a
// chapter's links (its uploader variant included); a rename of either is core's `FolderNameDialog`. The same detail
// serves a member and a share guest (inbox #401): the host picks the data source by `access.mode`. The sidebar's Favorites section is core's (`core/favorites/PinnedNodes.vue`, inbox #409), so Memories adds nothing to the
// `sidebarPins` list. The top bar's upload status is core's (`core/blob/UploadStatusMenu.vue`,
// cr-files-application B3) and lists Memories' batches with every other application's, so Memories adds nothing to the
// `topbarTray` list.
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { LevelActionContext, Registry } from '../../core/applications/registry'
import { MEMORY_VIEWS } from './memoryViews'
import MemoryDetailHost from './components/MemoryDetailHost.vue'
import { memoryRows } from './levelRows'
import { findStoredCover } from './storedMatch'
import MemoryNodeDialog from './components/MemoryNodeDialog.vue'
import ShareDialog from './components/ShareDialog.vue'
import MemoryShareBody from './components/MemoryShareBody.vue'
import PersonMemories from './components/PersonMemories.vue'
import MediumReview from './components/MediumReview.vue'
import { memoriesLifecycle } from './lifecycle'
import { memoryRights } from './rights'
import { targetOf } from '../../core/access'
import { exportMemoryContent, importMemoryContent, validateMemoryContent, MEMORIES_CONTENT_SCHEMA } from './exportImport'
// Memories' global stylesheet (inbox #513) — LAST, after the components above, as `memory.css` came after their scoped
// styles; what its place before core's `style.css` means for its rules is that sheet's header.
import './memories.css'

/** Memories' URL route slug (inbox #328): the segment before the name of a Memory in the fragment path. */
export const MEMORIES_ROUTE_SLUG = 'memories'

export function registerMemories(registry: Registry): void {
  // A Memory (cr-0104) and a chapter (ADR 0080 Decision 13) are both share roots for every role — the backend's
  // `MemoryShareRoots` — and both open the same detail, to the gallery (cr-0060). A chapter's share wraps its own
  // `K_node`: its guest holds nothing of the Memory above it.
  // Both name the one row source (called once, handed the nodes of both; a CHAPTER sits only inside a MEMORY, so at a folder
  // level that is the Memories).
  // One create dialog and one share dialog serve both kinds: the create's `kind` prop picks Memory or chapter, the
  // share dialog reads `nodeKind` for its wording. Core's delete dialog titles each by its own noun (`deleteTitle`):
  // "Delete Memory", "Delete chapter".
  // Its `views`: the card grid, its list variant and the places map (inbox #370) - core's level toolbar offers them; the grid
  // and list are core's, the map is Memories' own, drawn by the factory (its pins span the level's whole subtree); the person's
  // profile offers the very same list (`memoryViews.ts`, inbox #417). The same list names its tab and the plain text of that
  // tab while it has nothing.
  const list = { rows: memoryRows, tabLabel: 'memory.memories', views: MEMORY_VIEWS, emptyText: () => 'memory.memoryEmpty' }
  const memories = {
    detail: MemoryDetailHost, icon: 'pi pi-images', shareable: true, list, createDialog: MemoryNodeDialog, shareDialog: ShareDialog,
    // Both carry a cover (inbox #411: core's action, dialog, upload and display), set only by an upload in core's dialog. A
    // picked image equal to a gallery medium's original shares that medium's PREVIEW instead of being stored again.
    cover: { findStored: findStoredCover },
    exportContent: exportMemoryContent,
    contentSchema: MEMORIES_CONTENT_SCHEMA,
    validateContent: validateMemoryContent,
    // What a copy or an import does not carry of a Memory (core's copy dialog lists it). `importStreams`: a copy hands each
    // medium's existing tiers and sealed facts over and its import uploads them as they are, so it never decodes an original
    // (ADR 0082 decision 11, `exportImport.ts`); a download's import still does.
    copyLosses: ['memory.copyLoss'],
    importStreams: true,
  }
  // Where each may be moved (core's move dialog, inbox #358) mirrors the backend's `MemoryNodeKindRules`: a Memory at the
  // root or in a folder, a chapter only directly in a Memory.
  // Only a Memory is created from a level (core's "New" menu, inbox #406): the entry shows where Memories' rights
  // matrix lets a Memory be created, and is greyed while the scope's keys are locked. A chapter is created inside its Memory.
  const newMemory = {
    label: 'memory.create', icon: 'pi pi-images', order: 10,
    allowed: (ctx: LevelActionContext) => memoryRights(ctx.access, targetOf(ctx.tree, ctx.folder)).createMemory,
    enabled: (ctx: LevelActionContext) => ctx.epochs !== null,
  }
  registry.registerKind({
    kind: MEMORY_KIND, ...memories, importContent: importMemoryContent(MEMORY_KIND), createEntry: newMemory, deleteTitle: 'memory.deleteMemoryTitle', nounWithArticle: 'memory.aMemory',
    placement: { root: true, parents: [FOLDER_KIND] },
  })
  registry.registerKind({
    kind: CHAPTER_KIND, ...memories, importContent: importMemoryContent(CHAPTER_KIND), deleteTitle: 'memory.deleteChapterTitle', nounWithArticle: 'memory.aChapter',
    placement: { root: false, parents: [MEMORY_KIND] },
  })
  // Its nodes' URLs carry the application's segment: a Memory `rom` in `reisen` is `#reisen/memories/rom`, its chapters
  // extend that (`…/rom/tag-1`). The slug is reserved in the unprefixed namespace: a folder called "Memories" is
  // `memories-2` (`core/node/location.ts`, inbox #328).
  registry.claimRouteSlug(MEMORIES_ROUTE_SLUG, [MEMORY_KIND, CHAPTER_KIND])
  // The drop page of a WRITE_ONLY link rooted at a MEMORY or a CHAPTER (`PublicShare.rootKind`): its guest holds no key,
  // so it is the one page Memories draws for a guest alone. A viewer's or contributor's link is the detail above, for a
  // guest through core's share page (inbox #401).
  registry.claimShareBody(MEMORY_KIND, MemoryShareBody)
  registry.claimShareBody(CHAPTER_KIND, MemoryShareBody)
  // A person's profile (`#people/<userId>`, ADR 0083) lists the Memories they took part in: Memories' section of core's page.
  registry.claimPersonSection(PersonMemories)
  // A reported medium's review (uc.moderation.review-a-case A5): a medium is Memories' record, core cannot draw it.
  registry.claimItemReview(MediumReview)
  registry.registerLifecycle('memories', memoriesLifecycle)
}
