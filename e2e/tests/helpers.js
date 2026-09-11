import { expect } from '@playwright/test';

/** Shared e2e helpers. Credentials come from env so specs are environment-agnostic. */

export const ADMIN_EMAIL = process.env.E2E_ADMIN_EMAIL || 'admin@e2e.com';
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Passw0rd!';

/** Log in as a hospital admin and wait until we leave the login page. */
export async function loginAsAdmin(page, email = ADMIN_EMAIL, password = ADMIN_PASSWORD) {
  await page.goto('/login', { waitUntil: 'networkidle' });
  await page.fill('input[type="email"], input[name="email"]', email);
  await page.fill('input[type="password"]', password);
  await page.click('button[type="submit"]');
  await page.waitForURL((url) => !url.toString().includes('/login'), { timeout: 15_000 });
  await page.waitForLoadState('networkidle');
}

/** Open a sidebar group then a leaf tab by visible text. */
export async function openTab(page, group, leaf) {
  await page.getByRole('button', { name: group, exact: true }).first().click();
  await page.waitForTimeout(400);
  await page.getByRole('button', { name: leaf, exact: true }).first().click();
  await page.waitForLoadState('networkidle');
}

/** Fail the test if the browser logged any console errors during the run. */
export function trackConsoleErrors(page) {
  const errors = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(String(e)));
  return {
    assertNone: () => expect(errors, `console errors:\n${errors.join('\n')}`).toHaveLength(0),
  };
}
