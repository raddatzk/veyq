<script setup lang="ts">
// Files' share body for `FILES_DROPBOX` (`register.ts` `claimShareBody`). Two pages, picked by where the drop box sits
// in the link, never by a role the client could misread:
//
// - **The link rooted at the drop box** (no `subtreeRoot`; such a link is WRITE_ONLY only, `FilesShareRoots`): the
//   guest's page — name yourself, send files into your own folder (`DropBoxGuestForm.vue`).
// - **Embedded by core's folder page** (`subtreeRoot` set): a READ or READ_WRITE guest of a folder link ABOVE the drop
//   box. It holds the drop box's key, and the served subtree carries its drop private key and its guests' identity
//   folders (ADR 0080 decision 11), so it sees them — read-only (`DropBoxSharedView.vue`): no form to send files, no
//   delete, no move (owner decision 2026-10-08, inbox #380; the server refuses a guest's write in an identity folder and
//   any guest move out of one, `NodesHandler`/`ShareNodesHandler`). Embedding the guest's page here once offered a
//   WRITE_ONLY form under the folder's name that then failed.
import type { ShareBodyProps } from '../../../core/applications/registry'
import DropBoxGuestForm from './DropBoxGuestForm.vue'
import DropBoxSharedView from './DropBoxSharedView.vue'

const props = defineProps<ShareBodyProps>()
const emit = defineEmits<{ unavailable: [] }>()
</script>

<template>
  <DropBoxSharedView v-if="props.subtreeRoot" v-bind="props" :subtree-root="props.subtreeRoot" @unavailable="emit('unavailable')" />
  <DropBoxGuestForm v-else v-bind="props" />
</template>
