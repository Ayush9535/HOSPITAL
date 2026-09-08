import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import WardModal from './WardModal';

vi.mock('../services/icuService', () => ({
  default: { getUnitTypes: vi.fn(), getBoard: vi.fn(), getUnits: vi.fn() },
}));
vi.mock('../services/wardService', () => ({
  default: { createWard: vi.fn(), updateWard: vi.fn() },
}));
vi.mock('../services/authService', () => ({
  default: { getCurrentUser: vi.fn() },
}));
vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn() }),
}));

import icuService from '../services/icuService';
import WardService from '../services/wardService';
import authService from '../services/authService';

const UNIT_TYPES = [
  { key: 'GENERAL', label: 'General Ward', criticalCare: false },
  { key: 'ICU', label: 'Intensive Care Unit', criticalCare: true },
  { key: 'NICU', label: 'Neonatal ICU', criticalCare: true },
];

const withIcu = () => authService.getCurrentUser.mockReturnValue({ modules: ['IPD', 'ICU'] });
const withoutIcu = () => authService.getCurrentUser.mockReturnValue({ modules: ['IPD'] });

/** Fills the required fields so onSubmit reaches the service. */
const fillRequired = () => {
  fireEvent.change(screen.getByLabelText('Ward Name'), { target: { value: 'Ward A' } });
  fireEvent.change(screen.getByLabelText('Bed Price'), { target: { value: '1500' } });
  fireEvent.change(screen.getByLabelText('Total Beds'), { target: { value: '2' } });
};

describe('WardModal — ICU unit type (ICU-2 G3/G5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    WardService.createWard.mockResolvedValue({});
    WardService.updateWard.mockResolvedValue({});
  });

  it('shows the selector and lets ICU be chosen when the module is on and the list loads', async () => {
    withIcu();
    icuService.getUnitTypes.mockResolvedValue(UNIT_TYPES);

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    const select = await screen.findByLabelText('Unit Type');
    expect(select).toBeEnabled();
    expect(screen.getByRole('option', { name: 'Intensive Care Unit' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Neonatal ICU' })).toBeInTheDocument();

    fireEvent.change(select, { target: { value: 'ICU' } });
    expect(select.value).toBe('ICU');
  });

  it('sends the chosen unit type when creating a ward', async () => {
    withIcu();
    icuService.getUnitTypes.mockResolvedValue(UNIT_TYPES);

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);
    fireEvent.change(await screen.findByLabelText('Unit Type'), { target: { value: 'ICU' } });
    fillRequired();
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(WardService.createWard).toHaveBeenCalled());
    expect(WardService.createWard.mock.calls[0][0]).toMatchObject({ unitType: 'ICU' });
  });

  it('does NOT silently disappear when the unit-type request fails', async () => {
    // G3: the whole field used to vanish, which reads as "this hospital has no ICU
    // classification" rather than "the list could not be loaded".
    withIcu();
    icuService.getUnitTypes.mockRejectedValue({ response: { data: { error: 'Access Denied' } } });

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    const select = await screen.findByLabelText('Unit Type');
    expect(select).toBeInTheDocument();
    expect(select).toBeDisabled();
    expect(screen.getByText(/Access Denied/)).toBeInTheDocument();
    expect(screen.getByText(/keep its current classification/)).toBeInTheDocument();
  });

  it('omits unitType from the payload when the list could not be loaded', async () => {
    // Sending a value from a failed fetch would overwrite the ward's real classification.
    withIcu();
    icuService.getUnitTypes.mockRejectedValue(new Error('network'));

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByLabelText('Unit Type');
    fillRequired();
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(WardService.createWard).toHaveBeenCalled());
    expect(WardService.createWard.mock.calls[0][0]).not.toHaveProperty('unitType');
  });

  it('shows the selector disabled when the list comes back empty', async () => {
    withIcu();
    icuService.getUnitTypes.mockResolvedValue([]);

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    const select = await screen.findByLabelText('Unit Type');
    expect(select).toBeDisabled();
    expect(screen.getByText(/No unit types were returned/)).toBeInTheDocument();
  });

  it('hides the selector entirely when the hospital has no ICU module', async () => {
    // Absent module is a different thing from a failed load: there is nothing to explain.
    withoutIcu();

    render(<WardModal open initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);

    expect(screen.queryByLabelText('Unit Type')).not.toBeInTheDocument();
    expect(icuService.getUnitTypes).not.toHaveBeenCalled();
  });

  it('preloads the existing classification when editing a ward', async () => {
    withIcu();
    icuService.getUnitTypes.mockResolvedValue(UNIT_TYPES);

    render(
      <WardModal
        open
        initial={{ wardId: 3, wardName: 'ICU-1', bedPrice: 5000, totalBeds: 4, unitType: 'NICU' }}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />
    );

    await waitFor(() => expect(screen.getByLabelText('Unit Type').value).toBe('NICU'));
  });
});
