import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: '/hospital/doctor', search: '' }),
  useSearchParams: () => [new URLSearchParams(''), vi.fn()],
}));

vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => ({ role: 'DOCTOR', name: 'Dr Demo', modules: ['OPD', 'IPD'], userId: 1 }),
    getLoginUrl: () => '/login',
    logout: vi.fn(),
  },
}));

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

vi.mock('../../hooks/useWebSocket', () => ({ default: () => {} }));
vi.mock('../../services/hospitalService', () => ({
  default: {
    getDoctorQueue: vi.fn(),
    getMyAppointments: vi.fn().mockResolvedValue({ content: [], totalElements: 0 }),
    getTodaysFollowUps: vi.fn().mockResolvedValue([]),
    getOpds: vi.fn(),
    getPatients: vi.fn().mockResolvedValue({ content: [] }),
  },
}));

import hospitalService from '../../services/hospitalService';
import DoctorDashboard from './DoctorDashboard';

/**
 * "No patients waiting" and "we could not reach the server" call for opposite responses from a
 * doctor, and only one of them is ever true. The queue loader used to catch its failure and set
 * an empty list, so an outage looked exactly like a quiet clinic — on the screen a doctor trusts
 * most.
 */
describe('DoctorDashboard — a failed load is not an empty clinic', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows an error with a retry when the queue cannot be loaded', async () => {
    hospitalService.getDoctorQueue.mockRejectedValue({
      response: { data: { error: 'Queue service unavailable' } },
    });

    render(<DoctorDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load your patients");
    expect(alert).toHaveTextContent('Queue service unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('does not raise an error for a genuinely empty queue', async () => {
    hospitalService.getDoctorQueue.mockResolvedValue([]);

    render(<DoctorDashboard />);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('clears the error once a retry succeeds', async () => {
    const user = userEvent.setup();
    hospitalService.getDoctorQueue
      .mockRejectedValueOnce({ response: { data: { error: 'Temporary failure' } } })
      .mockResolvedValue([]);

    render(<DoctorDashboard />);
    await screen.findByRole('alert');

    await user.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  /** The banner must not hide a raw backend stack trace or internal detail. */
  it('shows a professional message, not a raw error object', async () => {
    hospitalService.getDoctorQueue.mockRejectedValue(new Error('java.lang.NullPointerException at com.hms'));

    render(<DoctorDashboard />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load your patients");
    expect(alert.textContent).not.toContain('com.hms');
  });
});
