import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Reception is the role that broke worst when APPOINTMENTS was withheld: the appointment stats
 * call was the first statement of loadData, so a 403 there skipped the OPD queue, the patient
 * lookup, the doctor list, the IPD request count and today's follow-ups — the entire desk —
 * behind a single "Failed to load data" toast.
 *
 * These tests assert the two supported operational modes end to end at the component level:
 * with the module the appointment surfaces are present and populated, and without it they are
 * gone AND the rest of the desk still loads.
 */

const toastError = vi.fn();
const toastSuccess = vi.fn();
const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: toastSuccess, error: toastError }),
  ToastProvider: ({ children }) => children,
}));

vi.mock('../../hooks/useWebSocket', () => ({ default: () => ({ lastMessage: null }) }));
vi.mock('../../hooks/useEnabledVitals', () => ({
  default: () => ({ vitals: [], loading: false, isEnabled: () => true }),
}));
vi.mock('../../hooks/useOtPermissions', () => ({
  default: () => ({ can: () => false, loading: false, hasOt: false }),
}));
vi.mock('../../services/otService', () => ({
  default: { getRequests: vi.fn().mockResolvedValue([]), getBoard: vi.fn().mockResolvedValue([]) },
}));

const currentUser = { modules: [] };
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

// Every appointment endpoint rejects with 403, exactly as the backend answers a tenant whose
// plan does not include APPOINTMENTS. If any of them is still called ungated, or if a rejection
// can still escape into the shared load, these tests fail.
const forbidden = () =>
  Promise.reject(Object.assign(new Error('Forbidden'), { response: { status: 403 } }));

const hospitalService = {
  getAppointmentStats: vi.fn(forbidden),
  getAppointments: vi.fn(forbidden),
  getTodaysAppointments: vi.fn(forbidden),
  getDoctors: vi.fn().mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] }),
  getPatients: vi.fn().mockResolvedValue({ content: [{ id: 5, name: 'Asha Rao' }] }),
  getTodaysFollowUps: vi.fn().mockResolvedValue([{ id: 9, patientName: 'Asha Rao' }]),
  getHospitalQueue: vi.fn().mockResolvedValue([]),
  getDoctorQueue: vi.fn().mockResolvedValue([]),
  getOpds: vi.fn().mockResolvedValue({ content: [] }),
  getIpdAdmissions: vi.fn().mockResolvedValue({ content: [] }),
  getConsultationDetails: vi.fn().mockResolvedValue({}),
  getConsultationDetailsByOpd: vi.fn().mockResolvedValue({}),
};
vi.mock('../../services/hospitalService', () => ({ default: hospitalService }));

const renderDashboard = async (modules) => {
  currentUser.modules = modules;
  const { default: ReceptionistDashboard } = await import('./ReceptionistDashboard');
  return render(
    <MemoryRouter initialEntries={['/']}>
      <ReceptionistDashboard />
    </MemoryRouter>
  );
};

beforeEach(() => {
  vi.clearAllMocks();
  consoleError.mockClear();
  hospitalService.getAppointmentStats.mockImplementation(forbidden);
  hospitalService.getAppointments.mockImplementation(forbidden);
  hospitalService.getTodaysAppointments.mockImplementation(forbidden);
  hospitalService.getDoctors.mockResolvedValue({ content: [{ id: 1, name: 'Dr Mandal' }] });
  hospitalService.getPatients.mockResolvedValue({ content: [{ id: 5, name: 'Asha Rao' }] });
  hospitalService.getTodaysFollowUps.mockResolvedValue([{ id: 9, patientName: 'Asha Rao' }]);
  hospitalService.getHospitalQueue.mockResolvedValue([]);
});

afterEach(() => {
  vi.resetModules();
});

describe('ReceptionistDashboard — APPOINTMENTS disabled', () => {
  it('never calls an appointment endpoint', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING']);

    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());

    expect(hospitalService.getAppointmentStats).not.toHaveBeenCalled();
    expect(hospitalService.getAppointments).not.toHaveBeenCalled();
    expect(hospitalService.getTodaysAppointments).not.toHaveBeenCalled();
  });

  it('renders follow-ups and the queue while appointment requests remain skipped', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING']);

    // These are the loads that a 403 on appointments used to take down with it.
    await waitFor(() => {
      expect(hospitalService.getDoctors).toHaveBeenCalled();
      expect(hospitalService.getPatients).toHaveBeenCalled();
      expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled();
    });
    expect(hospitalService.getAppointmentStats).not.toHaveBeenCalled();
    expect(hospitalService.getAppointments).not.toHaveBeenCalled();
    expect(screen.getByText("Today's Follow-Ups")).toBeInTheDocument();
    expect(screen.getByText('Asha Rao')).toBeInTheDocument();
    expect(screen.getByText('Queue')).toBeInTheDocument();
  });

  it('does not report a load failure to the user', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING']);

    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());
    expect(toastError).not.toHaveBeenCalled();
  });

  it('hides the Appointments navigation entry', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING']);

    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());
    expect(screen.queryByText('Appointments')).not.toBeInTheDocument();
  });

  it('offers no way to schedule an appointment', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING']);

    await waitFor(() => expect(hospitalService.getDoctors).toHaveBeenCalled());
    expect(screen.queryByText(/Schedule Appointment/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Add Appointment/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Active Appointments/i)).not.toBeInTheDocument();
  });
});

describe('ReceptionistDashboard — APPOINTMENTS enabled', () => {
  it('shows the Appointments navigation entry', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING', 'APPOINTMENTS']);

    expect(screen.getAllByText('Appointments').length).toBeGreaterThan(0);
  });

  it('loads appointment data', async () => {
    hospitalService.getAppointmentStats.mockResolvedValue({ today: 4, pending: 2, total: 9 });
    hospitalService.getAppointments.mockResolvedValue({
      content: [],
      totalPages: 1,
      totalElements: 0,
    });

    await renderDashboard(['OPD', 'IPD', 'BILLING', 'APPOINTMENTS']);

    await waitFor(() => expect(hospitalService.getAppointmentStats).toHaveBeenCalled());
  });

  it('renders appointment and follow-up operational panels together', async () => {
    hospitalService.getAppointmentStats.mockResolvedValue({ today: 4, pending: 2, total: 9 });
    hospitalService.getAppointments.mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });

    await renderDashboard(['OPD', 'IPD', 'BILLING', 'APPOINTMENTS']);

    await waitFor(() => expect(screen.getByText("Today's Follow-Ups")).toBeInTheDocument());
    expect(screen.getByText('Asha Rao')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Appointments' })).toBeInTheDocument();
    expect(screen.getByText('Queue')).toBeInTheDocument();
  });

  it('surfaces an enabled appointment failure while doctors, patients, and follow-ups still load', async () => {
    await renderDashboard(['OPD', 'IPD', 'BILLING', 'APPOINTMENTS']);

    await waitFor(() => {
      expect(hospitalService.getAppointmentStats).toHaveBeenCalled();
      expect(hospitalService.getAppointments).toHaveBeenCalled();
      expect(hospitalService.getDoctors).toHaveBeenCalled();
      expect(hospitalService.getPatients).toHaveBeenCalled();
      expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled();
      expect(toastError).toHaveBeenCalledWith('Failed to load appointments');
    });
    expect(screen.getByText('Follow-ups')).toBeInTheDocument();
    expect(consoleError).toHaveBeenCalledWith(
      expect.stringContaining('Failed to load appointment'),
      expect.any(Error)
    );
  });
});
