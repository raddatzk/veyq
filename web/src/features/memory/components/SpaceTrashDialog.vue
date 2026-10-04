<script setup lang="ts">
// The space's node trash (uc.memory.delete-node, ADR 0061). A trashed *medium* lives in a tab on its Memory; a
// trashed *Memory* cannot, because its own Memory is what was deleted — so the node trash hangs off the SPACE
// and opens from the browse toolbar. It lists only the ROOT of each deleted subtree: the descendants went down
// with it and come back with it, so offering them separately would offer a restore that cannot stand alone.
//
// Restore can legitimately fail: a node created while this one sat in the trash may already hold the name. That
// is the ordinary disambiguation a create takes (A1), so the row turns into a name field and retries — it does
// NOT silently rename, and it does not refuse forever.
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { listTrash, restoreNode, type TrashedNode } from '../../../core/node/tree'
import { NodeKind } from '../../../api/generated/content'
import type { EpochKeys } from '../../spaces/epochs'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const props = defineProps<{ scopeId: string; epochs: EpochKeys }>()
const emit = defineEmits<{ restored: []; close: [] }>()
const { t, locale } = useI18n()

const rows = ref<TrashedNode[]>([])
const loadFailed = ref(false)
/** The row currently being disambiguated after a `slug_taken`, and the name the user is retrying under. */
const renaming = ref<string | null>(null)
const retryName = ref('')
const rowError = ref<{ id: string; message: string } | null>(null)
const busy = ref<string | null>(null)

const { run: load, pending: loading } = useAsyncAction(
  async () => {
    rows.value = await listTrash(props.scopeId, props.epochs)
  },
  { onError: () => { loadFailed.value = true } },
)

function day(d: Date): string {
  return d.toLocaleDateString(locale.value)
}

function icon(kind: NodeKind): string {
  return kind === NodeKind.Folder ? 'pi pi-folder' : 'pi pi-images'
}

function startRetry(row: TrashedNode) {
  renaming.value = row.id
  retryName.value = row.name
}

async function restore(row: TrashedNode, newName?: string) {
  rowError.value = null
  busy.value = row.id
  try {
    await restoreNode(props.scopeId, props.epochs, row, newName)
    rows.value = rows.value.filter((r) => r.id !== row.id)
    renaming.value = null
    emit('restored')
  } catch (e) {
    // A `slug_taken` is the ONLY recoverable one, and it is recovered the way a create is — the row turns into
    // a name field. `scope_deleting` and everything else are a plain failure: the remedy is not a new name.
    if (await isCodedError(e, HttpStatus.conflict, 'slug_taken')) {
      rowError.value = { id: row.id, message: t('memory.slugTaken') }
      startRetry(row)
    } else {
      rowError.value = { id: row.id, message: t('memory.spaceTrash.restoreFailed') }
    }
  } finally {
    busy.value = null
  }
}

onMounted(() => void load())
</script>

<template>
  <Dialog
    :visible="true"
    :header="t('memory.spaceTrash.title')"
    modal
    :style="{ width: 'min(38rem, 92vw)' }"
    @update:visible="emit('close')"
  >
    <p class="dialog-copy">{{ t('memory.spaceTrash.hint') }}</p>

    <div v-if="loading" class="trash-state"><ProgressSpinner style="width: 2rem; height: 2rem" /></div>
    <Message v-else-if="loadFailed" severity="error" :closable="false">{{ t('memory.spaceTrash.loadFailed') }}</Message>
    <p v-else-if="rows.length === 0" class="trash-state">{{ t('memory.spaceTrash.empty') }}</p>

    <div v-else class="veyq-list">
      <div v-for="row in rows" :key="row.id" class="veyq-list-row trash-row">
        <div class="veyq-row-main">
          <strong class="veyq-row-title"><i :class="icon(row.kind)" aria-hidden="true"></i> {{ row.name }}</strong>
          <span class="veyq-row-subtitle">
            {{ t('memory.spaceTrash.deletedOn', { day: day(row.deletedAt) }) }} ·
            {{ t('memory.spaceTrash.purgeOn', { day: day(row.purgeAfter) }) }}
          </span>
          <Message
            v-if="rowError && rowError.id === row.id"
            severity="error"
            :closable="false"
            class="trash-row-error"
          >{{ rowError.message }}</Message>
        </div>

        <div v-if="renaming === row.id" class="trash-actions">
          <InputText
            v-model="retryName"
            :aria-label="t('memory.spaceTrash.retryName')"
            :disabled="busy === row.id"
            @keyup.enter="restore(row, retryName)"
          />
          <Button
            :label="t('memory.spaceTrash.restore')"
            icon="pi pi-undo"
            size="small"
            :loading="busy === row.id"
            :disabled="!retryName.trim()"
            @click="restore(row, retryName)"
          />
          <Button
            :label="t('common.cancel')"
            severity="secondary"
            outlined
            size="small"
            :disabled="busy === row.id"
            @click="renaming = null"
          />
        </div>
        <div v-else class="trash-actions">
          <Button
            :label="t('memory.spaceTrash.restore')"
            icon="pi pi-undo"
            size="small"
            :loading="busy === row.id"
            @click="restore(row)"
          />
        </div>
      </div>
    </div>

    <div class="veyq-dialog-action-row">
      <Button type="button" :label="t('common.done')" severity="secondary" outlined @click="emit('close')" />
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
  color: var(--app-muted);
}
.trash-state {
  color: var(--app-muted);
  margin: 0.5rem 0;
}
.trash-row {
  align-items: flex-start;
  gap: 0.75rem;
}
.trash-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.trash-row-error {
  margin-top: 0.4rem;
}
</style>
