// uc.memory.concurrent-curation (web) — what the Memory header's meta writers do when a write does not land
// (ADR 0077). Shared by the date, place and cover slices so the three editors answer a conflict the same way.
import { useI18n } from 'vue-i18n'
import { useToast } from '../../shared/state/useToast'
import { MetaConflictError, MetaUnreadableError, staleMetaOf, type SealedMetaState } from './sealedMetaWrite'

/** A node's sealed meta moved — the host patches its tree copy (the blob and the version it is at). */
export type NodeMetaUpdate = { nodeId: string } & SealedMetaState

export function useMetaWriteFeedback(onMetaUpdated: (payload: NodeMetaUpdate) => void) {
  const { t } = useI18n()
  const toast = useToast()

  /**
   * An EDITOR's save failed. A same-field conflict patches the tree with the current value, re-opens the editor on
   * it and says so; a blob that does not open says so (nothing was saved); a conflict that outlasted every retry
   * still patches the tree with what the server handed back. Anything else stays as quiet as it always was.
   */
  async function editorFailed(nodeId: string, e: unknown, reopen: () => void): Promise<void> {
    if (e instanceof MetaConflictError) {
      onMetaUpdated({ nodeId, ...e.current })
      reopen()
      toast.error(t('memory.meta.conflict'))
      return
    }
    if (e instanceof MetaUnreadableError) {
      toast.error(t('memory.meta.unreadable'))
      return
    }
    const fresh = await staleMetaOf(e)
    if (fresh) onMetaUpdated({ nodeId, ...fresh })
  }

  /** A SELF-HEAL's write failed: never a word to the user (nobody asked for it); only keep the tree current. */
  async function selfHealFailed(nodeId: string, e: unknown): Promise<void> {
    const fresh = await staleMetaOf(e)
    if (fresh) onMetaUpdated({ nodeId, ...fresh })
  }

  return { editorFailed, selfHealFailed }
}
