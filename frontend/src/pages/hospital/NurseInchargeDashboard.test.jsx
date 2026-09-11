import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { toastSuccess, toastError } = vi.hoisted(() => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));
vi.mock('../../context/ToastContext', () => ({ useToast: () => ({ success: toastSuccess, error: toastError }) }));
vi.mock('../../services/nurseService', () => ({ default: {
  getUnassignedPatients: vi.fn(), getWardStaffNurses: vi.fn(), assignPatientNurse: vi.fn(),
} }));

import nurseService from '../../services/nurseService';
import { UnassignedPatientsView } from './NurseInchargeDashboard';

const patient = { ipdAdmissionId: 41, patientName: 'Ward-visible patient', wardName: 'Ward A', wardId: 7, bedCode: 'A-1', ipdNumber: 'IPD-41', admissionDateTime: '2026-08-25T10:00:00' };

describe('UnassignedPatientsView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    nurseService.getUnassignedPatients.mockResolvedValue([patient]);
    nurseService.getWardStaffNurses.mockResolvedValue([{ id: 12, name: 'Nurse A' }]);
  });

  it('renders a ward-visible patient without a primary assignment and removes it after success', async () => {
    nurseService.getUnassignedPatients.mockResolvedValueOnce([patient]).mockResolvedValueOnce([]);
    nurseService.assignPatientNurse.mockResolvedValue({});
    render(<UnassignedPatientsView refreshKey={0} />);
    await screen.findByText('Ward-visible patient');
    expect(screen.getByText('Ward A / A-1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /assign nurse/i }));
    await screen.findByRole('option', { name: 'Nurse A' });
    fireEvent.change(screen.getByLabelText('Primary nurse'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: /^assign$/i }));
    await waitFor(() => expect(nurseService.assignPatientNurse).toHaveBeenCalledWith(41, 12));
    await waitFor(() => expect(screen.getByText('No unassigned patients')).toBeInTheDocument());
    expect(toastSuccess).toHaveBeenCalledWith('Primary nurse assigned');
  });

  it('keeps the selected row and nurse actionable after a failed assignment, then permits retry', async () => {
    nurseService.assignPatientNurse.mockRejectedValueOnce({ response: { data: { error: 'Nurse is unavailable' } } }).mockResolvedValueOnce({});
    render(<UnassignedPatientsView refreshKey={0} />);
    await screen.findByText('Ward-visible patient');
    fireEvent.click(screen.getByRole('button', { name: /assign nurse/i }));
    await screen.findByRole('option', { name: 'Nurse A' });
    fireEvent.change(screen.getByLabelText('Primary nurse'), { target: { value: '12' } });
    fireEvent.click(screen.getByRole('button', { name: /^assign$/i }));
    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Nurse is unavailable'));
    expect(screen.getByText('Assign primary nurse')).toBeInTheDocument();
    expect(screen.getByLabelText('Primary nurse')).toHaveValue('12');
    fireEvent.click(screen.getByRole('button', { name: /^assign$/i }));
    await waitFor(() => expect(nurseService.assignPatientNurse).toHaveBeenCalledTimes(2));
  });
});
