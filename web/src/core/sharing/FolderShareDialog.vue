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
// One dialog, four views: the list, the create form, the same form editing a link in place (inbox #364), and the
// freshly-minted link (after a create or a renew). The edit is Memories' share edit on core's form: label, role, note,
// password and expiry, saved with `updateShare` on the same link — its key and its guests untouched — and offered on
// the caller's own links only, as the server lets only a link's owner edit it (ADR 0036 point 3) and its recovery copy
// is what re-opens `K_share` to re-seal them.
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
  updateShare,
  readShareName,
  readShareNote,
  readSharePassword,
  hasLocaleText,
  type LocaleMap,
  type ManagedShare,
} from './manageShares'
import { NO_NOTE, type ShareNote } from './shareNote'
import ShareNoteAcceptField from './ShareNoteAcceptField.vue'
import { buildShareLink, recoverShareLink } from './shareLink'
import { ShareRole } from '../../api/generated/sharing'
import type { NodeShareDialogProps } from '../applications/registry'
import type { ShareDialogCopy } from './shareDialogCopy'

/** A password-protected link needs at least this many characters (the Memory dialog's convention, cr-0118). */
const MIN_PW = 10

// FOLDER's pre-registered `shareDialog` (cr-files-application P4c), so it takes the platform's share-dialog props: the
// folder's scope (every link's recovery copy of `K_share` is bound to it, ADR 0072), the folder's own content key as
// the key chain opens it (`nodeKey`, ADR 0080 decision 1 — what a link wraps; key material) and its name (`nodeName`,
// re-sealed under `K_share` so a guest sees it without the scope key). `nodeKind` is always FOLDER here; `uploader` is
// never set for a folder (no WRITE_ONLY link on a folder) and would be ignored. `contribute` (the rights function's
// `shareContribute`, handed on by the host) lets the form offer READ_WRITE for a new link.
const props = defineProps<NodeShareDialogProps & { words?: ShareDialogCopy }>()
const emit = defineEmits<{ close: [] }>()
/** The i18n key of a sentence that says what the node is: the folder's own (`share.folder.*`) unless the wrapping dialog
 *  of another kind hands its own (`words`, Depot's single-file link). */
const copyKey = (name: keyof ShareDialogCopy) => props.words?.[name] ?? `share.folder.${name}`

const { t, locale } = useI18n()
const { ask } = useConfirm()

type View = 'list' | 'create' | 'edit' | 'link'
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
      hasNote: hasLocaleText(readShareNote(props.epochs, addressOf(s), s, s.sealedNote).texts),
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
    message: t(copyKey('deleteConfirm')),
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
    message: t(copyKey('reissueWarning')),
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
/** The note's acceptance switch, sealed inside the note (uc.sharing.share-note). */
const noteMustAccept = ref(false)
/** The note as it is saved: none when its section is off. */
const effectiveNote = (): ShareNote => (noteEnabled.value ? { texts: note.value, mustAccept: noteMustAccept.value } : NO_NOTE)
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

// READ and READ_WRITE only: a WRITE_ONLY link on a folder is refused by the backend. READ_WRITE only where the host
// handed `contribute` (the rights function's `shareContribute`, `core.public-shares.contribute`) — or on the edit of a
// link that already is one, so an existing contributor link keeps its role on screen instead of showing no selection.
// While the switch is off such a link serves no guest (ADR 0088 decision 5), but the member still edits it or narrows it to
// READ; the server refuses only a change INTO it.
const roleOptions = computed(() => [
  { label: t('share.roleViewer'), value: ShareRole.Read },
  ...(props.contribute || (view.value === 'edit' && editing.value?.role === ShareRole.ReadWrite)
    ? [{ label: t('share.folder.roleContributor'), value: ShareRole.ReadWrite }]
    : []),
])
const roleHint = computed(() =>
  role.value === ShareRole.ReadWrite ? t(copyKey('roleContributorHint')) : t(copyKey('roleViewerHint')),
)
const passwordTooShort = computed(() => passwordEnabled.value && password.value.trim().length < MIN_PW)

// --- the edit (inbox #364): the create form, pre-filled from the link and saved in place ------------------------
/** The link being edited, and the password its recovery copy opened (to tell untouched / changed / removed apart). */
const editing = ref<ManagedShare | null>(null)
const currentPassword = ref('')
const editError = ref(false)
/** Edit: the caller's own link, whose recovery copy re-opens `K_share` — the Memory dialog's rule. */
const canEdit = (s: ManagedShare) => s.mine && !!s.sealedShareKey
// ADR 0076 point 5 (#303): a link without a write verifier refuses every new guest as READ_WRITE, and an edit cannot add
// one — only a renew stores it. So graded READ_WRITE, such a link warns and offers that renew.
const roleNeedsReissue = computed(
  () => view.value === 'edit' && !!editing.value?.writeVerifierMissing && role.value === ShareRole.ReadWrite,
)

function openEdit(s: ManagedShare) {
  const address = addressOf(s)
  name.value = readShareName(props.epochs, address, s, s.sealedShareName)
  role.value = s.role
  const opened = readShareNote(props.epochs, address, s, s.sealedNote)
  note.value = { ...opened.texts }
  noteMustAccept.value = opened.mustAccept
  noteEnabled.value = hasLocaleText(note.value)
  currentPassword.value = readSharePassword(props.epochs, address, s, s.sealedPassword)
  password.value = currentPassword.value
  // A password the copy cannot open leaves the toggle on with an empty field: the save then waits for a new one
  // rather than quietly dropping the link's protection.
  passwordEnabled.value = s.passwordRequired || currentPassword.value.length > 0
  revealPw.value = false
  expiryEnabled.value = !!s.expiresAt
  expiresAt.value = s.expiresAt
  editError.value = false
  editing.value = s
  view.value = 'edit'
}

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    const s = editing.value!
    const hadPassword = s.passwordRequired
    // The password tri-state: off removes one that was set, a changed value sets it, an unchanged one is left alone.
    const passwordChange = !passwordEnabled.value
      ? hadPassword ? ({ remove: true } as const) : undefined
      : password.value === currentPassword.value ? undefined : ({ set: password.value } as const)
    // Note, label and expiry are full replaces: the pre-filled values are always re-sent, so an unrelated edit keeps them.
    await updateShare(
      addressOf(s), props.epochs, s, effectiveNote(), name.value,
      expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null, role.value, passwordChange,
    )
    editing.value = null
    view.value = 'list'
    await loadShares()
  },
  { onError: () => void (editError.value = true) },
)

async function onSave() {
  if (passwordTooShort.value) return
  editError.value = false
  await runSave()
}

/** The edit hands over to a renew (#303): back to the list, then the renew's own question. */
async function reissueFromEdit() {
  const s = editing.value
  if (!s) return
  editing.value = null
  view.value = 'list'
  await onReissue(s)
}

function openCreate() {
  editing.value = null
  name.value = ''
  role.value = ShareRole.Read
  noteEnabled.value = false
  note.value = {}
  noteMustAccept.value = false
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

const { run: runCreate, pending: minting } = useAsyncAction(
  async () => {
    const minted = await mintShare(
      props.scopeId, props.nodeId, props.nodeKey, props.epochs, props.nodeName, role.value,
      expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null,
      effectiveNote(), name.value, passwordEnabled.value ? password.value : '',
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
/** A create or an edit is in flight: the form and the dialog's close wait for it. */
const creating = computed(() => minting.value || saving.value)

const header = computed(() =>
  view.value === 'create' ? t('share.createNew') : view.value === 'edit' ? t('share.editTitle') : t(copyKey('title')),
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

    <!-- The create form, and the same form editing a link in place (#364). -->
    <div v-else-if="view === 'create' || view === 'edit'" class="veyq-form">
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
        <template v-if="roleNeedsReissue">
          <p class="veyq-form-copy folder-share-hint">{{ t('share.roleNeedsReissue') }}</p>
          <Button
            type="button"
            :label="t('share.reissue')"
            icon="pi pi-replay"
            severity="secondary"
            outlined
            size="small"
            :disabled="creating"
            @click="reissueFromEdit"
          />
        </template>
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
          <ShareNoteAcceptField v-model="noteMustAccept" :note="note" :disabled="creating" />
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

      <p v-if="view === 'create' && createError" class="veyq-form-error">{{ t('share.createFailed') }}</p>
      <p v-if="view === 'edit' && editError" class="veyq-form-error">{{ t('share.editFailed') }}</p>
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
          v-if="view === 'edit'"
          class="folder-share-save"
          :label="saving ? t('share.noteSaving') : t('common.save')"
          icon="pi pi-check"
          :loading="saving"
          :disabled="creating || passwordTooShort"
          @click="onSave"
        />
        <Button
          v-else
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
      <p class="veyq-form-copy">{{ t(copyKey('intro')) }}</p>
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
                  <!-- An unnamed link's title is already its role (`titleFor`): the badge would say it twice (inbox #385). -->
                  <span v-if="decoded.get(share.shareId)?.name" class="veyq-badge success folder-share-role">{{ roleLabel(share.role) }}</span>
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
                  v-if="canEdit(share)"
                  type="button"
                  class="veyq-icon-button folder-share-edit"
                  icon="pi pi-pencil"
                  :aria-label="t('share.edit')"
                  :title="t('share.edit')"
                  severity="secondary"
                  text
                  :disabled="busyId === share.shareId"
                  @click="openEdit(share)"
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
