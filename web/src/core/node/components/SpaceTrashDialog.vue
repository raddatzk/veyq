<script setup lang="ts">
// The space's node trash (uc.memory.delete-node, ADR 0061). A trashed *medium* lives in a tab on its Memory; a
// trashed *Memory* cannot, because its own Memory is what was deleted — so the node trash hangs off the SPACE
// and opens from the browse toolbar. It lists only the ROOT of each deleted subtree: the descendants went down
// with it and come back with it, so offering them separately would offer a restore that cannot stand alone. The one
// exception the server makes (inbox #357): a descendant whose own name was freed by an earlier delete stays in the
// trash when its parent is restored, and is then a root of its own — so after a restore the list is read again, once
// the host has reloaded the tree (that descendant's name opens under its now live parent's key, which only the
// reloaded tree holds; read against the old one it would refuse to open).
//
// Restore can legitimately fail: a node created while this one sat in the trash may already hold the name. That
// is the ordinary disambiguation a create takes (A1), so the row turns into a name field and retries — it does
// NOT silently rename, and it does not refuse forever.
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { listTrash, restoreNode, type TrashedNode, type TreeNode } from '../tree'
import type { NodeKind } from '../kind'
import { registry } from '../../applications/registry'
import type { EpochKeys } from '../../../features/spaces/epochs'
import { HttpStatus, isCodedError } from '../../../shared/httpError'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

// `tree` is the live tree: a trashed root's name and its restore's slug token live under its live parent's key (ADR 0080).
const props = defineProps<{ scopeId: string; epochs: EpochKeys; tree: TreeNode[] }>()
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
    rows.value = await listTrash(props.scopeId, props.epochs, props.tree)
  },
  { onError: () => { loadFailed.value = true } },
)

/** Re-read the list without the spinner, after the host handed over a reloaded tree. A later reload wins over an
 *  earlier one still in flight (two restores in a row). */
let reloads = 0
async function reload() {
  const mine = ++reloads
  try {
    const fresh = await listTrash(props.scopeId, props.epochs, props.tree)
    if (mine === reloads) { rows.value = fresh; loadFailed.value = false }
  } catch {
    if (mine === reloads) loadFailed.value = true
  }
}
// The host answers `restored` by reloading the tree and passing the new one in — only then is the list read again.
watch(() => props.tree, () => void reload())

function day(d: Date): string {
  return d.toLocaleDateString(locale.value)
}

// The icon is the kind's own, from the registry (ADR 0079): core names no application kind. A kind nobody
// registered (a newer server) gets a neutral icon rather than a blank one.
function icon(kind: NodeKind): string {
  const info = registry.kind(kind)
  return info.supported ? info.icon : 'pi pi-file'
}

function startRetry(row: TrashedNode) {
  renaming.value = row.id
  retryName.value = row.name
}

async function restore(row: TrashedNode, newName?: string) {
  rowError.value = null
  busy.value = row.id
  try {
    await restoreNode(props.scopeId, props.epochs, props.tree, row, newName)
    rows.value = rows.value.filter((r) => r.id !== row.id) // at once; the reload after the tree's replaces the list
    renaming.value = null
    emit('restored') // the host reloads the tree; the new `tree` prop then reloads this list
  } catch (e) {
    // A `slug_taken` is the ONLY recoverable one, and it is recovered the way a create is — the row turns into
    // a name field. `scope_deleting` and everything else are a plain failure: the remedy is not a new name.
    if (await isCodedError(e, HttpStatus.conflict, 'slug_taken')) {
      rowError.value = { id: row.id, message: t('node.slugTaken') }
      startRetry(row)
    } else {
      rowError.value = { id: row.id, message: t('node.trash.restoreFailed') }
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
    :header="t('node.trash.title')"
    modal
    :style="{ width: 'min(38rem, 92vw)' }"
    @update:visible="emit('close')"
  >
    <p class="dialog-copy">{{ t('node.trash.hint') }}</p>

    <div v-if="loading" class="trash-state"><ProgressSpinner style="width: 2rem; height: 2rem" /></div>
    <Message v-else-if="loadFailed" severity="error" :closable="false">{{ t('node.trash.loadFailed') }}</Message>
    <p v-else-if="rows.length === 0" class="trash-state">{{ t('node.trash.empty') }}</p>

    <div v-else class="veyq-list">
      <div v-for="row in rows" :key="row.id" class="veyq-list-row trash-row">
        <div class="veyq-row-main">
          <strong class="veyq-row-title"><i :class="icon(row.kind)" aria-hidden="true"></i> {{ row.name }}</strong>
          <span class="veyq-row-subtitle">
            {{ t('node.trash.deletedOn', { day: day(row.deletedAt) }) }} ·
            {{ t('node.trash.purgeOn', { day: day(row.purgeAfter) }) }}
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
            :aria-label="t('node.trash.retryName')"
            :disabled="busy === row.id"
            @keyup.enter="restore(row, retryName)"
          />
          <Button
            :label="t('node.trash.restore')"
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
            :label="t('node.trash.restore')"
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
/* Both dates in full: the list row's subtitle is one cut line, which cut the purge date off (inbox #385). */
.trash-row .veyq-row-subtitle {
  white-space: normal;
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
