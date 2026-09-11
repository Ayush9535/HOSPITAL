import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import VitalsPanel from './VitalsPanel';

vi.mock('../../../services/icuService', () => ({
  default: {
    getStaysForAdmission: vi.fn(),
    getBoard: vi.fn(),
    getUnits: vi.fn(),
    getUnitTypes: vi.fn(),
  },
}));
vi.mock('../../../services/nurseService', () => ({
  default: {
    getVitals: vi.fn(),
    createVitals: vi.fn(),
    getAdmissionForm: vi.fn(),
    getSeparateNurseLogin: vi.fn(),
    getWardStaffNurses: vi.fn(),
    getMyNurses: vi.fn(),
  },
}));
vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: vi.fn() },
}));
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

import icuService from '../../../services/icuService';
import nurseService from '../../../services/nurseService';
import authService from '../../../services/authService';

const row = (o = {}) => ({
  id: 1,
  publicId: 'v-1',
  recordedAt: '2026-08-25T10:00:00',
  spo2: 92,
  supersedesVitalsId: null,
  ...o,
});

const withModules = (modules) =>
  authService.getCurrentUser.mockReturnValue({ role: 'NURSE_INCHARGE', modules });

describe('VitalsPanel — ICU observations (ICU-4)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    nurseService.getVitals.mockResolvedValue([]);
    nurseService.getAdmissionForm.mockResolvedValue({});
    nurseService.getSeparateNurseLogin.mockResolvedValue(true);
    nurseService.getWardStaffNurses.mockResolvedValue([]);
    nurseService.getMyNurses.mockResolvedValue([]);
    icuService.getStaysForAdmission.mockResolvedValue([]);
  });

  it('shows ICU fields only while the patient has an ACTIVE ICU stay', async () => {
    withModules(['IPD', 'ICU']);
    icuService.getStaysForAdmission.mockResolvedValue([{ status: 'ACTIVE' }]);

    render(<VitalsPanel admissionId={7} />);

    expect(await screen.findByText('MAP (mmHg)')).toBeInTheDocument();
    expect(screen.getByText('CVP (cmH₂O)')).toBeInTheDocument();
    expect(screen.getByText('Urine (mL)')).toBeInTheDocument();
    expect(screen.getByText('GCS Eye')).toBeInTheDocument();
  });

  it('renders the ordinary ward form when the ICU stay is closed', async () => {
    withModules(['IPD', 'ICU']);
    icuService.getStaysForAdmission.mockResolvedValue([{ status: 'CLOSED' }]);

    render(<VitalsPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('Temp (°F)')).toBeInTheDocument());
    expect(screen.queryByText('MAP (mmHg)')).not.toBeInTheDocument();
  });

  it('never asks for ICU data when the hospital has no ICU module', async () => {
    withModules(['IPD']);

    render(<VitalsPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('Temp (°F)')).toBeInTheDocument());
    expect(icuService.getStaysForAdmission).not.toHaveBeenCalled();
    expect(screen.queryByText('MAP (mmHg)')).not.toBeInTheDocument();
  });

  it('falls back to the ward form when the ICU lookup fails', async () => {
    // An ICU field on a ward patient would be wrong, not merely unexplained, so hiding is right.
    withModules(['IPD', 'ICU']);
    icuService.getStaysForAdmission.mockRejectedValue(new Error('403'));

    render(<VitalsPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText('Temp (°F)')).toBeInTheDocument());
    expect(screen.queryByText('MAP (mmHg)')).not.toBeInTheDocument();
  });

  it('keeps a superseded observation visible and marks the correction', async () => {
    // The core ICU-4 property: correcting must never make the earlier value disappear.
    withModules(['IPD', 'ICU']);
    icuService.getStaysForAdmission.mockResolvedValue([{ status: 'ACTIVE' }]);
    nurseService.getVitals.mockResolvedValue([
      row({ id: 2, publicId: 'v-2', spo2: 88, supersedesVitalsId: 1 }),
      row({ id: 1, publicId: 'v-1', spo2: 92 }),
    ]);

    render(<VitalsPanel admissionId={7} />);

    expect(await screen.findByText('Correction')).toBeInTheDocument();
    expect(screen.getByText('Superseded')).toBeInTheDocument();
    // Both values remain on screen — the original is not removed.
    expect(screen.getByText(/SpO₂:\s*92/)).toBeInTheDocument();
    expect(screen.getByText(/SpO₂:\s*88/)).toBeInTheDocument();
  });

  it('shows recorded ICU values without deriving any judgement', async () => {
    withModules(['IPD', 'ICU']);
    icuService.getStaysForAdmission.mockResolvedValue([{ status: 'ACTIVE' }]);
    nurseService.getVitals.mockResolvedValue([
      row({ mapMmhg: 72, cvpCmh2o: 8, urineOutputMl: 45, gcsTotal: 12 }),
    ]);

    render(<VitalsPanel admissionId={7} />);

    await waitFor(() => expect(screen.getByText(/MAP:\s*72/)).toBeInTheDocument());
    expect(screen.getByText(/CVP:\s*8/)).toBeInTheDocument();
    expect(screen.getByText(/Urine:\s*45/)).toBeInTheDocument();
    expect(screen.getByText(/GCS:\s*12/)).toBeInTheDocument();
    expect(screen.queryByText(/critical|severe|high risk|deteriorat/i)).not.toBeInTheDocument();
  });
});
