// The legs CI runs split the suite by domain, the way playwright.config.ts says (ADR 0090 decision 7, ADR 0091 decision
// 1, `pipeline.yml`'s `stack` legs). A domain is a folder under `tests/features/` and a Playwright project of the same name;
// `features/instance/` is the dependency project `instance`, not a domain. Each leg runs one domain with
// `--project <domain>`, and Playwright runs that project's dependencies with it. What can go wrong silently: a spec
// left directly in `features/` (or two folders deep) matches no project and runs in no leg, a folder no leg names never
// runs in CI, a leg whose folder is gone runs nothing but the dependencies — and none of them makes a test red. So this
// file lists the suite once whole and once per leg, with no stack booted, and holds:
//   1. every feature spec lies in exactly one folder one level below `features/`, and none directly in `features/`;
//   2. the projects are `setup`, `scenarios`, `instance` and one per domain folder — nothing else, so a new kind of
//      project is a decision this file is told about — and every spec runs in exactly one of them;
//   3. `pipeline.yml`'s matrix names exactly the domain folders, and its `--project` passes the leg's domain;
//   4. every leg runs `setup`, `scenarios` and `instance` whole — the state its feature specs start from — and its own
//      domain whole, and nothing of another domain.
// The checks are a pure function over what the listing and the files say (`problems`), bitten below with a spec outside
// a domain folder, a spec in two projects, a folder missing from the matrix and a matrix leg with no folder.
// `pnpm run check` runs this file, and `pipeline.yml`'s frontend job runs that on every commit.
//
// Its limit: it proves the split, not that a feature spec reads nothing another feature spec left — that is
// playwright.config.ts's rule for the domains, kept by each spec, and a breach shows only as a red leg.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('.', import.meta.url))
const FEATURES = join(ROOT, 'tests', 'features')
const CI = fileURLToPath(new URL('../../.github/workflows/pipeline.yml', import.meta.url))
const CLI = fileURLToPath(new URL('./node_modules/@playwright/test/cli.js', import.meta.url))
const WHOLE = ['setup', 'scenarios', 'instance']
const INSTANCE = 'instance'

/** Every `*.spec.ts` under `features/`, as its path below `features/` (`depot/depot-files.spec.ts`), and its folders. */
function layout() {
  const specs = []
  const folders = []
  const walk = (dir, rel) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = rel ? `${rel}/${entry.name}` : entry.name
      if (entry.isDirectory()) {
        if (!rel) folders.push(entry.name)
        walk(join(dir, entry.name), path)
      } else if (entry.name.endsWith('.spec.ts')) specs.push(path)
    }
  }
  walk(FEATURES, '')
  return { specs: specs.sort(), folders: folders.sort() }
}

/** The domains `pipeline.yml`'s `stack` job runs, one per leg: its matrix `domain: [...]`, and whether it passes the leg's. */
function ciLegs() {
  const ci = readFileSync(CI, 'utf8')
  const stack = ci.slice(ci.indexOf('\n  stack:\n'), ci.indexOf('\n  upgrade-proof:\n'))
  assert.ok(stack.length > 0, 'pipeline.yml has no `stack` job before `upgrade-proof` — this file reads it from there')
  const matrix = /^\s+domain: \[([a-z0-9, -]+)\]$/m.exec(stack)
  assert.ok(matrix, "the stack job's matrix names no `domain: [...]`")
  assert.match(stack, /-- --project "\$\{DOMAIN\}"/, 'the stack job passes no `-- --project "${DOMAIN}"` to the orchestrator')
  assert.match(stack, /DOMAIN: \$\{\{ matrix\.domain \}\}/, 'the stack job does not hand `matrix.domain` to the run as DOMAIN')
  return matrix[1].split(',').map((d) => d.trim()).filter(Boolean)
}

/** Project → spec file (relative to the suite's `tests/`) → test count, as `playwright test --list` selects them. */
function listed(args = []) {
  // No stack: the config lists the tests without one (playwright.config.ts's `booted`).
  const env = { ...process.env }
  delete env.VEYQ_STACK_FILE
  const out = execFileSync(process.execPath, [CLI, 'test', '--list', '--reporter=json', ...args],
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

/**
 * Everything wrong with the split, as messages; empty when it holds. [specs] and [folders] are `layout()`'s, [matrix]
 * the legs' domains, [all] the whole suite's listing and [legs] each leg's (domain → listing).
 */
export function problems({ specs, folders, matrix, all, legs }) {
  const found = []
  const domains = folders.filter((f) => f !== INSTANCE)
  // 1. One folder deep, never directly in features/ and never deeper.
  for (const spec of specs) {
    const parts = spec.split('/')
    if (parts.length !== 2) found.push(`features/${spec} is not in exactly one domain folder (features/<domain>/<name>.spec.ts)`)
  }
  if (!folders.includes(INSTANCE)) found.push('features/ has no `instance/` folder, the dependency project every leg runs')
  // 2. The projects, and each spec in exactly one of them.
  const expected = [...WHOLE, ...domains].sort()
  const projects = [...all.keys()].sort()
  for (const p of projects) if (!expected.includes(p)) found.push(`project \`${p}\` is neither a dependency project nor a domain folder`)
  for (const p of expected) if (!projects.includes(p)) found.push(`folder or project \`${p}\` lists no test`)
  const owners = new Map()
  for (const [project, files] of all) {
    for (const file of files.keys()) owners.set(file, [...(owners.get(file) ?? []), project])
  }
  for (const spec of specs) {
    const runs = owners.get(`features/${spec}`) ?? []
    if (runs.length === 0) found.push(`features/${spec} runs in no project`)
    if (runs.length > 1) found.push(`features/${spec} runs in ${runs.length} projects: ${runs.join(', ')}`)
    const folder = spec.split('/')[0]
    if (runs.length === 1 && spec.includes('/') && runs[0] !== folder) {
      found.push(`features/${spec} runs in project \`${runs[0]}\`, not in its folder's \`${folder}\``)
    }
  }
  // 3. The matrix names exactly the domain folders.
  for (const d of domains) if (!matrix.includes(d)) found.push(`domain folder \`${d}\` is no leg of pipeline.yml's stack matrix`)
  for (const d of matrix) if (!domains.includes(d)) found.push(`pipeline.yml's stack matrix has a leg \`${d}\` with no domain folder`)
  if (new Set(matrix).size !== matrix.length) found.push("pipeline.yml's stack matrix names a domain twice")
  // 4. Each leg: the dependency projects whole, its own domain whole, nothing else.
  for (const [domain, leg] of legs) {
    for (const name of [...WHOLE, domain]) {
      const want = [...(all.get(name) ?? new Map())].sort()
      const got = [...(leg.get(name) ?? new Map())].sort()
      if (JSON.stringify(got) !== JSON.stringify(want)) found.push(`leg \`${domain}\` does not run \`${name}\` whole`)
    }
    for (const name of leg.keys()) {
      if (![...WHOLE, domain].includes(name)) found.push(`leg \`${domain}\` also runs project \`${name}\``)
    }
  }
  return found
}

test('each CI leg runs the dependency projects whole and one domain folder, and every spec lies in one', () => {
  const { specs, folders } = layout()
  const matrix = ciLegs()
  const all = listed()
  const legs = new Map(matrix.filter((d) => folders.includes(d)).map((d) => [d, listed(['--project', d])]))
  assert.deepEqual(problems({ specs, folders, matrix, all, legs }), [])
  assert.ok(matrix.length >= 2, `pipeline.yml runs ${matrix.length} leg(s); this file holds a split`)
})

// The bites: each breach, planted in a split that holds, must be named — or a green above proves nothing.
test('the checks name a spec outside a domain folder, a spec in two projects, a missing leg and a leg with no folder', () => {
  const file = (name) => new Map([[name, 1]])
  const base = () => ({
    specs: ['instance/instance-mail.spec.ts', 'depot/depot-files.spec.ts', 'memories/memory-banner.spec.ts'],
    folders: ['depot', 'instance', 'memories'],
    matrix: ['depot', 'memories'],
    all: new Map([
      ['setup', file('setup/first-run.setup.ts')],
      ['scenarios', file('scenarios/memory-roundtrip.spec.ts')],
      ['instance', file('features/instance/instance-mail.spec.ts')],
      ['depot', file('features/depot/depot-files.spec.ts')],
      ['memories', file('features/memories/memory-banner.spec.ts')],
    ]),
    legs: new Map(),
  })
  const whole = base()
  for (const d of whole.matrix) {
    whole.legs.set(d, new Map([...whole.all].filter(([p]) => [...WHOLE, d].includes(p))))
  }
  assert.deepEqual(problems(whole), [], 'the planted split holds before a breach is planted')

  const outside = base()
  outside.specs.push('stray.spec.ts')
  assert.match(problems(outside).join('\n'), /features\/stray\.spec\.ts is not in exactly one domain folder/)
  assert.match(problems(outside).join('\n'), /features\/stray\.spec\.ts runs in no project/)

  const twice = base()
  twice.all.get('memories').set('features/depot/depot-files.spec.ts', 1)
  assert.match(problems(twice).join('\n'), /features\/depot\/depot-files\.spec\.ts runs in 2 projects/)

  const missing = base()
  missing.matrix = ['depot']
  assert.match(problems(missing).join('\n'), /domain folder `memories` is no leg/)

  const orphan = base()
  orphan.matrix = ['depot', 'memories', 'expenses']
  assert.match(problems(orphan).join('\n'), /a leg `expenses` with no domain folder/)

  const partial = structuredClone(whole)
  partial.legs.get('depot').delete('instance')
  partial.legs.get('memories').set('depot', file('features/depot/depot-files.spec.ts'))
  const found = problems(partial).join('\n')
  assert.match(found, /leg `depot` does not run `instance` whole/)
  assert.match(found, /leg `memories` also runs project `depot`/)
})
