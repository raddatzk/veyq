// Flat config (ESLint 10), as @veyq/crypto's: the unused-import guardrail and no-explicit-any. `dist/` is the compiled
// core, Gradle's output, never linted.
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { name: 'veyq-core-js/ignores', ignores: ['node_modules/**', 'dist/**'] },
  ...tseslint.configs.recommended,
  {
    name: 'veyq-core-js/rules',
    files: ['**/*.ts'],
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { args: 'all', argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }],
    },
  },
)
