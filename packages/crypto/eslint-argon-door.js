// `veyq/argon-through-the-worker` — Argon2id reaches production code through `@veyq/crypto/argonWorker` and nothing
// else. One production-params derivation blocks the thread it runs on for ~0.8 s; the front door runs it in a Worker
// and falls back to the page thread only where no Worker can run. Four call sites once bypassed it (device trust,
// share-password create and open, account birth), each found by reading, none by a check: this is the check.
//
// Written once, here, and imported by the three ESLint configs (packages/crypto, web, admin-web), so the five entry
// points cannot drift between them. A rule of its own rather than `no-restricted-syntax` + `no-restricted-imports`:
// flat config resolves a rule by NAME and the last matching block wins, and web/ already spends both core names (and
// `@typescript-eslint/no-restricted-imports`) on other doors — a second block setting them would silently replace
// those doors' options for every file both blocks match.
//
// What it refuses, in any production file outside the exempt set below:
//  - a named import of an entry point from the v1 module (`import { kekRecovery } from '@veyq/crypto/v1'`), or a
//    re-export of one (`export { kekRecovery } from …`, `export * from …`);
//  - a member access on a namespace import of the v1 module, under whatever local name (`v1.kekRecovery`,
//    `v1['kekRecovery']`), and destructuring one out of it (`const { kekRecovery } = v1`).
// LIMIT: a dynamic `import()`, the namespace copied into another binding first (`const x = v1; x.kekRecovery(…)`),
// and a re-export through an EXEMPT file are not seen — the same class of limit `veyq/one-sign-out` states.

/** The five Argon2id entry points of v1.ts (its only `argon2id(` callers) → the front door's async twin of each. */
const ENTRY_POINTS = new Map([
  ['deriveAuthKeypair', 'deriveAuthKeypairAsync'],
  ['kekPasswordSecret', 'unlockKekAsync'],
  ['kekDevicePassword', 'deviceKekAsync'],
  ['kekRecovery', 'recoveryKekAsync'],
  ['sharesPwToken', 'sharesPwTokenAsync'],
])

/** `@veyq/crypto/v1` from a consumer; `./v1` (or any relative `…/v1`) inside the package. */
const V1_MODULE = /^(@veyq\/crypto\/v1|\.{1,2}\/(?:.*\/)?v1(?:\.ts)?)$/

const rule = {
  meta: {
    type: 'problem',
    docs: { description: 'Argon2id runs through @veyq/crypto/argonWorker, never directly on the calling thread.' },
    schema: [],
    messages: {
      direct:
        "{{name}} runs Argon2id on the calling thread. Await {{twin}} from '@veyq/crypto/argonWorker' instead: it runs in the Worker and falls back to the page thread only where none can run.",
      reexport:
        'Re-exporting the v1 module hands its Argon2id entry points past the worker door. Export what the caller needs by name, and never one of the five.',
    },
  },
  create(context) {
    const namespaces = new Set()
    const report = (node, name) => context.report({ node, messageId: 'direct', data: { name, twin: ENTRY_POINTS.get(name) } })
    const keyName = (key, computed) =>
      !computed && key.type === 'Identifier' ? key.name : key.type === 'Literal' ? String(key.value) : null
    const isV1 = (source) => source != null && V1_MODULE.test(String(source.value))

    return {
      ImportDeclaration(node) {
        if (!isV1(node.source) || node.importKind === 'type') return
        for (const spec of node.specifiers) {
          if (spec.type === 'ImportNamespaceSpecifier') namespaces.add(spec.local.name)
          else if (spec.type === 'ImportSpecifier' && spec.importKind !== 'type') {
            const name = keyName(spec.imported, false)
            if (ENTRY_POINTS.has(name)) report(spec, name)
          }
        }
      },
      ExportNamedDeclaration(node) {
        if (!isV1(node.source) || node.exportKind === 'type') return
        for (const spec of node.specifiers) {
          const name = keyName(spec.local, false)
          if (ENTRY_POINTS.has(name)) report(spec, name)
        }
      },
      ExportAllDeclaration(node) {
        if (isV1(node.source) && node.exportKind !== 'type') context.report({ node, messageId: 'reexport' })
      },
      MemberExpression(node) {
        if (node.object.type !== 'Identifier' || !namespaces.has(node.object.name)) return
        const name = keyName(node.property, node.computed)
        if (ENTRY_POINTS.has(name)) report(node, name)
      },
      VariableDeclarator(node) {
        if (node.id.type !== 'ObjectPattern' || node.init?.type !== 'Identifier' || !namespaces.has(node.init.name)) return
        for (const prop of node.id.properties) {
          if (prop.type !== 'Property') continue
          const name = keyName(prop.key, prop.computed)
          if (ENTRY_POINTS.has(name)) report(prop, name)
        }
      },
    }
  },
}

export const plugin = { meta: { name: 'veyq-crypto' }, rules: { 'argon-through-the-worker': rule } }

/**
 * The flat-config block each of the three configs includes as-is. Exempt: the suite that defines the entry points,
 * the two worker files that are the door, tests, test harnesses (`src/test/**`) and node seed scripts — none of them
 * runs on a page a user waits on.
 */
export const argonThroughTheWorker = {
  name: 'veyq/argon-through-the-worker',
  files: ['**/*.{ts,mts,vue}'],
  ignores: ['src/v1.ts', 'src/argonWorker.ts', 'src/argon.worker.ts', '**/*.test.ts', 'src/test/**', 'scripts/**'],
  plugins: { 'veyq-crypto': plugin },
  rules: { 'veyq-crypto/argon-through-the-worker': 'error' },
}
