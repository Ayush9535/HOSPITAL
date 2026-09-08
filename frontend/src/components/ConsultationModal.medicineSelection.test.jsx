import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// headlessui's combobox measures itself when it closes; jsdom has no ResizeObserver.
globalThis.ResizeObserver =
  globalThis.ResizeObserver ||
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };

const toastError = vi.fn();

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, info: vi.fn() }),
}));

vi.mock('../services/authService', () => ({
  default: {
    getCurrentUser: () => ({ role: 'DOCTOR', name: 'Dr Demo', modules: ['OPD'], userId: 1 }),
  },
}));

vi.mock('../services/vitalsService', () => ({
  default: { enabled: vi.fn().mockResolvedValue([]) },
}));

vi.mock('../services/hospitalService', () => ({
  default: {
    submitConsultation: vi.fn().mockResolvedValue({ message: 'ok' }),
    searchMedicines: vi.fn().mockResolvedValue([]),
    getInventoryMedicines: vi.fn().mockResolvedValue([]),
    getHospitalFees: vi.fn().mockResolvedValue([]),
    getCustomFees: vi.fn().mockResolvedValue([]),
    getHospitalInventory: vi.fn().mockResolvedValue([]),
    getHospitalServices: vi.fn().mockResolvedValue([]),
    getInClinicPresets: vi.fn().mockResolvedValue([]),
    getPrescriptionPresets: vi.fn().mockResolvedValue([]),
    getConsultationNotePresets: vi.fn().mockResolvedValue([]),
    getPatientConsultationDetails: vi
      .fn()
      .mockResolvedValue({
        patient: { name: 'Ravi Kumar', age: 46 },
        opdHistory: [],
        ipdHistory: [],
      }),
  },
}));

import hospitalService from '../services/hospitalService';
import ConsultationModal from './ConsultationModal';

/**
 * The medicine field names nothing until something is picked from the catalogue.
 *
 * <p>A doctor who types "Ascoril" and reaches for Complete has, as far as the software is
 * concerned, prescribed nothing — and the field wipes itself as soon as focus leaves, so there is
 * nothing left on screen to contradict them. These run against the real combobox, because the
 * behaviour under test is that widget's own.
 */
describe('ConsultationModal — catalogue selection is required', () => {
  const open = () =>
    render(
      <ConsultationModal
        isOpen
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        appointment={null}
        patient={{ id: 7, publicId: 'ppub-7', name: 'Ravi Kumar' }}
        opd={{ id: 42, patient: { id: 7, publicId: 'ppub-7' } }}
      />
    );

  const openPrescriptionTab = (user) =>
    user.click(screen.getByRole('button', { name: /^Prescription \(/ }));

  const submit = (user) =>
    user.click(screen.getByRole('button', { name: /Complete Consultation/, hidden: true }));

  beforeEach(() => {
    vi.clearAllMocks();
    hospitalService.submitConsultation.mockResolvedValue({ message: 'ok' });
    hospitalService.searchMedicines.mockResolvedValue([]);
  });

  it('says so under the field as soon as text is typed but nothing is picked', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    await user.type(screen.getByPlaceholderText('Search medicine from catalog...'), 'Ascoril');

    // By text, not by role: the open dropdown hides the rest of the page from the a11y tree.
    const message = await screen.findByText(/Select a medicine from the suggestions/);
    expect(message).toHaveAttribute('role', 'alert');
    // Same reason for hidden: the dropdown is open over it.
    expect(screen.getByRole('button', { name: '+ Add Medicine', hidden: true })).toBeDisabled();
  });

  it('refuses to complete while that typed medicine is unresolved', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    await user.type(screen.getByPlaceholderText('Search medicine from catalog...'), 'Ascoril');
    await submit(user);

    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith(
      'Select the medicine from the suggestions, or clear the field, before submitting.'
    );
  });

  /** The blur that empties the field must not also empty the warning. */
  it('still refuses after the field has wiped itself on blur', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    const field = screen.getByPlaceholderText('Search medicine from catalog...');
    await user.type(field, 'Ascoril');
    await user.click(screen.getByPlaceholderText('Dosage (e.g., 500mg)'));

    expect(field).toHaveValue('');
    await submit(user);
    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();
  });

  it('lets the consultation through once the doctor comes back to the empty field', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    const field = screen.getByPlaceholderText('Search medicine from catalog...');
    await user.type(field, 'Ascoril');
    await submit(user);
    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();

    // Returning to an empty field is the way out: they saw the warning and chose to move on.
    await user.click(field);
    await submit(user);
    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalledTimes(1));
  });

  it('lets the consultation through once a catalogue medicine is picked', async () => {
    hospitalService.searchMedicines.mockResolvedValue([
      { id: 5, name: 'Paracetamol 500', type: 'Tablet' },
    ]);
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    await user.type(screen.getByPlaceholderText('Search medicine from catalog...'), 'Paracet');
    await user.click(await screen.findByRole('option', { name: /Paracetamol 500/ }));

    expect(screen.queryByText(/Select a medicine from the suggestions/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ Add Medicine', hidden: true })).toBeEnabled();
  });
});
