/**
 * lint-staged — runs fast, auto-fixing checks on STAGED files only (pre-commit).
 *
 * Frontend JS/JSX: ESLint --fix then Prettier. Config + JSON/CSS/MD: Prettier.
 * Backend Java formatting is intentionally NOT run here — see docs/DEVELOPMENT.md.
 * Spotless with ratchetFrom=origin/main would reformat every file that differs
 * from the (currently stale) main branch, so it is exposed as `npm run format:java`
 * and will be wired into hooks/CI in a later phase once main is current.
 */
export default {
  'frontend/**/*.{js,jsx}': ['eslint --fix', 'prettier --write'],
  'frontend/**/*.{json,css,md}': ['prettier --write'],
  '*.{json,md,yml,yaml}': ['prettier --write'],
};
