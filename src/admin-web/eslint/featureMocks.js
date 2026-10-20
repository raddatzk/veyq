// `veyq-admin/feature-mocks-bare-fn` — rule S6 of `veyq-admin/api-mocks-typed-by-the-contract` (eslint.config.js): a
// mock of a `features/` module exports bare `vi.fn()`s, and a test sets behaviour through `vi.mocked(fn)`, which is
// typed by the real function. The string form of `vi.mock` types its factory as nothing, so a payload written there
// is checked against nothing.
//
// The same rule as web's `web/eslint/featureMocks.js`, kept as this package's own copy as S1–S7 are: the workspace
// shares `@veyq/crypto` and nothing else (ADR 0065). A rule rather than an AST selector, because which module a mock
// reaches depends on the FILE it is written in: `'./tenants'` from `src/features/tenants/` is a features module, the
// same spelling from `src/cli/` is not. The selector form matched only a path that spells `features/`, so every test
// inside `features/` that mocked a sibling by a relative path went unseen (inbox #309). This resolves the path
// against the test's own location instead.
//
// Allowed in the factory's returned object: a bare `vi.fn()`, a spread (`...(await importOriginal())`), a member of
// it, any non-function value, and a composable (`use*`), which returns reactive UI state, not a model.
// LIMIT: a factory whose return is not an in-line object literal (a variable, a call), and a module-level `vi.fn()`
// the test drives directly (typed or not — `vi.fn<typeof f>()` is the typed form), are not seen; neither is an
// aliased path (`@/features/…`), which this package does not use.
import { dirname, resolve, sep } from 'node:path'

const FEATURES = `${sep}src${sep}features${sep}`

const isFunction = (n) => n?.type === 'ArrowFunctionExpression' || n?.type === 'FunctionExpression'
const isViCall = (n, name) =>
  n?.type === 'CallExpression' &&
  n.callee.type === 'MemberExpression' &&
  n.callee.object.type === 'Identifier' &&
  n.callee.object.name === 'vi' &&
  !n.callee.computed &&
  n.callee.property.name === name
const isBareViFn = (n) => isViCall(n, 'fn') && n.arguments.length === 0

/** Whether the mocked module is a `features/` module (never a `.vue` component), seen from `filename`. */
export function reachesFeatures(spec, filename) {
  if (spec.endsWith('.vue')) return false
  if (spec.startsWith('./') || spec.startsWith('../')) return resolve(dirname(filename), spec).includes(FEATURES)
  return /(^|\/)features\//.test(spec)
}

/** The object literal a factory returns directly: `() => ({ … })` or `() => { return { … } }`. */
function returnedObject(factory) {
  if (factory.body.type === 'ObjectExpression') return factory.body
  if (factory.body.type !== 'BlockStatement') return null
  for (const stmt of factory.body.body) {
    if (stmt.type === 'ReturnStatement' && stmt.argument?.type === 'ObjectExpression') return stmt.argument
  }
  return null
}

const rule = {
  meta: {
    type: 'problem',
    docs: { description: 'A features/ module mock exports bare vi.fn()s; behaviour is set through vi.mocked(fn).' },
    schema: [],
    messages: {
      bare: 'Mock a features/ module with bare vi.fn() exports; set behaviour per test through vi.mocked(fn), which is typed by the real function.',
    },
  },
  create(context) {
    return {
      CallExpression(node) {
        if (!isViCall(node, 'mock')) return
        const [spec, factory] = node.arguments
        if (spec?.type !== 'Literal' || typeof spec.value !== 'string' || !isFunction(factory)) return
        if (!reachesFeatures(spec.value, context.filename)) return
        const obj = returnedObject(factory)
        if (!obj) return
        for (const prop of obj.properties) {
          if (prop.type !== 'Property') continue
          if (!prop.computed && prop.key.type === 'Identifier' && /^use[A-Z]/.test(prop.key.name)) continue
          const v = prop.value
          if (isFunction(v) || (v.type === 'CallExpression' && !isBareViFn(v))) context.report({ node: v, messageId: 'bare' })
        }
      },
    }
  },
}

export const plugin = { meta: { name: 'veyq-admin' }, rules: { 'feature-mocks-bare-fn': rule } }
