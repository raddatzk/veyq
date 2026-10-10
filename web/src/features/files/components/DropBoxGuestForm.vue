<script setup lang="ts">
// The public page of a drop-box link (cr-files-application F3, ADR 0080 decision 11; what Files' share body for
// `FILES_DROPBOX`, `DropBoxShareBody`, renders on the link rooted at the drop box): a WRITE_ONLY guest names itself once (its own identity key `K_up`, `dropBox.ts`), gets its one
// folder in the drop box, picks files and uploads them there — one core-queue job per file, the folder contributor's
// guest job (`shareUpload.ts`) with its folder as the one key it holds. It sees nothing of the drop box or of other
// guests (the resolve serves no node, `listShareFiles` an empty list): only what it sent in this session. Its session
// (identity, token, `K_up`, the name it gave) stays on the device, so a reload keeps uploading into the same folder.
//
// Inbox #385 (a): the page says whose session it is with a "Not you?" that forgets it on this device and asks for a name
// again — a new identity; the old one's folder stays in the drop box untouched. Both the form and that line are core's one
// guest-naming component (`core/sharing/GuestNaming.vue`, inbox #401), which asks before it forgets. The same action
// starts over after the link stopped taking files from this guest (its folder trashed, inbox #381). The folder is made
// with the FIRST file, not when the guest names itself (`ensureDropFolder` is idempotent: the same folder on every later
// call), so a visitor who names itself and leaves leaves no empty folder behind — only the identity.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
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
import GuestNaming from '../../../core/sharing/GuestNaming.vue'
import { FileUploadError } from '../uploadFile'

const props = defineProps<ShareBodyProps>()
const { t, locale } = useI18n()

const session = ref<DropGuestSession | null>(loadDropGuest(props.shareId))
/** A start over after a gone link is reading the link again. */
const restarting = ref(false)
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

/** The guest names itself (core's form): its own identity key and folder-to-be, the session kept on the device. A link
 *  that takes nothing any more ends the form; any other refusal is the form's own message. */
async function start(name: string) {
  error.value = null
  try {
    const named = await nameDropGuest(props.head, { name, pwToken: props.pwToken })
    saveDropGuest(props.shareId, named)
    session.value = named
  } catch (e) {
    if (await isDropLinkGone(e)) linkGone.value = true
    else throw e
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
    const name = session.value.displayName || t('files.dropBox.guestNameFallback')
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

/** "Not you?" / start over: forget this device's session and ask for a name again. Nothing on the server changes.
 *  "Not you?" asks first (core's `GuestNaming`) — the session is the only way back into this folder from here (inbox
 *  #395). After a gone link it does not ask: the link itself is read again first (inbox #393 (a)): a revoked or deleted
 *  one leaves the shell's "not available" page (its `reload` says so) instead of a name form for a link that takes
 *  nothing; one that still resolves — this guest's folder was trashed, the link lives — starts over under a new name. */
async function notYou() {
  if (linkGone.value) {
    restarting.value = true
    try {
      await props.reload() // a dead link: the shell turns to its unavailable state and this page goes
    } finally {
      restarting.value = false
    }
  }
  forgetDropGuest(props.shareId)
  session.value = null
  folder = null
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
    <GuestNaming v-else-if="!session" :action="start" :hint="t('files.dropBox.guestNameHint')" :error-text="() => t('files.dropBox.guestFailed')" />

    <template v-else>
      <GuestNaming
        :current="session"
        :forget="notYou"
        :forget-label="linkGone ? t('files.dropBox.guestStartOver') : undefined"
        :ask-before-forget="!linkGone"
        :forget-disabled="sending"
        :forget-busy="restarting"
      />
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
