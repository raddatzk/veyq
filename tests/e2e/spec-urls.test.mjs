// Every file a spec reaches by `new URL('<relative>', import.meta.url)` exists (cr-repo-layout-infra-and-tests,
// ADR 0106). The specs take fixtures from outside this package (`../../../../web/…`, `../../../../contracts/…`), and
// such a specifier is a string: no type check reads it, and `playwright test --list` loads a spec without reading
// the file a `fileURLToPath` names — so a directory move that miscounts one `../` stays green until lane L1 runs.
// `pnpm run check` runs this file, and `checks.yml`'s frontend job runs that on every commit.
//
// Its limit: it sees only the `new URL(<string literal>, import.meta.url)` form; a path built any other way
// (`path.join`, a template string, a variable) is not read.
import assert from 'node:assert/strict'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = fileURLToPath(new URL('.', import.meta.url))
const SPECIFIER = /new URL\(\s*(['"])([^'"]+)\1\s*,\s*import\.meta\.url\s*\)/g

function* files(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) yield* files(path)
    else if (path.endsWith('.ts')) yield path
  }
}

test('every new URL(…, import.meta.url) in the suite names a file that exists', () => {
  const missing = []
  let seen = 0
  for (const file of files(join(ROOT, 'tests'))) {
    for (const [, , specifier] of readFileSync(file, 'utf8').matchAll(SPECIFIER)) {
      seen += 1
      const target = fileURLToPath(new URL(specifier, pathToFileURL(file)))
      if (!existsSync(target)) missing.push(`${relative(ROOT, file)}: '${specifier}' -> ${target}`)
    }
  }
  // A scan that read nothing would pass over a broken tree as readily as over a sound one.
  assert.ok(seen > 0, 'no new URL(…, import.meta.url) found under tests/ — the scan checked nothing')
  assert.deepEqual(missing, [], `a spec names a file that does not exist:\n  ${missing.join('\n  ')}`)
})
