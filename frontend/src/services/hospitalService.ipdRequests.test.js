import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./apiService', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
  API_BASE_URL: 'http://localhost:8080',
}));

import apiClient from './apiService';
import hospitalService from './hospitalService';

/**
 * P0-3 — the reception dashboard's "IPD Requests" tile.
 *
 * It used to call getOpds('', 0, 1000, '', '') and filter the page in the browser, so any
 * recommendation past row 1000 was invisible, and a failed request was caught and reported as 0 —
 * indistinguishable from having no pending admissions. The count now comes from the server, and a
 * failure must propagate so the caller can render an error instead of a number.
 */
describe('hospitalService pending IPD requests', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the count from the server rather than paging OPDs', async () => {
    apiClient.get.mockResolvedValue({ data: { count: 1207 } });

    await expect(hospitalService.getPendingIpdRequestCount()).resolves.toBe(1207);
    expect(apiClient.get).toHaveBeenCalledWith('/hospital/opd/ipd-requests/count');
  });

  it('never sends a hospital id — the server derives the tenant from the token', async () => {
    apiClient.get.mockResolvedValue({ data: { count: 0 } });

    await hospitalService.getPendingIpdRequestCount();

    const [url, config] = apiClient.get.mock.calls[0];
    expect(url).not.toMatch(/hospitalId/i);
    expect(JSON.stringify(config ?? {})).not.toMatch(/hospitalId/i);
  });

  it('propagates failures instead of resolving to zero', async () => {
    apiClient.get.mockRejectedValue(new Error('Network Error'));

    await expect(hospitalService.getPendingIpdRequestCount()).rejects.toThrow('Network Error');
  });

  it('treats a malformed body as zero rather than undefined', async () => {
    apiClient.get.mockResolvedValue({ data: {} });

    await expect(hospitalService.getPendingIpdRequestCount()).resolves.toBe(0);
  });

  it('pages the request list server-side', async () => {
    apiClient.get.mockResolvedValue({ data: { content: [], totalElements: 0 } });

    await hospitalService.getPendingIpdRequests(2, 25);

    expect(apiClient.get).toHaveBeenCalledWith('/hospital/opd/ipd-requests?page=2&size=25');
  });
});
