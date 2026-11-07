<script setup lang="ts">
// The platform's share dialog for a FOLDER (uc.sharing.share-public-viewer:folder-share-root, ADR 0080 decisions 6 and
// 12): a member lists the folder's links, makes a new one (READ or READ_WRITE), copies, switches off, re-activates,
// renews and deletes them. Core owns FOLDER, so this is core's own dialog, built only on `./manageShares.ts` and
// `./shareLink.ts` — never on the Memories application's share dialogs. The backend refuses a WRITE_ONLY link on a folder
// (`illegal_scope`), so the folder's own links are READ and READ_WRITE only.
//
// **"Add files only"** (owner 2026-11-04): the third role, in the Memory dialog's order - view only, view + add, add only.
// It is no link of the folder: it names a NEW node under it that the link roots at (ADR 0080 decision 11) - an
// application's, which fills the seam `app.folderUploadShare` (Depot: a drop box named by the link, and that link as its
// first). Offered where the host's offer says `upload` (`./folderUploadShare.ts`: a member who may create that node here)
// and the application registered it; core names no kind and says the role's hint in the application's words. Its form
// takes a name - required, it names the node its guests see -, a password and an end, no note; the name is checked
// against the folder's children before anything is sent, the server's `slug_taken` stays the race backstop. Once it ran
// (`done`), the host re-reads the tree, so the new node is a row behind the dialog; its links are its own dialog's, so
// this list never shows them. Mounted with no node (`nodeId` absent) it is the space's TOP LEVEL's dialog: a scope root is
// no link's root, so it lists nothing and offers this role alone.
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
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import { NOT_A_LOGIN } from '../../shared/ui/notALogin'
import VeyqSwitch from '../../shared/ui/VeyqSwitch.vue'
import VeyqToggleGroup from '../../shared/ui/VeyqToggleGroup.vue'
import VeyqDateField from '../../shared/ui/VeyqDateField.vue'
import { isoDateToLocalDate, localDateToIsoDate } from '../../shared/isoDate'
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
  rekeyingClosedLinks,
  type LocaleMap,
  type ManagedShare,
} from './manageShares'
import { NO_NOTE, type ShareNote } from './shareNote'
import ShareNoteAcceptField from './ShareNoteAcceptField.vue'
import ShareGuestStops from './ShareGuestStops.vue'
import { buildShareLink, recoverShareLink } from './shareLink'
import { lastExpiryDay, linkRules } from './linkRules'
import { decodeHttpError } from '../../shared/httpError'
import { ShareRole } from '../../api/generated/sharing'
import { registry, type NodeShareDialogProps } from '../applications/registry'
import type { ShareDialogCopy } from './shareDialogCopy'
import { FOLDER_KIND } from '../node/kind'
import type { NodeParent } from '../node/tree'
import { treeStore } from '../node/treeStore'
import { collidingSibling, nameTakenMessage } from '../node/siblingCollision'
import { httpStatus, HttpStatus } from '../../shared/httpError'

/** A password-protected link needs at least this many characters (the Memory dialog's convention, cr-0118). */
const MIN_PW = 10

// FOLDER's pre-registered `shareDialog` (cr-files-application P4c), so it takes the platform's share-dialog props: the
// folder's scope (every link's recovery copy of `K_share` is bound to it, ADR 0072), the folder's own content key as
// the key chain opens it (`nodeKey`, ADR 0080 decision 1 — what a link wraps; key material) and its name (`nodeName`,
// re-sealed under `K_share` so a guest sees it without the scope key). `nodeKind` is FOLDER here (or Depot's FILE, through
// `FileShareDialog`). The host's offer decides the roles of a new link, each absent not offered: `view` READ, `contribute`
// READ_WRITE, `upload` "Add files only" - the last only for a folder (and the top level) whose scope epoch the host handed
// (`nodeScopeEpoch`: the new node's create states it). The node's fields are absent only for the top level (above).
type NodeFields = 'nodeId' | 'nodeKey' | 'nodeEpoch' | 'nodeName' | 'nodeKind'
const props = defineProps<
  Omit<NodeShareDialogProps, NodeFields> & Partial<Pick<NodeShareDialogProps, NodeFields>> & { words?: ShareDialogCopy }
>()
const emit = defineEmits<{ close: []; done: [] }>()
/** The space's top level: no node, no links of its own, only the upload-only link (above). Fixed for the dialog's life. */
const atRoot = props.nodeId === undefined
/** The folder's key and epoch as a link wraps them (absent only at the top level, which mints no link of its own). */
const root = () => ({ key: props.nodeKey!, epoch: props.nodeEpoch! })
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
const addressOf = (s: ManagedShare) => ({ scopeId: props.scopeId, nodeId: props.nodeId!, shareId: s.shareId })

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
  return r === ShareRole.ReadWrite ? t(copyKey('roleContributor')) : t('share.roleViewer')
}
const titleFor = (s: ManagedShare) => decoded.value.get(s.shareId)?.name || roleLabel(s.role)
// A pending deletion (`readonly`, the host's): the server refuses every change below but switching a link off, so only
// that is offered - with the list and the copy (`NodeShareDialogProps.readonly`). Revoking only closes exposure, and the
// link stays off if the deletion is stopped (uc.spaces.scope-access A1).
const changes = computed(() => !props.readonly)
// Renew: the owner's own link, live or not; anyone's link once it is off (the new one is then theirs).
const canReissue = (s: ManagedShare) => changes.value && (s.mine || !s.enabled)

async function loadShares() {
  if (atRoot) return
  loadError.value = false
  try {
    shares.value = await listSharesFor(props.nodeId!)
  } catch {
    loadError.value = true
  }
}
// The top level has no links to list: it opens on the form.
onMounted(() => (atRoot ? openCreate() : loadShares()))

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

// A switch-off is a close (ADR 0114): the server marks the folder's subtree due and `revokeShare` starts its re-key here
// in the background. The switched-off fold opens, so the row that says so stays in view.
const onRevoke = (s: ManagedShare) =>
  rowAction(s, async () => {
    await revokeShare({ ...addressOf(s), role: s.role })
    offOpen.value = true
    await loadShares()
  })

async function onReactivate(s: ManagedShare) {
  let renew = false
  await rowAction(s, async () => {
    try {
      await reactivateShare(s, root(), props.epochs, props.nodeName!)
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
    acceptIcon: 'trash',
  })
  if (!confirmed) return
  await rowAction(s, async () => {
    await deleteShare({ ...s, ...addressOf(s) })
    shares.value = shares.value.filter((x) => x.shareId !== s.shareId)
  })
}

/** Renew (ADR 0075): a new link with a fresh key and the old one's settings, the old one deleted in the same request. */
async function onReissue(s: ManagedShare) {
  const confirmed = await ask({
    header: t('share.reissueTitle'),
    message: t(copyKey('reissueWarning')),
    acceptLabel: t('share.reissueSubmit'),
    acceptIcon: 'replay',
  })
  if (!confirmed) return
  busyId.value = s.shareId
  actionError.value = false
  alreadyReplaced.value = false
  try {
    const minted = await remintShare({ ...s, ...addressOf(s) }, root(), props.epochs, props.nodeName!)
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
// The space's link requirements (ADR 0088 decision 8), as the Memory dialog reads them: a required password or end date
// turns its card on and locks it, and the picker ends at the last day the link may run, counted from its own start.
const rules = linkRules()
const maxExpiry = computed(() => lastExpiryDay(rules.maxDays, editing.value?.createdAt ?? new Date()))
const expiryMissing = computed(() => rules.maxDays !== null && !expiresAt.value)
// The date field binds a calendar day (`YYYY-MM-DD`); the dialog keeps the `Date` it sends (`endOfDay`).
const expiryDay = computed<string>({
  get: () => localDateToIsoDate(expiresAt.value),
  set: (day) => {
    expiresAt.value = isoDateToLocalDate(day)
  },
})
const minExpiryDay = localDateToIsoDate(minExpiry)
const maxExpiryDay = computed(() => (maxExpiry.value ? localDateToIsoDate(maxExpiry.value) : undefined))
/** The server refused the link for a requirement (a rule set while the dialog was open). */
const requirementRefused = ref(false)
/** Turn on and fill what the requirements ask for: a password card, an end date within the limit. */
function applyLinkRules() {
  if (rules.passwordRequired) passwordEnabled.value = true
  if (rules.maxDays !== null) {
    expiryEnabled.value = true
    const last = maxExpiry.value
    if (last && (!expiresAt.value || expiresAt.value > last)) expiresAt.value = last
  }
}
async function noteRequirementRefusal(e: unknown) {
  const { code } = await decodeHttpError(e)
  requirementRefused.value = code === 'link_password_required' || code === 'link_expiry_required'
}
const createError = ref(false)
const link = ref('')
/** The copied-state id of the freshly-minted link (a share id is 43 base64url characters, so it never collides). */
const NEW_LINK = 'new'

// The roles of a new link, each where the host's offer grants it, in the Memory dialog's order: READ (`view`), READ_WRITE
// (`contribute`, `core.public-shares.contribute`) - or, on the edit, the role the link already is, so an existing link keeps
// its role on screen instead of showing no selection (while a switch is off such a link serves no guest, ADR 0088 decision 5,
// but the member still edits it or narrows it; the server refuses only a change INTO it) - and "Add files only" on a create
// (`upload` and the application's registration; never at a node that is no folder). The top level offers that one alone.
const uploadShare = registry.folderUploadShare()
/** Where the upload-only link's node lands: the folder (its key and both epochs), null at the top level; undefined where it
 *  is not offered. */
const uploadParent = computed<NodeParent | undefined>(() => {
  if (atRoot) return null
  if (props.nodeKind !== FOLDER_KIND || !props.nodeId || !props.nodeKey || props.nodeEpoch == null || props.nodeScopeEpoch == null) return undefined
  return { id: props.nodeId, key: props.nodeKey, epoch: props.nodeEpoch, scopeEpoch: props.nodeScopeEpoch }
})
const uploadOffered = computed(() => !!props.upload && !!uploadShare && !props.readonly && uploadParent.value !== undefined)
const roleOptions = computed(() => {
  const edits = (r: ShareRole) => view.value === 'edit' && editing.value?.role === r
  return [
    ...(!atRoot && (props.view || edits(ShareRole.Read)) ? [{ label: t('share.roleViewer'), value: ShareRole.Read }] : []),
    ...(!atRoot && (props.contribute || edits(ShareRole.ReadWrite))
      ? [{ label: t(copyKey('roleContributor')), value: ShareRole.ReadWrite }]
      : []),
    ...(view.value === 'create' && uploadOffered.value ? [{ label: t('share.folder.roleUploader'), value: ShareRole.WriteOnly }] : []),
  ]
})
/** The upload-only link is chosen: the form names the node it creates, and carries no note. */
const uploading = computed(() => role.value === ShareRole.WriteOnly)
const roleHint = computed(() =>
  uploading.value && uploadShare
    ? t(uploadShare.hint)
    : role.value === ShareRole.ReadWrite ? t(copyKey('roleContributorHint')) : t(copyKey('roleViewerHint')),
)
/** A create mints only a role the offer grants: with none, the submit stays off. */
const roleRefused = computed(() => view.value === 'create' && !roleOptions.value.some((o) => o.value === role.value))
/** The upload-only link's name names its node: required. */
const nameMissing = computed(() => uploading.value && name.value.trim().length === 0)
const passwordTooShort = computed(() => passwordEnabled.value && password.value.trim().length < MIN_PW)

// --- the edit (inbox #364): the create form, pre-filled from the link and saved in place ------------------------
/** The link being edited, and the password its recovery copy opened (to tell untouched / changed / removed apart). */
const editing = ref<ManagedShare | null>(null)
const currentPassword = ref('')
const editError = ref(false)
/** Edit: the caller's own link, whose recovery copy re-opens `K_share` — the Memory dialog's rule. */
const canEdit = (s: ManagedShare) => changes.value && s.mine && !!s.sealedShareKey
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
  applyLinkRules()
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
    // An end date moved past a passed one reopens the link (ADR 0114 decision 7): the folder's key and name go with it.
    await updateShare(
      addressOf(s), props.epochs, s, effectiveNote(), name.value,
      expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null, role.value, passwordChange,
      { root: root(), rootName: props.nodeName! },
    )
    editing.value = null
    view.value = 'list'
    await loadShares()
  },
  {
    onError: async (e) => {
      await noteRequirementRefusal(e)
      editError.value = true
    },
  },
)

async function onSave() {
  if (passwordTooShort.value || expiryMissing.value) return
  editError.value = false
  requirementRefused.value = false
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
  view.value = 'create'
  name.value = ''
  // the first role offered (`roleOptions` reads the create view): READ where it is, the upload-only link alone at the top level
  role.value = roleOptions.value[0]?.value ?? ShareRole.Read
  noteEnabled.value = false
  note.value = {}
  noteMustAccept.value = false
  passwordEnabled.value = false
  password.value = ''
  expiryEnabled.value = false
  expiresAt.value = null
  createError.value = false
  nameTaken.value = null
  applyLinkRules()
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
      props.scopeId, props.nodeId!, root(), props.epochs, props.nodeName!, role.value,
      expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null,
      effectiveNote(), name.value, passwordEnabled.value ? password.value : '',
    )
    link.value = buildShareLink(minted.shareId, minted.kShare) // the origin belongs here, not in the data layer
    view.value = 'link'
    await loadShares()
  },
  {
    onError: async (e) => {
      await noteRequirementRefusal(e)
      createError.value = true
    },
  },
)

// "Add files only": the application births the node named by the link under the folder and mints its first link (the
// registration's `create`). A name a child of the folder already folds to is refused before anything is sent.
/** Why the upload-only link's name was refused: a sibling it collides with, or the server's `slug_taken`. */
const nameTaken = ref<string | null>(null)
const { run: runUpload, pending: uploadMinting } = useAsyncAction(
  async () => {
    const share = uploadShare!
    const parent = uploadParent.value!
    const label = name.value.trim()
    const sibling = collidingSibling(treeStore.nodes, parent?.id ?? null, share.kind, label)
    if (sibling) {
      nameTaken.value = nameTakenMessage(t, sibling)
      return
    }
    try {
      const minted = await share.create({
        scopeId: props.scopeId, epochs: props.epochs, parent, name: label, password: passwordEnabled.value ? password.value : '',
        expiresAt: expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null,
      })
      link.value = buildShareLink(minted.shareId, minted.kShare)
      view.value = 'link'
    } finally {
      // The host re-reads the tree: the new node is a row now - also after a failed mint, which leaves it without a link.
      emit('done')
    }
  },
  {
    onError: async (e) => {
      if (httpStatus(e) === HttpStatus.conflict) {
        nameTaken.value = t('node.slugTaken')
        return
      }
      await noteRequirementRefusal(e)
      createError.value = true
    },
  },
)

async function onCreate() {
  if (passwordTooShort.value || expiryMissing.value || roleRefused.value || nameMissing.value) return
  createError.value = false
  requirementRefused.value = false
  nameTaken.value = null
  await (uploading.value ? runUpload() : runCreate())
}
/** Back from the form, or done with a fresh link: the list - or, at the top level, which has none, the dialog closes. */
function back() {
  if (atRoot) emit('close')
  else view.value = 'list'
}
/** A create or an edit is in flight: the form and the dialog's close wait for it. */
const creating = computed(() => minting.value || saving.value || uploadMinting.value)

// The top level's dialog is one new link throughout: it shares no folder, so it never says so.
const header = computed(() =>
  view.value === 'create' || atRoot ? t('share.createNew') : view.value === 'edit' ? t('share.editTitle') : t(copyKey('title')),
)
function onDialogVisible(visible: boolean) {
  if (!visible && !creating.value) emit('close')
}
</script>

<template>
  <VeyqDialog
    :visible="true"
    :header="header"
    class="share-dialog folder-share-dialog"
    :closable="!creating"
    :close-on-escape="!creating"
    @update:visible="onDialogVisible"
  >
    <!-- The freshly-minted link (after a create or a renew). -->
    <div v-if="view === 'link'" class="veyq-form">
      <p class="veyq-form-copy">{{ t('share.linkReady') }}</p>
      <div class="folder-share-link-row">
        <VeyqInput
          :model-value="link"
          readonly
          class="folder-share-link"
          :aria-label="t('share.linkLabel')"
          @focus="($event.target as HTMLInputElement).select()"
        />
        <VeyqButton
          :label="copiedLinkId === NEW_LINK ? t('share.copied') : t('share.copy')"
          :icon="copiedLinkId === NEW_LINK ? 'check' : 'copy'"
          variant="secondary"
          @click="copy(link, NEW_LINK)"
        />
      </div>
      <div class="veyq-dialog-action-row">
        <div class="veyq-action-group">
          <VeyqButton :label="t('common.done')" icon="check" @click="back" />
        </div>
      </div>
    </div>

    <!-- The create form, and the same form editing a link in place (#364). -->
    <div v-else-if="view === 'create' || view === 'edit'" class="veyq-form">
      <div class="veyq-form-field">
        <label for="folder-share-name">{{ t('share.name') }}</label>
        <VeyqInput v-bind="NOT_A_LOGIN"
          id="folder-share-name"
          v-model="name"
          :placeholder="uploading ? t('share.folder.uploadNamePlaceholder') : t('share.nameOptional')"
          :required="uploading"
          :disabled="creating"
        />
        <p v-if="nameTaken" class="veyq-form-error folder-share-name-taken">{{ nameTaken }}</p>
      </div>

      <div class="veyq-form-field">
        <label>{{ t('share.roleLabel') }}</label>
        <VeyqToggleGroup
          v-model="role"
          :options="roleOptions"
          :disabled="creating"
          class="folder-share-roles"
        />
        <p class="veyq-form-copy folder-share-hint">{{ roleHint }}</p>
        <template v-if="roleNeedsReissue">
          <p class="veyq-form-copy folder-share-hint">{{ t('share.roleNeedsReissue') }}</p>
          <VeyqButton
            :label="t('share.reissue')"
            icon="replay"
            variant="secondary"
            appearance="outlined"
            size="small"
            :disabled="creating"
            @click="reissueFromEdit"
          />
        </template>
      </div>

      <!-- An upload-only link carries no note (its node's application mints it without one). -->
      <div v-if="!uploading" class="veyq-form-field">
        <div class="folder-share-toggle">
          <span>
            <strong>{{ t('share.noteSection') }}</strong>
            <span class="veyq-form-copy folder-share-hint">{{ t('share.noteSectionHint') }}</span>
          </span>
          <VeyqSwitch v-model="noteEnabled" :aria-label="t('share.noteSection')" :disabled="creating" />
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
          <VeyqSwitch v-model="passwordEnabled" :aria-label="t('share.passwordSection')" :disabled="creating || rules.passwordRequired" />
        </div>
        <span v-if="rules.passwordRequired" class="veyq-form-copy folder-share-hint">{{ t('share.passwordRequiredByRule') }}</span>
        <template v-if="passwordEnabled">
          <label for="folder-share-password">{{ t('share.passwordLabel') }}</label>
          <div class="folder-share-link-row">
            <VeyqInput v-bind="NOT_A_LOGIN"
              id="folder-share-password"
              v-model="password"
              class="folder-share-link"
              :type="revealPw ? 'text' : 'password'"
              autocomplete="new-password"
              :placeholder="t('share.passwordPlaceholder')"
              :disabled="creating"
            />
            <VeyqButton
              class="veyq-icon-button"
              :icon="revealPw ? 'eye-slash' : 'eye'"
              :aria-label="revealPw ? t('common.hide') : t('common.show')"
              variant="secondary"
              appearance="text"
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
          <VeyqSwitch v-model="expiryEnabled" :aria-label="t('share.expirySection')" :disabled="creating || rules.maxDays !== null" />
        </div>
        <span v-if="rules.maxDays !== null" class="veyq-form-copy folder-share-hint">{{ t('share.expiryRequiredByRule', { days: rules.maxDays }) }}</span>
        <template v-if="expiryEnabled">
          <label for="folder-share-expiry">{{ t('share.expiryLabel') }}</label>
          <VeyqDateField
            v-model="expiryDay"
            input-id="folder-share-expiry"
            :min-date="minExpiryDay"
            :max-date="maxExpiryDay"
            :disabled="creating"
          />
        </template>
      </div>

      <p v-if="(createError || editError) && requirementRefused" class="veyq-form-error">{{ t('share.requirementRefused') }}</p>
      <p v-else-if="view === 'create' && createError" class="veyq-form-error">{{ t('share.createFailed') }}</p>
      <p v-else-if="view === 'edit' && editError" class="veyq-form-error">{{ t('share.editFailed') }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton
          :label="t('common.cancel')"
          icon="times"
          variant="secondary"
          appearance="outlined"
          :disabled="creating"
          @click="back"
        />
        <VeyqButton
          v-if="view === 'edit'"
          class="folder-share-save"
          :label="saving ? t('share.noteSaving') : t('common.save')"
          icon="check"
          :loading="saving"
          :disabled="creating || passwordTooShort || expiryMissing"
          @click="onSave"
        />
        <VeyqButton
          v-else
          class="folder-share-create"
          :label="creating ? t('share.creating') : t('share.create')"
          icon="link"
          :loading="creating"
          :disabled="creating || passwordTooShort || expiryMissing || roleRefused || nameMissing"
          @click="onCreate"
        />
      </div>
    </div>

    <!-- The list of the folder's links. -->
    <div v-else class="veyq-form">
      <p class="veyq-form-copy">{{ t(copyKey('intro')) }}</p>
      <VeyqButton v-if="changes" class="folder-share-new" :label="t('share.createNew')" icon="plus" @click="openCreate" />
      <p v-else class="veyq-form-copy folder-share-pending">{{ t('share.pendingDeletion') }}</p>

      <p v-if="loadError" class="veyq-form-error">{{ t('share.loadFailed') }}</p>
      <p v-else-if="!folderShares.length" class="veyq-empty veyq-empty-inline">{{ t('share.none') }}</p>
      <ul v-else class="veyq-list">
        <template v-for="section in sections" :key="section.key">
          <li v-if="section.foldable && section.shares.length">
            <VeyqButton
              class="folder-share-fold"
              :icon="offOpen ? 'chevron-down' : 'chevron-right'"
              :label="t('share.offCount', { count: section.shares.length }, section.shares.length)"
              :aria-expanded="offOpen"
              variant="secondary"
              appearance="text"
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
                <!-- ADR 0114 decision 9: what lies below a closed link is re-keyed on this device while this shows. -->
                <span v-if="!share.enabled && rekeyingClosedLinks.has(share.shareId)" class="veyq-row-subtitle folder-share-rekeying">
                  {{ t('share.closedRekeying') }}
                </span>
                <div class="veyq-row-meta">
                  <!-- An unnamed link's title is already its role (`titleFor`): the badge would say it twice (inbox #385). -->
                  <span v-if="decoded.get(share.shareId)?.name" class="veyq-badge success folder-share-role">{{ roleLabel(share.role) }}</span>
                  <span v-if="decoded.get(share.shareId)?.hasNote" class="veyq-badge">{{ t('share.hasNote') }}</span>
                  <span v-if="share.passwordRequired" class="veyq-badge"><VeyqIcon name="lock" />{{ t('share.passwordBadge') }}</span>
                  <span v-if="!share.mine" class="veyq-badge muted">{{ t('share.byMember') }}</span>
                  <span v-if="!share.enabled" class="veyq-badge muted folder-share-off">{{ t('share.disabled') }}</span>
                  <span v-if="share.reissueRequired" class="veyq-badge warning" :title="t('share.reissueRequiredHint')">
                    {{ t('share.reissueRequiredBadge') }}
                  </span>
                </div>
                <!-- inbox #457: the guest gate's own answer why a guest gets no link, with the edit or renew that fixes it. -->
                <ShareGuestStops
                  :share-id="share.shareId"
                  :stops="share.guestStops"
                  :created-at="share.createdAt"
                  :can-edit="canEdit(share)"
                  :can-renew="canReissue(share)"
                  @edit="openEdit(share)"
                  @renew="onReissue(share)"
                />
              </div>
              <div class="folder-share-actions">
                <VeyqButton
                  v-if="share.enabled && decoded.get(share.shareId)?.link"
                  class="veyq-icon-button"
                  :icon="copiedLinkId === share.shareId ? 'check' : 'copy'"
                  :aria-label="t('share.copy')"
                  :title="copiedLinkId === share.shareId ? t('share.copied') : t('share.copy')"
                  variant="secondary"
                  appearance="text"
                  @click="copy(decoded.get(share.shareId)!.link!, share.shareId)"
                />
                <VeyqButton
                  v-if="canEdit(share)"
                  class="veyq-icon-button folder-share-edit"
                  icon="pencil"
                  :aria-label="t('share.edit')"
                  :title="t('share.edit')"
                  variant="secondary"
                  appearance="text"
                  :disabled="busyId === share.shareId"
                  @click="openEdit(share)"
                />
                <VeyqButton
                  v-if="canReissue(share)"
                  class="veyq-icon-button"
                  icon="replay"
                  :aria-label="t('share.reissue')"
                  :title="t('share.reissue')"
                  variant="secondary"
                  appearance="text"
                  :disabled="busyId === share.shareId"
                  @click="onReissue(share)"
                />
                <VeyqButton
                  v-if="changes && !share.enabled && !share.reissueRequired"
                  class="veyq-icon-button"
                  icon="refresh"
                  :aria-label="t('share.reactivate')"
                  :title="t('share.reactivate')"
                  variant="secondary"
                  appearance="text"
                  :disabled="busyId === share.shareId"
                  @click="onReactivate(share)"
                />
                <VeyqButton
                  v-else-if="share.enabled && share.mine"
                  class="veyq-icon-button"
                  icon="ban"
                  :aria-label="t('share.revoke')"
                  :title="t('share.revoke')"
                  variant="danger"
                  appearance="text"
                  :disabled="busyId === share.shareId"
                  @click="onRevoke(share)"
                />
                <!-- Delete only a switched-off link, and only where the server's gate lets it through (ADR 0029 §7). -->
                <VeyqButton
                  v-if="changes && !share.enabled && share.deletable"
                  class="veyq-icon-button"
                  icon="trash"
                  :aria-label="t('share.delete')"
                  :title="t('share.delete')"
                  variant="danger"
                  appearance="text"
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
  </VeyqDialog>
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
.folder-share-roles :deep(.veyq-toggle-group__item) {
  flex: 1 1 0;
}
@media (max-width: 560px) {
  .folder-share-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
