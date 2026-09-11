import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { success, toastError } = vi.hoisted(() => ({ success: vi.fn(), toastError: vi.fn() }));

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success, error: toastError }),
}));

vi.mock('../../services/otService', () => ({
  default: {
    getRooms: vi.fn(),
    getRoomSuggestions: vi.fn(),
    createRoom: vi.fn(),
    updateRoom: vi.fn(),
    deactivateRoom: vi.fn(),
  },
}));

import otService from '../../services/otService';
import OtRoomsCard from './OtRoomsCard';

const room = {
  publicId: 'ot-1',
  name: 'Main Theatre',
  turnoverMinutes: 15,
  status: 'AVAILABLE',
};

describe('OtRoomsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getRooms.mockResolvedValue([room]);
    otService.getRoomSuggestions.mockResolvedValue([]);
  });

  it('creates a theatre with a string-backed turnover value parsed only on submit', async () => {
    otService.createRoom.mockResolvedValue({ ...room, publicId: 'ot-2', name: 'Day Theatre' });
    render(<OtRoomsCard />);

    await screen.findByText('Main Theatre');
    fireEvent.change(screen.getByLabelText('Theatre name'), { target: { value: 'Day Theatre' } });
    const turnover = screen.getByLabelText('Turnover (min)');
    fireEvent.change(turnover, { target: { value: '' } });
    expect(turnover).toHaveValue(null);
    fireEvent.change(turnover, { target: { value: '20' } });
    fireEvent.click(screen.getByRole('button', { name: /add/i }));

    await waitFor(() =>
      expect(otService.createRoom).toHaveBeenCalledWith({
        name: 'Day Theatre',
        turnoverMinutes: 20,
        sourceWardId: null,
      })
    );
  });

  it('edits and deactivates an existing theatre through the existing room API', async () => {
    otService.updateRoom.mockResolvedValue({ ...room, name: 'Main OT', turnoverMinutes: 25 });
    otService.deactivateRoom.mockResolvedValue(undefined);
    render(<OtRoomsCard />);

    await screen.findByText('Main Theatre');
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    fireEvent.change(screen.getByLabelText('Edit theatre name'), { target: { value: 'Main OT' } });
    fireEvent.change(screen.getByLabelText('Edit theatre turnover'), { target: { value: '25' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(otService.updateRoom).toHaveBeenCalledWith('ot-1', {
        name: 'Main OT',
        turnoverMinutes: 25,
      })
    );
    await screen.findByText('Main OT');
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(otService.deactivateRoom).toHaveBeenCalledWith('ot-1'));
  });

  it('keeps the theatre visible and shows an actionable error when deactivation fails', async () => {
    otService.deactivateRoom.mockRejectedValue(new Error('Network timeout'));
    render(<OtRoomsCard />);

    await screen.findByText('Main Theatre');
    fireEvent.click(screen.getByRole('button', { name: 'Deactivate' }));

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Network timeout'));
    expect(screen.getByText('Main Theatre')).toBeInTheDocument();
  });
});
