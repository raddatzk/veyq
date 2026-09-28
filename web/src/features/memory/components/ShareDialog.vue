<script setup lang="ts">
// The curator's share MANAGEMENT dialog (cr-0116, redesigned toward the legacy shape): a **list-only** view of a
// Memory's share links + a "New" button that opens the separate create dialog. Shared by BOTH share flows via the
// `uploader` flag so they stay identical by construction: the NORMAL variant lists VIEWER/CONTRIBUTOR shares and
// creates them with a role switcher; the UPLOADER (drop-box) variant lists uploader shares and creates them with no
// switcher (a separate memory action, capability-gated at that action). Each row shows the share's private name (or
// the role), badges (note present / disabled / by another member), and per-row actions: **copy link** (copy-only —
// reconstructed via the member-recoverable K_share), edit name+note, and revoke / re-activate.
// Operator-blind throughout: names/notes are sealed under K_share and decrypted on device; K_share itself is
// recovered through the scope's epoch keys (K_SC), which no share link carries (ADR 0036 point 4).
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import ShareCreateDialog from './ShareCreateDialog.vue'
import {
  listSharesFor,
  revokeShare,
  reactivateShare,
  readShareName,
  readShareNoteMap,
  hasLocaleText,
  type ManagedShare,
} from '../share'
import { recoverShareLink } from '../shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import type { EpochKeys } from '../../spaces/epochs'

const props = defineProps<{
  /** The shared Memory's scope — every share's recovery copy of `K_share` is bound to it (ADR 0072). */
  scopeId: string
  nodeId: string
  memoryKey: Uint8Array
  /** The scope's epoch keys — every share's recovery copy of `K_share` opens under `K_SC` at its epoch. */
  epochs: EpochKeys
  memoryName: string
  /** The uploader (drop-box) variant: list only uploader shares, create with no role switcher. Identical otherwise. */
  uploader?: boolean
}>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()

const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const revokingId = ref<string | null>(null)
const reactivatingId = ref<string | null>(null)
const actionError = ref(false)
const copiedLinkId = ref<string | null>(null)

// The create/edit dialog: `createOpen` for a new share, `editShare` for editing an existing one's name + note.
const createOpen = ref(false)
const editShare = ref<ManagedShare | null>(null)

// The two flows differ only in which shares they list + their labels — the uploader variant filters to UPLOADER, the
// normal variant to everything else. Guaranteed identical look because it is the SAME component.
const visibleShares = computed(() =>
  shares.value.filter((s) => (props.uploader ? s.role === ShareRole.Uploader : s.role !== ShareRole.Uploader)),
)
const headerTitle = computed(() => (props.uploader ? t('share.uploaderTitle') : t('share.title')))
const introText = computed(() => (props.uploader ? t('share.uploaderDialogCopy') : t('share.intro')))
const createLabel = computed(() => (props.uploader ? t('share.uploaderCreate') : t('share.createNew')))
const emptyLabel = computed(() => (props.uploader ? t('share.uploaderNone') : t('share.none')))

function roleLabel(r: ShareRole | string): string {
  if (r === ShareRole.Uploader) return t('share.roleUploader')
  if (r === ShareRole.Contributor) return t('share.roleContributor')
  return t('share.roleViewer')
}

// A share's recovery copy is bound to (scope, Memory, share) (ADR 0072): the scope + Memory this dialog was opened
// for — the address it listed by — and the row's share id, never the row's own claim of where it lives.
const addressOf = (s: ManagedShare) => ({ scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId })

// Decrypt each share's name + note-presence once per load (a symUnwrap per share, not per render).
const decoded = computed(() => {
  const m = new Map<string, { name: string; hasNote: boolean }>()
  for (const s of shares.value) {
    m.set(s.shareId, {
      name: readShareName(props.epochs, addressOf(s), s, s.sealedShareName),
      hasNote: hasLocaleText(readShareNoteMap(props.epochs, addressOf(s), s, s.sealedNote)),
    })
  }
  return m
})
const shareLinks = computed(() => {
  const m = new Map<string, string>()
  for (const s of shares.value) {
    const link = recoverShareLink(addressOf(s), props.epochs, s)
    if (link) m.set(s.shareId, link)
  }
  return m
})
function titleFor(share: ManagedShare): string {
  return decoded.value.get(share.shareId)?.name || roleLabel(share.role)
}

async function loadShares() {
  loadError.value = false
  try {
    shares.value = await listSharesFor(props.nodeId)
  } catch {
    loadError.value = true
  }
}
onMounted(loadShares)

function openCreate() {
  editShare.value = null
  createOpen.value = true
}
function openEdit(share: ManagedShare) {
  editShare.value = share
  createOpen.value = true
}
async function onSaved() {
  await loadShares()
}
function onDialogClose() {
  createOpen.value = false
  editShare.value = null
}

async function copyShareLink(share: ManagedShare) {
  const link = shareLinks.value.get(share.shareId)
  if (!link) return
  try {
    await navigator.clipboard.writeText(link)
    copiedLinkId.value = share.shareId
    setTimeout(() => { if (copiedLinkId.value === share.shareId) copiedLinkId.value = null }, 2000)
  } catch {
    /* clipboard denied */
  }
}

async function onRevoke(shareId: string) {
  revokingId.value = shareId
  actionError.value = false
  try {
    await revokeShare(shareId)
    await loadShares()
  } catch {
    actionError.value = true
  } finally {
    revokingId.value = null
  }
}
async function onReactivate(shareId: string) {
  reactivatingId.value = shareId
  actionError.value = false
  try {
    await reactivateShare(shareId)
    await loadShares()
  } catch {
    actionError.value = true
  } finally {
    reactivatingId.value = null
  }
}

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}
</script>

<template>
  <Dialog :visible="true" :header="headerTitle" modal class="share-dialog" @update:visible="emit('close')">
    <div class="share-manage-head">
      <p class="share-intro">{{ introText }}</p>
      <Button class="share-new-button" :label="createLabel" icon="pi pi-plus" @click="openCreate" />
    </div>

    <p v-if="loadError" class="share-error">{{ t('share.loadFailed') }}</p>
    <p v-else-if="!visibleShares.length" class="share-empty">{{ emptyLabel }}</p>

    <ul v-else class="share-list">
      <li v-for="share in visibleShares" :key="share.shareId" class="share-list-item">
        <div class="share-list-meta">
          <strong class="share-list-title">{{ titleFor(share) }}</strong>
          <span class="share-list-when">{{ t('share.created', { date: fmtDate(share.createdAt) }) }}</span>
          <span v-if="share.expiresAt" class="share-list-when share-list-expires">{{ t('share.expires', { date: fmtDate(share.expiresAt) }) }}</span>
          <div class="share-list-badges">
            <span class="share-list-role">{{ roleLabel(share.role) }}</span>
            <span v-if="decoded.get(share.shareId)?.hasNote" class="veyq-badge">{{ t('share.hasNote') }}</span>
            <span v-if="share.passwordRequired" class="veyq-badge"><span class="pi pi-lock" /> {{ t('share.passwordBadge') }}</span>
            <span v-if="!share.mine" class="veyq-badge muted">{{ t('share.byMember') }}</span>
            <span v-if="!share.enabled" class="veyq-badge muted">{{ t('share.disabled') }}</span>
          </div>
        </div>
        <div class="share-list-actions">
          <Button
            v-if="share.enabled && shareLinks.get(share.shareId)"
            type="button"
            :icon="copiedLinkId === share.shareId ? 'pi pi-check' : 'pi pi-copy'"
            :aria-label="t('share.copy')"
            :title="copiedLinkId === share.shareId ? t('share.copied') : t('share.copy')"
            severity="secondary"
            text
            @click="copyShareLink(share)"
          />
          <Button
            v-if="share.mine && share.sealedShareKey"
            type="button"
            icon="pi pi-pencil"
            :aria-label="t('share.edit')"
            :title="t('share.edit')"
            severity="secondary"
            text
            @click="openEdit(share)"
          />
          <Button
            v-if="!share.enabled"
            type="button"
            icon="pi pi-refresh"
            :aria-label="t('share.reactivate')"
            :title="t('share.reactivate')"
            severity="secondary"
            text
            :disabled="reactivatingId === share.shareId"
            @click="onReactivate(share.shareId)"
          />
          <Button
            v-else-if="share.mine"
            type="button"
            icon="pi pi-trash"
            :aria-label="t('share.revoke')"
            :title="t('share.revoke')"
            severity="danger"
            text
            :disabled="revokingId === share.shareId"
            @click="onRevoke(share.shareId)"
          />
        </div>
      </li>
    </ul>
    <p v-if="actionError" class="share-error">{{ t('share.actionFailed') }}</p>

    <ShareCreateDialog
      v-if="createOpen"
      :scope-id="scopeId"
      :node-id="nodeId"
      :memory-key="memoryKey"
      :epochs="epochs"
      :memory-name="memoryName"
      :edit-share="editShare"
      :uploader="uploader"
      @saved="onSaved"
      @close="onDialogClose"
    />
  </Dialog>
</template>

<style scoped>
.share-manage-head {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 1rem;
}
.share-intro {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
/* A prominent, full-width "New link" button below the intro (the legacy shape), so a long intro can never
   squeeze/clip its label. */
.share-new-button {
  width: 100%;
  justify-content: center;
}
.share-error {
  margin: 0.5rem 0 0;
  color: var(--app-danger);
  font-size: 0.85rem;
}
.share-empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.88rem;
}
.share-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 0.5rem;
}
.share-list-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
}
.share-list-meta {
  display: grid;
  gap: 0.2rem;
  min-width: 0;
}
.share-list-title {
  font-size: 0.95rem;
  overflow-wrap: anywhere;
}
.share-list-when {
  color: var(--app-muted);
  font-size: 0.78rem;
}
.share-list-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
  align-items: center;
}
.share-list-role {
  font-size: 0.68rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.02em;
  color: var(--app-accent);
}
.share-list-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
  flex: 0 0 auto;
}
</style>
