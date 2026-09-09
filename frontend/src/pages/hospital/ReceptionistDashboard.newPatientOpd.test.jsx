import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * OPD "New Patient".
 *
 * Switching the Create OPD Entry modal to New Patient swaps the patient search for the
 * patient fields themselves (the shared PatientFormFields that PatientModal also renders),
 * so the user fills ONE form and one submit does two things: POST /hospital/patients via
 * hospitalService.addPatient, then POST /hospital/opd with the id that came back.
 *
 * What these tests pin down:
 *  - the existing search-and-select path is untouched and still the default,
 *  - New Patient shows the patient fields inline, with no second dialog,
 *  - one submit creates the patient and then the OPD, in that order, with that id,
 *  - the same client validation still guards the patient fields,
 *  - a failed patient creation never submits an OPD.
 */

const toastError = vi.fn();
const toastSuccess = vi.fn();
vi.spyOn(console, 'error').mockImplementation(() => {});
vi.spyOn(console, 'log').mockImplementation(() => {});

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError }),
  ToastProvider: ({ children }) => children,
}));

vi.mock('../../hooks/useWebSocket', () => ({ default: () => ({ lastMessage: null }) }));
// Vitals off keeps the OPD form to the fields these tests actually drive; they are all
// optional either way, so the create payload is unaffected.
vi.mock('../../hooks/useEnabledVitals', () => ({
  default: () => ({ isOn: () => false, customs: [], loaded: true }),
}));
vi.mock('../../hooks/useOtPermissions', () => ({
  default: () => ({ can: () => false, loading: false, hasOt: false }),
}));
vi.mock('../../services/otService', () => ({
  default: { getRequests: vi.fn().mockResolvedValue([]), getBoard: vi.fn().mockResolvedValue([]) },
}));

const currentUser = { id: 7, role: 'RECEPTIONIST', modules: ['OPD', 'BILLING'] };
vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => currentUser,
    isReceptionist: () => true,
    isDoctor: () => false,
    getProfile: vi.fn().mockResolvedValue({}),
    updateCurrentUser: () => null,
    logout: vi.fn(),
    getLoginUrl: () => '/login/hospital',
  },
}));

const existingPatient = { id: 5, name: 'Asha Rao', phone: '9990001111', customId: 'PAT5' };

const hospitalService = {
  getAppointmentStats: vi.fn().mockResolvedValue({}),
  getAppointments: vi.fn().mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 }),
  getTodaysAppointments: vi.fn().mockResolvedValue([]),
  getDoctors: vi.fn(),
  getPatients: vi.fn(),
  getTodaysFollowUps: vi.fn(),
  getHospitalQueue: vi.fn(),
  getDoctorQueue: vi.fn(),
  getOpds: vi.fn(),
  getIpdAdmissions: vi.fn(),
  getConsultationDetails: vi.fn().mockResolvedValue({}),
  getConsultationDetailsByOpd: vi.fn().mockResolvedValue({}),
  addPatient: vi.fn(),
  createOpd: vi.fn(),
};
vi.mock('../../services/hospitalService', () => ({ default: hospitalService }));

// Imported statically, not lazily inside a test: userEvent.setup() redefines
// HTMLElement.prototype.focus as a getter, and a component graph loaded after that
// point blows up when @react-aria (via @headlessui) tries to assign it.
const { default: ReceptionistDashboard } = await import('./ReceptionistDashboard');

const renderDashboard = async () => {
  render(
    <MemoryRouter initialEntries={['/']}>
      <ReceptionistDashboard />
    </MemoryRouter>
  );
  await waitFor(() => expect(hospitalService.getPatients).toHaveBeenCalled());
};

/** Opens the Create OPD Entry modal from the overview queue panel. */
const openOpdModal = async (user) => {
  await user.click(await screen.findByRole('button', { name: /Add OPD/i }));
  return screen.getByText('New OPD / Case').closest('div.fixed');
};

/** Fills the inline patient fields with the same values the Patients tab would take. */
const fillPatientFields = async (user, scope = screen) => {
  await user.type(scope.getByPlaceholderText(/full name/i), 'Neha Kulkarni');
  await user.type(scope.getByPlaceholderText(/phone number/i), '9876543210');
  await user.selectOptions(scope.getByLabelText('Day'), '12');
  await user.selectOptions(scope.getByLabelText('Month'), '03');
  await user.selectOptions(scope.getByLabelText('Year'), '1990');
  await user.selectOptions(scope.getByLabelText(/Gender/i), 'FEMALE');
};

beforeEach(() => {
  vi.clearAllMocks();
  currentUser.role = 'RECEPTIONIST';
  hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] });
  hospitalService.getPatients.mockResolvedValue({ content: [existingPatient] });
  hospitalService.getTodaysFollowUps.mockResolvedValue([]);
  hospitalService.getHospitalQueue.mockResolvedValue([]);
  hospitalService.getDoctorQueue.mockResolvedValue([]);
  hospitalService.getOpds.mockResolvedValue({ content: [] });
  hospitalService.getIpdAdmissions.mockResolvedValue({ content: [] });
  hospitalService.createOpd.mockResolvedValue({ id: 100, caseId: 'OPD-100' });
  hospitalService.addPatient.mockResolvedValue({
    id: 42,
    name: 'Neha Kulkarni',
    phone: '9876543210',
    customId: 'PAT42',
  });
});

describe('OPD modal — existing patient flow (unchanged)', () => {
  it('defaults to Existing Patient and still creates the OPD from a searched patient', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);

    expect(scope.getByRole('button', { name: 'Existing Patient' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
    expect(scope.getByRole('button', { name: 'New Patient' })).toHaveAttribute(
      'aria-pressed',
      'false'
    );

    await user.type(scope.getByPlaceholderText(/search patient/i), 'Asha');
    await user.click(await scope.findByText('Asha Rao'));
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 5 });
    expect(hospitalService.addPatient).not.toHaveBeenCalled();
  });
});

describe('OPD modal — new patient flow', () => {
  it('New Patient swaps the search for the patient fields, with no second dialog', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);

    expect(scope.getByPlaceholderText(/search patient/i)).toBeInTheDocument();
    await user.click(scope.getByRole('button', { name: 'New Patient' }));

    // One form: the search is gone, the patient fields are here, and no nested dialog opened.
    expect(scope.queryByPlaceholderText(/search patient/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Add New Patient')).not.toBeInTheDocument();
    expect(scope.getByText('New Patient Details')).toBeInTheDocument();
    expect(scope.getByPlaceholderText(/full name/i)).toBeInTheDocument();
    expect(scope.getByPlaceholderText(/phone number/i)).toBeInTheDocument();
    expect(scope.getByLabelText(/Gender/i)).toBeInTheDocument();
    expect(scope.getByLabelText('Year')).toBeInTheDocument();
    // Still the OPD form, and still one submit button.
    expect(scope.getByLabelText(/Problem \/ Reason/i)).toBeInTheDocument();
    expect(scope.getByRole('button', { name: /Create OPD/i })).toBeInTheDocument();
  });

  it('one submit creates the patient, then the OPD with the returned id', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);
    await user.click(scope.getByRole('button', { name: 'New Patient' }));

    await fillPatientFields(user, scope);
    await user.type(scope.getByLabelText(/Problem \/ Reason/i), 'Fever');
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));

    // The patient went through the existing service, with the existing payload shape.
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
    expect(hospitalService.addPatient.mock.calls[0][0]).toMatchObject({
      name: 'Neha Kulkarni',
      phone: '9876543210',
      dateOfBirth: '1990-03-12',
      gender: 'FEMALE',
    });
    // insurance is UI-only and must never be sent.
    expect(hospitalService.addPatient.mock.calls[0][0]).not.toHaveProperty('insurance');

    // Then the OPD, with the id that came back.
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({
      patientId: 42,
      problem: 'Fever',
    });
    expect(hospitalService.addPatient.mock.invocationCallOrder[0]).toBeLessThan(
      hospitalService.createOpd.mock.invocationCallOrder[0]
    );
  });

  it('does not submit an OPD when patient creation fails', async () => {
    hospitalService.addPatient.mockRejectedValue({
      response: { data: { error: 'Phone number must be 10 digits' } },
    });
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);
    await user.click(scope.getByRole('button', { name: 'New Patient' }));

    await fillPatientFields(user, scope);
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    await waitFor(() => expect(hospitalService.addPatient).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
    // The form stays open with what was typed, so nothing has to be re-entered.
    expect(screen.getByText('New OPD / Case')).toBeInTheDocument();
    expect(scope.getByPlaceholderText(/full name/i)).toHaveValue('Neha Kulkarni');
    expect(toastError).toHaveBeenCalledWith('Phone number must be 10 digits');
  });

  it('the shared client validation still guards the patient fields', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);
    await user.click(scope.getByRole('button', { name: 'New Patient' }));

    // Name, phone and date of birth, but no gender. Name and phone carry the native
    // required attribute; gender is only caught by the shared patientFormRules.
    await user.type(scope.getByPlaceholderText(/full name/i), 'Neha Kulkarni');
    await user.type(scope.getByPlaceholderText(/phone number/i), '9876543210');
    await user.selectOptions(scope.getByLabelText('Day'), '12');
    await user.selectOptions(scope.getByLabelText('Month'), '03');
    await user.selectOptions(scope.getByLabelText('Year'), '1990');
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    expect(hospitalService.addPatient).not.toHaveBeenCalled();
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith('Please correct the highlighted patient details');
  });

  it('switching back to Existing Patient restores the search', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);
    const scope = within(modal);

    await user.click(scope.getByRole('button', { name: 'New Patient' }));
    expect(scope.getByText('New Patient Details')).toBeInTheDocument();

    await user.click(scope.getByRole('button', { name: 'Existing Patient' }));
    expect(scope.queryByText('New Patient Details')).not.toBeInTheDocument();

    await user.type(scope.getByPlaceholderText(/search patient/i), 'Asha');
    await user.click(await scope.findByText('Asha Rao'));
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 5 });
    expect(hospitalService.addPatient).not.toHaveBeenCalled();
  });

  it('hides New Patient from a role that cannot create patients', async () => {
    currentUser.role = 'DOCTOR';
    const user = userEvent.setup();
    await renderDashboard();
    const modal = await openOpdModal(user);

    expect(within(modal).queryByRole('button', { name: 'New Patient' })).not.toBeInTheDocument();
    // The existing search path is unaffected by the gate.
    expect(within(modal).getByPlaceholderText(/search patient/i)).toBeInTheDocument();
  });
});
