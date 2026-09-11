import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('../services/hospitalService', () => ({
  default: {
    getInventoryMedicines: vi.fn(),
    getMedicinePurchases: vi.fn(),
    searchMedicines: vi.fn().mockResolvedValue([]),
  },
}));

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

import hospitalService from '../services/hospitalService';
import MedicineInventoryTab from './MedicineInventoryTab';

/**
 * A failed read is not an empty pharmacy.
 *
 * <p>The fetch swallowed its error and left the list at whatever it already held — [] on first
 * load — so an unreachable server drew a facility that stocks no medicines at all. Someone
 * checking whether a drug is available would be told, with no hint of doubt, that it is not.
 */
describe('MedicineInventoryTab — load failure is its own state', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows an error with a retry instead of an empty inventory', async () => {
    hospitalService.getInventoryMedicines.mockRejectedValue({
      response: { data: { error: 'Inventory service unavailable' } },
    });

    render(<MedicineInventoryTab />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load medicines");
    expect(alert).toHaveTextContent('Inventory service unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('recovers on retry and then shows the real inventory', async () => {
    const user = userEvent.setup();
    hospitalService.getInventoryMedicines
      .mockRejectedValueOnce({ response: { data: { error: 'Temporary failure' } } })
      .mockResolvedValueOnce([
        { id: 1, name: 'Paracetamol 500', stockQuantity: 200, unitPrice: 1.5 },
      ]);

    render(<MedicineInventoryTab />);
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(await screen.findByText('Paracetamol 500')).toBeInTheDocument();
  });

  it('a genuinely empty inventory is not shown as an error', async () => {
    hospitalService.getInventoryMedicines.mockResolvedValue([]);

    render(<MedicineInventoryTab />);

    await waitFor(() => expect(hospitalService.getInventoryMedicines).toHaveBeenCalled());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });
});
