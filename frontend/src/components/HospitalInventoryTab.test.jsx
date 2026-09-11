import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { success, toastError } = vi.hoisted(() => ({ success: vi.fn(), toastError: vi.fn() }));

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success, error: toastError }),
}));

vi.mock('../services/hospitalService', () => ({
  default: {
    getHospitalInventory: vi.fn(),
    getHospitalServices: vi.fn(),
    getHospitalInventoryPurchases: vi.fn(),
    getGlobalMasterItems: vi.fn(),
  },
}));

import hospitalService from '../services/hospitalService';
import HospitalInventoryTab from './HospitalInventoryTab';

describe('HospitalInventoryTab load states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hospitalService.getHospitalServices.mockResolvedValue([]);
    hospitalService.getGlobalMasterItems.mockResolvedValue([]);
    hospitalService.getHospitalInventoryPurchases.mockResolvedValue([]);
  });

  afterEach(() => cleanup());

  /**
   * The reported defect: every fetch helper caught its own error and only console.error'd, so
   * loadData's catch never ran and a failed API call rendered as an empty inventory table --
   * a hospital with stock looked like a hospital with none.
   */
  it('shows a retryable error instead of an empty inventory when the API fails', async () => {
    hospitalService.getHospitalInventory.mockRejectedValue({
      response: { data: { error: 'Inventory service unavailable' } },
    });

    render(<HospitalInventoryTab />);

    await screen.findByText(/couldn't load inventory/i);
    expect(screen.getByText('Inventory service unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();

    // Crucially: it must NOT claim there is simply no stock.
    expect(screen.queryByText(/no inventory items found/i)).not.toBeInTheDocument();
  });

  it('renders the empty state only when the API genuinely returns no rows', async () => {
    hospitalService.getHospitalInventory.mockResolvedValue([]);

    render(<HospitalInventoryTab />);

    await waitFor(() => expect(hospitalService.getHospitalInventory).toHaveBeenCalled());
    expect(screen.queryByText(/couldn't load inventory/i)).not.toBeInTheDocument();
  });
});
