import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('../../../services/hospitalService', () => ({
  default: {
    getDispensableMedicines: vi.fn(),
    dispenseMedicine: vi.fn(),
  },
}));

const toastError = vi.fn();
vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError }),
}));

import hospitalService from '../../../services/hospitalService';
import DispenseModal from './DispenseModal';

const linkedOrder = {
  id: 41,
  name: 'Azithromycin',
  dosage: '500mg',
  frequency: '1-0-1',
  medicineId: 7,
  inventoryStatus: 'LINKED_AVAILABLE',
  availableQuantity: 50,
  earliestExpiry: '2027-03-31',
  quantityDispensed: 0,
};

const unlinkedOrder = {
  id: 42,
  name: 'Handwritten mixture',
  dosage: '5ml',
  medicineId: null,
  inventoryStatus: 'UNLINKED',
};

describe('DispenseModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hospitalService.getDispensableMedicines.mockResolvedValue([
      { medicineId: 7, name: 'Azithromycin', type: 'Tablet', availableQuantity: 50, earliestExpiry: '2027-03-31' },
      { medicineId: 8, name: 'Azithromycin', type: 'Syrup', availableQuantity: 4, earliestExpiry: '2026-11-30' },
    ]);
    hospitalService.dispenseMedicine.mockResolvedValue({ quantityDispensed: 5 });
  });

  it('shows usable stock and the lot that would go out first', async () => {
    render(<DispenseModal prescription={linkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);

    expect(screen.getByText('50')).toBeInTheDocument();
    expect(screen.getByText('2027-03-31')).toBeInTheDocument();
  });

  it('dispenses the quantity entered, not a quantity it invented', async () => {
    const user = userEvent.setup();
    const onDispensed = vi.fn();
    const onClose = vi.fn();
    render(
      <DispenseModal prescription={linkedOrder} onClose={onClose} onDispensed={onDispensed} />
    );

    await user.type(screen.getByLabelText('Quantity to dispense'), '5');
    await user.click(screen.getByRole('button', { name: 'Dispense' }));

    await waitFor(() => expect(hospitalService.dispenseMedicine).toHaveBeenCalledTimes(1));
    const [prescriptionId, payload] = hospitalService.dispenseMedicine.mock.calls[0];
    expect(prescriptionId).toBe(41);
    expect(payload.quantity).toBe(5);
    expect(payload.medicineId).toBe(7);
    expect(payload.idempotencyKey).toBeTruthy();

    // Refetch first, close second — the user must not land back on a stale figure.
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(onDispensed).toHaveBeenCalled();
  });

  /**
   * An unlinked order carries no inventory row, so the medicine has to be chosen. Two rows share
   * the name here on purpose: the previous server behaviour took whichever sorted first, and
   * nothing in this UI is allowed to make that choice on the user's behalf.
   */
  it('requires choosing the medicine for an unlinked order and never preselects one', async () => {
    const user = userEvent.setup();
    render(<DispenseModal prescription={unlinkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);

    const select = await screen.findByLabelText('Inventory medicine');
    expect(select).toHaveValue('');

    await user.type(screen.getByLabelText('Quantity to dispense'), '2');
    expect(screen.getByRole('button', { name: 'Dispense' })).toBeDisabled();

    await user.selectOptions(select, '8');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Dispense' })).not.toBeDisabled()
    );

    await user.click(screen.getByRole('button', { name: 'Dispense' }));
    await waitFor(() => expect(hospitalService.dispenseMedicine).toHaveBeenCalled());
    expect(hospitalService.dispenseMedicine.mock.calls[0][1].medicineId).toBe(8);
  });

  it('refuses a quantity larger than the usable stock before it reaches the server', async () => {
    const user = userEvent.setup();
    render(<DispenseModal prescription={linkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);

    await user.type(screen.getByLabelText('Quantity to dispense'), '51');

    expect(screen.getByText('Only 50 units are in usable stock.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dispense' })).toBeDisabled();
    expect(hospitalService.dispenseMedicine).not.toHaveBeenCalled();
  });

  it('refuses zero and negative quantities', async () => {
    const user = userEvent.setup();
    render(<DispenseModal prescription={linkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);

    const qty = screen.getByLabelText('Quantity to dispense');
    await user.type(qty, '0');
    expect(screen.getByRole('button', { name: 'Dispense' })).toBeDisabled();

    await user.clear(qty);
    await user.type(qty, '-3');
    expect(screen.getByRole('button', { name: 'Dispense' })).toBeDisabled();
    expect(hospitalService.dispenseMedicine).not.toHaveBeenCalled();
  });

  /**
   * The failure case is the one that matters: a modal that closes on error tells the user stock
   * moved when it did not, and there is no way back to what they typed.
   */
  it('stays open on failure, keeps the inputs, shows the server error and allows a retry', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    hospitalService.dispenseMedicine.mockRejectedValueOnce({
      response: { data: { error: 'Insufficient stock for Azithromycin: requested 5, available 2' } },
    });

    render(<DispenseModal prescription={linkedOrder} onClose={onClose} onDispensed={vi.fn()} />);
    await user.type(screen.getByLabelText('Quantity to dispense'), '5');
    await user.click(screen.getByRole('button', { name: 'Dispense' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Insufficient stock');
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Quantity to dispense')).toHaveValue(5);
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    // ...and the retry goes through.
    await user.click(screen.getByRole('button', { name: 'Dispense' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(hospitalService.dispenseMedicine).toHaveBeenCalledTimes(2);
  });

  /** A retried submission is one act of dispensing, and carries the key that says so. */
  it('reuses one idempotency key across retries of the same dispense', async () => {
    const user = userEvent.setup();
    hospitalService.dispenseMedicine.mockRejectedValueOnce({
      response: { data: { error: 'Temporary failure' } },
    });

    render(<DispenseModal prescription={linkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);
    await user.type(screen.getByLabelText('Quantity to dispense'), '5');
    await user.click(screen.getByRole('button', { name: 'Dispense' }));
    await screen.findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Dispense' }));

    await waitFor(() => expect(hospitalService.dispenseMedicine).toHaveBeenCalledTimes(2));
    const [first, second] = hospitalService.dispenseMedicine.mock.calls;
    expect(second[1].idempotencyKey).toBe(first[1].idempotencyKey);
  });

  /** A double-click submits once: the button is disabled for the duration of the request. */
  it('cannot be submitted twice by double-clicking', async () => {
    const user = userEvent.setup();
    let release;
    hospitalService.dispenseMedicine.mockImplementationOnce(
      () => new Promise((resolve) => { release = resolve; })
    );

    render(<DispenseModal prescription={linkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);
    await user.type(screen.getByLabelText('Quantity to dispense'), '5');

    const button = screen.getByRole('button', { name: 'Dispense' });
    await user.click(button);
    expect(screen.getByRole('button', { name: 'Dispensing…' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Dispensing…' }));

    expect(hospitalService.dispenseMedicine).toHaveBeenCalledTimes(1);
    release?.({});
  });

  /** An unreachable medicine list must not read as a facility that stocks nothing. */
  it('reports a failed medicine lookup instead of showing an empty selector', async () => {
    hospitalService.getDispensableMedicines.mockRejectedValueOnce({
      response: { data: { error: 'Inventory unavailable' } },
    });

    render(<DispenseModal prescription={unlinkedOrder} onClose={vi.fn()} onDispensed={vi.fn()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Inventory unavailable');
    expect(screen.queryByLabelText('Inventory medicine')).not.toBeInTheDocument();
  });
});
