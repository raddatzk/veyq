<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` takes (ADR 0079): everything Memories-shaped — the pin
// state, the item-mark key, the personal-scope flag, the chapters bucket, the meta patch — is resolved here,
// inside the application, and so are the RIGHTS (inbox #401): Memories' own fail-closed matrix over the host's
// `access` and the opened node (`../rights.ts`), which `MemoryDetail` narrows to its data source's doors.
//
// **The data source is picked here, once** (inbox #401 S4, `../source.ts`): the member's over the unlocked scope, or a
// share guest's over its link — by `access.mode`, which does not change while the detail is mounted (a page mounts it
// per node and session). For a guest nothing of the member's world is read: no `activeSpace` (the item-mark key, the
// scope kind), no pin store, no `treeStore` patch — the guest's rights grant none of them, and the guest's source
// carries no door to them. A guest gets it from core's share page (`core/sharing/ShareTreePage.vue`) for every Memory and
// chapter of a `READ` or `READ_WRITE` link, the link's root included (inbox #401 S5).
import { computed } from 'vue'
import type { NodeDetailProps, NodeDetailEmits } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { downloadHeaderActions } from '../../../core/export/downloadActions'
import { copyHeaderActions } from '../../../core/export/copyActions'
import { canReport, reportHeaderActions, reportNodes, reportScope } from '../../../core/report/reportActions'
import { openReport, useReportOffered } from '../../../core/report/report'
import { mediumReportKeys, type MediaItem } from '../media'
import { useNodeCover } from '../../../core/node/cover/useNodeCover'
import CoverEditor from '../../../core/node/cover/components/CoverEditor.vue'
import { useI18n } from 'vue-i18n'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { keyAt } from '../../spaces/epochs'
import { memoryRights } from '../rights'
import { targetOf } from '../../../core/access'
import { guestMemorySource, memberMemorySource } from '../source'
import MemoryDetail from './MemoryDetail.vue'
import { useToast } from '../../../shared/state/useToast'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])
const rights = computed(() => memoryRights(props.access, targetOf(props.tree, props.node)))

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
const toast = useToast()
// Core's heart (inbox #409) and cover action (inbox #411): the same actions every kind's header gets; a guest's list is empty.
// The cover DIALOG is core's too (`CoverEditor`, mounted below): a cover is set only by an upload there. The Memory only says
// where an equal picture is already stored (its descriptor's `cover.findStored`), so the cover shares it.
const cover = useNodeCover(() => props.access, () => props.tree, () => [props.node])
const reportOn = useReportOffered()
// …and core's "Copy to…" (uc.platform.copy-node) beside the move, a member's, where this build copies the kind.
const coreActions = computed(() => [
  ...favoriteHeaderActions(t, toast.error, props.access, props.node),
  ...cover.headerActions(props.node),
  ...copyHeaderActions(t, props.access, props.node, () => emit('copy')),
  ...downloadHeaderActions(t, props.access, props.node, () => emit('download')),
  ...reportHeaderActions(t, reportOn.value, props.access, props.node, () => reportNodes(props.access, props.tree, [props.node], null)),
])
// Core's "Report" (uc.moderation.report) for the Memory itself in the header, and for one medium in the lightbox: the
// medium is this application's item, so its keys are Memories' to name (`mediumReportKeys`).
const reportsMedia = computed(() => reportOn.value && canReport(props.access))
function onReportMedium(item: MediaItem) {
  const memoryKey = props.contentKey
  if (!memoryKey) return
  const scopeId = reportScope(props.access)
  openReport({
    access: props.access,
    scopeId,
    targets: [{ kind: 'ITEM', id: item.mediaId, keys: () => mediumReportKeys(scopeId, memoryKey, item, !guest) }],
  })
}

function onMetaUpdated(e: { nodeId: string; wrappedMeta: string | null; metaRevision: number }) {
  if (!guest) patchNodeMeta(e.nodeId, e.wrappedMeta, e.metaRevision)
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
    :can-report="reportsMedia"
    :chapters="chapters"
    :rights="rights"
    :source="source"
    @rename="emit('rename')"
    @move="emit('move', node)"
    @delete="emit('delete')"
    @create-chapter="emit('createChild', CHAPTER_KIND)"
    @open-chapter="emit('openNode', $event)"
    @share="emit('share', $event)"
    @share-upload="emit('shareUpload')"
    @meta-updated="onMetaUpdated"
    @edit-cover="cover.open(node)"
    @report-medium="onReportMedium"
  />
  <CoverEditor :cover="cover" />
</template>
