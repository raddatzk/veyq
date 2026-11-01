// "Copy to…" as a CORE action (uc.platform.copy-node), contributed to the two places a node shows its
// actions - the header's `coreActions` and the level list's `coreRowActions` - beside the application's own "Move to…"
// (`placement: { after: 'move' }`), with no change to any application's action list. Gated by {@link canCopy}: a copy is a
// member flow (ADR 0082 decision 5, as the import), so a share guest - whatever the role - is never offered one.
import { registry } from '../applications/registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { applicationWritable, type Access } from '../access'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'

type Translate = (key: string) => string

/** Whether this build can copy a node of `kind`: a folder, a kind that imports as one (a drop box), or a kind with both
 *  hooks whose import takes a payload read by frame (`importStreams`). */
export function copiesKind(kind: NodeKind): boolean {
  if (kind === FOLDER_KIND) return true
  const d = registry.kind(kind)
  return d.supported && !!d.exportContent && (!!d.importsAsFolder || (!!d.importContent && !!d.importStreams))
}

/** Whether `access` may copy `node`: a member whose keys are open, on a scope that is not pending deletion (a copy writes),
 *  of a kind this build copies. The original needs only to be read; where the copy may go is the dialog's question. */
export function canCopy(access: Access, node: { kind: NodeKind } | null): boolean {
  if (!node || access.mode !== 'member' || access.epochs === null || access.readonly) return false
  // A copy creates a node of the kind: not while its application is switched off (uc.platform.switch-application-off).
  return copiesKind(node.kind) && applicationWritable(access, node.kind)
}

function action(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onCopy: () => void): (CoreRowAction & CoreHeaderAction)[] {
  const shown = canCopy(access, node)
  return shown ? [{ id: 'copy', label: t('node.copy.action'), icon: 'copy', placement: { after: 'move' }, shown, onClick: onCopy }] : []
}

export function copyHeaderActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onCopy: () => void): CoreHeaderAction[] {
  return action(t, access, node, onCopy)
}

export function copyRowActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onCopy: () => void): CoreRowAction[] {
  return action(t, access, node, onCopy)
}
