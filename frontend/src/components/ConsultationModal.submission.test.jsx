import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// headlessui's combobox measures itself when it closes; jsdom has no ResizeObserver, and the
// resulting unhandled error leaks across tests in this file.
globalThis.ResizeObserver =
  globalThis.ResizeObserver ||
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };

vi.mock('./MedicineAutocomplete', () => ({
  default: ({ value, onChange, onSelect, onUnresolvedTextChange }) => (
    <div>
      <input
        placeholder="Search medicine from catalog..."
        value={value}
        onChange={(e) => {
          onChange('');
          if (onUnresolvedTextChange) onUnresolvedTextChange(e.target.value);
        }}
      />
      <button
        type="button"
        onClick={() => {
          if (onUnresolvedTextChange) onUnresolvedTextChange('');
          onSelect({ name: 'Paracetamol' });
        }}
      >
        pick Paracetamol
      </button>
      <button
        type="button"
        onClick={() => {
          if (onUnresolvedTextChange) onUnresolvedTextChange('');
          onSelect({ name: 'Azithromycin' });
        }}
      >
        pick Azithromycin
      </button>
    </div>
  ),
}));

const toastError = vi.fn();
const toastSuccess = vi.fn();

vi.mock('../context/ToastContext', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError, info: vi.fn() }),
}));

vi.mock('../services/authService', () => ({
  default: {
    getCurrentUser: () => ({
      role: 'DOCTOR',
      name: 'Dr Demo',
      modules: ['OPD', 'IPD', 'BILLING', 'MEDICAL_INVENTORY'],
      userId: 1,
    }),
  },
}));

vi.mock('../services/vitalsService', () => ({
  default: { enabled: vi.fn().mockResolvedValue([]) },
}));

vi.mock('../services/hospitalService', () => ({
  default: {
    submitConsultation: vi.fn(),
    getInventoryMedicines: vi.fn().mockResolvedValue([]),
    getHospitalFees: vi.fn().mockResolvedValue([]),
    getCustomFees: vi.fn().mockResolvedValue([]),
    getHospitalInventory: vi.fn().mockResolvedValue([]),
    getHospitalServices: vi.fn().mockResolvedValue([]),
    getInClinicPresets: vi.fn().mockResolvedValue([]),
    getPrescriptionPresets: vi.fn().mockResolvedValue([]),
    getNotePresets: vi.fn().mockResolvedValue([]),
    getConsultationNotePresets: vi.fn().mockResolvedValue([]),
    getPatientConsultationDetails: vi.fn().mockResolvedValue({
      patient: { name: 'Ravi Kumar', age: 46 },
      opdHistory: [],
      ipdHistory: [],
    }),
    searchMedicines: vi.fn().mockResolvedValue([]),
  },
}));

import hospitalService from '../services/hospitalService';
import ConsultationModal from './ConsultationModal';

/**
 * What the doctor's last click actually sends, and what they see when it fails.
 *
 * <p>The backend suite proves the server handles this payload; these prove the payload leaving
 * the browser is the one it was tested with, and that a refused consultation says so instead of
 * quietly emptying the form the doctor just spent five minutes filling.
 */
describe('ConsultationModal — submission', () => {
  const opd = { id: 42, patient: { id: 7, publicId: 'ppub-7' } };
  const patient = { id: 7, publicId: 'ppub-7', name: 'Ravi Kumar' };

  const open = () =>
    render(
      <ConsultationModal
        isOpen
        onClose={vi.fn()}
        onSuccess={vi.fn()}
        appointment={null}
        patient={patient}
        opd={opd}
      />
    );

  const openPrescriptionTab = async (user) => {
    await user.click(screen.getByRole('button', { name: /^Prescription \(/ }));
  };

  /** The catalogue field only yields a name when one is picked from the list, so pick one. */
  const addMedicine = async (user, { name, dosage, duration, frequency = true }) => {
    if (!screen.queryByPlaceholderText('Search medicine from catalog...')) {
      await openPrescriptionTab(user);
    }
    await user.type(screen.getByPlaceholderText('Search medicine from catalog...'), name);
    await user.click(screen.getByRole('button', { name: `pick ${name}` }));

    await user.type(screen.getByPlaceholderText('Dosage (e.g., 500mg)'), dosage);
    await user.type(screen.getByPlaceholderText('Duration (e.g., 5 Days)'), duration);
    if (frequency) {
      const morning = screen.getByLabelText('Morning dose');
      await user.clear(morning);
      await user.type(morning, '1');
    }
    await user.click(screen.getByRole('button', { name: '+ Add Medicine' }));
  };

  const submit = async (user) =>
    user.click(screen.getByRole('button', { name: /Complete Consultation/, hidden: true }));

  const payloadOf = () => hospitalService.submitConsultation.mock.calls[0][0];

  beforeEach(() => {
    vi.clearAllMocks();
    hospitalService.getInventoryMedicines.mockResolvedValue([]);
    hospitalService.submitConsultation.mockResolvedValue({ message: 'ok' });
  });

  it('sends the case, the diagnosis and a complete medicine row', async () => {
    const user = userEvent.setup();
    open();

    await user.type(screen.getByPlaceholderText('Enter diagnosis...'), 'Viral fever');
    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalledTimes(1));
    const payload = payloadOf();
    expect(payload.opdId).toBe(42);
    expect(payload.diagnosis).toBe('Viral fever');
    expect(payload.prescription).toHaveLength(1);
    expect(payload.prescription[0]).toMatchObject({
      medicineName: 'Paracetamol',
      dosage: '500mg',
      duration: '5 Days',
    });
    expect(payload.prescription[0].frequency).toBeTruthy();
    // The shapes the server is tested against must all be present, even when empty.
    expect(payload.administeredItems).toEqual([]);
    expect(Array.isArray(payload.charges)).toBe(true);
    expect(payload.hospitalInventoryItems).toEqual([]);
    expect(payload.labTests).toEqual([]);
  });

  it('serialises several medicines in order', async () => {
    const user = userEvent.setup();
    open();

    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await addMedicine(user, { name: 'Azithromycin', dosage: '250mg', duration: '3 Days' });
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalled());
    expect(payloadOf().prescription.map((p) => p.medicineName)).toEqual([
      'Paracetamol',
      'Azithromycin',
    ]);
  });

  it('serialises an administered medicine with the fields the server reads', async () => {
    hospitalService.getInventoryMedicines.mockResolvedValue([
      { id: 91, name: 'Saline 500ml', stockQuantity: 20, unitPrice: 40, isActive: true },
    ]);
    const user = userEvent.setup();
    open();

    const stockSearch = await screen.findByPlaceholderText(/Search active clinical stock/);
    await user.type(stockSearch, 'Saline');
    await user.click(await screen.findByRole('button', { name: /Saline 500ml/ }));

    // Given at the clinic, so the row needs a frequency like any other -- "As Per Required" is
    // what a nurse ticks for a one-off.
    await user.click(screen.getAllByLabelText('As Per Required')[0]);
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalled());
    const [item] = payloadOf().administeredItems;
    expect(item).toMatchObject({ medicineId: 91, medicineName: 'Saline 500ml', quantity: 1 });
    expect(item).toHaveProperty('dosage');
    expect(item).toHaveProperty('frequency');
    expect(item).toHaveProperty('duration');
    expect(item).toHaveProperty('instructions');
  });

  // -- refusals before the request ---------------------------------------------

  it('will not submit a medicine the doctor picked but never added', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    await user.click(screen.getByRole('button', { name: 'pick Paracetamol' }));
    await submit(user);

    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith(
      "Please click '+ Add Medicine' or clear the medicine fields before submitting."
    );
  });

  it('will not submit a medicine with no frequency, and says which one', async () => {
    const user = userEvent.setup();
    open();

    await addMedicine(user, {
      name: 'Paracetamol',
      dosage: '500mg',
      duration: '5 Days',
      frequency: false,
    });

    // Refused where the mistake was made, so the row never joins the prescription.
    expect(toastError).toHaveBeenCalledWith(expect.stringContaining('Set a frequency'));
    expect(screen.getByRole('button', { name: /^Prescription \(0\)/ })).toBeInTheDocument();

    // And the half-finished row still in the form blocks the submit, rather than being dropped.
    await submit(user);
    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith(
      "Please click '+ Add Medicine' or clear the medicine fields before submitting."
    );
  });

  it('lets the consultation through once a catalogue medicine is picked and added', async () => {
    const user = userEvent.setup();
    open();

    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalledTimes(1));
    expect(payloadOf().prescription).toHaveLength(1);
  });

  // -- admit to IPD -------------------------------------------------------------

  const admit = (user) =>
    user.click(screen.getByRole('button', { name: /Admit to IPD/, hidden: true }));

  it('asks for admission through the same consultation, with the flag set', async () => {
    const user = userEvent.setup();
    open();

    await user.type(screen.getByPlaceholderText('Enter diagnosis...'), 'Dengue suspected');
    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await admit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalledTimes(1));
    const payload = payloadOf();
    expect(payload.ipdAdmitRecommended).toBe(true);
    expect(payload.opdId).toBe(42);
    expect(payload.diagnosis).toBe('Dengue suspected');
    expect(payload.prescription).toHaveLength(1);
    expect(payload.administeredItems).toEqual([]);
  });

  it('will not ask for admission while a typed medicine is unresolved', async () => {
    const user = userEvent.setup();
    open();

    await openPrescriptionTab(user);
    await user.type(screen.getByPlaceholderText('Search medicine from catalog...'), 'Ascoril');
    await admit(user);

    expect(hospitalService.submitConsultation).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith(
      'Select the medicine from the suggestions, or clear the field, before submitting.'
    );
  });

  it('reports a refused admission request and keeps the consultation intact', async () => {
    hospitalService.submitConsultation.mockRejectedValue({
      response: { status: 400, data: { error: 'Prescription dosage is required' } },
    });
    const user = userEvent.setup();
    open();

    await user.type(screen.getByPlaceholderText('Enter diagnosis...'), 'Dengue suspected');
    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await admit(user);

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Prescription dosage is required'));
    expect(toastSuccess).not.toHaveBeenCalled();

    // Nothing announced, nothing lost: the doctor can correct it and press again.
    expect(screen.getByRole('button', { name: /Admit to IPD/, hidden: true })).not.toBeDisabled();
    expect(screen.getByRole('button', { name: /^Prescription \(1\)/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Clinical Notes/ }));
    expect(screen.getByPlaceholderText('Enter diagnosis...')).toHaveValue('Dengue suspected');
  });

  it('says nothing reassuring when the server fails outright', async () => {
    hospitalService.submitConsultation.mockRejectedValue({ response: { status: 500, data: {} } });
    const user = userEvent.setup();
    open();

    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await admit(user);

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Failed to submit consultation'));
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  // -- refusals from the server -------------------------------------------------

  it('shows what the server refused, not a generic failure', async () => {
    hospitalService.submitConsultation.mockRejectedValue({
      response: { status: 400, data: { error: 'Insufficient stock for: Paracetamol 500' } },
    });
    const user = userEvent.setup();
    open();

    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalled());
    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Insufficient stock for: Paracetamol 500')
    );
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it('stays safe when the server fails without saying anything useful', async () => {
    hospitalService.submitConsultation.mockRejectedValue({
      response: { status: 500, data: {} },
    });
    const user = userEvent.setup();
    open();

    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await submit(user);

    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalled());
    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Failed to submit consultation'));
    expect(toastSuccess).not.toHaveBeenCalled();
  });

  it('lets the doctor try again, with everything they typed still there', async () => {
    hospitalService.submitConsultation.mockRejectedValueOnce({
      response: { status: 400, data: { error: 'Insufficient stock for: Paracetamol 500' } },
    });
    const user = userEvent.setup();
    open();

    await user.type(screen.getByPlaceholderText('Enter diagnosis...'), 'Viral fever');
    await addMedicine(user, { name: 'Paracetamol', dosage: '500mg', duration: '5 Days' });
    await submit(user);

    await waitFor(() => expect(toastError).toHaveBeenCalled());

    // The button comes back, and nothing the doctor entered was thrown away.
    const button = await screen.findByRole('button', { name: /Complete Consultation/ });
    expect(button).not.toBeDisabled();
    expect(screen.getByRole('button', { name: /^Prescription \(1\)/ })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Clinical Notes/ }));
    expect(screen.getByPlaceholderText('Enter diagnosis...')).toHaveValue('Viral fever');

    hospitalService.submitConsultation.mockResolvedValue({ message: 'ok' });
    await user.click(button);
    await waitFor(() => expect(hospitalService.submitConsultation).toHaveBeenCalledTimes(2));
    expect(hospitalService.submitConsultation.mock.calls[1][0].diagnosis).toBe('Viral fever');
  });
});
