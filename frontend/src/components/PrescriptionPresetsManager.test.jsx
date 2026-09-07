import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

// MedicineAutocomplete does its own fetching; stub it to a plain input.
vi.mock('./MedicineAutocomplete', () => ({
  default: ({ value, onChange }) => (
    <input aria-label="medicine" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

vi.mock('../services/hospitalService', () => ({
  default: {
    getPrescriptionPresets: vi.fn(),
    createPrescriptionPreset: vi.fn(),
    updatePrescriptionPreset: vi.fn(),
    deletePrescriptionPreset: vi.fn(),
    getDoctors: vi.fn(),
  },
}));

import hospitalService from '../services/hospitalService';
import PrescriptionPresetsManager from './PrescriptionPresetsManager';

describe('PrescriptionPresetsManager', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    hospitalService.getPrescriptionPresets.mockResolvedValue([
      {
        id: 1,
        name: 'Fever Protocol',
        items: [{ medicineName: 'Paracetamol' }],
        displayOrder: 0,
        doctorId: null,
        doctorName: null,
      },
    ]);
    hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 7, name: 'Dr House' }] });
  });

  it('loads and lists existing presets with their medicines', async () => {
    render(<PrescriptionPresetsManager />);
    expect(await screen.findByText('Fever Protocol')).toBeInTheDocument();
    expect(screen.getByText(/Paracetamol/)).toBeInTheDocument();
  });

  it('opens the create form and creates a preset', async () => {
    hospitalService.createPrescriptionPreset.mockResolvedValue({
      id: 2,
      name: 'Cold Pack',
      items: [{ medicineName: 'Cetirizine' }],
      displayOrder: 1,
    });
    render(<PrescriptionPresetsManager />);
    await screen.findByText('Fever Protocol');

    fireEvent.click(screen.getByRole('button', { name: /create preset/i }));
    fireEvent.change(screen.getByPlaceholderText(/Preset name/i), {
      target: { value: 'Cold Pack' },
    });
    fireEvent.change(screen.getByLabelText('medicine'), { target: { value: 'Cetirizine' } });
    fireEvent.click(screen.getByRole('button', { name: /^create preset$/i }));

    await waitFor(() =>
      expect(hospitalService.createPrescriptionPreset).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'Cold Pack' })
      )
    );
  });

  it('exposes the doctor assignment dropdown for admins', async () => {
    render(<PrescriptionPresetsManager isAdmin />);
    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: /create preset/i }));
    expect(screen.getByRole('combobox')).toBeInTheDocument();
    expect(screen.getByText(/Shared \(all doctors\)/i)).toBeInTheDocument();
  });
});
