import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The OPD intake meets a mobile number that already belongs to a patient here.
 *
 * A phone number is a lookup key, not an identity — a parent and a child legitimately share one
 * mobile — so the server refuses to guess and answers 409 with the candidates. These tests pin
 * what reception then sees and what each of the two answers actually does:
 *
 *  - the matches are listed, all of them, with no patient created yet,
 *  - "Use This Patient" runs the OPD against that exact id and registers nobody,
 *  - a family member can be picked individually, not just whoever happens to be first,
 *  - "Register Different Patient" resubmits with an explicit acknowledgement,
 *  - the typed form survives the detour either way.
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

const parent = { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 };
const child = { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 };

/** The shape the API answers with when the typed number is already registered here. */
const duplicatePhone409 = (conflicts) => ({
  response: {
    status: 409,
    data: {
      success: false,
      code: 'CONFLICT',
      error: 'This mobile number is already registered to a patient at this hospital.',
      conflicts,
    },
  },
});

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

const { default: ReceptionistDashboard } = await import('./ReceptionistDashboard');

const renderDashboard = async () => {
  render(
    <MemoryRouter initialEntries={['/']}>
      <ReceptionistDashboard />
    </MemoryRouter>
  );
  await waitFor(() => expect(hospitalService.getPatients).toHaveBeenCalled());
};

const openOpdModal = async (user) => {
  await user.click(await screen.findByRole('button', { name: /Add OPD/i }));
  return screen.getByText('New OPD / Case').closest('div.fixed');
};

const fillPatientFields = async (user, scope) => {
  await user.type(scope.getByPlaceholderText(/full name/i), 'Aarav Patil');
  await user.type(scope.getByPlaceholderText(/phone number/i), '9876500001');
  await user.selectOptions(scope.getByLabelText('Day'), '21');
  await user.selectOptions(scope.getByLabelText('Month'), '09');
  await user.selectOptions(scope.getByLabelText('Year'), '2017');
  await user.selectOptions(scope.getByLabelText(/Gender/i), 'MALE');
};

/** Fills the intake as a new patient and submits into a duplicate-phone conflict. */
const submitIntoConflict = async (user, conflicts) => {
  hospitalService.addPatient.mockRejectedValueOnce(duplicatePhone409(conflicts));
  const modal = await openOpdModal(user);
  const scope = within(modal);
  await user.click(scope.getByRole('button', { name: 'New Patient' }));
  await fillPatientFields(user, scope);
  await user.type(scope.getByLabelText(/Problem \/ Reason/i), 'Fever');
  await user.click(scope.getByRole('button', { name: /Create OPD/i }));
  await screen.findByText('Mobile number already registered');
};

beforeEach(() => {
  vi.clearAllMocks();
  hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] });
  hospitalService.getPatients.mockResolvedValue({ content: [] });
  hospitalService.getTodaysFollowUps.mockResolvedValue([]);
  hospitalService.getHospitalQueue.mockResolvedValue([]);
  hospitalService.getDoctorQueue.mockResolvedValue([]);
  hospitalService.getOpds.mockResolvedValue({ content: [] });
  hospitalService.getIpdAdmissions.mockResolvedValue({ content: [] });
  hospitalService.createOpd.mockResolvedValue({ id: 100, caseId: 'OPD-100' });
});

describe('OPD intake — duplicate phone', () => {
  it('lists every patient on the number instead of failing with a toast', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent, child]);

    expect(screen.getByText('Rahul Patil')).toBeInTheDocument();
    expect(screen.getByText('Patient ID PAT101')).toBeInTheDocument();
    expect(screen.getByText('Aarav Patil')).toBeInTheDocument();
    expect(screen.getByText('Patient ID PAT145')).toBeInTheDocument();

    // A question, not an error: nothing was created and nothing was reported as broken.
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
    expect(toastError).not.toHaveBeenCalled();
  });

  /**
   * A single match is still a question. This is the case the old appointment path answered by
   * itself, and answering it wrong files the child's case under the parent.
   */
  it('asks even when only one patient holds the number', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent]);

    expect(screen.getAllByRole('button', { name: 'Use This Patient' })).toHaveLength(1);
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
  });

  it('"Use This Patient" runs the OPD against that patient and registers nobody', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent, child]);

    const parentRow = screen.getByText('Rahul Patil').closest('div.flex');
    await user.click(within(parentRow).getByRole('button', { name: 'Use This Patient' }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({
      patientId: 101,
      problem: 'Fever',
    });
    // One attempt, the one that was refused. No second patient exists anywhere.
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
  });

  /**
   * The whole reason the matches are listed rather than resolved: the child can be chosen on
   * their own, which "the first patient with this number" could never do.
   */
  it('a family member can be picked individually', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent, child]);

    const childRow = screen.getByText('Aarav Patil').closest('div.flex');
    await user.click(within(childRow).getByRole('button', { name: 'Use This Patient' }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 145 });
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
  });

  it('"Register Different Patient" resubmits with an explicit acknowledgement', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent]);

    hospitalService.addPatient.mockResolvedValueOnce({
      id: 146,
      name: 'Aarav Patil',
      phone: '9876500001',
      customId: 'PAT146',
    });
    await user.click(screen.getByRole('button', { name: 'Register Different Patient' }));

    await waitFor(() => expect(hospitalService.addPatient).toHaveBeenCalledTimes(2));

    // The first attempt asked for nothing; only the second, after a human said so, acknowledges.
    expect(hospitalService.addPatient.mock.calls[0][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
    expect(hospitalService.addPatient.mock.calls[1][1]).toMatchObject({
      acknowledgeDuplicatePhone: true,
    });
    // Same person, retyped by nobody.
    expect(hospitalService.addPatient.mock.calls[1][0]).toMatchObject({
      name: 'Aarav Patil',
      phone: '9876500001',
    });

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 146 });
  });

  it('cancelling leaves the intake form exactly as it was typed', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    await submitIntoConflict(user, [parent]);

    const chooser = screen.getByRole('dialog', { name: 'Mobile number already registered' });
    await user.click(within(chooser).getByRole('button', { name: 'Cancel' }));

    const modal = screen.getByText('New OPD / Case').closest('div.fixed');
    const scope = within(modal);
    expect(scope.getByPlaceholderText(/full name/i)).toHaveValue('Aarav Patil');
    expect(scope.getByPlaceholderText(/phone number/i)).toHaveValue('9876500001');
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
  });

  /** An ordinary failure is still an ordinary failure, and must not open the chooser. */
  it('a non-conflict failure still reports an error', async () => {
    const user = userEvent.setup();
    await renderDashboard();
    hospitalService.addPatient.mockRejectedValueOnce({
      response: { status: 400, data: { error: 'Phone number must be exactly 10 digits' } },
    });

    const modal = await openOpdModal(user);
    const scope = within(modal);
    await user.click(scope.getByRole('button', { name: 'New Patient' }));
    await fillPatientFields(user, scope);
    await user.click(scope.getByRole('button', { name: /Create OPD/i }));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(screen.queryByText('Mobile number already registered')).not.toBeInTheDocument();
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
  });
});
