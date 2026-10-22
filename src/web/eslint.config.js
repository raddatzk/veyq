import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import { argonThroughTheWorker } from '@veyq/crypto/eslint-argon-door'
import { plugin as featureMocks } from './eslint/featureMocks.js'

// `veyq/one-settings-door` below: every static spelling of a navigation to the settings hub, as AST selectors.
// Shared by the script rule and its template twin, because a template expression parses into the same node types.
const SETTINGS_DOOR_MESSAGE =
  "The shell has one door into the settings hub: ProfileMenu. Navigate through it, or add this file to the block's ignores with its reason."
const SETTINGS_DOOR = [
  'Literal[value=/^\\/settings(\\/|$)/]', // '/settings', '/settings/devices'
  'TemplateElement[value.raw=/^\\/settings(\\/|$)/]', // `/settings/${id}`
  "Property[key.name='name'][value.value='settings']", // { name: 'settings' }
].map((selector) => ({ selector, message: SETTINGS_DOOR_MESSAGE }))

// `veyq/trust-reset-one-door` below: the two imports that can start a refused trust document over.
const TRUST_RESET_IMPORT = {
  regex: '(^|/)trustReset(\\.[jt]s)?$',
  allowTypeImports: true,
  message:
    'Only TrustResetDialog.vue starts a trust document over (ADR 0028 Decision 7): no response, refusal, worker or boot path may reach the reset. Link to the settings hub with TrustResetLink instead.',
}
const SET_MARK_IMPORT = {
  regex: '(^|/)trustMarks(\\.[jt]s)?$',
  importNames: ['setMark'],
  message: 'setMark lowers a trust mark; only the user-confirmed reset (trustReset/trustReset.ts) may call it.',
}

// `veyq/ui-through-the-layer`: the component libraries live behind `src/shared/ui/` (ADR 0107 decision 2). No view,
// composable, application or test imports one; the next time a library has to go, the change stays in one directory.
// A constant because it joins every block that sets `no-restricted-imports` for a file under `src/` (flat config
// resolves a rule by name, last block wins: a block of its own would replace `veyq/one-sign-out`'s patterns for every
// `.vue` file, and `veyq/tests-use-the-real-catalog`'s would replace it for every test).
const UI_LIBRARY_IMPORT = {
  regex: '^(primevue|@primevue|@primeuix|primeicons|reka-ui|@lucide/vue)(/|$)',
  message: 'A component library is reached through the layer in src/shared/ui/ (ADR 0107): import its Veyq* component instead.',
}
const UI_LAYER = 'src/shared/ui/**'
const SESSION_END_IMPORT = {
  regex: '(^|/)sessionEnd(\\.[jt]s)?$',
  message: 'A screen signs out through features/account/login/signOut, never the session ending itself.',
}

// `veyq/seeders-use-the-generated-client` below.
const SEEDER_NO_SHELL = ['node:child_process', 'child_process'].map((name) => ({
  name,
  message: 'A seeder talks to the API only — no shelling out (and so no `docker exec` into the DB).',
}))
const SEEDER_FETCH_MESSAGE = 'A seeder calls the generated …Api client; only scripts/apiTransport.mts speaks HTTP by hand.'
const SEEDER_NO_RAW_HTTP = ['node:http', 'http', 'node:https', 'https'].map((name) => ({ name, message: SEEDER_FETCH_MESSAGE }))

// `veyq/tests-use-the-real-catalog` below. A constant because `veyq/api-mocks-typed-by-the-contract` must repeat it:
// both set `no-restricted-syntax` for test files, and flat config resolves a rule by name, last block wins.
const REAL_CATALOG_SYNTAX = [
  {
    selector: "CallExpression[callee.object.name='vi'][callee.property.name=/^(mock|doMock)$/][arguments.0.value='vue-i18n']",
    message: 'Do not mock vue-i18n; mount with realI18n() from src/test/i18n and assert through its t().',
  },
]

// `veyq/tests-use-the-real-catalog` below, and its twin for the layer's own tests.
const REAL_CATALOG_PATHS = [
  {
    name: 'vue-i18n',
    importNames: ['createI18n'],
    message: 'Mount with realI18n() from src/test/i18n — the real catalog, where a missing key fails the test.',
  },
]

// `veyq/api-mocks-typed-by-the-contract` below: the selectors, each with the count it was red at when it landed.
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
  // S6 — a `features/` module mock exports bare `vi.fn()`s — is a rule of its own, `veyq-web/feature-mocks-bare-fn`
  // (eslint/featureMocks.js), enabled in the same block: which module a RELATIVE mock path reaches depends on the
  // file it is written in, which a selector cannot see (inbox #286).
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
    name: 'veyq/files',
    files: ['**/*.{ts,mts,vue}'],
  },
  {
    name: 'veyq/ignores',
    ignores: [
      'dist/**',
      'node_modules/**',
      'src/api/generated/**', // generated client — mirrors the contracts, never hand-edited
    ],
  },
  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  {
    name: 'veyq/rules',
    rules: {
      // The app confirms through its own dialog (`shared/state/useConfirm`), never the browser's: a native
      // box carries no product voice, no danger colour, and on the sessions panel the longest sentence the
      // app owns (cr-web-own-confirm-dialog). This is what keeps that swap from being a one-time sweep.
      // Deliberately NOT exempted for test files: nothing in `src` may reach for a native dialog, and a
      // fixture is the easiest place to quietly re-introduce one.
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
    // One sign-out (uc.account.sign-out, ADR 0070): a screen calls `features/account/login/signOut`, never the
    // ending in `app/sessionEnd` — a screen that could reach the ending could assemble its own sign-out, and a
    // screen-level copy that skipped half the sequence is what this replaced. LIMIT: a `.ts` composable is not
    // covered, and neither is a screen that calls the session API's revoke directly and then navigates.
    // It carries `veyq/ui-through-the-layer`'s pattern too (see UI_LIBRARY_IMPORT), which the next block lifts for the
    // layer's own components.
    name: 'veyq/one-sign-out',
    files: ['**/*.vue'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [SESSION_END_IMPORT, UI_LIBRARY_IMPORT] }],
    },
  },
  {
    // The layer's components may import a library; they still sign nobody out.
    name: 'veyq/one-sign-out-in-the-layer',
    files: [`${UI_LAYER}/*.vue`],
    rules: {
      'no-restricted-imports': ['error', { patterns: [SESSION_END_IMPORT] }],
    },
  },
  {
    // The library restriction for script modules (see UI_LIBRARY_IMPORT); `.vue` files get it from `veyq/one-sign-out`,
    // tests from `veyq/tests-use-the-real-catalog`. LIMIT: a dynamic `import()` or a re-export through a module of the
    // layer is not seen, and nothing stops the layer from re-exporting a library wholesale (ADR 0107 decision 3 forbids
    // it; review holds it).
    name: 'veyq/ui-through-the-layer',
    files: ['src/**/*.ts'],
    ignores: [UI_LAYER, 'src/**/*.test.ts'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [UI_LIBRARY_IMPORT] }],
    },
  },
  {
    // Starting a refused trust document over (ADR 0028 Decision 7's way back, inbox #192) writes the EMPTY pin
    // store or device register — exactly what Decision 7 refuses to read a refused document as — so the whole
    // defence is who can cause it: the server can cause the refusal, never this write. So the reset module is
    // imported by its one confirm dialog and nothing else (no response handler, no worker, no boot step), and
    // `setMark` — the only operation that LOWERS a trust mark — by the reset module and nothing else. Its own
    // test and the dialog's may import the reset; the dialog may not import `setMark`.
    // It is `@typescript-eslint/no-restricted-imports`, not the core rule, on purpose: flat config resolves a rule by
    // name and the last block wins, so a `no-restricted-imports` here would silently replace `veyq/one-sign-out`'s
    // patterns for every `.vue` file (and the real-catalog block would replace this one for tests). A different
    // rule name stacks. Type-only imports are allowed: a type cannot call anything.
    // LIMIT: it pins who IMPORTS the function, not that the dialog calls it only on a click — the dialog's own
    // test owns that. A dynamic `import()` or a re-export through a third module is not seen either.
    name: 'veyq/trust-reset-one-door',
    files: ['src/**/*.{ts,vue}'],
    ignores: [
      'src/features/account/trustReset/trustReset.ts',
      'src/features/account/trustReset/trustReset.test.ts',
      'src/features/account/trustReset/TrustResetDialog.vue',
      'src/features/account/trustReset/TrustResetDialog.test.ts',
    ],
    rules: {
      '@typescript-eslint/no-restricted-imports': ['error', { patterns: [TRUST_RESET_IMPORT, SET_MARK_IMPORT] }],
    },
  },
  {
    // The three files that may import the reset still may not lower a mark themselves.
    name: 'veyq/trust-reset-one-door-set-mark',
    files: [
      'src/features/account/trustReset/trustReset.test.ts',
      'src/features/account/trustReset/TrustResetDialog.vue',
      'src/features/account/trustReset/TrustResetDialog.test.ts',
    ],
    rules: {
      '@typescript-eslint/no-restricted-imports': ['error', { patterns: [SET_MARK_IMPORT] }],
    },
  },
  {
    // The shell offers exactly one door into the settings hub: ProfileMenu's settings item (ADR 0022 §5). A second
    // door is how the hub drifts back into scattered preference screens, so every STATIC spelling of a navigation
    // to it is refused outside the files that legitimately hold one: the path and its deep links (`'/settings'`,
    // `` `/settings/${id}` ``), the named route (`{ name: 'settings' }`), and a static or bound `to`/`href`. It
    // matches AST nodes, so a comment naming the route is not a door. Ignored: `router.ts` declares the route,
    // `ProfileMenu.vue` IS the door, and `SettingsHubView.vue` navigates INSIDE the hub (a door from the hub to the
    // hub has nothing to catch). `ConfirmEmailView.vue` is the second door: a page outside the shell that a mail
    // link opens, whose one onward step is the Security card showing the address it just confirmed
    // (uc.account.confirm-email-address). `TrustResetLink.vue` is the third: every trust-state refusal notice
    // carries it to the hub's one reset (ADR 0028 Decision 7's way back), as ONE component rather than a door per
    // notice. `FinishProfilePrompt.vue` is the fourth: the one-time "finish your profile" notice carries a member whose
    // profile is empty to Your profile (ADR 0084 decision 7), as one component with one dismissal.
    // `ConfirmEmailChangeView.vue` is the fifth, the twin of the second: the page a change mail's link opens, whose one
    // onward step is the same Security card, now showing the new address (uc.account.change-email). A further legitimate
    // door is one more `ignores` entry. LIMIT: a path
    // assembled at runtime is not a literal and is not seen (`'/' + 'settings'`, a variable passed as `path`,
    // `window.location`). And flat config resolves a rule by name, last block wins: a later block that sets
    // `no-restricted-syntax` for these files replaces these selectors rather than adding to them.
    name: 'veyq/one-settings-door',
    files: ['src/**/*.{ts,vue}'],
    ignores: [
      'src/**/*.test.ts', 'src/router.ts', 'src/components/app/ProfileMenu.vue', 'src/views/SettingsHubView.vue',
      'src/views/ConfirmEmailView.vue', 'src/views/ConfirmEmailChangeView.vue', 'src/features/account/trustReset/TrustResetLink.vue',
      'src/features/account/profile/FinishProfilePrompt.vue',
    ],
    rules: {
      'no-restricted-syntax': ['error', ...SETTINGS_DOOR],
      'vue/no-restricted-syntax': ['error', ...SETTINGS_DOOR],
      'vue/no-restricted-static-attribute': [
        'error',
        ...['to', 'href'].map((key) => ({ key, value: '/^\\/settings(\\/|$)/', message: SETTINGS_DOOR_MESSAGE })),
      ],
    },
  },
  {
    // A test renders the REAL catalog, through `src/test/i18n.ts` (inbox #171). A hand-written stub catalog, or a
    // mocked `t`, keeps a test green on a string the app no longer shows: stub keys had outlived the catalog
    // when this rule was written, among them the retired `members.copyLink`. LIMIT: a stub reached some other way
    // (a hand-rolled plugin providing `$t`) is not caught.
    name: 'veyq/tests-use-the-real-catalog',
    files: ['src/**/*.test.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: REAL_CATALOG_PATHS,
          // `veyq/ui-through-the-layer` (see UI_LIBRARY_IMPORT): a test mounts the layer, never a library.
          patterns: [UI_LIBRARY_IMPORT],
        },
      ],
      'no-restricted-syntax': ['error', ...REAL_CATALOG_SYNTAX],
    },
  },
  {
    // The layer's own tests prove a wrapper against the library it wraps, so they may import it; the catalog rule stays.
    name: 'veyq/tests-use-the-real-catalog-in-the-layer',
    files: [`${UI_LAYER}/*.test.ts`],
    rules: {
      'no-restricted-imports': ['error', { paths: REAL_CATALOG_PATHS }],
    },
  },
  {
    // A mocked API response is typed by the generated model at the seam (cr-api-callers-typed-by-the-contract,
    // inbox #195). A test that hands the code `{ m: vi.fn().mockResolvedValue(p) } as unknown as ReturnType<typeof
    // xApi>` checks `p` against nothing — the untyped `vi.fn()` is the hole, the cast only lets it in — so when
    // `ShareIdentity.sealedDisplayName` was removed, the fixture serving it stayed green. Every such mock goes
    // through src/test/apiMock.ts, which holds the one cast and types each payload by its model.
    // It repeats REAL_CATALOG_SYNTAX: both blocks set `no-restricted-syntax` for test files, and the last one wins.
    // LIMIT: a factory whose return is not an in-line object literal (a variable, a `...spread`) escapes S3, and a
    // helper-built payload held in a variable loses its excess-property check (styleguide §10: a fixture builder
    // annotates its return type with the model).
    name: 'veyq/api-mocks-typed-by-the-contract',
    files: ['src/**/*.test.ts'],
    plugins: { 'veyq-web': featureMocks },
    rules: {
      'no-restricted-syntax': ['error', ...REAL_CATALOG_SYNTAX, ...API_MOCKS_SYNTAX],
      'veyq-web/feature-mocks-bare-fn': 'error', // S6
    },
  },
  {
    // A dev seeder is a client of the API like any other (cr-api-callers-typed-by-the-contract). It reaches the
    // backend through the generated client and nothing else: no seeder shells out, so none can `docker exec`
    // past the API into the database. The one seeder that did gave a tenant the domain `127.0.0.1`, which the
    // contract's hostname pattern refuses and which the ingress projector re-checks precisely because a row can
    // arrive "by any path that skips" the boundary; the dev tenant is a `*.localhost` domain instead.
    // And it never hand-writes a request: `fetch` and `node:http` are refused everywhere under scripts/ except the
    // one transport module, which hands the generated client a Host-pinned `fetchApi`. A hand-written body is what
    // let login verify gain `nonce` while `bootstrap:user` kept posting the old shape and failed its own login.
    // LIMIT: a dynamic `import('node:child_process')` or a `require` is not seen; a seeder that reached the API
    // through some third module's `fetch` (src/test/mailpit.ts holds the Mailpit calls) is not seen either.
    name: 'veyq/seeders-use-the-generated-client',
    files: ['scripts/**/*.mts'],
    rules: {
      'no-restricted-globals': ['error', { name: 'fetch', message: SEEDER_FETCH_MESSAGE }],
      'no-restricted-imports': ['error', { paths: [...SEEDER_NO_SHELL, ...SEEDER_NO_RAW_HTTP] }],
    },
  },
  {
    // The transport itself: the one file that may speak HTTP by hand — still never shell out.
    name: 'veyq/seeders-use-the-generated-client-transport',
    files: ['scripts/apiTransport.mts'],
    rules: {
      'no-restricted-globals': 'off',
      'no-restricted-imports': ['error', { paths: SEEDER_NO_SHELL }],
    },
  },
  {
    // Test files, test kits, the i18n extractor and build config may use `any` for terse fixtures /
    // untyped tooling boundaries. Production `src` code stays held to no-explicit-any (currently
    // zero violations — the guard exists to keep it that way).
    name: 'veyq/tests-and-tooling',
    files: [
      '**/*.test.ts',
      '**/*.spec.ts',
      'scripts/extract-admin-i18n.mts', // untyped i18n tooling; the seeders are typed by the generated client
      'vite.config.ts',
      'eslint.config.js',
    ],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
