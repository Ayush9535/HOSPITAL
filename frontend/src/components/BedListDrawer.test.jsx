import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
const { error, getCurrentUser } = vi.hoisted(() => ({ error: vi.fn(), getCurrentUser: vi.fn() }));
vi.mock('../context/ToastContext', () => ({ useToast: () => ({ error }) }));
vi.mock('../services/authService', () => ({ default: { getCurrentUser } }));
vi.mock('../services/wardService', () => ({ default: { getBeds: vi.fn(), updateBedStatus: vi.fn() } }));
import WardService from '../services/wardService';
import BedListDrawer from './BedListDrawer';
const ward = { wardId: 1, wardName: 'Ward A' };
describe('BedListDrawer audited transition', () => {
  beforeEach(() => { vi.clearAllMocks(); getCurrentUser.mockReturnValue({ role: 'HOSPITAL_ADMIN' }); WardService.getBeds.mockResolvedValue([{ bedId: 8, bedCode: 'A-1', status: 'maintenance' }]); });
  it('posts canonical transition then reloads the displayed beds', async () => {
    WardService.updateBedStatus.mockResolvedValue({}); WardService.getBeds.mockResolvedValueOnce([{ bedId: 8, bedCode: 'A-1', status: 'maintenance' }]).mockResolvedValueOnce([{ bedId: 8, bedCode: 'A-1', status: 'available' }]);
    render(<BedListDrawer open ward={ward} onClose={vi.fn()} onStatusChange={vi.fn()} />);
    await screen.findByText('Make Available'); fireEvent.click(screen.getByText('Make Available'));
    await waitFor(() => expect(WardService.updateBedStatus).toHaveBeenCalledWith(8, 'Back to available'));
    await waitFor(() => expect(screen.getByText('available')).toBeInTheDocument());
  });
  it('hides mutation action for non-managing roles and preserves retry on failure', async () => {
    getCurrentUser.mockReturnValue({ role: 'DOCTOR' }); const first = render(<BedListDrawer open ward={ward} onClose={vi.fn()} />);
    await screen.findByText('maintenance'); expect(screen.queryByText('Make Available')).toBeNull();
    first.unmount(); cleanup(); getCurrentUser.mockReturnValue({ role: 'HOSPITAL_ADMIN' }); WardService.updateBedStatus.mockRejectedValue({ response: { data: { error: 'Blocked' } } });
    render(<BedListDrawer open ward={ward} onClose={vi.fn()} />); await screen.findByText('Make Available'); fireEvent.click(screen.getByText('Make Available'));
    await waitFor(() => expect(error).toHaveBeenCalledWith('Blocked')); expect(screen.getByText('Make Available')).toBeInTheDocument();
  });
});
