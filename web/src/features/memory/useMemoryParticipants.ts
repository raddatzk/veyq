// uc.memory.participants (web) — the Memory header's participants slice (ADR 0083): who was there, shown as avatars (names in a popover) and
// edited in a picker over the scope's CURRENT members. The ids are the sealed `participants` of the Memory's meta
// (`memoryParticipants.ts`); the names come from the member directory on this device (`core/people/directory.ts`, real name
// else username). An id the directory does not list is a FORMER MEMBER: greyed, no link, and removable by hand - nothing
// prunes it. Shown only where the capability says so (`show`, a member's) and edited only where the meta may be written
// (`write`, the node-meta door the capabilities grant); a guest's view passes neither, and so loads and renders nothing.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { loadScopePeople, type Person } from '../../core/people/directory'
import { nodeMetaState } from '../../core/node/nodeMeta'
import { MetaConflictError, MetaUnreadableError } from '../../core/node/sealedMetaWrite'
import { useToast } from '../../shared/state/useToast'
import { MAX_PARTICIPANTS, unsealMemoryParticipants } from './memoryParticipants'
import { useMetaWriteFeedback, type NodeMetaUpdate } from './useMetaWriteFeedback'
import type { TreeNode } from '../../core/node/tree'
import type { NodeMetaDoor } from './source'

export interface MemoryParticipantsContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  /** Whether the section is shown at all (the `participants` capability, and not a personal scope). */
  show: () => boolean
  /** The node-meta writes this view may make, or null (read-only: a pending deletion, a guest). */
  write: () => NodeMetaDoor | null
  onMetaUpdated: (payload: NodeMetaUpdate) => void
}

/** A participant as a chip shows it. */
export interface ParticipantChip {
  userId: string
  name: string
  /** No longer a member of the scope: greyed, no link. */
  former: boolean
}

export function useMemoryParticipants(ctx: MemoryParticipantsContext) {
  const { t } = useI18n()
  const toast = useToast()
  const { editorFailed } = useMetaWriteFeedback(ctx.onMetaUpdated)

  const ids = computed(() => {
    const key = ctx.memoryKey()
    return ctx.show() && key ? unsealMemoryParticipants(ctx.scopeId(), key, ctx.node()) : []
  })
  const canEdit = computed(() => ctx.show() && !!ctx.memoryKey() && ctx.write() !== null)

  const people = ref<Person[] | null>(null)
  const loadFailed = ref(false)
  let loadedFor: string | null = null
  let ticket = 0

  /** Read the scope's members once per scope (the names of the chips and the picker's candidates). */
  async function ensurePeople(force = false): Promise<void> {
    const scopeId = ctx.scopeId()
    if (!force && loadedFor === scopeId && people.value) return
    const mine = ++ticket
    loadFailed.value = false
    try {
      const list = await loadScopePeople(scopeId)
      if (mine !== ticket) return
      people.value = list
      loadedFor = scopeId
    } catch {
      if (mine === ticket) loadFailed.value = true
    }
  }
  // Names are needed as soon as there is somebody to name; an empty list loads nothing until the picker opens.
  watch([ids, () => ctx.scopeId()], () => {
    if (ids.value.length > 0) void ensurePeople()
  }, { immediate: true })

  const chips = computed<ParticipantChip[]>(() => {
    const list = people.value
    if (!list) return []
    const byId = new Map(list.map((p) => [p.userId, p]))
    return ids.value.map((userId) => {
      const p = byId.get(userId)
      return { userId, name: p ? p.displayName : t('memory.participants.former'), former: !p }
    })
  })
  /** Names are still being read for a list that has entries. */
  const namesPending = computed(() => ids.value.length > 0 && !people.value && !loadFailed.value)

  const pickerOpen = ref(false)
  async function openPicker(): Promise<void> {
    if (!canEdit.value) return
    pickerOpen.value = true
    await ensurePeople(loadFailed.value)
  }

  /** Save the picker's choice (the ids in the order picked). */
  async function onPickerSave(chosen: string[]): Promise<void> {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write || !canEdit.value) return
    const node = ctx.node()
    try {
      const state = await write.setParticipants(node.id, key, chosen, nodeMetaState(node))
      ctx.onMetaUpdated({ nodeId: node.id, ...state })
    } catch (e) {
      await editorFailed(node.id, e, () => (pickerOpen.value = true))
      if (!(e instanceof MetaConflictError) && !(e instanceof MetaUnreadableError)) toast.error(t('memory.participants.saveFailed'))
    }
  }

  return { ids, chips, namesPending, people, loadFailed, canEdit, pickerOpen, openPicker, onPickerSave, ensurePeople, max: MAX_PARTICIPANTS }
}
