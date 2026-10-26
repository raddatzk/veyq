// The shards CI runs split the suite the way playwright.config.ts says (ADR 0091 decision 1, `ci.yml`'s `stack` legs).
// Playwright shards only the projects no other project depends on and runs every dependency project whole in every
// shard, so a project that comes to depend on `features` (the erasure project did, until 2026-10-26) turns `features`
// into a dependency: the first shard then runs the whole suite and the others nothing, and no test is red for it. This
// file lists the suite once per shard, with no stack booted, and holds:
//   1. the shard count is the one `ci.yml` runs (its matrix `shard: [1, …, n]` and its `--shard "${SHARD}/n"`);
//   2. every shard runs `setup`, `scenarios` and `instance` whole — the state its feature specs start from;
//   3. `features` is split: each of its spec files lands in exactly one shard, every shard gets at least one, and no
//      other project's tests are shared out;
//   4. no project but these four exists, so a new one is a decision this file is told about.
// `npm run check` runs this file, and `ci.yml`'s frontend job runs that on every commit.
//
// Its limit: it proves the split, not that a feature spec reads nothing another feature spec left — that is
// playwright.config.ts's rule for `features`, kept by each spec, and a breach shows only as a red shard.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('.', import.meta.url))
const CI = fileURLToPath(new URL('../../.github/workflows/ci.yml', import.meta.url))
const CLI = fileURLToPath(new URL('../../node_modules/@playwright/test/cli.js', import.meta.url))
const WHOLE = ['setup', 'scenarios', 'instance']
const SPLIT = 'features'

/** The shard count of `ci.yml`'s `stack` job: its matrix and its `--shard`, which must agree. */
function ciShards() {
  const ci = readFileSync(CI, 'utf8')
  const stack = ci.slice(ci.indexOf('\n  stack:\n'), ci.indexOf('\n  upgrade-proof:\n'))
  assert.ok(stack.length > 0, 'ci.yml has no `stack` job before `upgrade-proof` — this file reads it from there')
  const matrix = /^\s+shard: \[([\d, ]+)\]$/m.exec(stack)
  const flag = /--shard "\$\{SHARD\}\/(\d+)"/.exec(stack)
  assert.ok(matrix, "the stack job's matrix names no `shard: [1, …, n]`")
  assert.ok(flag, 'the stack job passes no `--shard "${SHARD}/n"` to the orchestrator')
  const total = Number(flag[1])
  assert.deepEqual(matrix[1].split(',').map((n) => Number(n.trim())), Array.from({ length: total }, (_, i) => i + 1),
    `the matrix's shards are not 1 to ${total}, the --shard's total`)
  return total
}

/** Project → spec file → test count, as `playwright test --list` selects them for [shard]. */
function listed(shard) {
  // No stack: the config lists the tests without one (playwright.config.ts's `booted`).
  const env = { ...process.env }
  delete env.VEYQ_STACK_FILE
  const out = execFileSync(process.execPath, [CLI, 'test', '--list', '--reporter=json', ...(shard ? [`--shard=${shard}`] : [])],
    { cwd: ROOT, encoding: 'utf8', env, maxBuffer: 64 * 1024 * 1024 })
  const report = JSON.parse(out)
  assert.deepEqual(report.errors ?? [], [], 'the listing reported errors')
  const projects = new Map()
  const walk = (suite) => {
    for (const spec of suite.specs ?? []) {
      for (const t of spec.tests) {
        const files = projects.get(t.projectName) ?? new Map()
        files.set(spec.file, (files.get(spec.file) ?? 0) + 1)
        projects.set(t.projectName, files)
      }
    }
    for (const child of suite.suites ?? []) walk(child)
  }
  for (const suite of report.suites) walk(suite)
  return projects
}

test('each CI shard runs the dependency projects whole and its own part of features', () => {
  const total = ciShards()
  assert.ok(total >= 2, `ci.yml runs ${total} shard(s); this file holds a split`)
  const all = listed(null)
  assert.deepEqual([...all.keys()].sort(), [...WHOLE, SPLIT].sort(), 'the suite has a project this file does not know')
  const owner = new Map()
  for (let i = 1; i <= total; i++) {
    const shard = listed(`${i}/${total}`)
    for (const name of WHOLE) {
      assert.deepEqual(shard.get(name), all.get(name), `shard ${i}/${total} does not run \`${name}\` whole`)
    }
    const features = shard.get(SPLIT) ?? new Map()
    assert.ok(features.size > 0, `shard ${i}/${total} runs no feature spec`)
    for (const [file, count] of features) {
      assert.equal(owner.get(file), undefined, `${file} runs in shard ${owner.get(file)} and in shard ${i}`)
      assert.equal(count, all.get(SPLIT).get(file), `${file} is split across shards`)
      owner.set(file, i)
    }
  }
  assert.deepEqual([...owner.keys()].sort(), [...all.get(SPLIT).keys()].sort(), 'a feature spec runs in no shard')
})
