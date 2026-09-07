import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: '/hospital/receptionist', search: '' }),
  useSearchParams: () => [new URLSearchParams(''), vi.fn()],
}));

vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => ({
      role: 'RECEPTIONIST',
      name: 'Reception Desk',
      modules: ['OPD', 'IPD', 'BILLING', 'APPOINTMENTS'],
      userId: 2,
    }),
    getLoginUrl: () => '/login',
    logout: vi.fn(),
  },
}));

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }),
}));

vi.mock('../../hooks/useWebSocket', () => ({ default: () => {} }));

vi.mock('../../services/otService', () => ({
  default: { getRequests: vi.fn().mockResolvedValue([]), getBoard: vi.fn().mockResolvedValue([]) },
}));

vi.mock('../../services/hospitalService', () => ({
  default: {
    getAppointmentStats: vi.fn(),
    getPendingIpdRequestCount: vi.fn(),
    getAppointments: vi.fn(),
    getDoctors: vi.fn(),
    getPatients: vi.fn(),
    getTodaysFollowUps: vi.fn(),
    getOpds: vi.fn(),
    getAdmittedIpdAdmissions: vi.fn(),
    getPendingIpdRequests: vi.fn(),
    getDoctorQueue: vi.fn(),
    getHospitalQueue: vi.fn(),
    getBills: vi.fn(),
  },
}));

import hospitalService from '../../services/hospitalService';
import ReceptionistDashboard from './ReceptionistDashboard';

/**
 * The desk's screen is the one that decides whether a patient is turned away.
 *
 * <p>Every list here loaded through one try/catch that logged the failure and left the state
 * alone — [] on a first load. An unreachable server drew a morning with nobody booked, nobody
 * waiting and no follow-ups due, which is exactly what a genuinely quiet morning looks like.
 * A toast that fades after three seconds is not a state; the screen has to keep saying it.
 */
describe('ReceptionistDashboard — a failed load is not an empty desk', () => {
  const happyPath = () => {
    hospitalService.getAppointmentStats.mockResolvedValue({ total: 0, today: 0 });
    hospitalService.getPendingIpdRequestCount.mockResolvedValue(0);
    hospitalService.getAppointments.mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });
    hospitalService.getDoctors.mockResolvedValue({ content: [] });
    hospitalService.getPatients.mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });
    hospitalService.getTodaysFollowUps.mockResolvedValue([]);
    hospitalService.getOpds.mockResolvedValue({ content: [] });
    hospitalService.getAdmittedIpdAdmissions.mockResolvedValue([]);
    hospitalService.getPendingIpdRequests.mockResolvedValue({ content: [] });
    hospitalService.getDoctorQueue.mockResolvedValue([]);
    hospitalService.getHospitalQueue.mockResolvedValue([]);
    hospitalService.getBills.mockResolvedValue({ content: [] });
  };

  beforeEach(() => {
    vi.clearAllMocks();
    happyPath();
  });

  it('says nothing loaded when nothing loaded', async () => {
    hospitalService.getAppointmentStats.mockRejectedValue({
      response: { data: { error: 'Appointments service unavailable' } },
    });

    render(<ReceptionistDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load this screen");
    expect(alert).toHaveTextContent('Appointments service unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('stays quiet when the desk genuinely has nothing booked', async () => {
    render(<ReceptionistDashboard />);

    await waitFor(() => expect(hospitalService.getAppointmentStats).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('warns that what is on screen may be out of date rather than blanking it', async () => {
    hospitalService.getPatients.mockRejectedValue({
      response: { data: { error: 'Patient service unavailable' } },
    });

    render(<ReceptionistDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/may be out of date/);
  });

  it('never shows a raw server exception at the desk', async () => {
    hospitalService.getAppointmentStats.mockRejectedValue(
      new Error('java.lang.NullPointerException at com.hms.service.AppointmentService')
    );

    render(<ReceptionistDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).not.toHaveTextContent('NullPointerException');
    expect(alert).toHaveTextContent(/Couldn't load this screen/);
  });

  it('clears the error once the retry succeeds', async () => {
    const user = userEvent.setup();
    hospitalService.getAppointmentStats
      .mockRejectedValueOnce({ response: { data: { error: 'Temporary failure' } } })
      .mockResolvedValue({ total: 0, today: 0 });

    render(<ReceptionistDashboard />);
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  /** A failed follow-up load must not read as "nobody is due back today". */
  it('does not present a failed follow-up load as nobody due', async () => {
    hospitalService.getTodaysFollowUps.mockRejectedValue({
      response: { data: { error: 'Follow-up service unavailable' } },
    });

    render(<ReceptionistDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Follow-up service unavailable');
  });
});
