import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import IcuBedBoard from './IcuBedBoard';

const navigate = vi.fn();
vi.mock('react-router-dom', () => ({ useNavigate: () => navigate }));
vi.mock('../../../services/icuService', () => ({
  default: { getBoard: vi.fn(), getUnits: vi.fn(), getUnitTypes: vi.fn() },
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

const unit = {
  wardId: 10,
  wardName: 'ICU-1',
  unitType: 'ICU',
  unitTypeLabel: 'Intensive Care Unit',
};

const board = (beds, totals = {}) => ({
  hasCriticalCareUnits: true,
  totals: counts({ totalBeds: beds.length, ...totals }),
  units: [{ ...unit, counts: counts({ totalBeds: beds.length, ...totals }) }],
  beds,
});

describe('IcuBedBoard', () => {
  beforeEach(() => vi.clearAllMocks());

  it('opens the existing patient workspace when an occupied bed is clicked', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 1,
            bedCode: 'ICU-1-B1',
            wardId: 10,
            status: 'occupied',
            ipdAdmissionId: 555,
            patientName: 'Asha',
            age: 44,
            gender: 'FEMALE',
            ipdNumber: 'IPD-9',
            occupancyConsistent: true,
          },
        ],
        { occupied: 1 }
      )
    );

    render(<IcuBedBoard />);

    fireEvent.click(await screen.findByText('ICU-1-B1'));
    expect(navigate).toHaveBeenCalledWith('/ipd/555');
  });

  it('does not navigate from an empty bed', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 2,
            bedCode: 'ICU-1-B2',
            wardId: 10,
            status: 'available',
            occupancyConsistent: true,
          },
        ],
        { available: 1 }
      )
    );

    render(<IcuBedBoard />);

    fireEvent.click(await screen.findByText('ICU-1-B2'));
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows recorded SpO2 and respiratory rate as values, with no severity verdict', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 1,
            bedCode: 'ICU-1-B1',
            wardId: 10,
            status: 'occupied',
            ipdAdmissionId: 555,
            patientName: 'Asha',
            latestSpo2: 92,
            latestRespiratoryRate: 24,
            occupancyConsistent: true,
          },
        ],
        { occupied: 1 }
      )
    );

    render(<IcuBedBoard />);

    expect(await screen.findByText('SpO₂ 92% · RR 24')).toBeInTheDocument();
    // No derived judgement anywhere on the card.
    expect(screen.queryByText(/critical|high risk|severe|deteriorat/i)).not.toBeInTheDocument();
  });

  it('flags a bed whose records disagree instead of silently picking one', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 3,
            bedCode: 'ICU-1-B3',
            wardId: 10,
            status: 'occupied',
            occupancyConsistent: false,
            occupancyNote: 'Bed is marked occupied but has no active admission',
          },
        ],
        { occupied: 1, occupancyMismatches: 1 }
      )
    );

    render(<IcuBedBoard />);

    expect(
      await screen.findByText(/Bed is marked occupied but has no active admission/)
    ).toBeInTheDocument();
    expect(screen.getByText('1 need attention')).toBeInTheDocument();
  });

  it('hides the patient of an occupied bed outside the caller scope', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 4,
            bedCode: 'ICU-1-B4',
            wardId: 10,
            status: 'occupied',
            occupancyConsistent: true,
          },
        ],
        { occupied: 1 }
      )
    );

    render(<IcuBedBoard />);

    expect(await screen.findByText('Occupied — patient not in your scope')).toBeInTheDocument();
  });

  it('filters the grid by bed status', async () => {
    icuService.getBoard.mockResolvedValue(
      board(
        [
          {
            bedId: 1,
            bedCode: 'ICU-1-B1',
            wardId: 10,
            status: 'occupied',
            ipdAdmissionId: 5,
            patientName: 'Asha',
            occupancyConsistent: true,
          },
          {
            bedId: 2,
            bedCode: 'ICU-1-B2',
            wardId: 10,
            status: 'available',
            occupancyConsistent: true,
          },
        ],
        { occupied: 1, available: 1 }
      )
    );

    render(<IcuBedBoard />);

    await waitFor(() => expect(screen.getByText('ICU-1-B1')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'available' } });

    expect(screen.queryByText('ICU-1-B1')).not.toBeInTheDocument();
    expect(screen.getByText('ICU-1-B2')).toBeInTheDocument();
  });

  it('prompts for setup when no ward is classified as critical care', async () => {
    icuService.getBoard.mockResolvedValue({
      hasCriticalCareUnits: false,
      totals: counts(),
      units: [],
      beds: [],
    });

    render(<IcuBedBoard />);

    expect(await screen.findByText('No critical care units yet')).toBeInTheDocument();
  });
});
