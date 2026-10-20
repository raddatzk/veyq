// The upgrade record's validator against the fixture `scripts/upgrade_proof_selftest.py` holds the orchestrator's to:
// both must accept the fixture's record and refuse every edit it lists (src/web/src/test/upgradeRecord.ts).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { upgradeRecordProblems } from './upgradeRecord'

type Edit = { why: string; path: (string | number)[]; value?: unknown; delete?: boolean }
const fixture = JSON.parse(readFileSync(fileURLToPath(new URL('./upgradeRecord.fixture.json', import.meta.url)), 'utf8')) as {
  record: unknown
  refused: Edit[]
}

function edited(edit: Edit): unknown {
  const copy = structuredClone(fixture.record) as Record<string | number, unknown>
  let at = copy
  for (const step of edit.path.slice(0, -1)) at = at[step] as Record<string | number, unknown>
  const last = edit.path[edit.path.length - 1]
  if (edit.delete) delete at[last]
  else at[last] = edit.value
  return copy
}

describe('upgrade record validation', () => {
  it('accepts the fixture record', () => {
    expect(upgradeRecordProblems(fixture.record)).toEqual([])
  })

  it.each(fixture.refused.map((edit) => [edit.why, edit] as const))('refuses %s', (_why, edit) => {
    // Exactly one problem: an edit that tripped two checks would stay red with the one it is named for removed.
    expect(upgradeRecordProblems(edited(edit))).toHaveLength(1)
  })

  it('refuses what is no object at all', () => {
    expect(upgradeRecordProblems(null)).toEqual(['the record is not a JSON object'])
    expect(upgradeRecordProblems([])).toEqual(['the record is not a JSON object'])
  })
})
