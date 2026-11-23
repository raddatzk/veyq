<script setup lang="ts">
// The public page of a drop-box link (cr-files-application F3, ADR 0080 decision 11; what Depot's share body for
// `DEPOT_DROPBOX`, `DropBoxShareBody`, renders on the link rooted at the drop box): a WRITE_ONLY guest picks files and
// uploads them into its one folder in the drop box — one core-queue job per file, the folder contributor's guest job
// (`shareUpload.ts`) with its folder as the one key it holds. It sees nothing of the drop box or of other guests (the
// resolve serves no node, `listShareFiles` an empty list): only what it sent in this session. Its session (identity,
// token, its own identity key `K_up`, `dropBox.ts`, the name it gave) stays on the device, so a reload keeps uploading
// into the same folder.
//
// Its name is asked at its first pick, never before (inbox #1090, ADR 0154 decision 1: every client asks a guest's name
// at the write): the shell's one naming dialog (`GuestAccess.requestGuest`, with this page's own identity as what the
// name makes), so the page's body holds no name form. Declined, nothing is sent and nothing is said. The identity AND the
// folder are made with that first pick (inbox #385 (a); `ensureDropFolder` is idempotent: the same folder on every later
// call), so a visitor who leaves without picking leaves nothing behind on the server.
//
// Inbox #385 (a): once named, the page says whose session it is with a "Not you?" that forgets it on this device — the
// next pick asks for a name again, a new identity; the old one's folder stays in the drop box untouched. That line is
// core's one guest-naming component (`core/sharing/GuestNaming.vue`, inbox #401), which asks before it forgets, as a
// READ_WRITE link's line does. The same action starts over after the link stopped taking files from this guest (its
// folder trashed, inbox #381).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import type { VeyqIconName } from '../../../shared/ui/icons'
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
import type { GuestIdentity } from '../../../core/sharing/guestIdentity'
import OrderNotice from '../../../core/sharing/OrderNotice.vue'
import { shareAtOf } from '../../../core/sharing/resolvePublicShare'
import StatementNotice from '../../moderation/statement/StatementNotice.vue'
import { FileUploadError } from '../uploadFile'

const props = defineProps<ShareBodyProps>()
const { t, locale } = useI18n()

// The link as what this device keeps of it is keyed: its id and the instance that serves it (ADR 0121).
const at = shareAtOf(props.head)
const session = ref<DropGuestSession | null>(loadDropGuest(at))
/** The first pick is asking the name, or creating the identity or the folder: a second pick in the meantime waits for it
 *  (no second question, no second identity), and "Not you?" waits for it, as for a file on its way. */
let preparation: Promise<DropGuestSession | null> | null = null
/** A pick is being prepared (see {@link preparation}). */
const preparing = ref(false)
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
      return t('depot.dropBox.guestErrorGone')
    case QUOTA_EXCEEDED:
      return t('depot.dropBox.guestErrorFull')
    case FileUploadError.slugTaken:
      return t('depot.dropBox.guestErrorNameTaken')
    default:
      return t('depot.dropBox.guestErrorFailed')
  }
}
/** What a row says under the name: its size once sent, that it is being sent, or why it was not. */
function detail(item: UploadItem): string {
  if (item.status === 'failed') return reason(item)
  if (item.status === 'done') return t('depot.dropBox.guestDone', { size: formatBytes(item.sizeBytes, locale.value) })
  return t('depot.dropBox.guestSending')
}
function icon(item: UploadItem): VeyqIconName {
  if (item.status === 'done') return 'check'
  if (item.status === 'failed') return 'times-circle'
  return 'spinner'
}
/** A retry can help: not a gone link, not a full one, not a name this guest already sent. */
const retryable = (item: UploadItem) =>
  item.status === 'failed' && !linkGone.value && ![DROP_LINK_GONE, QUOTA_EXCEEDED, FileUploadError.slugTaken].includes(item.error ?? '')

/** A file of this session is still on its way: "Not you?" waits for it (it goes out under the current identity). */
const sending = computed(() => items.value.some((f) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading'))

/** What the guest's name makes when the shell's dialog asks it: its identity under its own `K_up`, kept on the device
 *  from here on. A link gone at this call is the page's gone state (the dialog closes, said here, not there). */
async function makeIdentity(name: string): Promise<GuestIdentity | null> {
  try {
    const named = await nameDropGuest(props.head, { name, pwToken: props.pwToken })
    saveDropGuest(at, named)
    session.value = named
    return named
  } catch (err) {
    if (!(await isDropLinkGone(err))) throw err // the dialog says it and stays open
    linkGone.value = true
    return null // closes the dialog: the gone page says why nothing was sent
  }
}

/** The guest's identity and its folder for a pick: the saved session, else its name asked now (the shell's dialog) —
 *  null where it declined, or the link is gone. */
async function prepare(): Promise<DropGuestSession | null> {
  if (!session.value) {
    await props.access.requestGuest(makeIdentity)
    if (!session.value || linkGone.value) return null // declined (nothing to say), or gone (the page says so)
  }
  const guest = session.value
  // The guest's one folder: made on its first upload, the same one on every later (and after a reload); the name sent
  // with the first call is the folder's, later calls' names are ignored by the server. A session saved before the name
  // was kept already has its folder, so its fallback is never used.
  const name = guest.displayName || t('depot.dropBox.guestNameFallback')
  folder ??= await ensureDropFolder(props.head, guest, name, props.pwToken)
  return guest
}

async function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || linkGone.value) return
  error.value = null
  preparing.value = true
  let guest: DropGuestSession | null
  try {
    guest = await (preparation ??= prepare().finally(() => (preparation = null)))
  } catch (err) {
    if (await isDropLinkGone(err)) linkGone.value = true
    else error.value = t('depot.dropBox.guestFailed')
    return
  } finally {
    preparing.value = false
  }
  if (!guest || !folder) return
  const ctx = dropGuestContext(props.head, guest, folder, props.pwToken)
  enqueue(
    files.map((file) => dropGuestJob(guestFileUploadJob({ ctx, parentId: folder!.nodeId }, file), link)),
    { label: props.head.name, key: batchKey.value },
  )
}

/** "Not you?" / start over: forget this device's session; the next pick asks for a name again. Nothing on the server
 *  changes.
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
  forgetDropGuest(at)
  session.value = null
  folder = null
  error.value = null
  linkGone.value = false
}
</script>

<template>
  <section class="veyq-section dropbox-guest">
    <div class="veyq-section-header">
      <h2><VeyqIcon name="inbox" /> {{ t('depot.dropBox.guestTitle') }}</h2>
    </div>
    <p>{{ t('depot.dropBox.guestHint', { name: head.name }) }}</p>

    <!-- Whose session this is, once the guest named itself at its first pick (inbox #1090): "Not you?" asks first, as a
         READ_WRITE link's line does; after a gone link it starts over without asking. Before that nothing is said here:
         the record's notice comes with the name, in the shell's dialog. -->
    <GuestNaming
      v-if="session"
      :current="session"
      :forget="notYou"
      :forget-label="linkGone ? t('depot.dropBox.guestStartOver') : undefined"
      :ask-before-forget="!linkGone"
      :forget-disabled="sending || preparing"
      :forget-busy="restarting"
      :record-days="head.share.guestWriteRecordDays"
    />
    <VeyqMessage v-if="linkGone" tone="error">{{ t('depot.dropBox.guestGone') }}</VeyqMessage>
    <!-- ADR 0093 § 7: a drop page has no placeholder, so every statement of reasons a moderator's measure on this
         guest's uploads — or on the guest, or the link — gave it is shown here, whether or not the link still lives. -->
    <StatementNotice v-if="session" every-guest-statement />
    <!-- TCO Art. 11: when the link went away under an upload, an authority's removal order may be why. -->
    <OrderNotice v-if="linkGone" :share-id="shareId" :api-base="head.apiBase" />
    <template v-else>
      <input ref="picker" class="dropbox-picker" type="file" multiple hidden @change="onPicked" />
      <div>
        <VeyqButton :label="t('depot.dropBox.guestPick')" icon="upload" @click="picker?.click()" />
      </div>
    </template>
    <div v-if="items.length">
      <h3>{{ t('depot.dropBox.guestSession') }}</h3>
      <ul class="veyq-list">
        <li v-for="item in items" :key="item.id" class="veyq-list-row dropbox-guest-row" :data-status="item.status">
          <VeyqIcon :name="icon(item)" :spin="icon(item) === 'spinner'" />
          <span class="veyq-row-main">
            <span class="veyq-row-title" :title="item.name">{{ item.name }}</span>
            <span class="veyq-row-subtitle dropbox-guest-detail">{{ detail(item) }}</span>
          </span>
          <VeyqButton
            v-if="retryable(item)"
            :label="t('depot.dropBox.guestRetry')"
            :aria-label="t('depot.dropBox.guestRetryLabel', { name: item.name })"
            size="small"
            variant="secondary"
            appearance="text"
            @click="retryFile(item.id)"
          />
        </li>
      </ul>
    </div>
    <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
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
.dropbox-guest-row[data-status='failed'] .veyq-icon {
  color: var(--app-danger);
}
.dropbox-guest-row[data-status='done'] .veyq-icon {
  color: var(--app-success-text);
}
.dropbox-guest-detail {
  white-space: normal;
}
</style>
