<script setup lang="ts">
// The drop box's `shareDialog` (cr-files-application F3): its WRITE_ONLY links — list, create, copy, switch off,
// delete — on core's `manageShares`/`shareLink`, as core's `FolderShareDialog` builds a folder's. A drop box is shared
// WRITE_ONLY only (the backend's `DepotShareRoots`), so there is no role to pick: a new link carries the drop box's drop
// public key (`mintDropBoxLink`), never its key. Switching a link off asks first and is undone by switching it on again
// (core's `reactivateShare`, as the folder dialog does, inbox #394); a link a departed member may hold comes back only by
// a renew (ADR 0075), which this dialog does not build — it says so and points at a new link. Not built here (the folder
// dialog's extras): a label, a note, a renew; a password and an end date only where the space requires them (ADR 0088
// decision 8, `core/sharing/linkRules.ts`), since a link without them would be refused. A link such a requirement stops
// is fixed in place (inbox #457, owner 2026-10-16): its row says why (core's `ShareGuestStops`) and its owner gives the
// SAME link the password or the end date it misses (`updateShare`), so its address and key stay; a link older than the
// lifetime is replaced by a new one from the form below, since no end date fits it.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import DatePicker from 'primevue/datepicker'
import Message from 'primevue/message'
import type { NodeShareDialogProps } from '../../../core/applications/registry'
import {
  deleteShare, isReissueRequired, listSharesFor, reactivateShare, readShareName, readShareNote, revokeShare, updateShare,
  type ManagedShare,
} from '../../../core/sharing/manageShares'
import { buildShareLink, recoverShareLink } from '../../../core/sharing/shareLink'
import { ShareRole } from '../../../api/generated/sharing'
import { mintDropBoxLink } from '../dropBox'
import { useConfirm } from '../../../shared/state/useConfirm'
import { lastExpiryDay, linkRules } from '../../../core/sharing/linkRules'
import ShareGuestStops from '../../../core/sharing/ShareGuestStops.vue'

const props = defineProps<NodeShareDialogProps>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const { ask } = useConfirm()

const shares = ref<ManagedShare[]>([])
const fresh = ref<string | null>(null)
const busy = ref(false)
const failed = ref(false)
const copied = ref<string | null>(null)

/** The core dialogs' minimum for a link password (a client-side convention; the server never sees it). */
const MIN_PW = 10
const rules = linkRules()
const password = ref('')
const expiresAt = ref<Date | null>(lastExpiryDay(rules.maxDays))
const minExpiry = new Date()
const maxExpiry = lastExpiryDay(rules.maxDays)
/** What the space requires and the form does not carry yet: the create waits for it. */
const missing = computed(() =>
  (rules.passwordRequired && password.value.trim().length < MIN_PW) || (rules.maxDays !== null && !expiresAt.value),
)

const links = computed(() =>
  shares.value
    .filter((s) => s.role === ShareRole.WriteOnly)
    .map((s) => ({ share: s, link: recoverShareLink({ scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId }, props.epochs, s) })),
)

async function load() {
  shares.value = await listSharesFor(props.nodeId)
}
onMounted(() => load().catch(() => (failed.value = true)))

async function run(action: () => Promise<void>) {
  busy.value = true
  failed.value = false
  try {
    await action()
    await load()
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}

const create = () =>
  run(async () => {
    const expiry = rules.maxDays !== null && expiresAt.value ? endOfDay(expiresAt.value) : null
    const minted = await mintDropBoxLink(
      props.scopeId, props.nodeId, props.nodeKey, props.epochs, props.nodeName, '',
      rules.passwordRequired ? password.value : '', expiry,
    )
    password.value = ''
    fresh.value = buildShareLink(minted.shareId, minted.kShare)
  })

/** Delete a link for good, after the member said so — the same confirmation core's folder share dialog asks (inbox #385).
 *  What its guests sent stays in the drop box. */
async function remove(shareId: string) {
  const confirmed = await ask({
    header: t('depot.dropBox.shareDelete'),
    message: t('depot.dropBox.shareDeleteConfirm'),
    acceptLabel: t('depot.dropBox.shareDelete'),
    acceptIcon: 'pi pi-trash',
  })
  if (confirmed) await run(() => deleteShare(shareId))
}

/** Switch a link off, after the member said so; it can be switched on again (below). */
async function switchOff(shareId: string) {
  const confirmed = await ask({
    header: t('depot.dropBox.shareRevoke'),
    message: t('depot.dropBox.shareRevokeConfirm'),
    acceptLabel: t('depot.dropBox.shareRevoke'),
    acceptIcon: 'pi pi-power-off',
  })
  if (confirmed) await run(() => revokeShare(shareId))
}

/** Switch a link back on (core's `reactivateShare`, which also hands it to the caller). Refused `reissue_required` for
 *  a link a departed member may hold: the list then marks it (its `reissueRequired`), with no switch to offer. */
const reissueRefused = ref(false)
const switchOn = (shareId: string) =>
  run(async () => {
    reissueRefused.value = false
    try {
      await reactivateShare(shareId)
    } catch (e) {
      if (!(await isReissueRequired(e))) throw e
      reissueRefused.value = true
    }
  })

/** The link whose missed requirement its owner is fixing in place (inbox #457): the same link, address and key kept. */
const fixing = ref<ManagedShare | null>(null)
const fixPassword = ref('')
const fixExpiresAt = ref<Date | null>(null)
/** The fix only for the owner of a link whose recovery copy re-opens `K_share` (a password is sealed under it). */
const canFix = (s: ManagedShare) => s.mine && !!s.sealedShareKey
const fixNeedsPassword = computed(() => !!fixing.value?.guestStops.some((stop) => stop.reason === 'linkPassword'))
/** The lifetime the server named (its strictest), else the one this client holds. */
const fixDays = computed(() => fixing.value?.guestStops.find((stop) => stop.reason === 'linkMaxDays')?.maxDays ?? rules.maxDays)
const fixNeedsExpiry = computed(
  () => !!fixing.value?.guestStops.some((stop) => stop.reason === 'linkMaxDays' || stop.reason === 'expired'),
)
/** Counted from when the link was made, as the server measures it: the last day an end date may fall on. */
const fixMaxExpiry = computed(() => (fixing.value ? lastExpiryDay(fixDays.value, fixing.value.createdAt) : null))
const fixMissing = computed(
  () => (fixNeedsPassword.value && fixPassword.value.trim().length < MIN_PW) || (fixNeedsExpiry.value && !fixExpiresAt.value),
)
function openFix(s: ManagedShare) {
  fixing.value = s
  fixPassword.value = ''
  fixExpiresAt.value = fixMaxExpiry.value
}
const saveFix = () =>
  run(async () => {
    const s = fixing.value!
    const address = { scopeId: props.scopeId, nodeId: props.nodeId, shareId: s.shareId }
    // A full replace of note, label and end date: what the link holds is sent back unchanged beside the fix.
    const note = readShareNote(props.epochs, address, s, s.sealedNote)
    const name = readShareName(props.epochs, address, s, s.sealedShareName)
    const expiry = fixNeedsExpiry.value && fixExpiresAt.value ? endOfDay(fixExpiresAt.value) : s.expiresAt
    await updateShare(address, props.epochs, s, note, name, expiry, undefined, fixNeedsPassword.value ? { set: fixPassword.value } : undefined)
    fixing.value = null
    fixPassword.value = ''
  })

/** End of the picked local day — the link works THROUGH that day, as the core dialogs save it. */
function endOfDay(d: Date): Date {
  const e = new Date(d)
  e.setHours(23, 59, 59, 999)
  return e
}

async function copy(text: string, id: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = id
  } catch {
    /* clipboard denied — the field stays selectable */
  }
}
</script>

<template>
  <Dialog :visible="true" :header="t('depot.dropBox.shareTitle')" modal @update:visible="emit('close')">
    <div class="veyq-form">
      <p>{{ t('depot.dropBox.shareHint') }}</p>
      <div v-if="fresh" class="dropbox-link">
        <InputText :model-value="fresh" readonly :aria-label="t('depot.dropBox.shareCopy')" />
        <Button type="button" icon="pi pi-copy" :label="copied === 'fresh' ? t('depot.dropBox.shareCopied') : t('depot.dropBox.shareCopy')" @click="copy(fresh, 'fresh')" />
      </div>
      <p v-if="!links.length" class="veyq-empty veyq-empty-inline">{{ t('depot.dropBox.shareNone') }}</p>
      <ul v-else class="veyq-list">
        <li v-for="row in links" :key="row.share.shareId" class="veyq-list-row">
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ row.link ?? row.share.shareId }}</span>
            <span v-if="!row.share.enabled" class="veyq-row-subtitle">
              <span class="veyq-badge muted dropbox-link-off">{{ t('depot.dropBox.shareOff') }}</span>
              <template v-if="row.share.reissueRequired"> {{ t('depot.dropBox.shareReissueHint') }}</template>
            </span>
            <!-- inbox #457: why a guest gets no link, and for a missed requirement the fix on this same link. No renew:
                 a link older than the lifetime is replaced by a new one from the form below. -->
            <ShareGuestStops
              :stops="row.share.guestStops"
              :created-at="row.share.createdAt"
              :can-edit="canFix(row.share)"
              :can-renew="false"
              @edit="openFix(row.share)"
            />
          </span>
          <span class="veyq-action-group veyq-action-group-icons">
            <Button
              v-if="row.link && row.share.enabled"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-copy"
              :aria-label="t('depot.dropBox.shareCopy')"
              severity="secondary"
              outlined
              size="small"
              @click="copy(row.link, row.share.shareId)"
            />
            <Button
              v-if="row.share.enabled"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-power-off"
              :aria-label="t('depot.dropBox.shareRevoke')"
              severity="secondary"
              outlined
              size="small"
              :disabled="busy"
              @click="switchOff(row.share.shareId)"
            />
            <Button
              v-else-if="!row.share.reissueRequired"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-refresh"
              :aria-label="t('depot.dropBox.shareReactivate')"
              severity="secondary"
              outlined
              size="small"
              :disabled="busy"
              @click="switchOn(row.share.shareId)"
            />
            <Button
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-trash"
              :aria-label="t('depot.dropBox.shareDelete')"
              severity="danger"
              outlined
              size="small"
              :disabled="busy"
              @click="remove(row.share.shareId)"
            />
          </span>
        </li>
      </ul>
      <div v-if="fixing" class="veyq-form dropbox-fix">
        <strong>{{ t('share.guestStops.fixTitle') }}</strong>
        <template v-if="fixNeedsPassword">
          <label for="dropbox-fix-password">{{ t('share.passwordLabel') }}</label>
          <InputText id="dropbox-fix-password" v-model="fixPassword" type="password" autocomplete="new-password" :disabled="busy" />
          <small class="veyq-form-copy">{{ t('share.passwordSectionHint', { min: MIN_PW }) }}</small>
        </template>
        <template v-if="fixNeedsExpiry">
          <label for="dropbox-fix-expiry">{{ t('share.expiryLabel') }}</label>
          <DatePicker
            id="dropbox-fix-expiry"
            v-model="fixExpiresAt"
            :min-date="minExpiry"
            :max-date="fixMaxExpiry ?? undefined"
            date-format="yy-mm-dd"
            show-icon
            icon-display="input"
            :disabled="busy"
            fluid
          />
        </template>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" outlined :disabled="busy" @click="fixing = null" />
          <Button type="button" class="dropbox-fix-save" :label="t('common.save')" icon="pi pi-check" :loading="busy" :disabled="fixMissing" @click="saveFix" />
        </div>
      </div>
      <template v-if="rules.passwordRequired">
        <label for="dropbox-share-password">{{ t('share.passwordLabel') }}</label>
        <InputText id="dropbox-share-password" v-model="password" type="password" autocomplete="new-password" :disabled="busy" />
        <small class="veyq-form-copy">{{ t('share.passwordRequiredByRule') }} {{ t('share.passwordSectionHint', { min: MIN_PW }) }}</small>
      </template>
      <template v-if="rules.maxDays !== null">
        <label for="dropbox-share-expiry">{{ t('share.expiryLabel') }}</label>
        <DatePicker
          id="dropbox-share-expiry"
          v-model="expiresAt"
          :min-date="minExpiry"
          :max-date="maxExpiry ?? undefined"
          date-format="yy-mm-dd"
          show-icon
          icon-display="input"
          :disabled="busy"
          fluid
        />
        <small class="veyq-form-copy">{{ t('share.expiryRequiredByRule', { days: rules.maxDays }) }}</small>
      </template>
      <Message v-if="reissueRefused" severity="warn" :closable="false">{{ t('depot.dropBox.shareReissueHint') }}</Message>
      <Message v-else-if="failed" severity="error" :closable="false">{{ t('depot.dropBox.shareFailed') }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('depot.dropBox.close')" severity="secondary" outlined @click="emit('close')" />
        <Button type="button" :label="t('depot.dropBox.shareCreate')" icon="pi pi-plus" :loading="busy" :disabled="missing" @click="create" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.dropbox-link {
  display: flex;
  gap: 0.5rem;
}
.dropbox-link :deep(input) {
  flex: 1;
  min-width: 0;
}
</style>
