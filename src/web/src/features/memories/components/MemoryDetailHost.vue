<script setup lang="ts">
// The Memories application's adapter between the host's generic node-detail context (core/applications/registry:
// `NodeDetailProps`) and the props/events `MemoryDetail` takes (ADR 0079): everything Memories-shaped — the pin
// state, the item-mark key, the personal-scope flag, the chapters bucket, the meta patch — is resolved here,
// inside the application, and so are the RIGHTS (inbox #401): Memories' own fail-closed matrix over the host's
// `access` and the opened node (`../rights.ts`), narrowed here to the page's doors (`core/node/nodeHost.ts`: what
// `MemoryDetail` asks of the page - rename, move, delete, share, a chapter's create, a chapter opened - goes through them)
// and by `MemoryDetail` to its data source's doors.
//
// **The data source is picked here, once** (inbox #401 S4, `../source.ts`): the member's over the unlocked scope, or a
// share guest's over its link — by `access.mode`, which does not change while the detail is mounted (a page mounts it
// per node and session). For a guest nothing of the member's world is read: no `activeSpace` (the item-mark key, the
// scope kind), no pin store, no `treeStore` patch — the guest's rights grant none of them, and the guest's source
// carries no door to them. A guest gets it from core's share page (`core/sharing/ShareTreePage.vue`) for every Memory and
// chapter of a `READ` or `READ_WRITE` link, the link's root included (inbox #401 S5).
import { computed } from 'vue'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { hostRights, injectNodeHost } from '../../../core/node/nodeHost'
import { CHAPTER_KIND } from '../kinds'
import { activeSpace, activeEpochKeys } from '../../../app/activeSpace'
import { canReport, reportScope } from '../../../core/report/reportActions'
import { openReport, useReportOffered } from '../../../core/report/report'
import { mediumReportKeys, type MediaItem } from '../media'
import { useNodeCover } from '../../../core/node/cover/useNodeCover'
import { injectCoreNodeActions } from '../../../core/node/coreNodeActions'
import CoverEditor from '../../../core/node/cover/components/CoverEditor.vue'
import { patchNodeMeta } from '../../../core/node/treeStore'
import { memoryRights } from '../rights'
import { targetOf } from '../../../core/access'
import { guestMemorySource, memberMemorySource } from '../source'
import MemoryDetail from './MemoryDetail.vue'
import type { ScopeKey } from '../../spaces/epochs'

const props = defineProps<NodeDetailProps>()
const host = injectNodeHost()

const chapters = computed(() => props.children.byKind.get(CHAPTER_KIND) ?? [])
// An absent door is an action not offered: the chapter create needs the page's `createChild`, the rest `hostRights`.
const rights = computed(() => {
  const r = hostRights(memoryRights(props.access, targetOf(props.tree, props.node)), host)
  return { ...r, createChapter: r.createChapter && !!host.createChild }
})

// Who looks is fixed for this mount (see the header).
const guest = props.access.mode === 'guest' ? props.access : null
const source = guest
  ? guestMemorySource(() => (props.access.mode === 'guest' ? props.access : guest), props.tree)
  : memberMemorySource({ scopeId: () => props.scopeId })

// Item marks seal under the scope's one key at its epoch (ADR 0115 decisions 1 and 3) — every rotation re-seals them
// under the new key, through Memories' scope re-key participant. `activeSpace.epoch` is the reactive signal that a
// rotation happened — without reading it this would not recompute after `refreshActiveScope`. A member's only.
const itemMarkKey = computed<ScopeKey | null>(() => {
  if (guest) return null
  void activeSpace.epoch
  const eks = activeEpochKeys()
  return eks ? { epoch: eks.epoch, key: eks.key } : null
})
const personalScope = computed(() => !guest && activeSpace.kind === 'PERSONAL')
// Core's own actions (the heart, the cover, "Copy to…", the download, Report) come with core's header for this node
// (`MemoryDetail` hands `NodeHeader` its `node`). The cover tile - the header's leading visual - opens core's cover DIALOG
// too, the page's one (core's frame `NodePage` mounts it; mounted bare, this view has its own, `CoverEditor` below): a
// cover is set only by an upload there. The Memory only says where an equal picture is already stored (its descriptor's
// `cover.findStored`), so the cover shares it.
const page = injectCoreNodeActions()
const cover = page?.cover ?? useNodeCover(() => props.access, () => props.tree, () => [props.node])
const reportOn = useReportOffered()
// Core's "Report" (uc.moderation.report) for one medium in the lightbox, beside the header's for the Memory itself: the
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
    :cover-editing="cover.editor.value !== null"
    :can-set-cover="cover.canSet(node)"
    :can-report="reportsMedia"
    :chapters="chapters"
    :rights="rights"
    :source="source"
    @rename="host.rename?.(node)"
    @move="host.move?.(node)"
    @delete="host.delete?.(node)"
    @create-chapter="host.createChild?.(CHAPTER_KIND, node)"
    @open-chapter="host.open?.($event)"
    @share="host.share?.(node, $event)"
    @share-upload="host.share?.(node, { contribute: false, uploader: true })"

    @meta-updated="onMetaUpdated"
    @edit-cover="cover.open(node)"
    @report-medium="onReportMedium"
  />
  <CoverEditor v-if="!page" :cover="cover" />
</template>
