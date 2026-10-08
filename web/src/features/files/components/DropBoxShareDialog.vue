<script setup lang="ts">
// The drop box's `shareDialog` (cr-files-application F3): its WRITE_ONLY links — list, create, copy, switch off,
// delete — on core's `manageShares`/`shareLink`, as core's `FolderShareDialog` builds a folder's. A drop box is shared
// WRITE_ONLY only (the backend's `FilesShareRoots`), so there is no role to pick: a new link carries the drop box's drop
// public key (`mintDropBoxLink`), never its key. Not built here (the folder dialog's extras): a label, a note, a
// password, an expiry, a renew.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import type { NodeShareDialogProps } from '../../../core/applications/registry'
import { deleteShare, listSharesFor, revokeShare, type ManagedShare } from '../../../core/sharing/manageShares'
import { buildShareLink, recoverShareLink } from '../../../core/sharing/shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import { mintDropBoxLink } from '../dropBox'

const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()

const shares = ref<ManagedShare[]>([])
const fresh = ref<string | null>(null)
const busy = ref(false)
const failed = ref(false)
const copied = ref<string | null>(null)

const links = computed(() =>
  shares.value
    .filter((s) => s.role === ShareRole.WriteOnly)
    .map((s) => ({ share: s, link: recoverShareLink({ scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId }, props.epochs, s) })),
)

async function load() {
  shares.value = await listSharesFor(props.nodeId)
}
onMounted(() => load().catch(() => (failed.value = true)))

async function run(action: () => Promise<void>) {
  busy.value = true
  failed.value = false
  try {
    await action()
    await load()
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}

const create = () =>
  run(async () => {
    const minted = await mintDropBoxLink(props.scopeId, props.nodeId, props.nodeKey, props.epochs, props.nodeName)
    fresh.value = buildShareLink(minted.shareId, minted.kShare)
  })

async function copy(text: string, id: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = id
  } catch {
    /* clipboard denied — the field stays selectable */
  }
}
</script>

<template>
  <Dialog :visible="true" :header="t('files.dropBox.shareTitle')" modal @update:visible="emit('close')">
    <div class="veyq-form">
      <p>{{ t('files.dropBox.shareHint') }}</p>
      <div v-if="fresh" class="dropbox-link">
        <InputText :model-value="fresh" readonly :aria-label="t('files.dropBox.shareCopy')" />
        <Button type="button" icon="pi pi-copy" :label="copied === 'fresh' ? t('files.dropBox.shareCopied') : t('files.dropBox.shareCopy')" @click="copy(fresh, 'fresh')" />
      </div>
      <p v-if="!links.length" class="veyq-empty veyq-empty-inline">{{ t('files.dropBox.shareNone') }}</p>
      <ul v-else class="veyq-list">
        <li v-for="row in links" :key="row.share.shareId" class="veyq-list-row">
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ row.share.enabled ? row.link ?? row.share.shareId : t('files.dropBox.shareOff') }}</span>
          </span>
          <span class="veyq-action-group veyq-action-group-icons">
            <Button
              v-if="row.link && row.share.enabled"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-copy"
              :aria-label="t('files.dropBox.shareCopy')"
              severity="secondary"
              outlined
              size="small"
              @click="copy(row.link, row.share.shareId)"
            />
            <Button
              v-if="row.share.enabled"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-power-off"
              :aria-label="t('files.dropBox.shareRevoke')"
              severity="secondary"
              outlined
              size="small"
              :disabled="busy"
              @click="run(() => revokeShare(row.share.shareId))"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-trash"
              :aria-label="t('files.dropBox.shareDelete')"
              severity="danger"
              outlined
              size="small"
              :disabled="busy"
              @click="run(() => deleteShare(row.share.shareId))"
            />
          </span>
        </li>
      </ul>
      <Message v-if="failed" severity="error" :closable="false">{{ t('files.dropBox.shareFailed') }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('files.dropBox.close')" severity="secondary" outlined @click="emit('close')" />
        <Button type="button" :label="t('files.dropBox.shareCreate')" icon="pi pi-plus" :loading="busy" @click="create" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.dropbox-link {
  display: flex;
  gap: 0.5rem;
}
.dropbox-link :deep(input) {
  flex: 1;
  min-width: 0;
}
</style>
