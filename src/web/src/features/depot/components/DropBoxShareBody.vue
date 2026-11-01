<script setup lang="ts">
// Depot's share body for `DEPOT_DROPBOX` (`register.ts` `app.shareBody`): the page of the link rooted at the drop box —
// such a link is WRITE_ONLY only (`DepotShareRoots`), so its guest holds no key to the drop box and has nothing to see
// the member's way: name yourself, send files into your own folder (`DropBoxGuestForm.vue`). The one legitimate
// guest-only page of Depot (ADR 0079 Consequences, inbox #401).
//
// A READ or READ_WRITE guest of a folder link ABOVE the drop box never gets this body: core's page renders the drop box's
// `detail` (`DropBoxDetail.vue`) for it, handed the guest's access and tree — read-only (owner decision 2026-10-08,
// inbox #380). Embedding this page there once offered a WRITE_ONLY form under the folder's name that then failed.
import type { ShareBodyProps } from '../../../core/applications/registry'
import DropBoxGuestForm from './DropBoxGuestForm.vue'

const props = defineProps<ShareBodyProps>()
// Declared, so the shell's listener does not fall through to the form (which reports a gone link in its own page).
defineEmits<{ unavailable: [] }>()
</script>

<template>
  <DropBoxGuestForm v-bind="props" />
</template>
