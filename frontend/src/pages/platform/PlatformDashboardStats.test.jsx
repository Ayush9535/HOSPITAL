import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

vi.mock('../../services/platformService', () => ({
  default: {
    getHospitals: vi.fn(),
    getHospitalStats: vi.fn(),
    getAuditLogs: vi.fn(),
    getTickets: vi.fn(),
    getPlatformFaqs: vi.fn(),
    updateHospitalStatus: vi.fn(),
    getPlans: vi.fn(),
  },
}));

vi.mock('../../services/authService', () => ({
  default: { getCurrentUser: () => ({ name: 'Super Admin', email: 'sa@example.test' }) },
}));

import platformService from '../../services/platformService';
import statsCache from '../../services/platformStatsCache';
import PlatformDashboard from './PlatformDashboard';

const emptyPage = { content: [], totalPages: 0, totalElements: 0, number: 0, size: 10 };
const statsWith = (active, inactive) => ({
  hospitals: { total: active + inactive, active, inactive },
  clinics: { total: 0, active: 0, inactive: 0 },
  pharmacies: { total: 0, active: 0, inactive: 0 },
});

/**
 * The Total/Active/Inactive figures on the Hospitals overview card. The labels are
 * uppercased by CSS, so the DOM text is "Active", not "ACTIVE".
 */
const hospitalCardCounts = () => {
  const label = screen.getByText('Hospitals', { selector: 'p' });
  const card = label.closest('div.bg-white');
  const value = (name) =>
    Array.from(card.querySelectorAll('p')).find((p) => p.textContent === name)
      .previousElementSibling.textContent;
  return { total: value('Total'), active: value('Active'), inactive: value('Inactive') };
};

const renderDashboard = () =>
  render(
    <MemoryRouter initialEntries={['/platform/dashboard?tab=dashboard']}>
      <PlatformDashboard />
    </MemoryRouter>
  );

/**
 * SA-2 — the overview counts are cached for 60 seconds. Nothing used to invalidate that
 * cache except deletion, so after activating or deactivating a tenant the dashboard kept
 * showing the previous split while the hospitals list, on the same screen, showed the new
 * one. These tests pin the invalidation rather than the caching.
 */
describe('PlatformDashboard overview stats cache', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    sessionStorage.setItem('token', 'sa-token');
    statsCache.clear();
    platformService.getHospitals.mockResolvedValue(emptyPage);
    platformService.getAuditLogs.mockResolvedValue([]);
    platformService.getTickets.mockResolvedValue([]);
    platformService.getPlatformFaqs.mockResolvedValue([]);
    platformService.getPlans.mockResolvedValue([]);
  });

  it('fetches the counts on first view', async () => {
    platformService.getHospitalStats.mockResolvedValue(statsWith(1, 0));

    renderDashboard();

    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(hospitalCardCounts()).toEqual({ total: '1', active: '1', inactive: '0' })
    );
  });

  it('serves the cached counts on a revisit inside the TTL', async () => {
    platformService.getHospitalStats.mockResolvedValue(statsWith(1, 0));

    const first = renderDashboard();
    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1));
    first.unmount();

    renderDashboard();
    await waitFor(() => expect(platformService.getHospitals).toHaveBeenCalled());
    expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1);
  });

  // The regression: a mutation followed by in-app navigation must show current values
  // without anyone waiting out the 60-second TTL.
  it('refetches after a mutation invalidates the cache', async () => {
    platformService.getHospitalStats.mockResolvedValue(statsWith(1, 0));

    const first = renderDashboard();
    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1));
    first.unmount();

    // what handleToggleStatus / create / update / delete now do
    statsCache.clear();
    platformService.getHospitalStats.mockResolvedValue(statsWith(0, 1));

    renderDashboard();

    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(hospitalCardCounts()).toEqual({ total: '1', active: '0', inactive: '1' })
    );
  });

  it('does not reuse the previous session counts after the session changes', async () => {
    platformService.getHospitalStats.mockResolvedValue(statsWith(3, 0));

    const first = renderDashboard();
    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1));
    first.unmount();

    sessionStorage.setItem('token', 'a-different-admin');
    platformService.getHospitalStats.mockResolvedValue(statsWith(0, 0));

    renderDashboard();

    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(2));
  });

  /**
   * The end-to-end path, driven through the UI: deactivating a tenant from the hospitals
   * list must leave the overview showing the new split, not the cached one.
   */
  it('deactivating from the list refreshes the overview counts', async () => {
    const user = userEvent.setup();
    platformService.getHospitalStats.mockResolvedValue(statsWith(1, 0));
    platformService.getHospitals.mockResolvedValue({
      content: [
        {
          id: 1,
          publicId: 'pid-1',
          customId: 'HSP1',
          name: 'Toggle Co',
          type: 'HOSPITAL',
          isActive: true,
          planName: 'Standard',
          isSingleDoctor: false,
          createdAt: '2026-08-25T00:00:00',
        },
      ],
      totalPages: 1,
      totalElements: 1,
      number: 0,
      size: 10,
    });
    platformService.updateHospitalStatus.mockResolvedValue({});

    // Start on the overview so the cache is warm, then navigate in-app to the list.
    renderDashboard();
    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole('button', { name: 'Hospital', exact: true }));
    await user.click(screen.getByRole('button', { name: 'Hospitals', exact: true }));
    await waitFor(() => expect(screen.getByText('Toggle Co')).toBeInTheDocument());

    // Open the row's action menu and deactivate.
    const row = screen.getByText('Toggle Co').closest('tr');
    const buttons = within(row).getAllByRole('button');
    await user.click(buttons[buttons.length - 1]);
    await user.click(await screen.findByText(/Deactivate/i));

    // The confirmation requires a reason. ConfirmationModal moves focus to its Cancel button
    // 50ms after opening (BUG-039 focus trap), so typing the instant the dialog appears lets
    // that timer steal the rest of the keystrokes: the reason lands half-typed or not at all,
    // Confirm stays disabled, and the click silently does nothing. Wait for focus to settle,
    // then type, and confirm the value actually landed before clicking.
    const dialog = await screen.findByRole('dialog');
    await waitFor(() =>
      expect(within(dialog).getByLabelText('Cancel and close dialog')).toHaveFocus()
    );
    const reasonInput = within(dialog).getByRole('textbox');
    await user.type(reasonInput, 'audit regression');
    await waitFor(() => expect(reasonInput).toHaveValue('audit regression'));
    await user.click(within(dialog).getByLabelText('Confirm action'));
    await waitFor(() => expect(platformService.updateHospitalStatus).toHaveBeenCalled());

    // Now visit the overview: the counts must be refetched, not served from cache.
    platformService.getHospitalStats.mockResolvedValue(statsWith(0, 1));
    await user.click(screen.getByRole('button', { name: 'Dashboard', exact: true }));

    await waitFor(() => expect(platformService.getHospitalStats).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(hospitalCardCounts()).toEqual({ total: '1', active: '0', inactive: '1' })
    );
  });
});
