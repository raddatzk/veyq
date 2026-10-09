<script setup lang="ts">
// The curator's share MANAGEMENT dialog (cr-0116, redesigned toward the legacy shape): a **list-only** view of a
// Memory's or a chapter's share links (a folder's are core's `FolderShareDialog`) + a "New" button that opens the
// separate create dialog. Shared by BOTH share flows via the `uploader` flag so they stay identical by construction:
// the NORMAL variant lists READ/READ_WRITE shares and creates them with a role switcher; the WRITE_ONLY (drop-box) variant lists uploader shares and creates them with no
// switcher (a separate memory action, capability-gated at that action). Each row shows the share's private name (or
// the role), badges (note present / disabled / by another member), and per-row actions: **copy link** (copy-only —
// reconstructed via the member-recoverable K_share), edit name+note, switch off / re-activate, and **renew** (re-issue,
// ADR 0075): a new link with a fresh key, the old one deleted. A share whose link a departed member may hold is marked
// and offers no re-activate — re-activation would revive that very link. Switched-off links fold under a count, and
// each one the server says the caller may delete offers **delete** (ADR 0029 §7) behind a confirm — which is why
// switching off wears a "ban" icon and the trash can means delete.
// Operator-blind throughout: names/notes are sealed under K_share and decrypted on device; K_share itself is
// recovered through the scope's epoch keys (K_SC), which no share link carries (ADR 0036 point 4).
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import ShareCreateDialog from './ShareCreateDialog.vue'
import { useConfirm } from '../../../shared/state/useConfirm'
import {
  listSharesFor,
  revokeShare,
  reactivateShare,
  deleteShare,
  isReissueRequired,
  readShareName,
  readShareNoteMap,
  hasLocaleText,
  type ManagedShare,
} from '../share'
import { recoverShareLink } from '../shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import type { NodeShareDialogProps } from '../../../core/applications/registry'
import { CHAPTER_KIND } from '../kinds'

// Memories' `shareDialog` for MEMORY and CHAPTER (cr-files-application P4c), so it takes the platform's share-dialog
// props: the scope (every share's recovery copy of `K_share` is bound to it, ADR 0072, and opens under `K_SC` at its
// epoch), the node and its `K_mem` (`nodeKey`), its name, its kind (a chapter link says "chapter", not "Memory", 5b.5)
// and `uploader` — the drop-box variant: list only uploader shares, create with no role switcher. Identical otherwise.
const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const revokingId = ref<string | null>(null)
const reactivatingId = ref<string | null>(null)
// The switched-off links, folded under a count until opened.
const offOpen = ref(false)
const deletingId = ref<string | null>(null)
const actionError = ref(false)
const copiedLinkId = ref<string | null>(null)

// The create/edit dialog: `createOpen` for a new share, `editShare` for editing an existing one's name + note.
const createOpen = ref(false)
const editShare = ref<ManagedShare | null>(null)
// The share being re-issued (ADR 0075) — the create dialog pre-filled from it.
const reissueFrom = ref<ManagedShare | null>(null)

// The two flows differ only in which shares they list + their labels — the uploader variant filters to WRITE_ONLY, the
// normal variant to everything else. Guaranteed identical look because it is the SAME component.
const visibleShares = computed(() =>
  shares.value.filter((s) => (props.uploader ? s.role === ShareRole.WriteOnly : s.role !== ShareRole.WriteOnly)),
)
// Live links first; switched-off ones in a folded section of their own.
const sections = computed(() => [
  { key: 'on', foldable: false, shares: visibleShares.value.filter((s) => s.enabled) },
  { key: 'off', foldable: true, shares: visibleShares.value.filter((s) => !s.enabled) },
])
const isChapter = computed(() => props.nodeKind === CHAPTER_KIND)
const headerTitle = computed(() => (props.uploader ? t('share.uploaderTitle') : isChapter.value ? t('share.chapterTitle') : t('share.title')))
// The uploader variant names the node too (inbox #363): its title "Request upload" names none, its copy does.
const introText = computed(() =>
  props.uploader
    ? isChapter.value ? t('share.chapterUploaderDialogCopy') : t('share.uploaderDialogCopy')
    : isChapter.value ? t('share.chapterIntro') : t('share.intro'),
)
const createLabel = computed(() => (props.uploader ? t('share.uploaderCreate') : t('share.createNew')))
const emptyLabel = computed(() => (props.uploader ? t('share.uploaderNone') : t('share.none')))

function roleLabel(r: ShareRole | string): string {
  if (r === ShareRole.WriteOnly) return t('share.roleUploader')
  if (r === ShareRole.ReadWrite) return t('share.roleContributor')
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
  reissueFrom.value = null
  createOpen.value = true
}
function openEdit(share: ManagedShare) {
  reissueFrom.value = null
  editShare.value = share
  createOpen.value = true
}
function openReissue(share: ManagedShare) {
  editShare.value = null
  reissueFrom.value = share
  createOpen.value = true
}
// Renew: the owner's own share, enabled or not; anyone's share once it is off (the new one is then theirs).
const canReissue = (share: ManagedShare) => share.mine || !share.enabled
async function onSaved() {
  await loadShares()
}
function onDialogClose() {
  createOpen.value = false
  editShare.value = null
  reissueFrom.value = null
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
async function onReactivate(share: ManagedShare) {
  reactivatingId.value = share.shareId
  actionError.value = false
  try {
    await reactivateShare(share.shareId)
    await loadShares()
  } catch (e) {
    // The other half of the refusal — its owner left — is the server's to know: offer the renew it asks for.
    if (await isReissueRequired(e)) openReissue(share)
    else actionError.value = true
  } finally {
    reactivatingId.value = null
  }
}

// Delete is irreversible (ADR 0029 §7): the link can never come back; its guests' photos stay.
async function onDelete(share: ManagedShare) {
  const confirmed = await ask({
    header: t('share.deleteTitle'),
    message: isChapter.value ? t('share.chapterDeleteConfirm') : t('share.deleteConfirm'),
    acceptLabel: t('share.delete'),
    acceptIcon: 'pi pi-trash',
  })
  if (!confirmed) return
  deletingId.value = share.shareId
  actionError.value = false
  try {
    await deleteShare(share.shareId)
    // Gone for good — the server lists it nowhere any more, so drop it here rather than re-load for it.
    shares.value = shares.value.filter((s) => s.shareId !== share.shareId)
  } catch {
    actionError.value = true
  } finally {
    deletingId.value = null
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
      <template v-for="section in sections" :key="section.key">
        <li v-if="section.foldable && section.shares.length" class="share-list-fold">
          <button type="button" class="share-fold-toggle" :aria-expanded="offOpen" @click="offOpen = !offOpen">
            <i class="pi" :class="offOpen ? 'pi-chevron-down' : 'pi-chevron-right'" aria-hidden="true" />
            <span>{{ t('share.offCount', { count: section.shares.length }, section.shares.length) }}</span>
          </button>
        </li>
        <template v-if="!section.foldable || offOpen">
          <li v-for="share in section.shares" :key="share.shareId" class="share-list-item">
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
                <!-- The server's re-activation verdict (ADR 0075 point 4), served on the row: such a link is marked and
                     offers renew instead of re-activate, so the badge and the refusal cannot disagree. -->
                <span v-if="share.reissueRequired" class="veyq-badge muted share-reissue-required" :title="t('share.reissueRequiredHint')">{{ t('share.reissueRequiredBadge') }}</span>
                <!-- ADR 0076 point 5: a guest link minted before the write verifier takes no new guest until renewed —
                     the server's answer, since the verifier is never on the wire (#270). -->
                <span v-if="share.newGuestsRefused" class="veyq-badge muted share-new-guests-refused" :title="t('share.newGuestsRefusedHint')">{{ t('share.newGuestsRefusedBadge') }}</span>
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
                v-if="canReissue(share)"
                type="button"
                icon="pi pi-replay"
                :aria-label="t('share.reissue')"
                :title="t('share.reissue')"
                severity="secondary"
                text
                @click="openReissue(share)"
              />
              <Button
                v-if="!share.enabled && !share.reissueRequired"
                type="button"
                icon="pi pi-refresh"
                :aria-label="t('share.reactivate')"
                :title="t('share.reactivate')"
                severity="secondary"
                text
                :disabled="reactivatingId === share.shareId"
                @click="onReactivate(share)"
              />
              <Button
                v-else-if="share.enabled && share.mine"
                type="button"
                icon="pi pi-ban"
                :aria-label="t('share.revoke')"
                :title="t('share.revoke')"
                severity="danger"
                text
                :disabled="revokingId === share.shareId"
                @click="onRevoke(share.shareId)"
              />
              <!-- Delete is offered on a switched-off link only, and only where the server's gate would let it through
                   (its owner, or anyone once the owner left): deleting a co-member's parked link would destroy their
                   note and password copy. -->
              <Button
                v-if="!share.enabled && share.deletable"
                type="button"
                icon="pi pi-trash"
                :aria-label="t('share.delete')"
                :title="t('share.delete')"
                severity="danger"
                text
                :disabled="deletingId === share.shareId"
                @click="onDelete(share)"
              />
            </div>
          </li>
        </template>
      </template>
    </ul>
    <p v-if="actionError" class="share-error">{{ t('share.actionFailed') }}</p>


    <!-- Keyed by mode + share: an edit that hands over to the renew (#303) mounts afresh, so the form pre-fills again. -->
    <ShareCreateDialog
      v-if="createOpen"
      :key="reissueFrom ? `reissue:${reissueFrom.shareId}` : editShare ? `edit:${editShare.shareId}` : 'new'"
      :scope-id="scopeId"
      :node-id="nodeId"
      :memory-key="nodeKey"
      :epochs="epochs"
      :memory-name="nodeName"
      :edit-share="editShare"
      :reissue-from="reissueFrom"
      :uploader="uploader"
      :chapter="isChapter"
      @saved="onSaved"
      @close="onDialogClose"
      @reissue="openReissue"
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
.share-fold-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0;
  border: 0;
  background: none;
  color: var(--app-muted);
  font: inherit;
  font-size: 0.85rem;
  cursor: pointer;
}
.share-list-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
  flex: 0 0 auto;
}
</style>
