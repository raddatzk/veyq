// "Export" as a CORE action (ADR 0082), contributed to the two places a node shows its actions - the header's `coreActions`
// and the level list's `coreRowActions` - for every kind that can be exported, with no change to any application. Gated by
// {@link canExport}: a signed-in member whose keys are open, on a node whose kind brings an export hook (or a folder).
// A share guest gets none for now: the walk needs the member's tree and keys (a READ guest's export is a later step).
import { registry } from '../applications/registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import type { Access } from '../access'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'

type Translate = (key: string) => string

/** Whether `node` can be exported by `access`. Reading is allowed while the scope is read-only (pending deletion). */
export function canExport(access: Access, node: { kind: NodeKind } | null): boolean {
  if (!node || access.mode !== 'member' || access.epochs === null) return false
  if (node.kind === FOLDER_KIND) return true
  const d = registry.kind(node.kind)
  return d.supported && !!d.exportContent
}

function action<T extends 'header' | 'row'>(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onExport: () => void): (T extends 'row' ? CoreRowAction : CoreHeaderAction)[] {
  if (!canExport(access, node)) return []
  return [{ id: 'export', label: t('export.action'), icon: 'pi pi-download', placement: 'after', onClick: onExport }]
}

export function exportHeaderActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onExport: () => void): CoreHeaderAction[] {
  return action<'header'>(t, access, node, onExport)
}

export function exportRowActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onExport: () => void): CoreRowAction[] {
  return action<'row'>(t, access, node, onExport)
}
