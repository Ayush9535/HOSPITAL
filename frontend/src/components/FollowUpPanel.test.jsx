import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/hospitalService', () => ({
  default: {
    getFollowUps: vi.fn(),
    arriveFollowUp: vi.fn(),
    rescheduleFollowUp: vi.fn(),
    completeFollowUp: vi.fn(),
    cancelFollowUp: vi.fn(),
  },
}));

const toast = { success: vi.fn(), error: vi.fn() };
vi.mock('../context/ToastContext', () => ({ useToast: () => toast }));

import hospitalService from '../services/hospitalService';
import FollowUpPanel from './FollowUpPanel';

const row = (over = {}) => ({
  medicalRecordId: 1,
  patientName: 'Asha Rao',
  patientCustomId: 'P-100',
  patientPhone: '9900000000',
  doctorName: 'Dr Mehta',
  followUpDate: '2026-09-01',
  followUpInstructions: 'Bring the BP diary',
  diagnosis: 'Hypertension',
  status: 'OPEN',
  timing: 'DUE_TODAY',
  daysOverdue: 0,
  ...over,
});

const openPanel = async (props = {}) => {
  render(<FollowUpPanel role="RECEPTIONIST" {...props} />);
  await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalled());
};

describe('FollowUpPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(console, 'error').mockImplementation(() => {});
    hospitalService.getFollowUps.mockResolvedValue([row()]);
  });

  // ── the three buckets ──────────────────────────────────────────────────

  it('lists follow-ups due today', async () => {
    await openPanel();
    expect(await screen.findByText('Asha Rao')).toBeInTheDocument();
    expect(hospitalService.getFollowUps).toHaveBeenCalledWith(
      expect.objectContaining({ timing: 'DUE_TODAY' }),
    );
  });

  it('switches to overdue and reports how late each is', async () => {
    const user = userEvent.setup();
    await openPanel();
    hospitalService.getFollowUps.mockResolvedValue([row({ timing: 'OVERDUE', daysOverdue: 3 })]);

    await user.click(screen.getByRole('tab', { name: 'Overdue' }));

    expect(await screen.findByText('3 days overdue')).toBeInTheDocument();
    expect(hospitalService.getFollowUps).toHaveBeenLastCalledWith(
      expect.objectContaining({ timing: 'OVERDUE' }),
    );
  });

  it('switches to upcoming', async () => {
    const user = userEvent.setup();
    await openPanel();
    await user.click(screen.getByRole('tab', { name: 'Upcoming' }));
    await waitFor(() =>
      expect(hospitalService.getFollowUps).toHaveBeenLastCalledWith(
        expect.objectContaining({ timing: 'UPCOMING' }),
      ),
    );
  });

  it('asks for a wider window when older follow-ups are requested', async () => {
    const user = userEvent.setup();
    await openPanel();
    await user.click(screen.getByRole('tab', { name: 'Overdue' }));
    await screen.findByText(/Showing the last 90 days/i);

    await user.click(screen.getByRole('button', { name: /Show older follow-ups/i }));

    await waitFor(() =>
      expect(hospitalService.getFollowUps).toHaveBeenLastCalledWith(
        expect.objectContaining({ timing: 'OVERDUE', overdueDays: expect.any(Number) }),
      ),
    );
  });

  // ── loading / empty / error / stale ────────────────────────────────────

  it('shows a loading state before the first result', async () => {
    let resolve;
    hospitalService.getFollowUps.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<FollowUpPanel role="RECEPTIONIST" />);
    expect(screen.getByText(/Loading follow-ups/i)).toBeInTheDocument();
    resolve([]);
    await waitFor(() => expect(screen.queryByText(/Loading follow-ups/i)).not.toBeInTheDocument());
  });

  it('reports a genuinely empty list as empty', async () => {
    hospitalService.getFollowUps.mockResolvedValue([]);
    await openPanel();
    expect(await screen.findByText(/No due today follow-ups/i)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('does not pass a failed load off as an empty list', async () => {
    hospitalService.getFollowUps.mockRejectedValue({
      response: { data: { error: 'Service unavailable' } },
    });
    await openPanel();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Service unavailable');
    expect(screen.queryByText(/No due today follow-ups/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('never shows a raw backend exception', async () => {
    hospitalService.getFollowUps.mockRejectedValue(
      new Error('java.lang.NullPointerException at com.hms.service.FollowUpService'),
    );
    await openPanel();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).not.toContain('com.hms');
  });

  it('keeps the rows it already had when a refresh fails, and says they may be stale', async () => {
    const user = userEvent.setup();
    await openPanel();
    await screen.findByText('Asha Rao');

    hospitalService.getFollowUps.mockRejectedValue({
      response: { data: { error: 'Network blip' } },
    });
    await user.click(screen.getByRole('tab', { name: 'Overdue' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/may be out of date/i);
    expect(screen.getByText('Asha Rao')).toBeInTheDocument();
  });

  // ── role-specific actions ──────────────────────────────────────────────

  it('offers reception everything except Complete', async () => {
    await openPanel({ role: 'RECEPTIONIST' });
    await screen.findByText('Asha Rao');
    expect(screen.getByRole('button', { name: 'Patient Arrived' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reschedule' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Complete' })).not.toBeInTheDocument();
  });

  it('offers a doctor the full set, scoped to their own patients', async () => {
    await openPanel({ role: 'DOCTOR', mine: true });
    await screen.findByText('Asha Rao');
    expect(screen.getByRole('button', { name: 'Complete' })).toBeInTheDocument();
    expect(hospitalService.getFollowUps).toHaveBeenCalledWith(
      expect.objectContaining({ mine: true }),
    );
  });

  it('offers an admin the full set across the facility', async () => {
    await openPanel({ role: 'HOSPITAL_ADMIN' });
    await screen.findByText('Asha Rao');
    expect(screen.getByRole('button', { name: 'Complete' })).toBeInTheDocument();
    expect(hospitalService.getFollowUps).toHaveBeenCalledWith(
      expect.objectContaining({ mine: false }),
    );
  });

  // ── patient arrived ────────────────────────────────────────────────────

  it('confirms before creating a visit, then re-reads the list', async () => {
    const user = userEvent.setup();
    hospitalService.arriveFollowUp.mockResolvedValue({});
    await openPanel();
    await screen.findByText('Asha Rao');

    await user.click(screen.getByRole('button', { name: 'Patient Arrived' }));
    const dialog = await screen.findByText(/creates a new follow-up OPD/i);
    expect(dialog).toBeInTheDocument();
    expect(hospitalService.arriveFollowUp).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: /Confirm action/i }));

    await waitFor(() => expect(hospitalService.arriveFollowUp).toHaveBeenCalledWith(1));
    // Two reads: the initial one and the authoritative re-read after the action.
    await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalledTimes(2));
    expect(toast.success).toHaveBeenCalled();
  });

  it('re-reads and warns when someone else already actioned it', async () => {
    const user = userEvent.setup();
    hospitalService.arriveFollowUp.mockRejectedValue({
      response: { status: 409, data: { error: 'Already actioned' } },
    });
    await openPanel();
    await screen.findByText('Asha Rao');

    await user.click(screen.getByRole('button', { name: 'Patient Arrived' }));
    await user.click(screen.getByRole('button', { name: /Confirm action/i }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Already actioned'));
    await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalledTimes(2));
  });

  // ── reschedule ─────────────────────────────────────────────────────────

  const openReschedule = async (user) => {
    await user.click(screen.getByRole('button', { name: 'Reschedule' }));
    return screen.findByRole('dialog', { name: /Reschedule follow-up/i });
  };

  // The input also carries min=today, which is why jsdom refuses to hold the past value at all;
  // either way the dialog stops and the server is never asked. The server enforces the same rule
  // independently — FollowUpLifecycleTest covers that side.
  it('refuses an invalid date before troubling the server', async () => {
    const user = userEvent.setup();
    await openPanel();
    await screen.findByText('Asha Rao');
    const dialog = await openReschedule(user);

    const input = within(dialog).getByLabelText(/New follow-up date/i);
    fireEvent.change(input, { target: { value: '2020-01-01' } });
    fireEvent.submit(dialog);

    // The claim that matters: the server is never asked.
    await waitFor(() => expect(hospitalService.rescheduleFollowUp).not.toHaveBeenCalled());
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/New follow-up date/i)).toHaveAttribute('min');
  });

  it('accepts a future date and re-reads afterwards', async () => {
    const user = userEvent.setup();
    hospitalService.rescheduleFollowUp.mockResolvedValue({});
    await openPanel();
    await screen.findByText('Asha Rao');
    const dialog = await openReschedule(user);

    const input = within(dialog).getByLabelText(/New follow-up date/i);
    fireEvent.change(input, { target: { value: '2099-12-31' } });
    fireEvent.submit(dialog);

    await waitFor(() =>
      expect(hospitalService.rescheduleFollowUp).toHaveBeenCalledWith(
        1, expect.objectContaining({ newFollowUpDate: '2099-12-31' }),
      ),
    );
    await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalledTimes(2));
  });

  // ── complete and cancel ────────────────────────────────────────────────

  it('spells out that completing does not create a visit', async () => {
    const user = userEvent.setup();
    hospitalService.completeFollowUp.mockResolvedValue({});
    await openPanel({ role: 'DOCTOR' });
    await screen.findByText('Asha Rao');

    await user.click(screen.getByRole('button', { name: 'Complete' }));
    expect(await screen.findByText(/without creating an OPD visit/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Confirm action/i }));
    await waitFor(() => expect(hospitalService.completeFollowUp).toHaveBeenCalled());
    await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalledTimes(2));
  });

  it('will not cancel without a reason', async () => {
    const user = userEvent.setup();
    await openPanel();
    await screen.findByText('Asha Rao');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await screen.findByText(/calls off the follow-up/i);
    await user.click(screen.getByRole('button', { name: /Confirm action/i }));

    expect(hospitalService.cancelFollowUp).not.toHaveBeenCalled();
  });

  it('cancels with a reason and re-reads', async () => {
    const user = userEvent.setup();
    hospitalService.cancelFollowUp.mockResolvedValue({});
    await openPanel();
    await screen.findByText('Asha Rao');

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await screen.findByText(/calls off the follow-up/i);
    fireEvent.change(screen.getByPlaceholderText(/Why is this follow-up being cancelled/i), {
      target: { value: 'Moved away' },
    });
    await user.click(screen.getByRole('button', { name: /Confirm action/i }));

    await waitFor(() => expect(hospitalService.cancelFollowUp).toHaveBeenCalledWith(1, 'Moved away'));
    await waitFor(() => expect(hospitalService.getFollowUps).toHaveBeenCalledTimes(2));
  });
});
