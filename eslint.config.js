// ESLint flat config (ESLint 9) for the frontend (JavaScript + JSX, React 19).
// Lives at the repo root because the JS tooling is hoisted here (see package.json).
// Rules are intentionally lenient for gradual adoption on the existing ~188-file
// codebase: most issues are `warn`, so pre-commit lint-staged (--fix) improves
// files as they are touched without blocking legacy code.
import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import importPlugin from 'eslint-plugin-import';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import unusedImports from 'eslint-plugin-unused-imports';
import prettier from 'eslint-config-prettier';

export default [
  {
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/coverage/**',
      '**/target/**',
      '**/build/**',
      '**/*.config.js',
      '**/*.config.mjs',
      // one-off dev scripts (repo debt, not app code)
      'frontend/checkDuplicates.js',
      'frontend/check_duplicates.js',
      'frontend/cleanupPatients.js',
    ],
  },
  {
    files: ['frontend/**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: {
      react,
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
      import: importPlugin,
      'jsx-a11y': jsxA11y,
      'unused-imports': unusedImports,
    },
    rules: {
      ...js.configs.recommended.rules,
      ...react.configs.flat.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      ...jsxA11y.flatConfigs.recommended.rules,
      // React 19 / automatic JSX runtime — no need to import React in scope.
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // Accessibility matters for a patient-facing system, but keep it non-blocking
      // for gradual adoption on the existing codebase.
      'jsx-a11y/no-autofocus': 'off',
      // Auto-removable unused imports (fixed by lint-staged); unused vars stay a warn.
      'unused-imports/no-unused-imports': 'warn',
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-empty': ['warn', { allowEmptyCatch: true }],
      // Import hygiene — ordering + no duplicates, as warnings.
      'import/no-duplicates': 'warn',
      'import/order': [
        'warn',
        {
          groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
          'newlines-between': 'never',
          alphabetize: { order: 'asc', caseInsensitive: true },
        },
      ],
    },
    settings: {
      react: { version: '19.0' },
      // Resolve JS/JSX so import/order + no-duplicates work without false "unresolved".
      'import/resolver': { node: { extensions: ['.js', '.jsx'] } },
    },
  },
  // Must be last: disables ESLint stylistic rules that conflict with Prettier.
  prettier,
];
