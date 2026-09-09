import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The admin Create OPD Case modal gets the same inline "New Patient" option as reception:
 * the patient search is swapped for the shared PatientFormFields, and one submit creates the
 * patient via hospitalService.addPatient and then the OPD with the id it returned. Smaller
 * mirror of ReceptionistDashboard.newPatientOpd.test.jsx.
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

const currentUser = {
  id: 3,
  role: 'HOSPITAL_ADMIN',
  name: 'Admin',
  modules: ['OPD', 'BILLING'],
  tenantType: 'HOSPITAL',
};
vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => currentUser,
    isHospitalAdmin: () => true,
    isDoctor: () => false,
    getProfile: vi.fn().mockResolvedValue({}),
    updateCurrentUser: () => null,
    logout: vi.fn(),
    getLoginUrl: () => '/login/hospital',
  },
}));

const existingPatient = { id: 5, name: 'Asha Rao', phone: '9990001111', age: 34, gender: 'FEMALE' };

const hospitalService = {
  getAppointmentStats: vi.fn().mockResolvedValue({}),
  getGlobalStats: vi.fn().mockResolvedValue({}),
  getOpds: vi.fn(),
  getDoctors: vi.fn(),
  getPatients: vi.fn(),
  addPatient: vi.fn(),
  createOpd: vi.fn(),
};
vi.mock('../../services/hospitalService', () => ({ default: hospitalService }));

// Side services this tab never touches: every method resolves empty so nothing here
// depends on their real shape.
const emptyApi = () => ({ default: new Proxy({}, { get: () => vi.fn().mockResolvedValue([]) }) });
vi.mock('../../services/wardService', () => emptyApi());
vi.mock('../../services/timeSlotService', () => emptyApi());
vi.mock('../../services/pharmacy/branchesApi', () => emptyApi());
vi.mock('../../services/pharmacy/inventoryApi', () => emptyApi());
vi.mock('../../services/pharmacy/reportsApi', () => emptyApi());
vi.mock('../../services/pharmacy/salesApi', () => emptyApi());

// Static import: userEvent.setup() redefines HTMLElement.prototype.focus as a getter, which
// breaks @react-aria if the component graph is loaded after that point.
const { default: HospitalAdminDashboard } = await import('./HospitalAdminDashboard');

const renderOpdTab = async () => {
  render(
    <MemoryRouter initialEntries={['/hospital/admin?tab=opd']}>
      <HospitalAdminDashboard />
    </MemoryRouter>
  );
  await waitFor(() => expect(hospitalService.getOpds).toHaveBeenCalled());
};

const openOpdModal = async (user) => {
  await user.click(await screen.findByRole('button', { name: /New OPD/i }));
  return (await screen.findByText('New OPD Case')).closest('div.fixed');
};

const fillPatientFields = async (user, scope) => {
  await user.type(scope.getByPlaceholderText(/full name/i), 'Neha Kulkarni');
  await user.type(scope.getByPlaceholderText(/phone number/i), '9876543210');
  await user.selectOptions(scope.getByLabelText('Day'), '12');
  await user.selectOptions(scope.getByLabelText('Month'), '03');
  await user.selectOptions(scope.getByLabelText('Year'), '1990');
  await user.selectOptions(scope.getByLabelText(/Gender/i), 'FEMALE');
};

beforeEach(() => {
  vi.clearAllMocks();
  hospitalService.getOpds.mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });
  hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] });
  hospitalService.getPatients.mockResolvedValue({ content: [existingPatient] });
  hospitalService.createOpd.mockResolvedValue({ id: 100, caseId: 'OPD-100' });
  hospitalService.addPatient.mockResolvedValue({
    id: 42,
    name: 'Neha Kulkarni',
    phone: '9876543210',
    customId: 'PAT42',
  });
});

describe('Admin OPD modal — new patient', () => {
  it('still creates the OPD from a searched existing patient', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    const scope = within(await openOpdModal(user));

    expect(scope.getByRole('button', { name: 'Existing Patient' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );

    await user.type(scope.getByPlaceholderText(/patient name to search/i), 'Asha');
    await user.click(await scope.findByText('Asha Rao'));
    await user.click(scope.getByRole('button', { name: /Create OPD Case/i }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 5 });
    expect(hospitalService.addPatient).not.toHaveBeenCalled();
  });

  it('New Patient swaps the search for the patient fields, with no second dialog', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    const scope = within(await openOpdModal(user));

    await user.click(scope.getByRole('button', { name: 'New Patient' }));

    expect(scope.queryByPlaceholderText(/patient name to search/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Add New Patient')).not.toBeInTheDocument();
    expect(scope.getByText('New Patient Details')).toBeInTheDocument();
    expect(scope.getByPlaceholderText(/full name/i)).toBeInTheDocument();
    expect(scope.getByRole('button', { name: /Create OPD Case/i })).toBeInTheDocument();
  });

  it('one submit creates the patient, then the OPD with the returned id', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    const scope = within(await openOpdModal(user));

    await user.click(scope.getByRole('button', { name: 'New Patient' }));
    await fillPatientFields(user, scope);
    await user.click(scope.getByRole('button', { name: /Create OPD Case/i }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
    expect(hospitalService.addPatient.mock.calls[0][0]).toMatchObject({
      name: 'Neha Kulkarni',
      phone: '9876543210',
      dateOfBirth: '1990-03-12',
      gender: 'FEMALE',
    });
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 42 });
    expect(hospitalService.addPatient.mock.invocationCallOrder[0]).toBeLessThan(
      hospitalService.createOpd.mock.invocationCallOrder[0]
    );
  });

  it('does not submit an OPD when patient creation fails', async () => {
    hospitalService.addPatient.mockRejectedValue({ response: { data: { error: 'Boom' } } });
    const user = userEvent.setup();
    await renderOpdTab();
    const scope = within(await openOpdModal(user));

    await user.click(scope.getByRole('button', { name: 'New Patient' }));
    await fillPatientFields(user, scope);
    await user.click(scope.getByRole('button', { name: /Create OPD Case/i }));

    await waitFor(() => expect(hospitalService.addPatient).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
    expect(screen.getByText('New OPD Case')).toBeInTheDocument();
    expect(scope.getByPlaceholderText(/full name/i)).toHaveValue('Neha Kulkarni');
  });
});
