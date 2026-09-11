import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright end-to-end config for the Hospital Management System.
 *
 * E2E runs against a RUNNING stack (frontend + backend + MySQL). It is intentionally NOT part
 * of the fast PR CI — it runs in the dedicated e2e workflow (.github/workflows/e2e.yml, manual
 * / label-gated) which stands the stack up first. Locally, start the app and run
 * `npm run test:e2e`. See docs/testing/TESTING_STRATEGY.md §E2E.
 *
 * Base URL and a seeded admin login come from env vars so the same specs run against local,
 * a preview, or staging:
 *   E2E_BASE_URL      (default http://localhost:5173)
 *   E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['junit', { outputFile: 'playwright-report/results.xml' }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
