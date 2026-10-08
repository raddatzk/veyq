<script setup lang="ts">
// The public page of a drop-box link (cr-files-application F3, ADR 0080 decision 11; Files' `claimShareBody` for
// `FILES_DROPBOX`): a WRITE_ONLY guest names itself once (its own identity key `K_up`, `dropBox.ts`), gets its one
// folder in the drop box, picks files and uploads them there — one core-queue job per file, the folder contributor's
// guest job (`shareUpload.ts`) with its folder as the one key it holds. It sees nothing of the drop box or of other
// guests (the resolve serves no node, `listShareFiles` an empty list): only what it sent in this session. Its session
// (identity, token, `K_up`, the name it gave) stays on the device, so a reload keeps uploading into the same folder.
//
// Inbox #385 (a): the page says whose session it is ("Sending as Anna") with a "Not you?" that forgets it on this device
// and asks for a name again — a new identity; the old one's folder stays in the drop box untouched. The same action
// starts over after the link stopped taking files from this guest (its folder trashed, inbox #381). The folder is made
// with the FIRST file, not when the guest names itself (`ensureDropFolder` is idempotent: the same folder on every later
// call), so a visitor who names itself and leaves leaves no empty folder behind — only the identity.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import type { ShareBodyProps } from '../../../core/applications/registry'
import { enqueue, retryFile, useUploadQueue, QUOTA_EXCEEDED, type UploadItem } from '../../../core/blob/queue'
import { formatBytes } from '../../../shared/formatBytes'
import {
  DROP_LINK_GONE, dropGuestContext, dropGuestJob, ensureDropFolder, forgetDropGuest, isDropLinkGone, loadDropGuest, nameDropGuest,
  saveDropGuest,
  type DropFolder, type DropGuestSession,
} from '../dropBox'
import { guestFileUploadJob } from '../shareUpload'
import { FileUploadError } from '../uploadFile'

const props = defineProps<ShareBodyProps>()
const { t, locale } = useI18n()

const session = ref<DropGuestSession | null>(loadDropGuest(props.shareId))
const displayName = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const picker = ref<HTMLInputElement | null>(null)
// The folder's key is key material: kept out of a deep reactive ref (a plain variable, read when the jobs start).
let folder: DropFolder | null = null

// The link no longer takes files from this guest (revoked, deleted, its folder trashed — inbox #381): the page says so
// and offers no picker. Set by a failed folder call here and by any job (`dropGuestJob`), which then stops the rest.
const linkGone = ref(false)
const link = {
  get gone() {
    return linkGone.value
  },
  set gone(v: boolean) {
    linkGone.value = v
  },
}

const queue = useUploadQueue()
// Per identity: after "Not you?" the next visitor does not see the files the one before sent.
const batchKey = computed(() => `files-drop:${props.shareId}:${session.value?.identityId ?? ''}`)
/** Every file of this session but the cancelled ones: the guest page has no upload menu, so this list is where a
 *  guest sees what is being sent, what arrived and what did not, and why. */
const items = computed(() =>
  queue.batches.value.filter((b) => b.key === batchKey.value).flatMap((b) => b.files).filter((f) => f.status !== 'cancelled'),
)

/** Why a file was not sent, in the guest's words. */
function reason(item: UploadItem): string {
  switch (item.error) {
    case DROP_LINK_GONE:
      return t('files.dropBox.guestErrorGone')
    case QUOTA_EXCEEDED:
      return t('files.dropBox.guestErrorFull')
    case FileUploadError.slugTaken:
      return t('files.dropBox.guestErrorNameTaken')
    default:
      return t('files.dropBox.guestErrorFailed')
  }
}
/** What a row says under the name: its size once sent, that it is being sent, or why it was not. */
function detail(item: UploadItem): string {
  if (item.status === 'failed') return reason(item)
  if (item.status === 'done') return t('files.dropBox.guestDone', { size: formatBytes(item.sizeBytes, locale.value) })
  return t('files.dropBox.guestSending')
}
function icon(item: UploadItem): string {
  if (item.status === 'done') return 'pi pi-check'
  if (item.status === 'failed') return 'pi pi-times-circle'
  return 'pi pi-spin pi-spinner'
}
/** A retry can help: not a gone link, not a full one, not a name this guest already sent. */
const retryable = (item: UploadItem) =>
  item.status === 'failed' && !linkGone.value && ![DROP_LINK_GONE, QUOTA_EXCEEDED, FileUploadError.slugTaken].includes(item.error ?? '')

/** A file of this session is still on its way: "Not you?" waits for it (it goes out under the current identity). */
const sending = computed(() => items.value.some((f) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading'))

async function start() {
  const name = displayName.value.trim()
  if (!name) return
  busy.value = true
  error.value = null
  try {
    const named = await nameDropGuest(props.head, { name, pwToken: props.pwToken })
    saveDropGuest(props.shareId, named)
    session.value = named
  } catch (e) {
    if (await isDropLinkGone(e)) linkGone.value = true
    else error.value = t('files.dropBox.guestFailed')
  } finally {
    busy.value = false
  }
}

async function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !session.value || linkGone.value) return
  error.value = null
  try {
    // The guest's one folder: made on its first upload, the same one on every later (and after a reload); the name
    // sent with the first call is the folder's, later calls' names are ignored by the server. A session saved before
    // the name was kept already has its folder (it was made when the guest named itself), so its fallback is never used.
    const name = session.value.displayName || displayName.value.trim() || t('files.dropBox.guestNameFallback')
    folder ??= await ensureDropFolder(props.head, session.value, name, props.pwToken)
  } catch (err) {
    if (await isDropLinkGone(err)) linkGone.value = true
    else error.value = t('files.dropBox.guestFailed')
    return
  }
  const ctx = dropGuestContext(props.head, session.value, folder, props.pwToken)
  enqueue(
    files.map((file) => dropGuestJob(guestFileUploadJob({ ctx, parentId: folder!.nodeId }, file), link)),
    { label: props.head.name, key: batchKey.value },
  )
}

/** "Not you?" / start over: forget this device's session and ask for a name again. Nothing on the server changes. */
function notYou() {
  forgetDropGuest(props.shareId)
  session.value = null
  folder = null
  displayName.value = ''
  error.value = null
  linkGone.value = false
}
</script>

<template>
  <section class="veyq-section dropbox-guest">
    <div class="veyq-section-header">
      <h2><span class="pi pi-inbox" aria-hidden="true" /> {{ t('files.dropBox.guestTitle') }}</h2>
    </div>
    <p>{{ t('files.dropBox.guestHint', { name: head.name }) }}</p>

    <!-- Refused before a session exists (the link is gone): said, with no form to try again. -->
    <Message v-if="!session && linkGone" severity="error" :closable="false">{{ t('files.dropBox.guestGone') }}</Message>
    <form v-else-if="!session" class="veyq-form" @submit.prevent="start">
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
      <p class="dropbox-guest-identity">
        <span v-if="session.displayName">{{ t('files.dropBox.guestAs', { name: session.displayName }) }}</span>
        <Button
          type="button"
          class="dropbox-guest-not-you"
          :label="linkGone ? t('files.dropBox.guestStartOver') : t('files.dropBox.guestNotYou')"
          size="small"
          severity="secondary"
          text
          :disabled="sending"
          @click="notYou"
        />
      </p>
      <Message v-if="linkGone" severity="error" :closable="false">{{ t('files.dropBox.guestGone') }}</Message>
      <template v-else>
        <input ref="picker" class="dropbox-picker" type="file" multiple hidden @change="onPicked" />
        <div>
          <Button type="button" :label="t('files.dropBox.guestPick')" icon="pi pi-upload" @click="picker?.click()" />
        </div>
      </template>
      <div v-if="items.length">
        <h3>{{ t('files.dropBox.guestSession') }}</h3>
        <ul class="veyq-list">
          <li v-for="item in items" :key="item.id" class="veyq-list-row dropbox-guest-row" :data-status="item.status">
            <span :class="icon(item)" aria-hidden="true" />
            <span class="veyq-row-main">
              <span class="veyq-row-title" :title="item.name">{{ item.name }}</span>
              <span class="veyq-row-subtitle dropbox-guest-detail">{{ detail(item) }}</span>
            </span>
            <Button
              v-if="retryable(item)"
              type="button"
              :label="t('files.dropBox.guestRetry')"
              :aria-label="t('files.dropBox.guestRetryLabel', { name: item.name })"
              size="small"
              severity="secondary"
              text
              @click="retryFile(item.id)"
            />
          </li>
        </ul>
      </div>
    </template>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
  </section>
</template>

<style scoped>
.dropbox-guest-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  margin: 0;
}
/* A session row: the status icon inline before the name (the list row's grid stacks it on a phone), the name cut
   with its full text in the tooltip, the reason a file was not sent wrapped rather than cut. */
.dropbox-guest-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.dropbox-guest-row .veyq-row-main {
  flex: 1 1 auto;
}
.dropbox-guest-row[data-status='failed'] .pi {
  color: var(--app-danger);
}
.dropbox-guest-row[data-status='done'] .pi {
  color: var(--app-success-text);
}
.dropbox-guest-detail {
  white-space: normal;
}
</style>
