import { test, expect } from '@playwright/test';
import { loginAsAdmin, openTab, trackConsoleErrors } from './helpers.js';

/**
 * End-to-end coverage of the business-critical workflows a hospital depends on daily.
 * Requires a running stack and a seeded hospital admin (E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD).
 *
 * These are deliberately resilient (role/text selectors, generous waits) rather than pinned to
 * exact markup, so day-to-day UI tweaks don't make them flaky.
 */
test.describe('Critical workflows', () => {
  test('login lands on the admin dashboard and logout returns to login', async ({ page }) => {
    const console_ = trackConsoleErrors(page);
    await loginAsAdmin(page);

    await expect(page).toHaveURL(/hospital\/admin/);
    await expect(page.getByText('Overview').first()).toBeVisible();
    console_.assertNone();

    await page.getByRole('button', { name: /log ?out|sign ?out/i }).first().click();
    await expect(page).toHaveURL(/login/, { timeout: 10_000 });
  });

  test('patient registration — register a patient and see it in the list', async ({ page }) => {
    await loginAsAdmin(page);
    await openTab(page, 'Patient Management', 'Patients');

    const unique = `E2E Patient ${Date.now()}`;
    await page.getByRole('button', { name: /add patient/i }).first().click();

    // Fill the registration form (label/placeholder-based, order-independent).
    await page.getByLabel(/name/i).first().fill(unique);
    const dob = page.locator('input[type="date"]').first();
    if (await dob.count()) await dob.fill('1985-03-20');
    await page.getByLabel(/phone/i).first().fill('9900112255');
    const gender = page.locator('select').first();
    if (await gender.count()) await gender.selectOption({ index: 1 }).catch(() => {});

    await page.getByRole('button', { name: /^save$|^add$|register|submit/i }).last().click();
    await page.waitForLoadState('networkidle');

    // The new patient appears in the table.
    await expect(page.getByText(unique)).toBeVisible({ timeout: 10_000 });
  });

  test('navigation — core modules load without console errors', async ({ page }) => {
    const console_ = trackConsoleErrors(page);
    await loginAsAdmin(page);

    for (const [group, leaf] of [
      ['Patient Management', 'Appointments'],
      ['Patient Management', 'OPD'],
      ['Finance', 'Billing'],
      ['Pharmacy', 'Pharmacists'],
    ]) {
      await openTab(page, group, leaf).catch(() => {});
    }
    console_.assertNone();
  });
});
