<script setup lang="ts">
// EXPENSES_POT's `shareDialog` (uc.expenses.share-pot, ADR 0108 decision 2): the links of one Pot, READ
// (see its entries, balances and overviews) or READ_WRITE (and add and change entries and people) — core's link manager
// with the sentences about WHAT is shared swapped for the Pot's (`ShareDialogCopy`). A `WRITE_ONLY` link on a Pot does
// not exist (the backend's share roots refuse it), and core's dialog offers none for any kind.
import type { NodeShareDialogProps } from '../../../core/applications/registry'
import type { ShareDialogCopy } from '../../../core/sharing/shareDialogCopy'
import FolderShareDialog from '../../../core/sharing/FolderShareDialog.vue'

const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()

const words: ShareDialogCopy = {
  title: 'expenses.share.title',
  intro: 'expenses.share.intro',
  roleViewerHint: 'expenses.share.roleViewerHint',
  roleContributorHint: 'expenses.share.roleContributorHint',
  deleteConfirm: 'expenses.share.deleteConfirm',
  reissueWarning: 'expenses.share.reissueWarning',
}
</script>

<template>
  <FolderShareDialog v-bind="props" :words="words" @close="emit('close')" />
</template>
