// Flat config (ESLint 10) for lane L1's browser suite. Narrow like packages/crypto's: no Vue plugin (there are no
// components here), the unused-import guardrail and typescript-eslint's recommended set.
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { name: 'veyq-e2e/ignores', ignores: ['node_modules/**', 'test-results/**'] },
  ...tseslint.configs.recommended,
  {
    name: 'veyq-e2e/rules',
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
)
