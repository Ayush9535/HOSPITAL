import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useLocation: () => ({ pathname: '/hospital/ot-incharge' }),
}));

vi.mock('../../services/otService', () => ({
  default: {
    getBoard: vi.fn(),
    getRequests: vi.fn(),
    getMyOtPermissions: vi.fn(),
    approve: vi.fn(),
    preOp: vi.fn(),
    postpone: vi.fn(),
    start: vi.fn(),
    complete: vi.fn(),
    cancel: vi.fn(),
    close: vi.fn(),
    recordAnaesthesiaClearance: vi.fn(),
    getOtAnalytics: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => ({ role: 'OT_INCHARGE', modules: ['OT'], hospitalName: 'H' }),
    getLoginUrl: () => '/login',
    logout: vi.fn(),
  },
}));

vi.mock('../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

vi.mock('../../hooks/useWebSocket', () => ({ default: () => {} }));
vi.mock('../../components/Navbar', () => ({ default: () => <div /> }));
vi.mock('../../components/Sidebar', () => ({
  default: ({ tabs, onTabChange }) => (
    <nav>
      {tabs.map((t) => (
        <button key={t.id} onClick={() => onTabChange(t.id)}>
          {t.label}
        </button>
      ))}
    </nav>
  ),
}));
vi.mock('../../components/ProfileModal', () => ({ default: () => null }));
vi.mock('./ot/OtAnalyticsStrip', () => ({ default: () => <div /> }));
vi.mock('./ot/OtDayBoard', () => ({ default: () => <div>day board</div> }));
vi.mock('./ot/RecoveryModal', () => ({ default: () => <div>recovery modal</div> }));
vi.mock('./ot/ScheduleSurgeryModal', () => ({ default: () => <div>schedule modal</div> }));
vi.mock('./ot/SurgeryExecutionModal', () => ({ default: () => <div>execution modal</div> }));
vi.mock('./ot/SurgeryTeamModal', () => ({ default: () => <div>team modal</div> }));

import otService from '../../services/otService';
import OtInchargeDashboard from './OtInchargeDashboard';

const FULL = [
  'OT_VIEW', 'OT_CREATE', 'OT_APPROVE', 'OT_SCHEDULE', 'OT_RESCHEDULE', 'OT_CANCEL',
  'OT_ASSIGN_ROOM', 'OT_ASSIGN_TEAM', 'OT_PRE_OP', 'OT_ANAESTHESIA_CLEARANCE',
  'OT_TIME_OUT', 'OT_START', 'OT_COMPLETE', 'OT_RECOVERY', 'OT_TRANSFER', 'OT_CLOSE',
];

const surgeryAt = (status) => ({
  publicId: 'srg-1',
  surgeryId: 1,
  patientName: 'A Patient',
  procedureName: 'Appendicectomy',
  status,
});

/**
 * The normal theatre journey, driven through the product rather than through the API.
 *
 * <p>The backend journey already passes; what this holds is that a person can actually get from
 * one state to the next on screen. Two stages could not be reached at all before: approve, pre-op
 * and anaesthesia clearance had working endpoints and no control anywhere, and a case moved into
 * PRE_OP lost both its Start and its Checklist button — entering pre-op was a dead end escapable
 * only through the API.
 */
describe('OT workflow — every state offers the next step', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getMyOtPermissions.mockResolvedValue(FULL);
    otService.getRequests.mockResolvedValue([]);
    otService.approve.mockResolvedValue({});
    otService.preOp.mockResolvedValue({});
    otService.start.mockResolvedValue({});
    otService.complete.mockResolvedValue({});
    otService.close.mockResolvedValue({});
    otService.postpone.mockResolvedValue({});
  });

  const boardAt = async (status) => {
    otService.getBoard.mockResolvedValue([surgeryAt(status)]);
    render(<OtInchargeDashboard />);
    return (await screen.findByText('A Patient')).closest('tr');
  };

  it('SCHEDULED offers pre-op, anaesthesia, team, checklist, start, postpone', async () => {
    const row = await boardAt('SCHEDULED');
    for (const action of ['Pre-op', 'Anaesthesia', 'Team', 'Checklist', 'Start', 'Postpone']) {
      expect(within(row).getByRole('button', { name: action })).toBeInTheDocument();
    }
  });

  /** The dead end: PRE_OP used to offer neither Start nor Checklist. */
  it('PRE_OP still offers Start and the checklist', async () => {
    const row = await boardAt('PRE_OP');
    expect(within(row).getByRole('button', { name: 'Start' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Checklist' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Anaesthesia' })).toBeInTheDocument();
  });

  it('IN_PROGRESS offers the checklist and Complete', async () => {
    const row = await boardAt('IN_PROGRESS');
    expect(within(row).getByRole('button', { name: 'Checklist' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Complete' })).toBeInTheDocument();
  });

  it('COMPLETED offers Recovery and Close', async () => {
    const row = await boardAt('COMPLETED');
    expect(within(row).getByRole('button', { name: 'Recovery' })).toBeInTheDocument();
    expect(within(row).getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });

  it('a REQUESTED case can be approved from the requests list', async () => {
    const user = userEvent.setup();
    otService.getBoard.mockResolvedValue([]);
    otService.getRequests.mockResolvedValue([surgeryAt('REQUESTED')]);
    render(<OtInchargeDashboard />);

    await user.click(await screen.findByRole('button', { name: 'Requests' }));
    const approve = await screen.findByRole('button', { name: 'Approve' });
    await user.click(approve);

    await waitFor(() => expect(otService.approve).toHaveBeenCalledWith('srg-1'));
    // ...and the list is re-read, so the board the user is looking at is not stale.
    await waitFor(() => expect(otService.getRequests).toHaveBeenCalledTimes(2));
  });

  it('pre-op runs and refreshes the board', async () => {
    const user = userEvent.setup();
    const row = await boardAt('SCHEDULED');

    await user.click(within(row).getByRole('button', { name: 'Pre-op' }));

    await waitFor(() => expect(otService.preOp).toHaveBeenCalledWith('srg-1'));
    await waitFor(() => expect(otService.getBoard).toHaveBeenCalledTimes(2));
  });

  it('start runs and refreshes the board', async () => {
    const user = userEvent.setup();
    const row = await boardAt('SCHEDULED');

    await user.click(within(row).getByRole('button', { name: 'Start' }));

    await waitFor(() => expect(otService.start).toHaveBeenCalledWith('srg-1'));
    await waitFor(() => expect(otService.getBoard).toHaveBeenCalledTimes(2));
  });

  it('anaesthesia clearance opens a form, refuses an unstated outcome, and records one', async () => {
    const user = userEvent.setup();
    otService.recordAnaesthesiaClearance.mockResolvedValue({});
    const row = await boardAt('SCHEDULED');

    await user.click(within(row).getByRole('button', { name: 'Anaesthesia' }));
    const dialog = await screen.findByRole('dialog', { name: 'Anaesthesia clearance' });

    // No outcome is preselected: the HMS never infers clinical fitness.
    expect(within(dialog).getByRole('button', { name: 'Record clearance' })).toBeDisabled();

    await user.click(within(dialog).getByLabelText('Cleared'));
    await user.click(within(dialog).getByRole('button', { name: 'Record clearance' }));

    await waitFor(() =>
      expect(otService.recordAnaesthesiaClearance).toHaveBeenCalledWith(
        'srg-1',
        expect.objectContaining({ outcome: 'CLEARED' })
      )
    );
    await waitFor(() => expect(otService.getBoard).toHaveBeenCalledTimes(2));
  });

  it('a conditional clearance cannot be submitted without its conditions', async () => {
    const user = userEvent.setup();
    const row = await boardAt('SCHEDULED');

    await user.click(within(row).getByRole('button', { name: 'Anaesthesia' }));
    const dialog = await screen.findByRole('dialog', { name: 'Anaesthesia clearance' });

    await user.click(within(dialog).getByLabelText('Cleared with conditions'));
    expect(within(dialog).getByRole('button', { name: 'Record clearance' })).toBeDisabled();

    await user.type(within(dialog).getByLabelText(/Conditions/), 'Cardiology review first');
    expect(within(dialog).getByRole('button', { name: 'Record clearance' })).not.toBeDisabled();
  });

  /** A failed mutation must not close the form or claim success. */
  it('keeps the clearance form open and reports the server error on failure', async () => {
    const user = userEvent.setup();
    otService.recordAnaesthesiaClearance.mockRejectedValue({
      response: { data: { error: 'Surgery is not in a state that accepts clearance' } },
    });
    const row = await boardAt('SCHEDULED');

    await user.click(within(row).getByRole('button', { name: 'Anaesthesia' }));
    const dialog = await screen.findByRole('dialog', { name: 'Anaesthesia clearance' });
    await user.click(within(dialog).getByLabelText('Cleared'));
    await user.click(within(dialog).getByRole('button', { name: 'Record clearance' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Surgery is not in a state that accepts clearance'
    );
    expect(screen.getByRole('dialog', { name: 'Anaesthesia clearance' })).toBeInTheDocument();
  });

  /** None of the new controls may appear for a caller who cannot use them. */
  it('withholds approve, pre-op, anaesthesia and postpone without their permissions', async () => {
    otService.getMyOtPermissions.mockResolvedValue(['OT_VIEW']);
    const row = await boardAt('SCHEDULED');

    for (const action of ['Pre-op', 'Anaesthesia', 'Postpone', 'Start', 'Team']) {
      expect(within(row).queryByRole('button', { name: action })).not.toBeInTheDocument();
    }
  });
});
