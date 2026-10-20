import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import { argonThroughTheWorker } from '@veyq/crypto/eslint-argon-door'
import { plugin as featureMocks } from './eslint/featureMocks.js'

// `veyq-admin/api-mocks-typed-by-the-contract` below — the same selectors as web's block of that name.
const API_MOCK_HELPER = 'Mock a generated …Api through stubApi()/fakeApi() from src/test/apiMock, which type each payload by its model.'
const API_MOCKS_SYNTAX = [
  // S1: the seam itself — handing a hand-built object to the code as a generated …Api.
  {
    selector:
      "CallExpression[callee.property.name=/^mock(ReturnValue|Implementation)(Once)?$/][callee.object.callee.object.name='vi'][callee.object.callee.property.name='mocked'][callee.object.arguments.0.name=/Api$/]",
    message: API_MOCK_HELPER,
  },
  // S2: the cast that lets a partial object pass as one, wherever it reaches the code (DI, a fake passed in).
  {
    selector: "TSAsExpression[typeAnnotation.typeName.name='ReturnType'][typeAnnotation.typeArguments.params.0.exprName.name=/Api$/]",
    message: `No cast to a generated …Api. ${API_MOCK_HELPER}`,
  },
  // S3: an `api/client` mock factory that does more than export bare `vi.fn()`s.
  {
    selector:
      "CallExpression[callee.object.name='vi'][callee.property.name='mock'][arguments.0.value=/api\\/client$/] > ArrowFunctionExpression > ObjectExpression > Property > .value:not(CallExpression[callee.object.name='vi'][callee.property.name='fn'][arguments.length=0])",
    message: 'Mock src/api/client with bare vi.fn() exports; set behaviour per test through stubApi()/fakeApi() from src/test/apiMock.',
  },
  // S6 — a `features/` module mock exports bare `vi.fn()`s — is a rule of its own, `veyq-admin/feature-mocks-bare-fn`
  // (eslint/featureMocks.js), enabled in the same block: which module a RELATIVE mock path reaches depends on the
  // file it is written in, which a selector cannot see (inbox #309).
  // S7: `as never` switches a typed seam off — `vi.mocked(f).mockResolvedValue(x as never)` checks `x` against nothing.
  // A payload comes from a builder annotated with its model; a cast that must stay names its target
  // (`as unknown as <T>`), which a reader can grep for. LIMIT: `as unknown as <Model>` is not seen here; the
  // styleguide's rule against it (§10) is what holds it.
  {
    selector: 'TSAsExpression > TSNeverKeyword',
    message: 'No `as never` in a test: build the payload with its model type, or name the cast (`as unknown as <T>`).',
  },
]

// Flat config (ESLint 10). The type-checked ruleset is deliberately NOT enabled: this is a
// baseline guardrail (cr-0131 / audit P1a), not a strictness sweep. Unused-locals/params are
// covered by tsconfig (`noUnusedLocals`/`noUnusedParameters`); ESLint adds the Vue-template
// rules that tsc cannot see — notably `vue/no-v-html`, which the app relies on being enforced
// wherever it renders decrypted content.
export default defineConfigWithVueTs(
  {
    name: 'veyq-admin/files',
    files: ['**/*.{ts,mts,vue}'],
  },
  {
    name: 'veyq-admin/ignores',
    ignores: [
      'dist/**',
      // The CLI bundle (`npm run build:cli`) — generated, never hand-edited, and a lint run over a bundled
      // copy of every dependency reports on code this repo does not own.
      'dist-cli/**',
      'node_modules/**',
      'src/api/generated/**', // generated client — mirrors the contracts, never hand-edited
    ],
  },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    name: 'veyq-admin/rules',
    rules: {
      // The console confirms through its own dialog (`shared/state/useConfirm`), never the browser's: a native
      // box carries no product voice, no danger colour, and here it carries the sentence that has to spell out
      // that taking a tenant offline changes nothing inside it (cr-admin-web-own-confirm-dialog). This is what
      // keeps that swap from being a one-time sweep. Deliberately NOT exempted for test files: nothing in `src`
      // may reach for a native dialog, and a fixture is the easiest place to quietly re-introduce one — which
      // here would be worse than a style lapse, since a stubbed `window.confirm` passes a confirm-gating test
      // by confirming nothing at all.
      'no-alert': 'error',
      // Security backstop: the server is operator-blind and the client renders decrypted HTML.
      // Any `v-html` must be an explicit, reviewed opt-out with a sanitising helper — never silent.
      'vue/no-v-html': 'error',
      // The "dead imports/refs unguarded" guardrail (audit P1a). This carries the intent of
      // tsconfig `noUnusedLocals`/`noUnusedParameters` but — unlike those unscopable compiler
      // flags — it honours the ESLint ignore list, so the generated API client (regenerated,
      // gitignored, never hand-edited; its typescript-fetch template emits inherently-unused
      // params like `ignoreDiscriminator`) is not falsely flagged. `args: 'all'` matches
      // `noUnusedParameters`; the `^_` escape hatch matches tsc's own convention.
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          args: 'all',
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
        },
      ],
    },
  },
  // Argon2id only through @veyq/crypto/argonWorker — the rule, its exemptions and its limit live with the crypto
  // package (eslint-argon-door.js), written once for the three configs that include it.
  argonThroughTheWorker,
  {
    // A mocked API response is typed by the generated model at the seam (cr-api-callers-typed-by-the-contract,
    // inbox #195), exactly as in src/web/: a hand-built `…Api` object behind a cast checks its payloads against nothing.
    // Every such mock goes through src/test/apiMock.ts (this package's own copy), which holds the one cast.
    // LIMIT: a factory whose return is not an in-line object literal (a variable, a `...spread`) escapes S3, and a
    // helper-built payload held in a variable loses its excess-property check (styleguide §10: a fixture builder
    // annotates its return type with the model).
    name: 'veyq-admin/api-mocks-typed-by-the-contract',
    files: ['src/**/*.test.ts'],
    plugins: { 'veyq-admin': featureMocks },
    rules: {
      'no-restricted-syntax': ['error', ...API_MOCKS_SYNTAX],
      'veyq-admin/feature-mocks-bare-fn': 'error', // S6
    },
  },
  {
    // Test files, test kits, dev seeders and build config may use `any` for terse fixtures /
    // untyped tooling boundaries. Production `src` code stays held to no-explicit-any (currently
    // zero violations — the guard exists to keep it that way).
    name: 'veyq-admin/tests-and-tooling',
    files: [
      '**/*.test.ts',
      '**/*.spec.ts',
      'vite.config.ts',
      'eslint.config.js',
    ],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
