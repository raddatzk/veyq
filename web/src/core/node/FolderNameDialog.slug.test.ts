// @vitest-environment happy-dom
// A rename through core's name dialog folds the new name by the RENAMED NODE's kind (inbox #350, cr-files-application
// P4c): the dialog names no kind and no folding — it hands the node to the real `renameNode`, which asks
// `registry.slugFor(node.kind, name)`. Proven on the wire (the JSON at `fetch`), over the app's real registry as
// `src/test/setup.ts` fills it (Memories and Depot: FILE folds by `fileNameSlug`). Each token is compared with both
// foldings, so a dialog or a tree helper that fell back to the folder's `slugify` is told apart.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../sharing/manageShares', () => ({ resealShareNames: vi.fn(async () => ({ resealed: [], skipped: [], failed: [] })) }))

import FolderNameDialog from './FolderNameDialog.vue'
import { treeStore } from './treeStore'
import type { TreeNode } from './tree'
import { FOLDER_KIND } from './kind'
import { FILE_KIND } from '../../features/depot/kinds'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { fileNameSlug, slugify } from '../../shared/naming'
import { realI18n } from '../../test/i18n'
import { treeNode } from '../../test/models'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const A = '5c09e000-0000-4000-8000-0000000000a0'
const F = '5c09e000-0000-4000-8000-0000000000f0'
const scopeKey = v1.randomBytes(32)
const keyA = v1.randomBytes(32)
const keyF = v1.randomBytes(32)
const sealWrap = (kek: Uint8Array, id: string, k: Uint8Array) =>
  b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 3 }, kek, k))
const row = (id: string, parentId: string | null, kind: string, kek: Uint8Array, k: Uint8Array, name: string): TreeNode => treeNode({
  id, parentId, kind, orderIndex: 0, epoch: 0, name, wrappedContentKey: sealWrap(kek, id, k), wrappedMeta: null, metaVersion: 0, placementVersion: 3,
})

const stubs = {
  Dialog: { template: '<div><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'">{{ label }}</button>' },
  Message: { template: '<div><slot /></div>' },
}

afterEach(() => vi.unstubAllGlobals())

async function renameOnTheWire(kind: string, newName: string): Promise<Record<string, unknown>> {
  const bodies: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>)
    return new Response(null, { status: 204 })
  }))
  const node = row(F, A, kind, keyA, keyF, 'Old')
  treeStore.nodes = [row(A, null, FOLDER_KIND, scopeKey, keyA, 'Reisen'), node]
  const w = mount(FolderNameDialog, {
    props: { mode: 'rename', scopeId: SCOPE_ID, epochs: epochsOf(scopeKey), node },
    global: { plugins: [realI18n()], stubs },
  })
  await w.find('input').setValue(newName)
  await w.find('form').trigger('submit')
  await vi.waitFor(() => expect(w.emitted('done')).toBeTruthy())
  expect(bodies).toHaveLength(1)
  return bodies[0]
}

describe('FolderNameDialog: a rename folds by the node\'s kind (inbox #350)', () => {
  const NAME = 'Day1.MD'
  const token = (folded: string) => b64std(v1.slugToken(keyA, A, folded))

  it("a Depot node's new name folds by fileNameSlug, not slugify", async () => {
    expect(fileNameSlug(NAME)).not.toBe(slugify(NAME)) // the two foldings differ for this name (control)
    const body = await renameOnTheWire(FILE_KIND, NAME)
    expect(body.slugToken).toBe(token(fileNameSlug(NAME)))
    expect(body.slugToken).not.toBe(token(slugify(NAME)))
  })

  it("a folder's new name keeps slugify", async () => {
    const body = await renameOnTheWire(FOLDER_KIND, NAME)
    expect(body.slugToken).toBe(token(slugify(NAME)))
  })
})
