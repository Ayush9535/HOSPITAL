#!/usr/bin/env node
/**
 * Lightweight, cross-platform local secret scanner (pre-commit).
 *
 * Scans STAGED changes (default) or all tracked files (`--all`) for high-confidence
 * secret patterns and forbidden files, so credentials never reach GitHub. Tuned for
 * LOW false positives: generic patterns require an assignment + quotes + length.
 *
 * Bypass a specific line (use sparingly, only for genuine false positives) by adding
 * a trailing comment:  pragma: allowlist secret
 *
 * This is a fast local gate, NOT a replacement for CI secret scanning (Gitleaks).
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync, statSync } from 'node:fs';

const ALL = process.argv.includes('--all');
const ALLOW = /pragma:\s*allowlist secret/i;

// Files that must never be committed (real secret material), by basename/path.
const FORBIDDEN_FILE = [
  /^\.env(\.|$)(?!example|sample|template)/i, // .env, .env.local ... but NOT .env.example
  /\.pem$/i,
  /\.(key|keystore|jks|p12|pfx)$/i,
  /(^|\/)id_rsa$/i,
];

// Skip these paths entirely (build output, deps, binaries, or example files).
const SKIP_PATH =
  /(^|\/)(node_modules|dist|coverage|target|build)\/|\.min\.js$|package-lock\.json$|\.(png|jpe?g|gif|ico|pdf|jar|class|woff2?|ttf|eot|svg)$|\.example$|\.sample$/i;

// A control character in the first chunk => treat the file as binary and skip it.
const BINARY = /[\x00-\x08\x0e-\x1f]/;

// High-confidence secret content patterns.
const RULES = [
  { name: 'Private key block', re: /-----BEGIN (?:RSA |EC |OPENSSH |PGP |DSA )?PRIVATE KEY-----/ },
  { name: 'AWS access key id', re: /\bAKIA[0-9A-Z]{16}\b/ },
  { name: 'GitHub token', re: /\bgh[pousr]_[A-Za-z0-9]{36,}\b/ },
  { name: 'Slack token', re: /\bxox[baprs]-[A-Za-z0-9-]{10,}\b/ },
  { name: 'Google API key', re: /\bAIza[0-9A-Za-z_-]{35}\b/ },
  { name: 'Stripe secret key', re: /\bsk_(?:live|test)_[0-9a-zA-Z]{16,}\b/ },
  {
    name: 'JWT (three-part token)',
    re: /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/,
  },
  {
    name: 'Hardcoded credential assignment',
    re: /\b(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|private[_-]?key)\b\s*[:=]\s*["'][^"'\s]{8,}["']/i,
  },
];

// Placeholders that should NOT trip the credential rule (env examples, templates).
const PLACEHOLDER =
  /["'](?:\$\{[^}]+\}|<[^>]+>|your[_-]?|xxx+|changeme|placeholder|example|test|dummy|todo)/i;

function stagedFiles() {
  return execSync('git diff --cached --name-only --diff-filter=ACM', { encoding: 'utf8' })
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}
function allFiles() {
  return execSync('git ls-files', { encoding: 'utf8' })
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
}
function contentOf(file) {
  try {
    if (ALL) return existsSync(file) ? readFileSync(file, 'utf8') : '';
    return execSync('git show :"' + file + '"', { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 });
  } catch {
    return '';
  }
}

const files = ALL ? allFiles() : stagedFiles();
const findings = [];

for (const file of files) {
  const base = file.split('/').pop();
  if (FORBIDDEN_FILE.some((re) => re.test(base) || re.test(file))) {
    findings.push({ file, line: 0, rule: 'Forbidden file (secret material / .env)' });
    continue;
  }
  if (SKIP_PATH.test(file)) continue;
  try {
    if (ALL && existsSync(file) && statSync(file).size > 2 * 1024 * 1024) continue;
  } catch {
    /* ignore */
  }

  const text = contentOf(file);
  if (!text || BINARY.test(text.slice(0, 8000))) continue;

  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (ALLOW.test(line)) continue;
    for (const rule of RULES) {
      if (rule.re.test(line)) {
        if (rule.name.startsWith('Hardcoded') && PLACEHOLDER.test(line)) continue;
        findings.push({ file, line: i + 1, rule: rule.name });
      }
    }
  }
}

if (findings.length) {
  console.error('\nX  Potential secrets detected - commit blocked:\n');
  for (const f of findings) {
    console.error('  ' + f.file + (f.line ? ':' + f.line : '') + '  ->  ' + f.rule);
  }
  console.error(
    '\n  Remove the secret (use an environment variable / .env, which is gitignored).' +
      '\n  Genuine false positive? Append "pragma: allowlist secret" to that line, or' +
      '\n  bypass this one commit with  git commit --no-verify  (discouraged).\n'
  );
  process.exit(1);
}

if (ALL) console.log('OK secret-scan: no secrets found in tracked files.');
process.exit(0);
