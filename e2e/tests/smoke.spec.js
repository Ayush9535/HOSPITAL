import { test, expect } from '@playwright/test';
import { trackConsoleErrors } from './helpers.js';

/**
 * Smoke — the fastest possible "is the app usable?" check. Runs against any deployed frontend
 * without credentials. Kept intentionally tiny so it can gate a deploy.
 */
test.describe('Smoke', () => {
  test('public API health endpoint returns the expected response', async ({ request }) => {
    const apiBaseUrl = process.env.E2E_API_BASE_URL;
    expect(apiBaseUrl, 'E2E_API_BASE_URL must be configured for live smoke tests').toBeTruthy();

    const response = await request.get(new URL('/api/public/health', apiBaseUrl).toString());
    expect(response.status()).toBe(200);
    expect(await response.text()).toBe('HMS Backend Running');
  });

  test('login page renders with the login form and no console errors', async ({ page }) => {
    const console_ = trackConsoleErrors(page);
    await page.goto('/login', { waitUntil: 'networkidle' });

    await expect(page.locator('input[type="email"], input[name="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.locator('button[type="submit"]')).toBeVisible();

    console_.assertNone();
  });

  test('protected route redirects an unauthenticated user to login', async ({ page }) => {
    await page.goto('/hospital/admin', { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(/login/);
  });
});
