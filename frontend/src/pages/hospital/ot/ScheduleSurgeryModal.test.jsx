import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }));

vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError }),
}));

vi.mock('../../../services/otService', () => ({
  default: {
    getSurgeons: vi.fn(),
    getRooms: vi.fn(),
    schedule: vi.fn(),
    getMyOtPermissions: vi.fn(),
  },
}));

vi.mock('../../../services/wardService', () => ({ default: { getWards: vi.fn() } }));

vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: () => ({ modules: ['OT'] }) },
}));

import otService from '../../../services/otService';
import ScheduleSurgeryModal from './ScheduleSurgeryModal';

describe('ScheduleSurgeryModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getSurgeons.mockResolvedValue([{ doctorId: 9, name: 'Dr Test', specialization: 'General' }]);
    otService.getRooms.mockResolvedValue([{ id: 4, publicId: 'room-4', name: 'OT 1' }]);
    // Scheduling requires OT_SCHEDULE; the submit is disabled without it.
    otService.getMyOtPermissions.mockResolvedValue(['OT_VIEW', 'OT_SCHEDULE']);
  });

  it('sends the displayed lifecycle revision and tells the user to refresh on a stale conflict', async () => {
    otService.schedule.mockRejectedValue({ response: { status: 409 } });
    render(
      <ScheduleSurgeryModal
        surgery={{ publicId: 'surgery-1', lifecycleVersion: 7, patientName: 'Patient', procedureName: 'Case' }}
        onClose={vi.fn()}
      />
    );

    await screen.findByRole('option', { name: /dr test/i });
    fireEvent.change(screen.getByLabelText(/assign surgeon/i), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText(/date & time/i), { target: { value: '2026-08-26T10:00' } });
    fireEvent.change(screen.getByLabelText(/theatre/i), { target: { value: '4' } });
    fireEvent.click(screen.getByRole('button', { name: /^schedule$/i }));

    await waitFor(() =>
      expect(otService.schedule).toHaveBeenCalledWith(
        'surgery-1',
        expect.objectContaining({ expectedVersion: 7, otRoomId: 4 })
      )
    );
    expect(toastError).toHaveBeenCalledWith('Surgery was modified by another request. Refresh and retry.');
  });
});
