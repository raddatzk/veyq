<script setup lang="ts">
// A drop box seen by a READ or READ_WRITE guest of a folder link above it (owner decision 2026-10-08, inbox #380; ADR
// 0080 decision 11): what core's folder page embeds for the drop box (`DropBoxShareBody` with `subtreeRoot`). Its
// guests' identity folders, each named by its guest — the entry opened through the drop box's drop key, `K_up`, by
// core's walk (`openGuestTreeParts`, `childEntryKey`) — with how many files each holds; a folder opens on core's folder
// page (`?node=`, the `#k=` fragment kept), which is read-only inside an identity folder and lists its files through
// Files' share section with their download. Nothing here sends, deletes or moves: that is the member's
// (`DropBoxDetail.vue`), and the server refuses it to every guest.
//
// Two guests of one name are numbered at display time like the member's view ("Anna", "Anna (2)"), but in node-id order:
// a guest's resolve serves no creation time (inbox #377 (e) put `createdAt` on the members' listing only), so the
// numbers a guest sees may differ from the member's.
import { computed, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import type { ShareBodyProps } from '../../../core/applications/registry'
import { FOLDER_KIND } from '../../../core/node/kind'
import { openGuestTreeParts, type GuestTreeParts } from '../../../core/sharing/guestTree'
import { FILE_KIND } from '../kinds'

const props = defineProps<ShareBodyProps & { subtreeRoot: NonNullable<ShareBodyProps['subtreeRoot']> }>()
const emit = defineEmits<{ unavailable: [] }>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()

// The opened subtree (keys and names): a shallow ref, as everything holding key material.
const parts = shallowRef<GuestTreeParts | null>(null)
watch(
  () => props.head,
  (head) => {
    try {
      parts.value = openGuestTreeParts(head)
    } catch {
      emit('unavailable')
    }
  },
  { immediate: true },
)

/** The guests' folders the walk opened, by id; a repeated name numbered; with each one's file count. */
const folders = computed(() => {
  const p = parts.value
  if (!p) return []
  const all = [...p.nodes.values()]
  const own = all
    .filter((n) => n.parentId === props.subtreeRoot.nodeId && n.kind === FOLDER_KIND && p.names.has(n.id))
    .sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  const seen = new Map<string, number>()
  return own.map((folder) => {
    const name = p.names.get(folder.id) ?? ''
    const n = (seen.get(name) ?? 0) + 1
    seen.set(name, n)
    const files = all.filter((c) => c.parentId === folder.id && c.kind === FILE_KIND && p.names.has(c.id)).length
    return { id: folder.id, label: n === 1 ? name : `${name} (${n})`, files }
  })
})

/** Open a guest's folder on core's folder page: `?node=`, the fragment KEPT (it carries the share key). */
function open(id: string) {
  void router.push({ query: { node: id }, hash: route.hash })
}
</script>

<template>
  <section class="veyq-section dropbox-shared">
    <div class="veyq-section-header">
      <h2><span class="pi pi-inbox" aria-hidden="true" /> {{ subtreeRoot.name }}</h2>
      <span>{{ folders.length }}</span>
    </div>
    <p>{{ t('files.dropBox.sharedHint') }}</p>
    <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">{{ t('files.dropBox.sharedEmpty') }}</p>
    <ul v-else class="veyq-list">
      <li v-for="row in folders" :key="row.id" class="veyq-list-row" :data-node-id="row.id">
        <button type="button" class="dropbox-row-open" :aria-label="t('files.dropBox.open', { name: row.label })" @click="open(row.id)">
          <span class="pi pi-folder" aria-hidden="true" />
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ row.label }}</span>
            <span class="veyq-row-subtitle">{{ t('files.dropBox.guestFiles', row.files) }}</span>
          </span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.dropbox-row-open {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.dropbox-row-open:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
  border-radius: var(--app-radius-sm);
}
</style>
