import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

// The axios client is mocked, NOT platformService: the propagation being tested lives
// inside platformService, so mocking it would step over the code under test.
vi.mock('../../services/apiService', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  API_BASE_URL: 'http://localhost:8080',
}));

vi.mock('../../services/authService', () => ({
  default: { getCurrentUser: () => ({ name: 'Super Admin', email: 'sa@example.test' }) },
}));

import apiClient from '../../services/apiService';
import statsCache from '../../services/platformStatsCache';
import PlatformDashboard from './PlatformDashboard';

const pageOf = (names) => ({
  content: names.map((name, i) => ({
    id: i + 1,
    publicId: `pid-${name}`,
    customId: `CID-${name}`,
    name,
    type: 'HOSPITAL',
    isActive: true,
    planName: 'Standard',
    isSingleDoctor: false,
    createdAt: '2026-08-25T00:00:00',
  })),
  totalPages: 1,
  totalElements: names.length,
  number: 0,
  size: 10,
});

const EMPTY_STATS = {
  hospitals: { total: 0, active: 0, inactive: 0 },
  clinics: { total: 0, active: 0, inactive: 0 },
  pharmacies: { total: 0, active: 0, inactive: 0 },
};

const renderAt = (tab) =>
  render(
    <MemoryRouter initialEntries={[`/platform/dashboard?tab=${tab}`]}>
      <PlatformDashboard />
    </MemoryRouter>
  );

/**
 * SA-3 — switching tenant tabs quickly must not let the earlier tab's response land.
 *
 * The dashboard already created an AbortController per request, but platformService
 * declared three parameters and dropped the config object the caller passed as the
 * fourth. abort() therefore cancelled nothing and whichever response arrived last won --
 * which could be the older one. These tests exercise component, service and axios call
 * together, so the dropped argument is genuinely caught.
 */
describe('PlatformDashboard request supersession', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    sessionStorage.setItem('token', 'sa-token');
    statsCache.clear();
  });

  it('the abort signal reaches the axios call for the tenant list', async () => {
    apiClient.get.mockImplementation((url) => {
      if (url === '/platform/hospitals/stats') return Promise.resolve({ data: EMPTY_STATS });
      return Promise.resolve({ data: pageOf([]) });
    });

    renderAt('hospital:hospitals');

    await waitFor(() =>
      expect(apiClient.get).toHaveBeenCalledWith('/platform/hospitals', expect.anything())
    );
    const config = apiClient.get.mock.calls.find((c) => c[0] === '/platform/hospitals')[1];
    expect(config.signal).toBeInstanceOf(AbortSignal);
    expect(config.params).toMatchObject({ type: 'HOSPITAL' });
  });

  it('a slow earlier tab cannot overwrite the tab the user is now on', async () => {
    const user = userEvent.setup();

    apiClient.get.mockImplementation((url, config) => {
      if (url === '/platform/hospitals/stats') return Promise.resolve({ data: EMPTY_STATS });
      if (url !== '/platform/hospitals') return Promise.resolve({ data: [] });

      // PHARMACY answers at once; HOSPITAL dawdles and honours the abort, the way axios does.
      if (config?.params?.type === 'PHARMACY') {
        return Promise.resolve({ data: pageOf(['Pharmacy Row']) });
      }
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => resolve({ data: pageOf(['Hospital Row']) }), 100);
        config?.signal?.addEventListener('abort', () => {
          clearTimeout(timer);
          const err = new Error('canceled');
          err.name = 'CanceledError';
          reject(err);
        });
      });
    });

    renderAt('hospital:hospitals');
    await waitFor(() =>
      expect(apiClient.get.mock.calls.some((c) => c[0] === '/platform/hospitals')).toBe(true)
    );

    // Move to Pharmacies before the hospital list comes back.
    await user.click(screen.getByRole('button', { name: 'Pharmacy', exact: true }));
    await user.click(screen.getByRole('button', { name: 'Pharmacies', exact: true }));

    await waitFor(() => expect(screen.getByText('Pharmacy Row')).toBeInTheDocument());

    // Give the superseded hospital request every chance to land.
    await new Promise((r) => setTimeout(r, 250));

    expect(screen.queryByText('Hospital Row')).not.toBeInTheDocument();
    expect(screen.getByText('Pharmacy Row')).toBeInTheDocument();
  });
});
