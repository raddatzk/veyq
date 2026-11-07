// Every password field this surface draws decides whether it is a login: marked `not-a-login` (`v-bind="NOT_A_LOGIN"`,
// `./notALogin.ts`), or an account credential named by its standard token, `current-password` or `new-password`. A field
// with neither is the case a password manager guesses - the bookmark's link field offered the person's logins (2026-11-07).
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../..', import.meta.url))
function vueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'generated' ? [] : vueFiles(path)
    return name.endsWith('.vue') ? [path] : []
  })
}
/** Every password field drawn: a `VeyqPassword`, or a `VeyqInput` whose type is or may become `password`. */
function passwordFields(): { at: string; tag: string }[] {
  return vueFiles(SRC).flatMap((file) => {
    const text = readFileSync(file, 'utf8')
    return [...text.matchAll(/<(VeyqPassword|VeyqInput)\b([\s\S]*?)\/>/g)]
      .filter((m) => m[1] === 'VeyqPassword' || /\btype="password"|:type="[^"]*'password'/.test(m[2]))
      .map((m) => ({ at: `${relative(SRC, file)}:${text.slice(0, m.index).split('\n').length}`, tag: m[0] }))
  })
}
/** `SecretKeyField` draws the Secret Key, an account credential with no standard token (`autocomplete="off"`, its header). */
const EXEMPT = ['shared/ui/SecretKeyField.vue:']

describe('every password field says whether it is a login', () => {
  it('finds the fields it judges (control)', () => {
    expect(passwordFields().length).toBeGreaterThan(20)
  })

  it('is marked no login, or named an account credential by its standard token', () => {
    const undecided = passwordFields()
      .filter(({ at }) => !EXEMPT.some((e) => at.startsWith(e)))
      .filter(({ tag }) => !/\bnot-a-login\b|v-bind="NOT_A_LOGIN"/.test(tag) && !/\bautocomplete="(current-password|new-password)"/.test(tag))
      .map(({ at }) => at)
    expect(undecided).toEqual([])
  })
})
