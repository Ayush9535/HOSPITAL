import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn() }),
}));

vi.mock('../services/platformService', () => ({
  default: {
    getPlans: vi.fn(),
    getPlanCapabilities: vi.fn(),
    createPlan: vi.fn(),
    updatePlan: vi.fn(),
    deletePlan: vi.fn(),
  },
}));

import platformService from '../services/platformService';
import PlansTab from './PlansTab';

const hospitalCatalog = [
  { key: 'OPD', label: 'OPD', pharmacyTier: false },
  { key: 'BILLING', label: 'Billing', pharmacyTier: false },
];

describe('PlansTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    platformService.getPlans.mockResolvedValue([]);
    platformService.getPlanCapabilities.mockResolvedValue(hospitalCatalog);
  });

  it('renders modules supplied by the capability catalog', async () => {
    render(<PlansTab hospitalType="HOSPITAL" />);
    await screen.findByText('No plans found. Create one to get started.');
    fireEvent.click(screen.getByRole('button', { name: /create plan/i }));

    expect(screen.getByRole('button', { name: 'OPD' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Billing' })).toBeInTheDocument();
    expect(screen.queryByText('PATHOLOGY')).not.toBeInTheDocument();
    expect(platformService.getPlanCapabilities).toHaveBeenCalledWith('HOSPITAL');
  });

  it('sends only modules present in the catalog', async () => {
    platformService.createPlan.mockResolvedValue({});
    render(<PlansTab hospitalType="HOSPITAL" />);
    await screen.findByText('No plans found. Create one to get started.');
    fireEvent.click(screen.getByRole('button', { name: /create plan/i }));
    fireEvent.change(screen.getByLabelText(/plan name/i), { target: { value: 'Essential' } });
    fireEvent.change(screen.getByLabelText(/monthly price/i), { target: { value: '100' } });
    fireEvent.change(screen.getByLabelText(/yearly price/i), { target: { value: '1000' } });
    fireEvent.click(screen.getByRole('button', { name: 'OPD' }));
    fireEvent.click(screen.getAllByRole('button', { name: /create plan/i })[1]);

    await waitFor(() => expect(platformService.createPlan).toHaveBeenCalledWith(
      expect.objectContaining({ modules: ['OPD'] })
    ));
  });

  it('shows a controlled error and disables plan creation when the catalog is unavailable', async () => {
    platformService.getPlanCapabilities.mockRejectedValue(new Error('offline'));
    render(<PlansTab hospitalType="HOSPITAL" />);

    expect(await screen.findByText(/plan capabilities are unavailable/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create plan/i })).toBeDisabled();
  });
});
