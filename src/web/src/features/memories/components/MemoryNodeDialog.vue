<script setup lang="ts">
// Create a Memory / create a chapter (cr-0030 P4, ADR 0080 Decision 13): Memories' `createDialog`, registered for both
// its kinds (cr-files-application P4c; the folder create and every rename are core's `core/node/FolderNameDialog.vue`,
// with this dialog's markup). Ported dialog shape; wired to the E2EE tree data layer (names sealed client-side under
// the parent's key, K_SC at the scope root — ADR 0080). The kind to create picks the mode. Inline slug_taken (409) /
// illegal_structure (422) errors. Before sending, a name that collides with a sibling in the loaded tree is refused
// naming that sibling (core's `siblingCollision.ts`, inbox #389); the 409 stays the race backstop.
//
// A Memory's create can **pre-arm a location** (cr-0069) behind the personal maps opt-in — but at creation there are
// no photos yet, so the heavy map/name belong later (on the open Memory, via the shared editor). Here it is a
// single opt-in: "Ort — folgt automatisch deinen Fotos" arms a **pending `auto`** place whose position + name are
// materialized by the first geotagged upload (the `MemoryDetail` self-heal). Sealed at birth into `wrappedMeta`.
//
// **"I was there"** (uc.memory.participants, "at creation"): in a tenant or group scope a member's create offers it, ticked
// by default and not remembered; ticked, the same first `wrappedMeta` carries `participants: { userIds: [own id] }` (ADR
// 0083 - the opaque id, never a name), unticked it carries no `participants` key at all. Never in a personal scope (no
// participants there) and never to a share guest - whose create is core's name dialog on the share page, not this one;
// here an empty `account.userId` (no key material read) or a scope other than the open tenant / group one hides it too.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import type { NodeParent } from '../../../core/node/tree'
import { memberCreateOptions } from '../../../core/node/rereadNode'
import type { NodeCreateDialogProps, NodeCreateDialogEmits } from '../../../core/applications/registry'
import { createMemory, createChapter } from '../memoryNodes'
import { activeSpace } from '../../../app/activeSpace'
import { account } from '../../../app/account'
import { ScopeKind } from '../../../api/generated/scopes'
import { CHAPTER_KIND } from '../kinds'
import { treeStore } from '../../../core/node/treeStore'
import { collidingSibling, nameTakenMessage } from '../../../core/node/siblingCollision'
import { useMapConfig } from '../../../shared/state/useMapConfig'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { httpStatus, HttpStatus } from '../../../shared/httpError'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
// `kind`: MEMORY or CHAPTER (the kinds that register this dialog); a chapter is created inside the opened Memory.
const props = defineProps<NodeCreateDialogProps>()
const emit = defineEmits<NodeCreateDialogEmits>()
const { t } = useI18n()
const { mapsAvailable } = useMapConfig()

const isChapter = computed(() => props.kind === CHAPTER_KIND)
const name = ref('')
const error = ref('')
const wantsLocation = ref(false) // opt into a pending `auto` place (create-memory, behind the maps opt-in)
const showLocationStep = computed(() => !isChapter.value && mapsAvailable.value)
const wasThere = ref(true) // "I was there": ticked by default, never remembered (a fresh mount per create)
const showWasThere = computed(
  () =>
    !isChapter.value &&
    !!account.userId &&
    activeSpace.scopeId === props.scopeId &&
    (activeSpace.kind === ScopeKind.Tenant || activeSpace.kind === ScopeKind.Group),
)

const header = computed(() => (isChapter.value ? t('memory.createChapterTitle') : t('memory.createMemoryTitle')))
const confirmLabel = computed(() => t('common.create'))

/** Where a create lands: the scope root (wrapped under the write key), or under the parent with ITS key (ADR 0080
 *  Decision 1: a node inside a folder wraps under the folder's key, never `K_SC`). A parent whose key is missing
 *  creates nothing, rather than a node its parent's holders could not open. */
function createParent(): NodeParent {
  if (!props.parentId) return null
  if (!props.parentKey || props.parentEpoch == null) throw new Error('parent_key_unavailable')
  return { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch }
}

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const trimmed = name.value.trim()
    if (isChapter.value) {
      if (!props.parentId || !props.parentKey || props.parentEpoch == null) throw new Error('chapter_parent_missing')
      await createChapter(
        props.scopeId, props.epochs, props.parentKey, trimmed, props.parentId, props.parentEpoch,
        memberCreateOptions(props.scopeId, { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch }),
      )
    } else {
      const location = showLocationStep.value && wantsLocation.value ? { name: '', nameSource: 'geocoded' as const, derivedMode: 'auto' as const } : null
      const participants = showWasThere.value && wasThere.value ? [account.userId] : []
      const parent = createParent()
      await createMemory(props.scopeId, props.epochs, trimmed, parent, { location, participants }, memberCreateOptions(props.scopeId, parent))
    }
    emit('done')
    emit('close')
  },
  {
    onError: (e) => {
      const status = httpStatus(e)
      error.value = status === HttpStatus.conflict ? t('memory.slugTaken') : status === HttpStatus.unprocessable ? t('node.illegalStructure') : t('node.saveFailed')
    },
  },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  const sibling = collidingSibling(treeStore.nodes, props.parentId ?? null, props.kind, name.value.trim())
  if (sibling) {
    error.value = nameTakenMessage(t, sibling)
    return
  }
  await runSubmit()
}
</script>

<template>
  <VeyqDialog :visible="true" :header="header" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <VeyqInput v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>

      <div v-if="showLocationStep" class="node-location-optin">
        <VeyqCheckbox v-model="wantsLocation" input-id="wants-location" :disabled="loading" />
        <label for="wants-location" class="node-location-optin-text">
          <span>{{ t('memory.place.autoOptIn') }}</span>
          <span class="node-location-optin-hint">{{ t('memory.place.autoOptInHint') }}</span>
        </label>
      </div>

      <div v-if="showWasThere" class="node-location-optin">
        <VeyqCheckbox v-model="wasThere" input-id="was-there" :disabled="loading" />
        <label for="was-there" class="node-location-optin-text">
          <span>{{ t('memory.participants.wasThere') }}</span>
          <span class="node-location-optin-hint">{{ t('memory.participants.wasThereHint') }}</span>
        </label>
      </div>

      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="confirmLabel" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.node-location-optin {
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
}
.node-location-optin-text {
  display: grid;
  gap: 0.12rem;
  font-size: 0.92rem;
  line-height: 1.35;
}
.node-location-optin-hint {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
