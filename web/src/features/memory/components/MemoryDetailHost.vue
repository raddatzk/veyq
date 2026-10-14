<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` takes (ADR 0079): everything Memories-shaped — the pin
// state, the item-mark key, the personal-scope flag, the chapters bucket, the meta patch — is resolved here,
// inside the application, and so are the CAPABILITIES (inbox #401): Memories' own fail-closed matrix over the host's
// `access` and the opened node (`../capabilities.ts`), which `MemoryDetail` narrows to its data source's doors.
//
// **The data source is picked here, once** (inbox #401 S4, `../source.ts`): the member's over the unlocked scope, or a
// share guest's over its link — by `access.mode`, which does not change while the detail is mounted (a page mounts it
// per node and session). For a guest nothing of the member's world is read: no `activeSpace` (the item-mark key, the
// scope kind), no pin store, no `treeStore` patch — the guest's capabilities grant none of them, and the guest's source
// carries no door to them. A guest gets it from core's share page (`core/sharing/ShareTreePage.vue`) for every Memory and
// chapter of a `READ` or `READ_WRITE` link, the link's root included (inbox #401 S5).
import { computed } from 'vue'
import type { NodeDetailProps, NodeDetailEmits } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { downloadHeaderActions } from '../../../core/export/downloadActions'
import { useNodeCover } from '../../../core/node/cover/useNodeCover'
import CoverEditor from '../../../core/node/cover/components/CoverEditor.vue'
import { FULL_FRAME } from '../../../core/node/cover/coverModel'
import { useI18n } from 'vue-i18n'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { keyAt } from '../../spaces/epochs'
import { memoryCapabilities } from '../capabilities'
import { targetOf } from '../../../core/access'
import { guestMemorySource, memberMemorySource } from '../source'
import MemoryDetail from './MemoryDetail.vue'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])
const caps = computed(() => memoryCapabilities(props.access, targetOf(props.tree, props.node)))

// Who looks is fixed for this mount (see the header).
const guest = props.access.mode === 'guest' ? props.access : null
const source = guest
  ? guestMemorySource(() => (props.access.mode === 'guest' ? props.access : guest), props.tree)
  : memberMemorySource({ scopeId: () => props.scopeId })

// Item marks seal under `K_SC` at the **base epoch `E_0`** (ADR 0068), never under `activeScopeKey()`, which is
// the *write* key `E_current`: the marked set is re-sealed whole on every toggle, so a rotation would strand it.
// `activeSpace.epoch` is the reactive signal that a rotation happened — without reading it this would not
// recompute after `refreshActiveScope`. A member's only.
const itemMarkKey = computed<Uint8Array | null>(() => {
  if (guest) return null
  void activeSpace.epoch
  const eks = activeEpochKeys()
  return eks ? keyAt(eks, 0) : null
})
const personalScope = computed(() => !guest && activeSpace.kind === 'PERSONAL')
const { t } = useI18n()
// Core's heart (inbox #409) and cover action (inbox #411): the same actions every kind's header gets; a guest's list is empty.
// The cover DIALOG is core's too (`CoverEditor`, mounted below); the Memory only adds its extra source to it - a photo chosen
// in the gallery lightbox (`coverFromPhoto`) - through its descriptor's `cover.extraSource`.
const cover = useNodeCover(() => props.access, () => props.tree, () => [props.node])
const coreActions = computed(() => [...favoriteHeaderActions(t, props.access, props.node), ...cover.headerActions(props.node), ...downloadHeaderActions(t, props.access, props.node, () => emit('download'))])

/** The lightbox's "Set as cover": crop that photo in the dialog; a photo with no thumbnail yet is set full-frame at once. */
function onCoverFromPhoto(p: { mediaId: string; src: string | null }) {
  if (p.src) cover.open(props.node, { source: { id: p.mediaId, src: p.src } })
  else cover.save(props.node, { kind: 'media', mediaId: p.mediaId, crop: { ...FULL_FRAME } })
}

function onMetaUpdated(e: { nodeId: string; wrappedMeta: string | null; metaVersion: number }) {
  if (!guest) patchNodeMeta(e.nodeId, e.wrappedMeta, e.metaVersion)
}
</script>

<template>
  <MemoryDetail
    :node="node"
    :scope-id="scopeId"
    :memory-key="contentKey"
    :scope-key="itemMarkKey"
    :personal-scope="personalScope"
    :core-actions="coreActions"
    :cover-editing="cover.editor.value !== null"
    :chapters="chapters"
    :caps="caps"
    :source="source"
    @rename="emit('rename')"
    @move="emit('move', node)"
    @delete="emit('delete')"
    @create-chapter="emit('createChild', CHAPTER_KIND)"
    @open-chapter="emit('openNode', $event)"
    @share="emit('share')"
    @share-upload="emit('shareUpload')"
    @meta-updated="onMetaUpdated"
    @edit-cover="cover.open(node)"
    @cover-from-photo="onCoverFromPhoto"
  />
  <CoverEditor :cover="cover" />
</template>
