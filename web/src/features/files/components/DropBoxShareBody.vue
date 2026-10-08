<script setup lang="ts">
// The public page of a drop-box link (cr-files-application F3, ADR 0080 decision 11; Files' `claimShareBody` for
// `FILES_DROPBOX`): a WRITE_ONLY guest names itself once (its own identity key `K_up`, `dropBox.ts`), gets its one
// folder in the drop box, picks files and uploads them there — one core-queue job per file, the folder contributor's
// guest job (`shareUpload.ts`) with its folder as the one key it holds. It sees nothing of the drop box or of other
// guests (the resolve serves no node, `listShareFiles` an empty list): only what it sent in this session. Its session
// (identity, token, `K_up`) stays on the device, so a reload keeps uploading into the same folder.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import type { ShareBodyProps } from '../../../core/applications/registry'
import { enqueue, useUploadQueue } from '../../../core/blob/queue'
import { dropGuestContext, ensureDropFolder, loadDropGuest, nameDropGuest, saveDropGuest, type DropFolder, type DropGuestSession } from '../dropBox'
import { guestFileUploadJob } from '../shareUpload'

const props = defineProps<ShareBodyProps>()
const { t } = useI18n()

const session = ref<DropGuestSession | null>(loadDropGuest(props.shareId))
const displayName = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const picker = ref<HTMLInputElement | null>(null)
// The folder's key is key material: kept out of a deep reactive ref (a plain variable, read when the jobs start).
let folder: DropFolder | null = null

const queue = useUploadQueue()
const batchKey = computed(() => `files-drop:${props.shareId}`)
const sent = computed(() =>
  queue.batches.value.filter((b) => b.key === batchKey.value).flatMap((b) => b.files).filter((f) => f.status === 'done').map((f) => f.name),
)

async function start() {
  const name = displayName.value.trim()
  if (!name) return
  busy.value = true
  error.value = null
  try {
    const named = await nameDropGuest(props.head, { name, pwToken: props.pwToken })
    saveDropGuest(props.shareId, named)
    session.value = named
    folder = await ensureDropFolder(props.head, session.value, name, props.pwToken)
  } catch {
    error.value = t('files.dropBox.guestFailed')
  } finally {
    busy.value = false
  }
}

async function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !session.value) return
  error.value = null
  try {
    // The guest's one folder: made on its first upload, the same one on every later (and after a reload); the name
    // sent with the first call is the folder's, later calls' names are ignored by the server.
    folder ??= await ensureDropFolder(props.head, session.value, displayName.value.trim() || props.head.name, props.pwToken)
  } catch {
    error.value = t('files.dropBox.guestGone')
    return
  }
  const ctx = dropGuestContext(props.head, session.value, folder, props.pwToken)
  enqueue(files.map((file) => guestFileUploadJob({ ctx, parentId: folder!.nodeId }, file)), { label: props.head.name, key: batchKey.value })
}
</script>

<template>
  <section class="veyq-section dropbox-guest">
    <div class="veyq-section-header">
      <h2><span class="pi pi-inbox" aria-hidden="true" /> {{ t('files.dropBox.guestTitle') }}</h2>
    </div>
    <p>{{ t('files.dropBox.guestHint', { name: head.name }) }}</p>

    <form v-if="!session" class="veyq-form" @submit.prevent="start">
      <label>
        {{ t('files.dropBox.guestNameLabel') }}
        <InputText v-model="displayName" autocomplete="name" required :disabled="busy" />
        <small>{{ t('files.dropBox.guestNameHint') }}</small>
      </label>
      <div class="veyq-dialog-action-row">
        <Button type="submit" :label="t('files.dropBox.guestStart')" icon="pi pi-arrow-right" :loading="busy" :disabled="!displayName.trim()" />
      </div>
    </form>

    <template v-else>
      <input ref="picker" class="dropbox-picker" type="file" multiple hidden @change="onPicked" />
      <div>
        <Button type="button" :label="t('files.dropBox.guestPick')" icon="pi pi-upload" @click="picker?.click()" />
      </div>
      <div v-if="sent.length">
        <h3>{{ t('files.dropBox.guestSent') }}</h3>
        <ul class="veyq-list">
          <li v-for="(name, i) in sent" :key="`${i}:${name}`" class="veyq-list-row">
            <span class="pi pi-check" aria-hidden="true" />
            <span class="veyq-row-main"><span class="veyq-row-title">{{ name }}</span></span>
          </li>
        </ul>
      </div>
    </template>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </section>
</template>
