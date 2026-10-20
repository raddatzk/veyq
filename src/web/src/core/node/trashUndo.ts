// The undo after a member's delete into the space's trash (inbox #385 (c)): "“x” moved to the trash." with the app's
// undo-toast action (`useToast().showUndo`, App.vue's undo group). A node delete is committed at once (a soft delete —
// ADR 0061), so its undo is a restore through core's `restoreNode`, the trash dialog's own path; the caller says how
// (its tree, its reload). A restore that fails — a sibling took the name meanwhile, the network — says so and points at
// the trash, where a new name can be given. Composable: call it in `setup`.
import { useI18n } from 'vue-i18n'
import { useToast } from '../../shared/state/useToast'

/** How long the undo stays offered: the gallery's media delete's window. */
export const TRASH_UNDO_MS = 6000

/** Returns `offer(name, restore)`: show the undo toast for the node `name` just trashed; its Undo runs `restore`. */
export function useTrashUndo(): (name: string, restore: () => Promise<void>) => void {
  const toast = useToast()
  const { t } = useI18n()
  return (name, restore) =>
    toast.showUndo({
      summary: t('node.trash.moved', { name }),
      life: TRASH_UNDO_MS,
      onUndo: () => {
        restore().catch(() => toast.error(t('node.trash.undoFailed', { name })))
      },
    })
}
