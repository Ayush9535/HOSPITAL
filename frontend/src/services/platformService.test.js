import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./apiService', () => ({
  default: { get: vi.fn() },
  API_BASE_URL: 'http://localhost:8080',
}));

import apiClient from './apiService';
import platformService from './platformService';

/**
 * SA-3 — the abort signal has to reach axios.
 *
 * PlatformDashboard created an AbortController per request and called
 * `getHospitals(page, size, type, { signal })`. The method only declared three
 * parameters, so the config was dropped on the floor: abort() cancelled nothing and a
 * superseded response could still resolve and overwrite the newer tab's data. The
 * `err.name === 'CanceledError'` guards downstream were unreachable.
 */
describe('platformService request cancellation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.get.mockResolvedValue({ data: { content: [] } });
  });

  it('forwards the caller signal to getHospitals', async () => {
    const controller = new AbortController();

    await platformService.getHospitals(0, 10, 'HOSPITAL', { signal: controller.signal });

    const [url, config] = apiClient.get.mock.calls[0];
    expect(url).toBe('/platform/hospitals');
    expect(config.signal).toBe(controller.signal);
    expect(config.params).toEqual({ page: 0, size: 10, type: 'HOSPITAL' });
  });

  it('forwards the caller signal to getAuditLogs', async () => {
    const controller = new AbortController();

    await platformService.getAuditLogs({ signal: controller.signal });

    const [url, config] = apiClient.get.mock.calls[0];
    expect(url).toBe('/platform/audit-logs');
    expect(config.signal).toBe(controller.signal);
  });

  it('still works for callers that pass no config', async () => {
    await platformService.getHospitals();
    await platformService.getAuditLogs();

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/platform/hospitals', {
      params: { page: 0, size: 10 },
    });
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/platform/audit-logs', {});
  });

  /**
   * The behaviour that matters: a request whose controller is aborted must reject rather
   * than resolve, so its stale payload can never be written over a newer tab's data.
   */
  it('a superseded request rejects instead of delivering stale data', async () => {
    // Stand-in for axios' own signal handling.
    apiClient.get.mockImplementation((_url, config) =>
      new Promise((resolve, reject) => {
        const timer = setTimeout(() => resolve({ data: { content: [{ name: 'STALE' }] } }), 50);
        config?.signal?.addEventListener('abort', () => {
          clearTimeout(timer);
          const err = new Error('canceled');
          err.name = 'CanceledError';
          reject(err);
        });
      })
    );

    const stale = new AbortController();
    const inFlight = platformService.getHospitals(0, 10, 'HOSPITAL', { signal: stale.signal });
    stale.abort(); // the user switched tabs

    await expect(inFlight).rejects.toMatchObject({ name: 'CanceledError' });

    // The newer tab's request is unaffected and its data is what a caller receives.
    apiClient.get.mockResolvedValue({ data: { content: [{ name: 'CURRENT' }] } });
    const current = new AbortController();
    await expect(
      platformService.getHospitals(0, 10, 'PHARMACY', { signal: current.signal })
    ).resolves.toEqual({ content: [{ name: 'CURRENT' }] });
  });
});
