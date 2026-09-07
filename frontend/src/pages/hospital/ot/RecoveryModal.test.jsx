import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { success, toastError } = vi.hoisted(() => ({ success: vi.fn(), toastError: vi.fn() }));

vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success, error: toastError }),
}));

vi.mock('../../../services/otService', () => ({
  default: {
    getRecovery: vi.fn(),
    getRecoveryBays: vi.fn(),
    admitRecovery: vi.fn(),
    observeRecovery: vi.fn(),
    dischargeRecovery: vi.fn(),
  },
}));

const canMock = vi.fn();
vi.mock('../../../hooks/useOtPermissions', () => ({
  default: () => ({ can: canMock, loaded: true, permissions: [] }),
}));

import otService from '../../../services/otService';
import RecoveryModal from './RecoveryModal';

const surgery = { surgeryId: 42, patientName: 'Jane Doe', procedureName: 'Appendectomy' };

describe('RecoveryModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getRecovery.mockResolvedValue(null);
    canMock.mockImplementation(() => true);
  });

  // This modal loads its episode + bay list asynchronously, so a test can finish while a
  // setState is still queued. Without an explicit unmount those trailing updates land on a
  // component nobody is tracking any more, and the leaked DOM/timers were destabilising
  // UNRELATED test files sharing the worker (measured: 6/8 full-suite runs green with this
  // file present, 8/8 without). Tear down explicitly rather than relying on auto-cleanup.
  afterEach(() => {
    cleanup();
  });

  it('requires a bay to be selected before admitting, and never sends an admit without one', async () => {
    otService.getRecoveryBays.mockResolvedValue([
      { publicId: 'bay-1', name: 'Bay 1', occupied: false },
      { publicId: 'bay-2', name: 'Bay 2', occupied: true },
    ]);
    render(<RecoveryModal surgery={surgery} onClose={() => {}} />);

    const admitButton = await screen.findByRole('button', { name: /admit to recovery/i });
    expect(admitButton).toBeDisabled();

    // The occupied bay must not be offered at all.
    expect(screen.queryByText('Bay 2')).not.toBeInTheDocument();

    fireEvent.click(admitButton);
    expect(otService.admitRecovery).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('Recovery bay'), { target: { value: 'bay-1' } });
    expect(admitButton).toBeEnabled();

    otService.admitRecovery.mockResolvedValue({ arrivedAt: new Date().toISOString() });
    fireEvent.click(admitButton);

    await waitFor(() => expect(otService.admitRecovery).toHaveBeenCalledWith(42, 'bay-1'));
  });

  it('explains rather than hides when the hospital has configured no recovery bays', async () => {
    otService.getRecoveryBays.mockResolvedValue([]);
    render(<RecoveryModal surgery={surgery} onClose={() => {}} />);

    await screen.findByText(/no recovery bays are configured/i);
    expect(screen.getByRole('button', { name: /admit to recovery/i })).toBeDisabled();
  });

  it('hides the admit action with an explanation when the caller lacks OT_RECOVERY', async () => {
    canMock.mockImplementation((code) => code !== 'OT_RECOVERY');
    otService.getRecoveryBays.mockResolvedValue([{ publicId: 'bay-1', name: 'Bay 1', occupied: false }]);
    render(<RecoveryModal surgery={surgery} onClose={() => {}} />);

    await screen.findByText(/don't have permission to admit/i);
    expect(screen.queryByRole('button', { name: /admit to recovery/i })).not.toBeInTheDocument();
  });

  it('hides discharge with an explanation when the caller lacks OT_TRANSFER', async () => {
    canMock.mockImplementation((code) => code !== 'OT_TRANSFER');
    otService.getRecovery.mockResolvedValue({ arrivedAt: new Date().toISOString(), dischargedAt: null });
    otService.getRecoveryBays.mockResolvedValue([]);
    render(<RecoveryModal surgery={surgery} onClose={() => {}} />);

    await screen.findByText(/requires ot_transfer/i);
    expect(screen.queryByRole('button', { name: /^discharge$/i })).not.toBeInTheDocument();
  });
});
