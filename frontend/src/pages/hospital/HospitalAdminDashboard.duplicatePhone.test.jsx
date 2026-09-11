import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';

/**
 * The admin Create OPD Case modal meets an already-registered mobile number.
 *
 * Same rule as reception, and the same reason: a phone number is a lookup key, not an identity.
 * Admins register patients too, so the chooser has to be here as well — otherwise the one path
 * that skips it becomes the path staff use when the other one gets in their way.
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

const parent = { id: 101, publicId: 'pub-101', customId: 'PAT101', name: 'Rahul Patil', age: 38 };
const child = { id: 145, publicId: 'pub-145', customId: 'PAT145', name: 'Aarav Patil', age: 8 };

const duplicatePhone409 = (conflicts) => ({
  response: { status: 409, data: { code: 'CONFLICT', error: 'already registered', conflicts } },
});

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
  await user.type(scope.getByPlaceholderText(/full name/i), 'Aarav Patil');
  await user.type(scope.getByPlaceholderText(/phone number/i), '9876500001');
  await user.selectOptions(scope.getByLabelText('Day'), '21');
  await user.selectOptions(scope.getByLabelText('Month'), '09');
  await user.selectOptions(scope.getByLabelText('Year'), '2017');
  await user.selectOptions(scope.getByLabelText(/Gender/i), 'MALE');
};

beforeEach(() => {
  vi.clearAllMocks();
  hospitalService.getOpds.mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });
  hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] });
  hospitalService.getPatients.mockResolvedValue({ content: [] });
  hospitalService.createOpd.mockResolvedValue({ id: 100, caseId: 'OPD-100' });
});

/** Fills the intake as a new patient and submits it into a duplicate-phone conflict. */
const submitIntoConflict = async (user, conflicts) => {
  hospitalService.addPatient.mockRejectedValueOnce(duplicatePhone409(conflicts));
  const scope = within(await openOpdModal(user));
  await user.click(scope.getByRole('button', { name: 'New Patient' }));
  await fillPatientFields(user, scope);
  await user.click(scope.getByRole('button', { name: /Create OPD Case/i }));
  await screen.findByText('Mobile number already registered');
};

describe('Admin OPD intake — duplicate phone', () => {
  it('offers the patients on that number instead of failing', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    await submitIntoConflict(user, [parent, child]);

    expect(screen.getByText('Rahul Patil')).toBeInTheDocument();
    expect(screen.getByText('Patient ID PAT101')).toBeInTheDocument();
    expect(screen.getByText('Aarav Patil')).toBeInTheDocument();
    expect(hospitalService.createOpd).not.toHaveBeenCalled();
    expect(toastError).not.toHaveBeenCalled();
  });

  it('"Use This Patient" runs the OPD against that patient and registers nobody', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    await submitIntoConflict(user, [parent, child]);

    const childRow = screen.getByText('Aarav Patil').closest('div.flex');
    await user.click(within(childRow).getByRole('button', { name: 'Use This Patient' }));

    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 145 });
    expect(hospitalService.addPatient).toHaveBeenCalledTimes(1);
  });

  it('"Register Different Patient" resubmits with an explicit acknowledgement', async () => {
    const user = userEvent.setup();
    await renderOpdTab();
    await submitIntoConflict(user, [parent]);

    hospitalService.addPatient.mockResolvedValueOnce({
      id: 146,
      name: 'Aarav Patil',
      phone: '9876500001',
      customId: 'PAT146',
    });
    await user.click(screen.getByRole('button', { name: 'Register Different Patient' }));

    await waitFor(() => expect(hospitalService.addPatient).toHaveBeenCalledTimes(2));
    expect(hospitalService.addPatient.mock.calls[0][1]).toMatchObject({
      acknowledgeDuplicatePhone: false,
    });
    expect(hospitalService.addPatient.mock.calls[1][1]).toMatchObject({
      acknowledgeDuplicatePhone: true,
    });
    await waitFor(() => expect(hospitalService.createOpd).toHaveBeenCalledTimes(1));
    expect(hospitalService.createOpd.mock.calls[0][0]).toMatchObject({ patientId: 146 });
  });
});
