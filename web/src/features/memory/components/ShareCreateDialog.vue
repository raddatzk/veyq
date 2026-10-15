<script setup lang="ts">
// The share create/edit dialog (cr-0116), opened from the list-only ShareDialog. Shared by BOTH share flows via the
// `uploader` flag so they stay identical by construction: the normal flow shows a READ/READ_WRITE role switcher;
// the uploader (drop-box) flow fixes the role to WRITE_ONLY and hides the switcher — that is the ONLY difference.
// CREATE collects an optional name + per-locale note, mints the share and shows the copyable link. EDIT (editShare
// set): pre-fills name + note (decrypted via the K_SC-recoverable K_share) and saves them in place with `updateShare` —
// the link stays valid. Operator-blind: name + note sealed under K_share; the fresh K_share rides only the returned
// link. (The uploader-links capability is gated on the memory ACTION that opens the uploader dialog, not here.)
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import SelectButton from 'primevue/selectbutton'
import ToggleSwitch from 'primevue/toggleswitch'
import DatePicker from 'primevue/datepicker'
import LocaleMarkdownEditor from '../../../shared/ui/LocaleMarkdownEditor.vue'
import { createShare, updateShare, reissueShare, isShareDeleted, readShareNote, readShareName, readSharePassword, hasLocaleText, type LocaleMap, type ManagedShare } from '../share'
import { NO_NOTE, type ShareNote } from '../../../core/sharing/shareNote'
import ShareNoteAcceptField from '../../../core/sharing/ShareNoteAcceptField.vue'
import { buildShareLink } from '../shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import type { EpochKeys } from '../../spaces/epochs'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

/** cr-0118: a password-protected share requires at least this many characters (a client-side convention — the
 *  server never sees the password). */
const MIN_PW = 10

const props = defineProps<{
  /** The shared Memory's scope — the new share's recovery copy of `K_share` is bound to it (ADR 0072). */
  scopeId: string
  nodeId: string
  memoryKey: Uint8Array
  /** The scope's epoch keys: a new share's recovery copy seals under the current one, an edited share's opens at its
   *  own epoch (ADR 0036 point 4). */
  epochs: EpochKeys
  memoryName: string
  /** When set, the dialog edits this share's name + note in place instead of creating a new one. */
  editShare?: ManagedShare | null
  /** When set, the dialog RE-ISSUES this share (ADR 0075): the create form pre-filled from it, role fixed, and on
   *  submit a new link replaces it — the old one is switched off in the same request. */
  reissueFrom?: ManagedShare | null
  /** The uploader (drop-box) variant: role fixed to WRITE_ONLY, no switcher. Identical otherwise. */
  uploader?: boolean
  /** The shared node is a chapter: the hints say "chapter", not "Memory" (inbox #363; the parent decides by `nodeKind`). */
  chapter?: boolean
  /** Offer the READ_WRITE (contribute) role: the capability function's `shareContribute` (`core.public-shares.contribute`),
   *  handed down by the host, never read here. Absent: viewer only (fail closed). */
  contribute?: boolean
}>()
// `reissue`: the edit asks its parent to renew this share instead (#303) — the renew is the parent's own dialog.
const emit = defineEmits<{ close: []; saved: []; reissue: [share: ManagedShare] }>()
const { t } = useI18n()

const isEdit = computed(() => !!props.editShare)
const isReissue = computed(() => !isEdit.value && !!props.reissueFrom)
// A share's recovery copy is bound to (scope, Memory, share) (ADR 0072): this dialog's scope + Memory, never the row's.
const addressOf = (s: ManagedShare) => ({ scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId })
// The role switcher shows in the normal flow on BOTH create and edit (cr-0119: an existing READ/READ_WRITE share
// can be re-graded in place); the uploader variant never shows it (its role is fixed, its promise not flippable).
// A re-issue keeps the old share's role (the server takes it from the old row), so it shows no switcher either.
const showRoleSwitcher = computed(() => !props.uploader && !isReissue.value)
const headerLabel = computed(() =>
  isEdit.value
    ? t('share.editTitle')
    : isReissue.value
      ? t('share.reissueTitle')
      : props.uploader ? t('share.uploaderCreate') : t('share.createNew'),
)
const name = ref('')
const role = ref<ShareRole>(props.uploader ? ShareRole.WriteOnly : ShareRole.Read)
const note = ref<LocaleMap>({})
/** The note's acceptance switch, sealed inside the note (uc.sharing.share-note); off by default. */
const noteMustAccept = ref(false)
// cr-0118: an optional password. On edit it is pre-filled from the recovered reveal copy; comparing against
// `currentPassword` on save decides untouched / change / remove. `revealPw` toggles the field's visibility.
const password = ref('')
const currentPassword = ref('')
const revealPw = ref(false)
// cr-0118 / note grouping: each optional section is a grouped card gated by a toggle. On edit the toggles start
// from the existing share (a note present / a password set); on create both start off.
const noteEnabled = ref(false)
const passwordEnabled = ref(false)
// cr-share-link-expiry-ui / -edit: an optional expiry, on create AND edit. The backend accepts + enforces
// `expiresAt` on both create and update (a share past it resolves 404); on edit it is a full replace, so the card
// is pre-filled from the current share and always re-sent. minExpiry = today.
const expiryEnabled = ref(false)
const expiresAt = ref<Date | null>(null)
const minExpiry = new Date()
const error = ref(false)
// A renew lost to another renew or a delete (`409 share_deleted`, ADR 0075 point 1): nothing was minted, the list is
// re-read behind the dialog, and a retry could only be refused again — so the submit stays off.
const alreadyReplaced = ref(false)
const link = ref('')
const copied = ref(false)

// The password (when enabled) must be at least MIN_PW — blocks the save + shows an inline hint once typing starts.
const passwordTooShort = computed(() => passwordEnabled.value && password.value.trim().length < MIN_PW)

onMounted(() => {
  // Edit and re-issue both start from an existing share. A re-issue pre-fills even without a recovery copy (nothing
  // then opens, so the form starts empty) — and a password it cannot read leaves the toggle on with an empty field,
  // which blocks the submit until one is typed: a re-issue never quietly drops a link's protection.
  const s = props.editShare ?? props.reissueFrom
  if (s && (s.sealedShareKey || isReissue.value)) {
    name.value = readShareName(props.epochs, addressOf(s), s, s.sealedShareName)
    const opened = readShareNote(props.epochs, addressOf(s), s, s.sealedNote)
    note.value = { ...opened.texts }
    noteMustAccept.value = opened.mustAccept
    role.value = s.role
    currentPassword.value = readSharePassword(props.epochs, addressOf(s), s, s.sealedPassword)
    password.value = currentPassword.value
    noteEnabled.value = hasLocaleText(note.value)
    passwordEnabled.value = !!s.passwordRequired || currentPassword.value.length > 0
    expiryEnabled.value = !!s.expiresAt
    expiresAt.value = s.expiresAt
  }
})

// The normal flow's switcher offers READ + READ_WRITE only — the uploader is its own action/dialog (no switcher). READ_WRITE
// only where `contribute` is on — or on the edit of a link that already is one, so an existing contributor link keeps its
// role on screen instead of showing no selection. While the switch is off such a link serves no guest (ADR 0088 decision
// 5), but the member still edits its note and name or narrows it to READ; the server refuses only a change INTO it.
const roleOptions = computed(() => [
  { label: t('share.roleViewer'), value: ShareRole.Read },
  ...(props.contribute || props.editShare?.role === ShareRole.ReadWrite
    ? [{ label: t('share.roleContributor'), value: ShareRole.ReadWrite }]
    : []),
])
const roleHint = computed(() =>
  role.value === ShareRole.ReadWrite
    ? t('share.roleContributorHint')
    : props.chapter ? t('share.chapterRoleViewerHint') : t('share.roleViewerHint'),
)
// #303 (ADR 0076 point 5): a link minted before the write verifier refuses every new guest as a READ_WRITE, and an
// edit never adds a verifier (point 2) — only a renew stores one. So such a link, graded READ_WRITE, warns and offers
// that renew. The server says which links those are; the verifier itself is never on the wire.
const roleNeedsReissue = computed(
  () => isEdit.value && !!props.editShare?.writeVerifierMissing && role.value === ShareRole.ReadWrite,
)

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    // A disabled toggle means "no note / no password" — send an empty note / clear the password.
    const effectiveNote: ShareNote = noteEnabled.value ? { texts: note.value, mustAccept: noteMustAccept.value } : NO_NOTE
    // Expiry: the picker gives a local date; the link should work THROUGH that day, so expire at its end. Off /
    // no date → null (no expiry). Full replace on edit — always sent so an unrelated edit keeps it.
    const expiry = expiryEnabled.value && expiresAt.value ? endOfDay(expiresAt.value) : null
    if (isEdit.value && props.editShare?.sealedShareKey) {
      // cr-0118: the password tri-state from the toggle — off clears any existing one, on sets/changes it, an
      // unchanged value is left untouched.
      const hadPassword = !!props.editShare.passwordRequired
      const passwordChange = !passwordEnabled.value
        ? hadPassword
          ? ({ remove: true } as const)
          : undefined
        : password.value === currentPassword.value
          ? undefined
          : ({ set: password.value } as const)
      // cr-0119: a normal-flow edit may re-grade the role (READ ↔ READ_WRITE); the uploader variant never sends one.
      await updateShare(
        addressOf(props.editShare), props.epochs, props.editShare,
        effectiveNote, name.value, expiry, props.uploader ? undefined : role.value, passwordChange,
      )
      emit('saved')
      emit('close')
    } else if (isReissue.value && props.reissueFrom) {
      const minted = await reissueShare(props.reissueFrom, props.memoryKey, props.epochs, props.memoryName, {
        note: effectiveNote, name: name.value, password: passwordEnabled.value ? password.value : '', expiresAt: expiry,
      })
      link.value = buildShareLink(minted.shareId, minted.kShare)
      emit('saved')
    } else {
      const minted = await createShare(
        props.scopeId, props.nodeId, props.memoryKey, props.epochs, props.memoryName, role.value, expiry,
        effectiveNote, name.value, passwordEnabled.value ? password.value : '',
      )
      link.value = buildShareLink(minted.shareId, minted.kShare) // the origin belongs here, not in the data layer
      emit('saved')
    }
  },
  {
    onError: async (e) => {
      if (isReissue.value && (await isShareDeleted(e))) {
        alreadyReplaced.value = true
        emit('saved') // the parent re-reads the list: the link that won is there, the old one is gone
      } else error.value = true
    },
  },
)

async function onSubmit() {
  // Both guarded (the button is also disabled): never mint an under-length password, never retry a lost renew.
  if (passwordTooShort.value || alreadyReplaced.value) return
  error.value = false
  await runSubmit()
}

async function onCopy() {
  try {
    await navigator.clipboard.writeText(link.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied — the field stays selectable */
  }
}

/** End of the picked local day — so "expires on 1 Aug" lets the link work through 1 Aug, not from its midnight. */
function endOfDay(d: Date): Date {
  const e = new Date(d)
  e.setHours(23, 59, 59, 999)
  return e
}
</script>

<template>
  <Dialog
    :visible="true"
    :header="headerLabel"
    modal
    class="share-dialog"
    :closable="!busy"
    :close-on-escape="!busy"
    @update:visible="emit('close')"
  >
    <!-- The freshly-minted link (create only, after success). -->
    <template v-if="link">
      <p class="share-created-copy">{{ t('share.linkReady') }}</p>
      <div class="share-link-row">
        <input
          class="share-link-input"
          type="text"
          :value="link"
          readonly
          :aria-label="t('share.linkLabel')"
          @focus="($event.target as HTMLInputElement).select()"
        />
        <Button
          :label="copied ? t('share.copied') : t('share.copy')"
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          severity="secondary"
          @click="onCopy"
        />
      </div>
      <div class="veyq-dialog-action-row">
        <div class="veyq-action-group">
          <Button :label="t('common.done')" icon="pi pi-check" @click="emit('close')" />
        </div>
      </div>
    </template>

    <!-- The create/edit form. -->
    <template v-else>
      <div class="share-field">
        <label for="share-create-name">{{ t('share.name') }}</label>
        <InputText id="share-create-name" v-model="name" :placeholder="t('share.nameOptional')" :disabled="busy" />
      </div>

      <!-- Normal flow: pick READ/READ_WRITE on create AND edit (cr-0119, re-grade in place). Uploader flow: no
           switcher — just a short description of what a drop-box link does. -->
      <div v-if="showRoleSwitcher" class="share-field">
        <label>{{ t('share.roleLabel') }}</label>
        <SelectButton v-model="role" :options="roleOptions" option-label="label" option-value="value" :allow-empty="false" :disabled="busy" class="share-role-switcher" />
        <p class="share-hint">{{ roleHint }}</p>
        <div v-if="roleNeedsReissue" class="share-role-needs-reissue">
          <p class="share-hint share-reissue-warning">{{ t('share.roleNeedsReissue') }}</p>
          <Button
            type="button"
            :label="t('share.reissue')"
            icon="pi pi-replay"
            severity="secondary"
            outlined
            size="small"
            :disabled="busy"
            @click="emit('reissue', editShare!)"
          />
        </div>
      </div>
      <p v-else-if="uploader && !isEdit && !isReissue" class="share-hint share-uploader-desc">{{ chapter ? t('share.chapterRoleUploaderHint') : t('share.roleUploaderHint') }}</p>
      <p v-if="isReissue" class="share-hint share-reissue-warning">{{ t('share.reissueWarning') }}</p>

      <!-- The optional note, grouped in a card behind a toggle (like the password). On → the per-locale editor. -->
      <div class="share-group">
        <div class="share-group-head">
          <div class="share-group-headings">
            <div class="share-group-title">{{ t('share.noteSection') }}</div>
            <p class="share-group-sub">{{ t('share.noteSectionHint') }}</p>
          </div>
          <ToggleSwitch v-model="noteEnabled" :aria-label="t('share.noteSection')" :disabled="busy" />
        </div>
        <div v-if="noteEnabled" class="share-group-body">
          <LocaleMarkdownEditor v-model="note" :placeholder="t('share.notePlaceholder')" />
          <p class="share-hint">{{ t('share.noteHint') }}</p>
          <ShareNoteAcceptField v-model="noteMustAccept" :note="note" :disabled="busy" />
        </div>
      </div>

      <!-- cr-0118: the optional password, grouped in a card behind the "Passwortschutz" toggle. On → the field
           (min MIN_PW chars). On edit the current password is pre-filled (reveal to check it); toggling off removes
           it, changing it re-sets it — all on the same link. -->
      <div class="share-group">
        <div class="share-group-head">
          <div class="share-group-headings">
            <div class="share-group-title">{{ t('share.passwordSection') }}</div>
            <p class="share-group-sub">{{ t('share.passwordSectionHint', { min: MIN_PW }) }}</p>
          </div>
          <ToggleSwitch v-model="passwordEnabled" :aria-label="t('share.passwordSection')" :disabled="busy" />
        </div>
        <div v-if="passwordEnabled" class="share-group-body">
          <label for="share-create-password">{{ t('share.passwordLabel') }}</label>
          <div class="share-password-row">
            <InputText
              id="share-create-password"
              v-model="password"
              :type="revealPw ? 'text' : 'password'"
              autocomplete="new-password"
              :placeholder="t('share.passwordPlaceholder')"
              :disabled="busy"
            />
            <Button
              type="button"
              :icon="revealPw ? 'pi pi-eye-slash' : 'pi pi-eye'"
              :aria-label="revealPw ? t('common.hide') : t('common.show')"
              :title="revealPw ? t('common.hide') : t('common.show')"
              severity="secondary"
              text
              :disabled="busy"
              @click="revealPw = !revealPw"
            />
          </div>
          <p v-if="password.length > 0 && passwordTooShort" class="share-error">{{ t('share.passwordTooShort', { min: MIN_PW }) }}</p>
        </div>
      </div>

      <!-- cr-share-link-expiry-ui / -edit: an optional expiry, on create AND edit. On → a date picker; the link
           stops working after that day. On edit it is pre-filled from the current share and always re-sent. -->
      <div class="share-group">
        <div class="share-group-head">
          <div class="share-group-headings">
            <div class="share-group-title">{{ t('share.expirySection') }}</div>
            <p class="share-group-sub">{{ t('share.expirySectionHint') }}</p>
          </div>
          <ToggleSwitch v-model="expiryEnabled" :aria-label="t('share.expirySection')" :disabled="busy" />
        </div>
        <div v-if="expiryEnabled" class="share-group-body">
          <label for="share-create-expiry">{{ t('share.expiryLabel') }}</label>
          <DatePicker
            id="share-create-expiry"
            v-model="expiresAt"
            :min-date="minExpiry"
            date-format="yy-mm-dd"
            show-icon
            icon-display="input"
            :disabled="busy"
            fluid
          />
        </div>
      </div>

      <p v-if="alreadyReplaced" class="share-error">{{ t('share.reissueAlreadyReplaced') }}</p>
      <p v-else-if="error" class="share-error">{{ isEdit ? t('share.noteSaveFailed') : isReissue ? t('share.reissueFailed') : t('share.createFailed') }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="emit('close')" />
        <Button
          :label="busy
            ? (isEdit ? t('share.noteSaving') : isReissue ? t('share.reissuing') : t('share.creating'))
            : (isEdit ? t('share.noteSave') : isReissue ? t('share.reissueSubmit') : t('share.create'))"
          :icon="isEdit ? 'pi pi-check' : isReissue ? 'pi pi-replay' : 'pi pi-link'"
          :loading="busy"
          :disabled="busy || passwordTooShort || alreadyReplaced"
          @click="onSubmit"
        />
      </div>
    </template>
  </Dialog>
</template>

<style scoped>
.share-field {
  display: grid;
  gap: 0.4rem;
  margin-bottom: 1.1rem;
}
.share-field > label {
  font-size: 0.85rem;
  font-weight: 600;
}
.share-hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.82rem;
}
/* The uploader-flow description stands in for the (absent) role switcher, so it carries the field's spacing. */
.share-uploader-desc,
.share-reissue-warning {
  margin-bottom: 1.1rem;
}
/* A grouped, lighter card for an optional section (note / password): a toggle-led header + a body shown when on. */
.share-group {
  margin-bottom: 1.1rem;
  padding: 0.85rem 0.95rem;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-surface-muted);
}
.share-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.share-group-headings {
  display: grid;
  gap: 0.15rem;
  min-width: 0;
}
.share-group-title {
  font-size: 0.95rem;
  font-weight: 600;
}
.share-group-sub {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.82rem;
}
.share-group-body {
  display: grid;
  gap: 0.4rem;
  margin-top: 0.9rem;
}
.share-group-body > label {
  font-size: 0.85rem;
  font-weight: 600;
}
.share-password-row {
  display: flex;
  gap: 0.4rem;
  align-items: center;
}
.share-password-row :deep(.p-inputtext) {
  flex: 1 1 auto;
  min-width: 0;
}
/* The role switcher spans the full dialog width, its options splitting it evenly. */
.share-role-switcher {
  display: flex;
  width: 100%;
}
.share-role-switcher :deep(.p-togglebutton) {
  flex: 1 1 0;
}
.share-error {
  margin: 0 0 0.5rem;
  color: var(--app-danger);
  font-size: 0.85rem;
}
.share-created-copy {
  margin: 0 0 0.6rem;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.share-link-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.share-link-input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
}
</style>
