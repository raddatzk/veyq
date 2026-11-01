// The question core asks before ONE node goes to the trash (inbox #375): a folder has its own title and sentence, any other
// node takes them from its kind's `deleteTitle` / `deleteCopy` in the registry (Memories: "Delete Memory"; a drop box names
// its guests' folders and links, inbox #395), else the neutral ones - core's strings name no application. Every delete of
// one node through core's `DeleteNodesDialog` asks it: a row's default delete, a selection of one, and a page's own node
// from its header.
import { FOLDER_KIND } from './kind'
import { registry } from '../applications/registry'
import type { TreeNode } from './tree'

type Translate = (key: string, values?: Record<string, unknown>) => string

export function deleteQuestion(t: Translate, node: Pick<TreeNode, 'kind'>, name: string): { header: string; copy: string } {
  if (node.kind === FOLDER_KIND) return { header: t('node.deleteFolderTitle'), copy: t('node.deleteFolderCopy', { name }) }
  const info = registry.kind(node.kind)
  return {
    header: t(info.supported && info.deleteTitle ? info.deleteTitle : 'node.delete'),
    copy: t(info.supported && info.deleteCopy ? info.deleteCopy : 'node.deleteCopy', { name }),
  }
}
