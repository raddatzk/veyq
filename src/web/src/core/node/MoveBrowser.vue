<script setup lang="ts">
// Core's move browser (owner decision 2026-10-14, replacing inbox #358's list of every destination): ONE place at a time,
// the way the level shows a folder. It opens where the moved nodes are (`move.ts` `moveStart`), shows a trail up to the top
// of what the session reads (a member's scope root, a guest's link root), and lists only the places a user may go into or
// move into (`movePlaces`), drawn as the level draws them - core's `LevelRowItem`, the kind's icon or the node's cover, in
// the level's order (`orderLevel`), with no actions. The place on screen is the `place` model; the dialog around it says
// whether "Move here" may be chosen there (`moveHereBlock`) and does the write.
//
// It takes the moved nodes as a LIST (a level's selection, inbox #421, through `MoveNodesDialog`): a place is offered only
// where it takes every one of them, and each of them is listed blocked - with the reason - and cannot be opened.
//
// Keyboard and screen reader: every place and every crumb is a real `<button>` (a crumb is never a link: nothing here
// navigates the page, so a guest's `#k=` fragment is never touched - web/CLAUDE.md, the breadcrumb trap), and focus goes
// to the list after a place was opened, so the next Tab reaches its first place.
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { FOLDER_KIND } from './kind'
import { registry } from '../applications/registry'
import { movePlaces, moveTrail } from './move'
import { orderLevel } from './levelList'
import type { LevelRow } from './levelRow'
import LevelRowItem from './components/LevelRowItem.vue'
import NodeCoverImage from './cover/components/NodeCoverImage.vue'
import { useCovers } from './cover/coverStore'

const props = defineProps<{
  /** What is being moved: one node (`MoveNodeDialog`) or a level's selection (`MoveNodesDialog`, inbox #421). */
  nodes: readonly TreeNode[]
  access: Access
  tree: TreeSource<TreeNode>
  /** The member's scope root as the trail names it. */
  rootLabel?: string
  /** A write is running: nothing can be opened. */
  disabled?: boolean
}>()
/** The place on screen: a node id, or null for the member's scope root. */
const place = defineModel<string | null>('place', { required: true })
const { t, locale } = useI18n()

const nameOf = (id: string | null) => (id === null ? (props.rootLabel ?? '') : props.tree.nameOf(id))
const placeName = computed(() => nameOf(place.value))
const movedName = computed(() => (props.nodes.length === 1 ? props.tree.nameOf(props.nodes[0].id) || props.nodes[0].name : ''))

function open(id: string | null): void {
  if (props.disabled || id === place.value) return
  place.value = id
}

// --- the trail ----------------------------------------------------------------------------------------------------------
type Crumb = MenuItem & { id: string | null; current: boolean }
const crumbs = computed<Crumb[]>(() => {
  const trail = moveTrail(props.tree, place.value)
  return trail.map((id, i) => ({ key: id ?? '\u0000root', id, label: nameOf(id), current: i === trail.length - 1 }))
})

// --- the places here ----------------------------------------------------------------------------------------------------
const places = computed(() => {
  const listed = movePlaces(props.tree, props.nodes, props.access.mode, place.value)
  const blocked = new Map(listed.map((p) => [p.node.id, p.blocked]))
  const folders = listed.filter((p) => p.node.kind === FOLDER_KIND).map((p) => p.node)
  const others = listed.filter((p) => p.node.kind !== FOLDER_KIND).map((p) => p.node)
  return orderLevel(folders, others, locale.value).map((node) => ({ node, blocked: blocked.get(node.id) ?? null }))
})

// The covers of the places, resolved the way the level resolves them (a member's from the shared store, a guest's through
// the link's own reads): the image with its crop where a node has one, the kind's icon otherwise.
const a0 = props.access
const coverStore = useCovers(a0.mode === 'member' ? { mode: 'member', scopeId: a0.scopeId } : a0)
watch(
  () => places.value.map((p) => `${p.node.id}:${p.node.wrappedMeta ?? ''}`).join('|'),
  () => coverStore.ensure(places.value.map((p) => p.node), (id) => props.tree.keyOf(id)),
  { immediate: true },
)

function iconOf(kind: string): string {
  if (kind === FOLDER_KIND) return 'pi pi-folder'
  const info = registry.kind(kind)
  return info.supported ? info.icon : 'pi pi-question-circle'
}
function rowOf(node: TreeNode, blocked: 'self' | 'inside' | null): LevelRow {
  const name = props.tree.nameOf(node.id) || node.name
  const loaded = coverStore.covers.value.get(node.id)
  return {
    icon: iconOf(node.kind),
    leading: loaded ? { component: NodeCoverImage, props: { src: loaded.url, crop: loaded.crop, alt: '' } } : undefined,
    title: name,
    // A folder says what it is, as on the level; another kind's line is its application's row data, which the browser
    // does not build - its icon or cover says it.
    meta: node.kind === FOLDER_KIND ? t('node.kindFolder') : '',
    details: blocked ? [{ id: 'blocked', text: t(`node.move.blocked.${blocked}`) }] : [],
    primary: { label: name, onClick: () => open(node.id), disabled: blocked !== null || props.disabled },
    actions: [],
  }
}

// After a place was opened, focus its list (or what it says when it is empty).
const region = ref<HTMLElement | null>(null)
watch(place, async () => {
  await nextTick()
  region.value?.focus()
})
</script>

<template>
  <div class="move-browser">
    <Breadcrumb :model="crumbs" class="app-breadcrumb move-browser-trail" :aria-label="t('node.move.trail')">
      <template #item="{ item }">
        <button
          type="button"
          class="move-browser-crumb"
          :aria-current="(item as Crumb).current ? 'location' : undefined"
          :disabled="disabled"
          @click="open((item as Crumb).id)"
        >
          {{ item.label }}
        </button>
      </template>
    </Breadcrumb>
    <div ref="region" class="move-browser-places" tabindex="-1" role="group" :aria-label="t('node.move.places', { place: placeName })">
      <ul v-if="places.length" class="veyq-level-list is-list">
        <LevelRowItem
          v-for="p in places"
          :key="p.node.id"
          :row="rowOf(p.node, p.blocked)"
          mode="list"
          :kind="p.node.kind"
          :folder="p.node.kind === FOLDER_KIND"
        />
      </ul>
      <p v-else class="veyq-empty veyq-empty-inline">{{ nodes.length === 1 ? t('node.move.empty', { name: movedName }) : t('node.move.many.empty') }}</p>
    </div>
  </div>
</template>

<style scoped>
.move-browser {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  min-width: 0;
}
.move-browser-crumb {
  padding: 0.125rem 0.25rem;
  border: 0;
  border-radius: var(--app-radius-sm);
  background: none;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.move-browser-crumb[aria-current='location'] {
  font-weight: 600;
  cursor: default;
}
.move-browser-crumb:not([aria-current='location']):hover {
  background: var(--app-hover);
}
.move-browser-crumb:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.move-browser-places {
  max-height: 50vh;
  overflow-y: auto;
  border-radius: var(--app-radius-sm);
}
.move-browser-places:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
</style>
