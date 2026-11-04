<script setup lang="ts">
// FILE's `shareDialog` (inbox #378): the links of ONE file, READ (see and download it) or READ_WRITE (and replace its
// content) — a single-file share, ADR 0080 decisions 1 and 6: the file's own key goes under `K_share` like any node's,
// so the guest opens that file and nothing beside or above it. It is core's link manager (`FolderShareDialog`: list,
// create, edit, copy, switch off, renew, delete) with the sentences about WHAT is shared swapped for the file's
// (`ShareDialogCopy`): there is no second manager to drift. A `WRITE_ONLY` link on a file does not exist (the backend's
// `DepotShareRoots` refuses it), and the dialog never offers one — core's dialog has no such option for any kind.
import type { NodeShareDialogProps } from '../../../core/applications/registry'
import type { ShareDialogCopy } from '../../../core/sharing/shareDialogCopy'
import FolderShareDialog from '../../../core/sharing/FolderShareDialog.vue'

const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()

const words: ShareDialogCopy = {
  title: 'depot.share.title',
  intro: 'depot.share.intro',
  roleContributor: 'depot.share.roleContributor',
  roleViewerHint: 'depot.share.roleViewerHint',
  roleContributorHint: 'depot.share.roleContributorHint',
  deleteConfirm: 'depot.share.deleteConfirm',
  reissueWarning: 'depot.share.reissueWarning',
}
</script>

<template>
  <FolderShareDialog v-bind="props" :words="words" @close="emit('close')" />
</template>
