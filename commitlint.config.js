/**
 * commitlint — enforces Conventional Commits on the `commit-msg` git hook.
 * See CONTRIBUTING.md §3 and docs/DEVELOPMENT.md for the convention.
 */
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat',
        'fix',
        'docs',
        'style',
        'refactor',
        'perf',
        'test',
        'build',
        'ci',
        'chore',
        'revert',
        'security',
      ],
    ],
    'scope-enum': [
      1,
      'always',
      [
        'opd',
        'ipd',
        'pharmacy',
        'nurse',
        'ot',
        'billing',
        'auth',
        'security',
        'platform',
        'backend',
        'frontend',
        'ci',
        'deps',
        'docs',
        'release',
      ],
    ],
    'scope-case': [2, 'always', 'kebab-case'],
    'subject-case': [2, 'never', ['upper-case', 'pascal-case', 'start-case']],
    'subject-empty': [2, 'never'],
    'subject-full-stop': [2, 'never', '.'],
    'header-max-length': [2, 'always', 100],
    'body-leading-blank': [2, 'always'],
    'footer-leading-blank': [1, 'always'],
  },
};
