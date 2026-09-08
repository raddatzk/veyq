// Flat config (ESLint 10) for the shared crypto package. Deliberately narrower than `web/`'s: no Vue plugin
// (there are no components here), same two rules that matter — the unused-import guardrail and no-explicit-any
// on production source, relaxed for test fixtures.
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { name: 'veyq-crypto/ignores', ignores: ['node_modules/**', 'reports/**', '.stryker-tmp/**'] },
  ...tseslint.configs.recommended,
  {
    name: 'veyq-crypto/rules',
    files: ['**/*.ts'],
    rules: {
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
  {
    name: 'veyq-crypto/tests',
    files: ['**/*.test.ts', 'vitest.config.ts', 'eslint.config.js', 'stryker.config.mjs'],
    rules: { '@typescript-eslint/no-explicit-any': 'off' },
  },
)
