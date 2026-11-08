// A dialog closes on a tap beside it only while it holds no field (VeyqDialog, owner 2026-11-08). These dialogs hold none
// in a state where that tap would lose something, so each says `keepOnOutside` there - audited 2026-11-08 over all 42
// VeyqDialog uses. Read from source: a dialog's state is the test of its own file, this pins only that the flag stays.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

const KEPT: Record<string, { flag: RegExp; why: string }> = {
  'features/spaces/InviteMemberDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the invite code is shown once and never stored' },
  'core/export/ExportSubtreeDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'a close aborts the running ZIP write' },
  'core/report/ReportDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the report reference is shown once' },
  'core/sharing/FolderShareDialog.vue': { flag: /:keep-on-outside="busyId !== null"/, why: "a row's re-issue would lose its new link view" },
  'core/node/components/SpaceTrashDialog.vue': { flag: /:keep-on-outside="busy !== null"/, why: 'a restore would lose its reload and its rename prompt' },
  'features/depot/components/DropBoxShareDialog.vue': { flag: /:keep-on-outside="busy"/, why: 'a create would lose its new link view' },
  'features/memories/components/ShareDialog.vue': { flag: /:keep-on-outside="busyRow"/, why: "a row's request would lose its answer" },
  'core/export/CopyNodesDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the destination browsed to, and a failure count' },
  'core/export/ImportSubtreeDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the dry run, and the leftovers a failure names' },
  'core/node/MoveNodeDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the destination browsed to' },
  'core/node/MoveNodesDialog.vue': { flag: /\skeep-on-outside[\s>]/, why: 'the destination browsed to, and what was not moved' },
}

describe('dialogs that hold no field but must not close on a tap beside them', () => {
  it.each(Object.entries(KEPT))('%s keeps itself open (%s)', (path, { flag }) => {
    const source = readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8')
    expect(source).toMatch(flag)
  })
})
