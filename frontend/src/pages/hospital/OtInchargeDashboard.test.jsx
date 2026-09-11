import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn(), useLocation: () => ({ pathname: '/hospital/ot-incharge' }) }));

vi.mock('../../services/otService', () => ({
  default: {
    getBoard: vi.fn(),
    getRequests: vi.fn(),
    getMyOtPermissions: vi.fn(),
    cancel: vi.fn(),
    start: vi.fn(),
    complete: vi.fn(),
    close: vi.fn(),
    getOtAnalytics: vi.fn().mockResolvedValue({}),
  },
}));

vi.mock('../../services/authService', () => ({
  default: {
    getCurrentUser: () => ({ role: 'OT_INCHARGE', modules: ['OT'], hospitalName: 'Test Hospital' }),
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
  default: ({ tabs }) => (
    <nav>
      {tabs.map((t) => (
        <span key={t.id}>{t.label}</span>
      ))}
    </nav>
  ),
}));
vi.mock('../../components/ProfileModal', () => ({ default: () => null }));
vi.mock('./ot/OtAnalyticsStrip', () => ({ default: () => <div /> }));
vi.mock('./ot/OtDayBoard', () => ({ default: () => <div>day board</div> }));
vi.mock('./ot/RecoveryModal', () => ({ default: () => null }));
vi.mock('./ot/ScheduleSurgeryModal', () => ({ default: () => null }));
vi.mock('./ot/SurgeryExecutionModal', () => ({ default: () => null }));
vi.mock('./ot/SurgeryTeamModal', () => ({ default: () => null }));

import otService from '../../services/otService';
import OtInchargeDashboard from './OtInchargeDashboard';

const scheduled = {
  publicId: 'srg-1',
  surgeryId: 1,
  patientName: 'A Patient',
  procedureName: 'Appendicectomy',
  status: 'SCHEDULED',
};

/**
 * OT_INCHARGE could not reach the product at all: no route, and the post-login switch fell
 * through to its default and sent the user back to the login page. This dashboard is that role's
 * landing place, and — like every other OT surface now — it offers an action only when the caller
 * holds the permission the endpoint requires.
 */
describe('OtInchargeDashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getBoard.mockResolvedValue([scheduled]);
    otService.getRequests.mockResolvedValue([]);
  });

  const renderWith = async (permissions) => {
    otService.getMyOtPermissions.mockResolvedValue(permissions);
    render(<OtInchargeDashboard />);
    await waitFor(() => expect(otService.getBoard).toHaveBeenCalled());
  };

  const FULL = [
    'OT_VIEW', 'OT_CREATE', 'OT_APPROVE', 'OT_SCHEDULE', 'OT_RESCHEDULE', 'OT_CANCEL',
    'OT_ASSIGN_ROOM', 'OT_ASSIGN_TEAM', 'OT_PRE_OP', 'OT_ANAESTHESIA_CLEARANCE',
    'OT_EMERGENCY_OVERRIDE', 'OT_TIME_OUT', 'OT_START', 'OT_COMPLETE', 'OT_RECOVERY',
    'OT_TRANSFER', 'OT_CLOSE', 'OT_FORM_VIEW', 'OT_FORM_EDIT',
  ];

  it('shows the theatre board and its actions to a full OT incharge', async () => {
    await renderWith(FULL);

    expect(await screen.findByText('A Patient')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Team' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Checklist' })).toBeInTheDocument();
  });

  it('carries only theatre work — no reception or admin sections', async () => {
    await renderWith(FULL);

    expect(screen.getByText('OT Board')).toBeInTheDocument();
    expect(screen.getByText("Today's List")).toBeInTheDocument();
    for (const foreign of ['Appointments', 'Patients', 'Billing', 'IPD', 'OPD', 'Pharmacy']) {
      expect(screen.queryByText(foreign)).not.toBeInTheDocument();
    }
  });

  /** A hospital that narrows the role in its matrix gets a dashboard that narrows with it. */
  it('withholds actions the hospital has not granted this role', async () => {
    await renderWith(['OT_VIEW']);

    expect(await screen.findByText('A Patient')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Team' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Schedule' })).not.toBeInTheDocument();
  });

  it('hides the Requests tab without OT_SCHEDULE', async () => {
    await renderWith(['OT_VIEW', 'OT_START']);
    expect(screen.queryByText('Requests')).not.toBeInTheDocument();
  });

  it('says so plainly when the hospital has granted no theatre access at all', async () => {
    await renderWith([]);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No theatre access');
    expect(screen.queryByText('A Patient')).not.toBeInTheDocument();
  });

  /** A theatre showing an empty board because the read failed is a dangerous claim. */
  it('reports a failed board load instead of drawing an empty theatre', async () => {
    otService.getBoard.mockRejectedValue({ response: { data: { error: 'OT service unavailable' } } });
    await renderWith(FULL);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("Couldn't load the OT board");
    expect(alert).toHaveTextContent('OT service unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
