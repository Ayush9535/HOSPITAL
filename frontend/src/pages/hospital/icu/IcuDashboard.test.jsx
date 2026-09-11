import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import IcuDashboard from './IcuDashboard';

vi.mock('../../../services/icuService', () => ({
  default: { getUnits: vi.fn(), getBoard: vi.fn(), getUnitTypes: vi.fn() },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn() }),
}));

import icuService from '../../../services/icuService';

const counts = (o = {}) => ({
  totalBeds: 0,
  occupied: 0,
  available: 0,
  cleaning: 0,
  maintenance: 0,
  patients: 0,
  newAdmissionsToday: 0,
  pendingConfirmation: 0,
  awaitingCleaning: 0,
  occupancyMismatches: 0,
  ...o,
});

describe('IcuDashboard', () => {
  beforeEach(() => vi.clearAllMocks());

  it('prompts for setup when the hospital has no critical care ward', async () => {
    icuService.getUnits.mockResolvedValue({
      hasCriticalCareUnits: false,
      units: [],
      beds: [],
      totals: counts(),
    });

    render(<IcuDashboard />);

    expect(await screen.findByText('No critical care units yet')).toBeInTheDocument();
  });

  it('distinguishes "not configured" from "not in your scope"', async () => {
    icuService.getUnits.mockResolvedValue({
      hasCriticalCareUnits: true,
      units: [],
      beds: [],
      totals: counts(),
    });

    render(<IcuDashboard />);

    expect(await screen.findByText('No ICU units in your scope')).toBeInTheDocument();
  });

  it('renders the counts the API reported, without recomputing them', async () => {
    icuService.getUnits.mockResolvedValue({
      hasCriticalCareUnits: true,
      totals: counts({
        totalBeds: 5,
        occupied: 2,
        available: 1,
        patients: 2,
        newAdmissionsToday: 1,
      }),
      units: [
        {
          wardId: 10,
          wardName: 'ICU-1',
          unitType: 'ICU',
          unitTypeLabel: 'Intensive Care Unit',
          counts: counts({ totalBeds: 5, occupied: 2, available: 1, patients: 2 }),
        },
      ],
      beds: [],
    });

    render(<IcuDashboard />);

    await waitFor(() => expect(screen.getByText('ICU-1')).toBeInTheDocument());
    expect(screen.getByText('Intensive Care Unit')).toBeInTheDocument();
    expect(screen.getByText('40% occupied')).toBeInTheDocument();
    expect(screen.getByText('1 admitted today')).toBeInTheDocument();
  });

  it('surfaces an occupancy mismatch rather than hiding it', async () => {
    icuService.getUnits.mockResolvedValue({
      hasCriticalCareUnits: true,
      totals: counts({ totalBeds: 2, occupied: 1, occupancyMismatches: 1 }),
      units: [
        {
          wardId: 10,
          wardName: 'ICU-1',
          unitType: 'ICU',
          unitTypeLabel: 'Intensive Care Unit',
          counts: counts({ totalBeds: 2, occupied: 1, occupancyMismatches: 1 }),
        },
      ],
      beds: [],
    });

    render(<IcuDashboard />);

    expect(await screen.findByText('1 bed need attention')).toBeInTheDocument();
  });

  it('shows the pending-admission warning only when there is one', async () => {
    icuService.getUnits.mockResolvedValue({
      hasCriticalCareUnits: true,
      totals: counts({ totalBeds: 1, occupied: 1, patients: 1 }),
      units: [
        {
          wardId: 10,
          wardName: 'ICU-1',
          unitType: 'ICU',
          unitTypeLabel: 'Intensive Care Unit',
          counts: counts({ totalBeds: 1 }),
        },
      ],
      beds: [],
    });

    render(<IcuDashboard />);

    await waitFor(() => expect(screen.getByText('ICU-1')).toBeInTheDocument());
    expect(screen.queryByText(/pending nurse confirmation/)).not.toBeInTheDocument();
    expect(screen.queryByText(/need attention/)).not.toBeInTheDocument();
  });
});
