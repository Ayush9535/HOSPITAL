import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/hospitalService', () => ({
  default: { getDashboardOverview: vi.fn() },
}));

// recharts measures its container, which jsdom reports as 0x0, so charts would render nothing and
// warn. Fixing the container size keeps the real chart components in the tree — the assertions
// below are about labels and ordering, not pixels.
vi.mock('recharts', async () => {
  const actual = await vi.importActual('recharts');
  return {
    ...actual,
    ResponsiveContainer: ({ children }) => (
      <div style={{ width: 640, height: 240 }}>{children}</div>
    ),
  };
});

import hospitalService from '../services/hospitalService';
import AdminOverviewAnalytics from './AdminOverviewAnalytics';

/** A response carrying only what the named blocks require; absent keys mean absent modules. */
const overview = (blocks = {}) => ({
  range: 'TODAY',
  from: '2026-09-06T00:00:00',
  toExclusive: '2026-09-07T00:00:00',
  timezone: 'Asia/Kolkata',
  core: { totalRegisteredPatients: 1280 },
  ...blocks,
});

const opdBlock = {
  opd: {
    consultations: 34,
    count: 41,
    trend: [{ date: '2026-09-06', count: 41 }],
    visitTypes: [
      { type: 'NEW', count: 25 },
      { type: 'FOLLOWUP', count: 16 },
    ],
    busiestSpecialities: [
      { speciality: 'Unassigned', count: 18 },
      { speciality: 'Cardiology', count: 12 },
      { speciality: 'Orthopedics', count: 11 },
    ],
  },
};

const bedsBlock = (extra = {}) => ({
  beds: {
    occupied: 12,
    usableCapacity: 20,
    currentlyAvailable: 6,
    cleaning: 2,
    maintenance: 3,
    unknownStatusCount: 0,
    occupancyRate: 60.0,
    asOf: '2026-09-06T14:30:00',
    ...extra,
  },
});

const settled = async () =>
  waitFor(() => expect(screen.queryByTestId('overview-analytics-loading')).not.toBeInTheDocument());

describe('AdminOverviewAnalytics', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  // ── core ───────────────────────────────────────────────────────────────────

  it('labels the core patient count as Active Patients, not Total Registered', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('Active Patients')).toBeInTheDocument();
    expect(screen.getByText('1,280')).toBeInTheDocument();
    // The backend field is totalRegisteredPatients but the query counts active patients only.
    expect(screen.queryByText(/total registered/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/lifetime patients/i)).not.toBeInTheDocument();
  });

  // ── module-aware rendering ─────────────────────────────────────────────────

  it('renders only the blocks the response actually carries', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ ...opdBlock, billing: { collection: 45000, currency: 'INR' } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('OPD Visits')).toBeInTheDocument();
    expect(screen.getByText('Consultations')).toBeInTheDocument();
    expect(screen.getByText('Collection')).toBeInTheDocument();

    expect(screen.queryByText('Admissions')).not.toBeInTheDocument();
    expect(screen.queryByText('Bed Occupancy')).not.toBeInTheDocument();
    expect(screen.queryByText('Pharmacy Sales')).not.toBeInTheDocument();
  });

  it('omits the OPD block entirely when the response has no opd key', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ ipd: { admissions: 4, trend: [] } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('Admissions')).toBeInTheDocument();
    expect(screen.queryByText('OPD Visits')).not.toBeInTheDocument();
    expect(screen.queryByText('OPD Trend')).not.toBeInTheDocument();
    expect(screen.queryByText('Visit Types')).not.toBeInTheDocument();
    expect(screen.queryByText('Busiest Specialities')).not.toBeInTheDocument();
  });

  it('renders the IPD block only when ipd is present', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview(opdBlock));
    const { unmount } = render(<AdminOverviewAnalytics />);
    await settled();
    expect(screen.queryByText('IPD Trend')).not.toBeInTheDocument();
    unmount();

    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ ipd: { admissions: 7, trend: [{ date: '2026-09-06', count: 7 }] } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();
    expect(screen.getByText('IPD Trend')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
  });

  it('renders beds only when beds is present', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview(bedsBlock()));
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('Bed Occupancy')).toBeInTheDocument();
    expect(screen.getByText('60%')).toBeInTheDocument();
    expect(screen.getByText('Occupied')).toBeInTheDocument();
    expect(screen.getByText('Maintenance')).toBeInTheDocument();
  });

  it('renders pharmacy only when pharmacy is present, and calls it Pharmacy Sales', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ pharmacy: { pharmacySales: 88 } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('Pharmacy Sales')).toBeInTheDocument();
    expect(screen.getByText('88')).toBeInTheDocument();
    expect(screen.queryByText(/medicines dispensed/i)).not.toBeInTheDocument();
  });

  it('never invents Laboratory, Radiology or Emergency analytics', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ ...opdBlock, ...bedsBlock(), ipd: { admissions: 3, trend: [] } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.queryByText(/laborator/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/radiolog/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/emergency/i)).not.toBeInTheDocument();
    // Nor the placeholder vocabulary of a module that is simply absent.
    expect(screen.queryByText(/not available/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/upgrade/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
  });

  // ── range selector ─────────────────────────────────────────────────────────

  it('defaults to TODAY and sends only the range enum, never a date', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(hospitalService.getDashboardOverview).toHaveBeenCalledWith('TODAY');
    const args = hospitalService.getDashboardOverview.mock.calls.flat();
    // The business day belongs to the hospital's timezone, so the client must not compute one.
    expect(args).toEqual(['TODAY']);
    expect(args.some((a) => typeof a === 'object')).toBe(false);
  });

  it('maps each range label to its enum and refetches on change', async () => {
    const user = userEvent.setup();
    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    render(<AdminOverviewAnalytics />);
    await settled();

    await user.click(screen.getByRole('button', { name: 'Last 7 Days' }));
    await waitFor(() =>
      expect(hospitalService.getDashboardOverview).toHaveBeenLastCalledWith('LAST_7_DAYS')
    );

    await user.click(screen.getByRole('button', { name: 'Last 30 Days' }));
    await waitFor(() =>
      expect(hospitalService.getDashboardOverview).toHaveBeenLastCalledWith('LAST_30_DAYS')
    );

    await user.click(screen.getByRole('button', { name: 'Today' }));
    await waitFor(() =>
      expect(hospitalService.getDashboardOverview).toHaveBeenLastCalledWith('TODAY')
    );
  });

  it('does not refetch when nothing changed', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(hospitalService.getDashboardOverview).toHaveBeenCalledTimes(1);
  });

  // ── the three states that must not collapse ───────────────────────────────

  it('shows a skeleton rather than zeros while loading', async () => {
    let resolve;
    hospitalService.getDashboardOverview.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      })
    );
    render(<AdminOverviewAnalytics />);

    expect(screen.getByTestId('overview-analytics-loading')).toBeInTheDocument();
    // A zero on screen during loading is a number someone could act on.
    expect(screen.queryByText('Active Patients')).not.toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();

    resolve(overview());
    await settled();
    expect(screen.getByText('1,280')).toBeInTheDocument();
  });

  it('renders an owned but quiet module as a real zero', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({
        opd: {
          consultations: 0,
          count: 0,
          trend: [{ date: '2026-09-06', count: 0 }],
          visitTypes: [],
          busiestSpecialities: [],
        },
        pharmacy: { pharmacySales: 0 },
      })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    // Present with zeros, which is a different fact from the block being absent.
    expect(screen.getByText('OPD Visits')).toBeInTheDocument();
    expect(screen.getByText('OPD Trend')).toBeInTheDocument();
    expect(screen.getByText('Pharmacy Sales')).toBeInTheDocument();
    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
    expect(screen.getByText('No OPD visits in this period.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows a retryable error instead of zeros when the request fails', async () => {
    const user = userEvent.setup();
    hospitalService.getDashboardOverview.mockRejectedValueOnce(new Error('boom'));
    render(<AdminOverviewAnalytics />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(/couldn't load the overview/i)).toBeInTheDocument();
    expect(within(alert).getByText(/not the same as having no activity/i)).toBeInTheDocument();
    expect(screen.queryByText('Active Patients')).not.toBeInTheDocument();

    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(screen.getByText('Active Patients')).toBeInTheDocument());
    expect(hospitalService.getDashboardOverview).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('keeps the range selector usable while an error is showing', async () => {
    hospitalService.getDashboardOverview.mockRejectedValue(new Error('boom'));
    render(<AdminOverviewAnalytics />);
    await screen.findByRole('alert');

    expect(screen.getByRole('button', { name: 'Last 7 Days' })).toBeInTheDocument();
  });

  // ── values the backend owns ───────────────────────────────────────────────

  it('shows a neutral dash, not NaN or 0%, when there is no usable capacity', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview(
        bedsBlock({
          occupied: 0,
          usableCapacity: 0,
          currentlyAvailable: 0,
          cleaning: 0,
          maintenance: 4,
          occupancyRate: null,
        })
      )
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText('NaN')).not.toBeInTheDocument();
    expect(screen.queryByText(/0%/)).not.toBeInTheDocument();
    expect(screen.getByText(/no usable capacity/i)).toBeInTheDocument();
  });

  it('shows the unknown bed status row only when there is one to show', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview(bedsBlock()));
    const { unmount } = render(<AdminOverviewAnalytics />);
    await settled();
    expect(screen.queryByText('Unknown Status')).not.toBeInTheDocument();
    unmount();

    hospitalService.getDashboardOverview.mockResolvedValue(
      overview(bedsBlock({ unknownStatusCount: 2 }))
    );
    render(<AdminOverviewAnalytics />);
    await settled();
    expect(screen.getByText('Unknown Status')).toBeInTheDocument();
  });

  it('renders Unassigned as an ordinary speciality and keeps the server ordering', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview(opdBlock));
    render(<AdminOverviewAnalytics />);
    await settled();

    const panel = screen.getByText('Busiest Specialities').closest('section');
    const labels = within(panel)
      .getAllByText(/Unassigned|Cardiology|Orthopedics/)
      .map((node) => node.textContent);

    // The backend already ranked and truncated these; the UI must not regroup or re-sort.
    expect(labels).toEqual(['Unassigned', 'Cardiology', 'Orthopedics']);
  });

  it('formats collection with the currency the backend reported', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({ billing: { collection: 45000, currency: 'USD' } })
    );
    render(<AdminOverviewAnalytics />);
    await settled();

    expect(screen.getByText('Collection')).toBeInTheDocument();
    // Not hard-coded to INR just because most tenants are Indian.
    expect(screen.getByText(/\$45,000/)).toBeInTheDocument();
  });

  // ── layout ────────────────────────────────────────────────────────────────

  it('does not reserve a fixed number of columns for modules that may be absent', async () => {
    hospitalService.getDashboardOverview.mockResolvedValue(overview());
    const { container, unmount } = render(<AdminOverviewAnalytics />);
    await settled();
    const coreOnly = container.querySelector('.grid').className;
    unmount();

    hospitalService.getDashboardOverview.mockResolvedValue(
      overview({
        ...opdBlock,
        ...bedsBlock(),
        ipd: { admissions: 3, trend: [] },
        pharmacy: { pharmacySales: 5 },
      })
    );
    const { container: full } = render(<AdminOverviewAnalytics />);
    await settled();

    // A single-card row must not sit in a multi-column grid, which is what leaves half the screen
    // blank for a hospital that only owns the core module.
    expect(coreOnly).toContain('grid-cols-1');
    expect(coreOnly).not.toMatch(/(sm|md|lg|xl):grid-cols-[2-6]/);
    expect(full.querySelector('.grid').className).toMatch(/grid-cols-[2-6]/);
  });
});
