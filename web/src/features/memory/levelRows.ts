// Memories' part of the unified level list (inbox #407, ADR 0079): the ROW DATA of a folder level's `MEMORY` (and `CHAPTER`)
// children. Core draws the list (`core/node/LevelView.vue`, `LevelRowItem.vue`); this factory is called once per level view
// (`register.ts` names it as both kinds' `list.rows`) and returns what core needs per node (`LevelRowSource`). It replaces
// the former `MemoriesSection.vue` and `MemoryCard.vue`; what those did beyond drawing lives here:
//
// - **The cover** of each Memory (the visual of its row or tile): core's (inbox #411) - the level resolves every node's cover
//   once (a member's from the shared store, a share guest's through the link's own reads) and hands the map to this source
//   (`host.covers`), which draws it with the Memory placeholder tile while there is none.
// - **The sealed date range and place** (the row's `details`, `memoryRowDetails.ts`, left out when absent) from each node's
//   `wrappedMeta` under the key its parent's chain opens - read from the tree, no media load. A rotated scope mixes epochs
//   across the level; locked (`epochs` null) means no facts.
// - **The map** (the third presentation, `register.ts` names it among the kinds' `views`): core asks `presentation('map')`
//   and draws the component handed back in place of the list. Its pins span the whole subtree (folders included), unsealed on
//   device from the `wrappedMeta` the tree already holds (operator-blind - there is no locations API), a guest's from the
//   opened subtree. Only Memories offers it.
//
// What a row offers is Memories' rights matrix for the node (`./rights.ts`, inbox #401), fail closed: a share guest
// gets the same row with the member-only functions removed. A row's primary click OPENS the Memory (its detail).
import { computed, watch, type Ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelPresentation, LevelRow, LevelRowAction, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../../core/node/levelRow'
import { parentContentKey, type TreeNode } from '../../core/node/tree'
import { nodeById } from '../../core/node/browse'
import { treeStore } from '../../core/node/treeStore'
import { activeSpace } from '../../app/activeSpace'
import { targetOf, type TreeSource } from '../../core/access'
import { collectLocatedPins, collectLocatedPinsBy } from './spaceMap'
import { memoryRights } from './rights'
import { memoryDetails, NO_FACTS, unsealRowFacts, type MemoryRowFacts } from './memoryRowDetails'
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import MemoryRowCover from './components/MemoryRowCover.vue'
import SpaceMap from './components/SpaceMap.vue'

/** Every node of `tree` in the subtree of `rootId`, `rootId`'s own included - what a guest's map spans. */
function subtreeNodes(tree: TreeSource<TreeNode>, rootId: string | null): TreeNode[] {
  const out: TreeNode[] = []
  const root = rootId ? tree.node(rootId) : null
  if (root) out.push(root)
  const queue: (string | null)[] = [rootId]
  for (let i = 0; i < queue.length; i++) {
    for (const child of tree.children(queue[i])) {
      out.push(child)
      queue.push(child.id)
    }
  }
  return out
}

export function memoryRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  const { t, locale } = useI18n()
  // Who looks does not change while the level is mounted (a host mounts its level for one session).
  const guestAccess = ctx.value.access.mode === 'guest' ? ctx.value.access : null
  const folderId = () => ctx.value.folder?.id ?? null
  const treeOf = () => ctx.value.tree as TreeSource<TreeNode>

  /** Only this application's nodes, whatever the level hands in. */
  const memoryNodes = computed(() => ctx.value.nodes.filter((n) => n.kind === MEMORY_KIND || n.kind === CHAPTER_KIND))

  // Each Memory's sealed date and place - each K_node opened under its parent's key: E_{node.epoch} at the scope root, the folder's
  // key inside a folder (ADR 0080 Decision 1); read from wrappedMeta, no media load. They are the row's DETAILS.
  const facts = computed(() => {
    const parentKey = (node: TreeNode): Uint8Array | null =>
      guestAccess
        ? (node.parentId ? ctx.value.tree.keyOf(node.parentId) : null)
        : ctx.value.epochs && activeSpace.scopeId ? parentContentKey(activeSpace.scopeId, ctx.value.epochs, treeStore.nodes, node) : null
    const scopeId = guestAccess ? guestAccess.head.scopeId : (activeSpace.scopeId ?? '')
    return new Map<string, MemoryRowFacts>(memoryNodes.value.map((n) => [n.id, unsealRowFacts(scopeId, parentKey(n), n)]))
  })

  // The map pins: every located node in this folder's subtree (or the whole space at root), unsealed on device from the
  // wrappedMeta `listNodes` already returned. Locked => no pins.
  const pins = computed(() =>
    guestAccess
      ? collectLocatedPinsBy(guestAccess.head.scopeId, (id) => ctx.value.tree.keyOf(id), subtreeNodes(treeOf(), folderId()), folderId())
      : ctx.value.epochs && activeSpace.scopeId
        ? collectLocatedPins(activeSpace.scopeId, ctx.value.epochs, treeStore.nodes, folderId())
        : [],
  )

  /** A pin click carries a node id - resolve it to the TreeNode and open it, exactly like a row click. */
  function onPinOpen(nodeId: string) {
    const node = guestAccess ? treeOf().node(nodeId) : nodeById(treeStore.nodes, nodeId)
    if (node) host.open(node)
  }

  // The covers are core's (inbox #411): the level resolves them for its own nodes (`host.covers`, a member's from the shared
  // store, a guest's through the link's own reads), so a row only READS the decrypted image. The one thing this source adds is
  // the nodes of its map - its pins span the whole subtree, beyond the level - asked of the same mechanism.
  const coverMap = computed(() => host.covers.value)
  const viewMode = computed(() => ctx.value.viewMode)
  watch([viewMode, pins], () => {
    if (ctx.value.viewMode === 'map') host.ensureCovers(pins.value.map((pin) => pin.node))
  }, { immediate: true })

  function memoryRow(node: TreeNode): LevelRow {
    // The matrix for THIS node: the rename and the move are about the Memory the row stands for. (The heart is core's
    // row action, `core/favorites`: one for every kind.)
    const c = memoryRights(ctx.value.access, targetOf(ctx.value.tree, node))
    const cover = coverMap.value.get(node.id)
    const actions: LevelRowAction[] = [
      { id: 'rename', label: t('node.rename'), icon: 'pi pi-pencil', shown: c.rename, onClick: () => host.rename(node) },
      { id: 'move', label: t('node.move.action'), icon: 'pi pi-file-export', shown: c.move, onClick: () => host.move(node) },
    ]
    return {
      icon: 'pi pi-images',
      // The cover always: a Memory's tile stays a cover tile (the soft placeholder) while it has none or it is loading.
      leading: { component: MemoryRowCover, props: { src: cover?.url ?? null, crop: cover?.crop ?? null, alt: node.name } },
      title: node.name,
      meta: t(node.kind === CHAPTER_KIND ? 'memory.kindChapter' : 'memory.kindMemory'),
      details: memoryDetails(facts.value.get(node.id) ?? NO_FACTS, t, locale.value),
      primary: { label: node.name, onClick: () => host.open(node) },
      actions,
      // With a selection of the level (inbox #421): the same matrix for THIS node; its delete is core's `deleteNode`, as the
      // Memory's own page asks the host for.
      selection: { move: c.move, delete: c.deleteNode },
    }
  }

  const rowTable = computed(() => new Map(memoryNodes.value.map((n) => [n.id, memoryRow(n)])))

  return {
    row: (node) => rowTable.value.get(node.id) ?? null,
    presentation(mode): LevelPresentation | null {
      return mode === 'map' ? { component: SpaceMap, props: { pins: pins.value, covers: coverMap.value }, on: { open: onPinOpen } } : null
    },
  }
}
