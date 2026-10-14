// "Download" as a CORE action (ADR 0082 decision 4: there is no "Export"), contributed to the two places a node shows its
// actions - the header's `coreActions` and the level list's `coreRowActions` - for every kind that downloads as a ZIP, with
// no change to any application. A kind that IS one file (`downloadsAsFile`: Files' file) downloads as itself through its
// own action, so core adds none for it. Gated by {@link canDownload}: a member whose keys are open, or a `READ` /
// `READ_WRITE` share guest (decision 5: the member view with rights removed), on a folder or a kind that brings an export
// hook. A `WRITE_ONLY` guest holds no key and downloads nothing.
import { registry } from '../applications/registry'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import type { Access } from '../access'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'
import { ShareRole } from '../../api/generated/sharing'

type Translate = (key: string) => string

/** Whether `access` may download `node` as a ZIP. Reading is allowed while the scope is read-only (pending deletion). */
export function canDownload(access: Access, node: { kind: NodeKind } | null): boolean {
  if (!node) return false
  if (access.mode === 'member' ? access.epochs === null : access.role !== ShareRole.Read && access.role !== ShareRole.ReadWrite) return false
  if (node.kind === FOLDER_KIND) return true
  const d = registry.kind(node.kind)
  return d.supported && !!d.exportContent && !d.downloadsAsFile
}

function action(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onDownload: () => void): (CoreRowAction & CoreHeaderAction)[] {
  if (!canDownload(access, node)) return []
  return [{ id: 'download', label: t('export.action'), icon: 'pi pi-download', placement: 'after', onClick: onDownload }]
}

export function downloadHeaderActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onDownload: () => void): CoreHeaderAction[] {
  return action(t, access, node, onDownload)
}

export function downloadRowActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null, onDownload: () => void): CoreRowAction[] {
  return action(t, access, node, onDownload)
}
