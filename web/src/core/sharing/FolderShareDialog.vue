<script setup lang="ts">
// The platform's share dialog for a FOLDER (cr-node-key-hierarchy 5b.3, ADR 0080 decisions 6 and 12): a member lists
// the folder's links, makes a new one (READ or READ_WRITE), copies, switches off, re-activates, renews and deletes
// them. Core owns FOLDER, so this is core's own dialog, built only on `./manageShares.ts` and `./shareLink.ts` — never
// on the Memories application's share dialogs, whose uploader (WRITE_ONLY) variant has no meaning here: the backend
// refuses a WRITE_ONLY link on a folder (`illegal_scope`), so no such option is offered.
//
// What a READ_WRITE link lets a guest do below a folder is the role matrix (docs/apps/memories.md): add folders, move
// and rename between them (core), and contribute photos to any Memory or chapter below (Memories) — never upload into
// a folder itself.
//
// One dialog, three views: the list, the create form, and the freshly-minted link (after a create or a renew). Not
// built: editing a link's label, note, password, expiry or role in place (Memories' `updateShare` form) — a member
// renews or deletes the link instead.
// Operator-blind throughout: the folder's key is wrapped under a fresh `K_share` that rides only the link's fragment;
// name, note and password are sealed under `K_share`; the list re-opens `K_share` through the recovery copy under the
// scope key, which no link carries (ADR 0036 point 4). Strings are the Memory dialog's wherever the meaning is the
// same; `share.folder.*` only where a Memory sentence would be wrong for a folder.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import SelectButton from 'primevue/selectbutton'
import ToggleSwitch from 'primevue/toggleswitch'
import DatePicker from 'primevue/datepicker'
import LocaleMarkdownEditor from '../../shared/ui/LocaleMarkdownEditor.vue'
import { useConfirm } from '../../shared/state/useConfirm'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import {
  mintShare,
  remintShare,
  listSharesFor,
  revokeShare,
  reactivateShare,
  deleteShare,
  isReissueRequired,
  isShareDeleted,
  readShareName,
  readShareNoteMap,
  hasLocaleText,
  type LocaleMap,
  type ManagedShare,
} from './manageShares'
import { buildShareLink, recoverShareLink } from './shareLink'
import { ShareRole } from '../../api/generated/sharing'
import type { NodeShareDialogProps } from '../applications/registry'

/** A password-protected link needs at least this many characters (the Memory dialog's convention, cr-0118). */
const MIN_PW = 10

// FOLDER's pre-registered `shareDialog` (cr-files-application P4c), so it takes the platform's share-dialog props: the
// folder's scope (every link's recovery copy of `K_share` is bound to it, ADR 0072), the folder's own content key as
// the key chain opens it (`nodeKey`, ADR 0080 decision 1 — what a link wraps; key material) and its name (`nodeName`,
// re-sealed under `K_share` so a guest sees it without the scope key). `nodeKind` is always FOLDER here; `uploader` is
// never set for a folder (no WRITE_ONLY link on a folder) and would be ignored.
const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()

const { t, locale } = useI18n()
const { ask } = useConfirm()

type View = 'list' | 'create' | 'link'
const view = ref<View>('list')

// --- the list ---------------------------------------------------------------------------------------------------
const shares = ref<ManagedShare[]>([])
const loadError = ref(false)
const actionError = ref(false)
const busyId = ref<string | null>(null)
const offOpen = ref(false)
const copiedLinkId = ref<string | null>(null)
const alreadyReplaced = ref(false)

// A folder link is READ or READ_WRITE; anything else on the folder (a row the server should never list) is not ours.
const folderShares = computed(() => shares.value.filter((s) => s.role !== ShareRole.WriteOnly))
const sections = computed(() => [
  { key: 'on', foldable: false, shares: folderShares.value.filter((s) => s.enabled) },
  { key: 'off', foldable: true, shares: folderShares.value.filter((s) => !s.enabled) },
])

// The recovery copy is bound to (scope, node, share): this dialog's folder, never the row's own claim (ADR 0072).
const addressOf = (s: ManagedShare) => ({ scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId })

const decoded = computed(() => {
  const m = new Map<string, { name: string; hasNote: boolean; link: string | null }>()
  for (const s of shares.value) {
    m.set(s.shareId, {
      name: readShareName(props.epochs, addressOf(s), s, s.sealedShareName),
      hasNote: hasLocaleText(readShareNoteMap(props.epochs, addressOf(s), s, s.sealedNote)),
      link: recoverShareLink(addressOf(s), props.epochs, s),
    })
  }
  return m
})

function roleLabel(r: ShareRole): string {
  return r === ShareRole.ReadWrite ? t('share.folder.roleContributor') : t('share.roleViewer')
}
const titleFor = (s: ManagedShare) => decoded.value.get(s.shareId)?.name || roleLabel(s.role)
// Renew: the owner's own link, live or not; anyone's link once it is off (the new one is then theirs).
const canReissue = (s: ManagedShare) => s.mine || !s.enabled

async function loadShares() {
  loadError.value = false
  try {
    shares.value = await listSharesFor(props.nodeId)
  } catch {
    loadError.value = true
  }
}
onMounted(loadShares)

async function copy(text: string, id: string) {
  try {
    await navigator.clipboard.writeText(text)
    copiedLinkId.value = id
    setTimeout(() => {
      if (copiedLinkId.value === id) copiedLinkId.value = null
    }, 2000)
  } catch {
    /* clipboard denied — the link field stays selectable */
  }
}

/** Run one row action; any failure shows the shared "didn't work" line under the list. */
async function rowAction(s: ManagedShare, action: () => Promise<void>) {
  busyId.value = s.shareId
  actionError.value = false
  alreadyReplaced.value = false
  try {
    await action()
  } catch {
    actionError.value = true
  } finally {
    busyId.value = null
  }
}

const onRevoke = (s: ManagedShare) =>
  rowAction(s, async () => {
    await revokeShare(s.shareId)
    await loadShares()
  })

async function onReactivate(s: ManagedShare) {
  let renew = false
  await rowAction(s, async () => {
    try {
      await reactivateShare(s.shareId)
    } catch (e) {
      // A departed member may hold the link (ADR 0075): only a renew brings it back, so offer that instead.
      if (!(await isReissueRequired(e))) throw e
      renew = true
      return
    }
    await loadShares()
  })
  if (renew) await onReissue(s)
}

async function onDelete(s: ManagedShare) {
  const confirmed = await ask({
    header: t('share.deleteTitle'),
    message: t('share.folder.deleteConfirm'),
    acceptLabel: t('share.delete'),
    acceptIcon: 'pi pi-trash',
  })
  if (!confirmed) return
  await rowAction(s, async () => {
    await deleteShare(s.shareId)
    shares.value = shares.value.filter((x) => x.shareId !== s.shareId)
  })
}

/** Renew (ADR 0075): a new link with a fresh key and the old one's settings, the old one deleted in the same request. */
async function onReissue(s: ManagedShare) {
  const confirmed = await ask({
    header: t('share.reissueTitle'),
    message: t('share.folder.reissueWarning'),
    acceptLabel: t('share.reissueSubmit'),
    acceptIcon: 'pi pi-replay',
  })
  if (!confirmed) return
  busyId.value = s.shareId
  actionError.value = false
  alreadyReplaced.value = false
  try {
    const minted = await remintShare({ ...s, ...addressOf(s) }, props.nodeKey, props.epochs, props.nodeName)
    link.value = buildShareLink(minted.shareId, minted.kShare)
    view.value = 'link'
  } catch (e) {
    if (await isShareDeleted(e)) alreadyReplaced.value = true
    else actionError.value = true
  } finally {
    busyId.value = null
    await loadShares()
  }
}

function fmtDate(d: Date): string {
  return new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium' }).format(d)
}

// --- the create form --------------------------------------------------------------------------------------------
const name = ref('')
const role = ref<ShareRole>(ShareRole.Read)
const noteEnabled = ref(false)
const note = ref<LocaleMap>({})
const passwordEnabled = ref(false)
const password = ref('')
const revealPw = ref(false)
const expiryEnabled = ref(false)
const expiresAt = ref<Date | null>(null)
const minExpiry = new Date()
const createError = ref(false)
const link = ref('')
/** The copied-state id of the freshly-minted link (a share id is 43 base64url characters, so it never collides). */
const NEW_LINK = 'new'

// READ and READ_WRITE only: a WRITE_ONLY link on a folder is refused by the backend.
const roleOptions = computed(() => [
  { label: t('share.roleViewer'), value: ShareRole.Read },
  { label: t('share.folder.roleContributor'), value: ShareRole.ReadWrite },
])
const roleHint = computed(() =>
  role.value === ShareRole.ReadWrite ? t('share.folder.roleContributorHint') : t('share.folder.roleViewerHint'),
)
const passwordTooShort = computed(() => passwordEnabled.value && password.value.trim().length < MIN_PW)

function openCreate() {
  name.value = ''
  role.value = ShareRole.Read
  noteEnabled.value = false
  note.value = {}
  passwordEnabled.value = false
  password.value = ''
  expiryEnabled.value = false
  expiresAt.value = null
  createError.value = false
  view.value = 'create'
}

/** End of the picked local day — the link works THROUGH that day. */
function endOfDay(d: Date): Date {
  const e = new Date(d)
  e.setHours(23, 59, 59, 999)
  return e
}

const { run: runCreate, pending: creating } = useAsyncAction(
  async () => {
    const minted = await mintShare(
      props.scopeId, props.nodeId, props.nodeKey, props.epochs, props.nodeName, role.value,
      expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null,
      noteEnabled.value ? note.value : {}, name.value, passwordEnabled.value ? password.value : '',
    )
    link.value = buildShareLink(minted.shareId, minted.kShare) // the origin belongs here, not in the data layer
    view.value = 'link'
    await loadShares()
  },
  { onError: () => void (createError.value = true) },
)

async function onCreate() {
  if (passwordTooShort.value) return
  createError.value = false
  await runCreate()
}

const header = computed(() =>
  view.value === 'create' ? t('share.createNew') : t('share.folder.title'),
)
function onDialogVisible(visible: boolean) {
  if (!visible && !creating.value) emit('close')
}
</script>

<template>
  <Dialog
    :visible="true"
    :header="header"
    modal
    class="share-dialog folder-share-dialog"
    :closable="!creating"
    :close-on-escape="!creating"
    @update:visible="onDialogVisible"
  >
    <!-- The freshly-minted link (after a create or a renew). -->
    <div v-if="view === 'link'" class="veyq-form">
      <p class="veyq-form-copy">{{ t('share.linkReady') }}</p>
      <div class="folder-share-link-row">
        <InputText
          :model-value="link"
          readonly
          class="folder-share-link"
          :aria-label="t('share.linkLabel')"
          @focus="($event.target as HTMLInputElement).select()"
        />
        <Button
          :label="copiedLinkId === NEW_LINK ? t('share.copied') : t('share.copy')"
          :icon="copiedLinkId === NEW_LINK ? 'pi pi-check' : 'pi pi-copy'"
          severity="secondary"
          @click="copy(link, NEW_LINK)"
        />
      </div>
      <div class="veyq-dialog-action-row">
        <div class="veyq-action-group">
          <Button :label="t('common.done')" icon="pi pi-check" @click="view = 'list'" />
        </div>
      </div>
    </div>

    <!-- The create form. -->
    <div v-else-if="view === 'create'" class="veyq-form">
      <div class="veyq-form-field">
        <label for="folder-share-name">{{ t('share.name') }}</label>
        <InputText id="folder-share-name" v-model="name" :placeholder="t('share.nameOptional')" :disabled="creating" />
      </div>

      <div class="veyq-form-field">
        <label>{{ t('share.roleLabel') }}</label>
        <SelectButton
          v-model="role"
          :options="roleOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="creating"
          class="folder-share-roles"
        />
        <p class="veyq-form-copy folder-share-hint">{{ roleHint }}</p>
      </div>

      <div class="veyq-form-field">
        <div class="folder-share-toggle">
          <span>
            <strong>{{ t('share.noteSection') }}</strong>
            <span class="veyq-form-copy folder-share-hint">{{ t('share.noteSectionHint') }}</span>
          </span>
          <ToggleSwitch v-model="noteEnabled" :aria-label="t('share.noteSection')" :disabled="creating" />
        </div>
        <template v-if="noteEnabled">
          <LocaleMarkdownEditor v-model="note" :placeholder="t('share.notePlaceholder')" />
          <p class="veyq-form-copy folder-share-hint">{{ t('share.noteHint') }}</p>
        </template>
      </div>

      <div class="veyq-form-field">
        <div class="folder-share-toggle">
          <span>
            <strong>{{ t('share.passwordSection') }}</strong>
            <span class="veyq-form-copy folder-share-hint">{{ t('share.passwordSectionHint', { min: MIN_PW }) }}</span>
          </span>
          <ToggleSwitch v-model="passwordEnabled" :aria-label="t('share.passwordSection')" :disabled="creating" />
        </div>
        <template v-if="passwordEnabled">
          <label for="folder-share-password">{{ t('share.passwordLabel') }}</label>
          <div class="folder-share-link-row">
            <InputText
              id="folder-share-password"
              v-model="password"
              class="folder-share-link"
              :type="revealPw ? 'text' : 'password'"
              autocomplete="new-password"
              :placeholder="t('share.passwordPlaceholder')"
              :disabled="creating"
            />
            <Button
              type="button"
              class="veyq-icon-button"
              :icon="revealPw ? 'pi pi-eye-slash' : 'pi pi-eye'"
              :aria-label="revealPw ? t('common.hide') : t('common.show')"
              severity="secondary"
              text
              :disabled="creating"
              @click="revealPw = !revealPw"
            />
          </div>
          <p v-if="password.length > 0 && passwordTooShort" class="veyq-form-error">
            {{ t('share.passwordTooShort', { min: MIN_PW }) }}
          </p>
        </template>
      </div>

      <div class="veyq-form-field">
        <div class="folder-share-toggle">
          <span>
            <strong>{{ t('share.expirySection') }}</strong>
            <span class="veyq-form-copy folder-share-hint">{{ t('share.expirySectionHint') }}</span>
          </span>
          <ToggleSwitch v-model="expiryEnabled" :aria-label="t('share.expirySection')" :disabled="creating" />
        </div>
        <template v-if="expiryEnabled">
          <label for="folder-share-expiry">{{ t('share.expiryLabel') }}</label>
          <DatePicker
            id="folder-share-expiry"
            v-model="expiresAt"
            :min-date="minExpiry"
            date-format="yy-mm-dd"
            show-icon
            icon-display="input"
            :disabled="creating"
            fluid
          />
        </template>
      </div>

      <p v-if="createError" class="veyq-form-error">{{ t('share.createFailed') }}</p>
      <div class="veyq-dialog-action-row">
        <Button
          type="button"
          :label="t('common.cancel')"
          icon="pi pi-times"
          severity="secondary"
          outlined
          :disabled="creating"
          @click="view = 'list'"
        />
        <Button
          class="folder-share-create"
          :label="creating ? t('share.creating') : t('share.create')"
          icon="pi pi-link"
          :loading="creating"
          :disabled="creating || passwordTooShort"
          @click="onCreate"
        />
      </div>
    </div>

    <!-- The list of the folder's links. -->
    <div v-else class="veyq-form">
      <p class="veyq-form-copy">{{ t('share.folder.intro') }}</p>
      <Button class="folder-share-new" :label="t('share.createNew')" icon="pi pi-plus" @click="openCreate" />

      <p v-if="loadError" class="veyq-form-error">{{ t('share.loadFailed') }}</p>
      <p v-else-if="!folderShares.length" class="veyq-empty veyq-empty-inline">{{ t('share.none') }}</p>
      <ul v-else class="veyq-list">
        <template v-for="section in sections" :key="section.key">
          <li v-if="section.foldable && section.shares.length">
            <Button
              type="button"
              class="folder-share-fold"
              :icon="offOpen ? 'pi pi-chevron-down' : 'pi pi-chevron-right'"
              :label="t('share.offCount', { count: section.shares.length }, section.shares.length)"
              :aria-expanded="offOpen"
              severity="secondary"
              text
              size="small"
              @click="offOpen = !offOpen"
            />
          </li>
          <template v-if="!section.foldable || offOpen">
            <li v-for="share in section.shares" :key="share.shareId" class="veyq-list-row folder-share-row">
              <div class="veyq-row-main">
                <span class="veyq-row-title">{{ titleFor(share) }}</span>
                <span class="veyq-row-subtitle">
                  {{ t('share.created', { date: fmtDate(share.createdAt) }) }}<template v-if="share.expiresAt">
                    · {{ t('share.expires', { date: fmtDate(share.expiresAt) }) }}</template>
                </span>
                <div class="veyq-row-meta">
                  <span class="veyq-badge success folder-share-role">{{ roleLabel(share.role) }}</span>
                  <span v-if="decoded.get(share.shareId)?.hasNote" class="veyq-badge">{{ t('share.hasNote') }}</span>
                  <span v-if="share.passwordRequired" class="veyq-badge"><span class="pi pi-lock" />{{ t('share.passwordBadge') }}</span>
                  <span v-if="!share.mine" class="veyq-badge muted">{{ t('share.byMember') }}</span>
                  <span v-if="!share.enabled" class="veyq-badge muted folder-share-off">{{ t('share.disabled') }}</span>
                  <span v-if="share.reissueRequired" class="veyq-badge warning" :title="t('share.reissueRequiredHint')">
                    {{ t('share.reissueRequiredBadge') }}
                  </span>
                </div>
              </div>
              <div class="folder-share-actions">
                <Button
                  v-if="share.enabled && decoded.get(share.shareId)?.link"
                  type="button"
                  class="veyq-icon-button"
                  :icon="copiedLinkId === share.shareId ? 'pi pi-check' : 'pi pi-copy'"
                  :aria-label="t('share.copy')"
                  :title="copiedLinkId === share.shareId ? t('share.copied') : t('share.copy')"
                  severity="secondary"
                  text
                  @click="copy(decoded.get(share.shareId)!.link!, share.shareId)"
                />
                <Button
                  v-if="canReissue(share)"
                  type="button"
                  class="veyq-icon-button"
                  icon="pi pi-replay"
                  :aria-label="t('share.reissue')"
                  :title="t('share.reissue')"
                  severity="secondary"
                  text
                  :disabled="busyId === share.shareId"
                  @click="onReissue(share)"
                />
                <Button
                  v-if="!share.enabled && !share.reissueRequired"
                  type="button"
                  class="veyq-icon-button"
                  icon="pi pi-refresh"
                  :aria-label="t('share.reactivate')"
                  :title="t('share.reactivate')"
                  severity="secondary"
                  text
                  :disabled="busyId === share.shareId"
                  @click="onReactivate(share)"
                />
                <Button
                  v-else-if="share.enabled && share.mine"
                  type="button"
                  class="veyq-icon-button"
                  icon="pi pi-ban"
                  :aria-label="t('share.revoke')"
                  :title="t('share.revoke')"
                  severity="danger"
                  text
                  :disabled="busyId === share.shareId"
                  @click="onRevoke(share)"
                />
                <!-- Delete only a switched-off link, and only where the server's gate lets it through (ADR 0029 §7). -->
                <Button
                  v-if="!share.enabled && share.deletable"
                  type="button"
                  class="veyq-icon-button"
                  icon="pi pi-trash"
                  :aria-label="t('share.delete')"
                  :title="t('share.delete')"
                  severity="danger"
                  text
                  :disabled="busyId === share.shareId"
                  @click="onDelete(share)"
                />
              </div>
            </li>
          </template>
        </template>
      </ul>
      <p v-if="alreadyReplaced" class="veyq-form-error">{{ t('share.reissueAlreadyReplaced') }}</p>
      <p v-else-if="actionError" class="veyq-form-error">{{ t('share.actionFailed') }}</p>
    </div>
  </Dialog>
</template>

<style scoped>
/* Only what no shared class expresses: the toggle-led section head, the link/password row, the row's trailing
   actions. Spacing and colours are the shared form/list classes' and the `--app-*` tokens'. */
.folder-share-row {
  grid-template-columns: minmax(0, 1fr) auto;
}
.folder-share-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
}
.folder-share-new {
  width: 100%;
  justify-content: center;
}
.folder-share-hint {
  display: block;
  font-size: 0.82rem;
}
.folder-share-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.folder-share-link-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.folder-share-link {
  flex: 1 1 auto;
  min-width: 0;
}
.folder-share-roles {
  display: flex;
  width: 100%;
}
.folder-share-roles :deep(.p-togglebutton) {
  flex: 1 1 0;
}
@media (max-width: 560px) {
  .folder-share-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
