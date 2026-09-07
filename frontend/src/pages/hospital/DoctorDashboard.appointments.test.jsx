import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * The doctor's Overview loaded appointments, then today's follow-ups, then (in solo mode) the
 * patient list, in one try block with a single await chain. An appointment 403 jumped straight
 * to the catch, so a doctor at a walk-in-only hospital silently lost their FOLLOW-UPS — the
 * return-visit mechanism that has nothing to do with appointments — with no error shown at all.
 */

const toastError = vi.fn();
vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError }),
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

const currentUser = { modules: [], name: 'Dr Mandal', receptionMode: 'HAS_RECEPTIONIST' };
vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => currentUser,
    isDoctor: () => true,
    isReceptionist: () => false,
    getProfile: vi.fn().mockResolvedValue({}),
    updateCurrentUser: () => null,
    logout: vi.fn(),
    getLoginUrl: () => '/login/hospital',
  },
}));

const forbidden = () =>
  Promise.reject(Object.assign(new Error('Forbidden'), { response: { status: 403 } }));

const hospitalService = {
  getMyAppointments: vi.fn(forbidden),
  getAppointmentStats: vi.fn(forbidden),
  getTodaysFollowUps: vi.fn().mockResolvedValue([{ id: 9, patientName: 'Asha Rao' }]),
  getPatients: vi.fn().mockResolvedValue({ content: [] }),
  getDoctorQueue: vi.fn().mockResolvedValue([]),
  getOpds: vi.fn().mockResolvedValue({ content: [] }),
  getDoctors: vi.fn().mockResolvedValue({ content: [] }),
  getIpdAdmissions: vi.fn().mockResolvedValue({ content: [] }),
  getDoctorProfile: vi.fn().mockResolvedValue({ id: 1, name: 'Dr Mandal' }),
};
vi.mock('../../services/hospitalService', () => ({ default: hospitalService }));

const renderDashboard = async (modules) => {
  currentUser.modules = modules;
  const { default: DoctorDashboard } = await import('./DoctorDashboard');
  return render(
    <MemoryRouter initialEntries={['/']}>
      <DoctorDashboard />
    </MemoryRouter>
  );
};

beforeEach(() => {
  vi.clearAllMocks();
  hospitalService.getMyAppointments.mockImplementation(forbidden);
  hospitalService.getTodaysFollowUps.mockResolvedValue([{ id: 9, patientName: 'Asha Rao' }]);
  hospitalService.getPatients.mockResolvedValue({ content: [] });
  hospitalService.getDoctorQueue.mockResolvedValue([]);
});

afterEach(() => vi.resetModules());

describe('DoctorDashboard — APPOINTMENTS disabled', () => {
  it('never calls an appointment endpoint', async () => {
    await renderDashboard(['OPD', 'IPD']);

    await waitFor(() => expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled());
    expect(hospitalService.getMyAppointments).not.toHaveBeenCalled();
  });

  it("still loads today's follow-ups", async () => {
    await renderDashboard(['OPD', 'IPD']);

    await waitFor(() => expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled());
    expect(hospitalService.getDoctorQueue).toHaveBeenCalled();
    expect(screen.getByText('Asha Rao')).toBeInTheDocument();
  });

  it('hides the Appointments navigation entry', async () => {
    await renderDashboard(['OPD', 'IPD']);

    await waitFor(() => expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled());
    expect(screen.queryByText('Appointments')).not.toBeInTheDocument();
  });

  it("renders no Today's Appointments panel or stat card", async () => {
    await renderDashboard(['OPD', 'IPD']);

    await waitFor(() => expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled());
    expect(screen.queryByText(/Today's Appointments/i)).not.toBeInTheDocument();
  });
});

describe('DoctorDashboard — APPOINTMENTS enabled', () => {
  it('shows the Appointments navigation entry and loads appointment data', async () => {
    hospitalService.getMyAppointments.mockResolvedValue({ content: [], totalElements: 0 });

    await renderDashboard(['OPD', 'IPD', 'APPOINTMENTS']);

    await waitFor(() => expect(hospitalService.getMyAppointments).toHaveBeenCalled());
    expect(screen.getAllByText('Appointments').length).toBeGreaterThan(0);
  });

  it('surfaces an enabled appointment failure while the queue and follow-ups still load', async () => {
    await renderDashboard(['OPD', 'IPD', 'APPOINTMENTS']);

    await waitFor(() => {
      expect(hospitalService.getMyAppointments).toHaveBeenCalled();
      expect(hospitalService.getTodaysFollowUps).toHaveBeenCalled();
      expect(hospitalService.getDoctorQueue).toHaveBeenCalled();
      expect(toastError).toHaveBeenCalledWith('Failed to load appointments');
    });
    expect(screen.getByText('Asha Rao')).toBeInTheDocument();
  });
});
