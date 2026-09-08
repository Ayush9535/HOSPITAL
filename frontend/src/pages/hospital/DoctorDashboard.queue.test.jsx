import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * The Overview's Queue panel is where OPD patients due for consultation appear, and it holds the
 * only walk-in "Consult" action. It has nothing to do with appointments: the handler behind it
 * opens the consultation with `appointment: null` and keys everything on the OPD.
 *
 * Making appointments optional collapsed this Overview to a single column when the module was
 * withheld. Nothing in the data path broke — the queue call, the panel, the button and its
 * `status === 'QUEUED'` condition were all untouched — but a fixed 650px Follow-Ups panel was
 * stacked on top of the Queue, pushing the due list and its Consult button below the fold. It
 * read to a doctor as "the OPD patients are gone".
 *
 * jsdom has no viewport, so a pixel assertion would prove nothing. What these tests pin is the
 * structural invariant that actually regressed: the Overview keeps its desktop two-column class
 * in BOTH module states, and a queued OPD renders a working Consult in both.
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

// Stand in for the real consultation modal so the walk-in contract can be asserted on its props
// rather than on the modal's internals.
const consultationModalProps = vi.fn();
vi.mock('../../components/ConsultationModal', () => ({
  default: (props) => {
    consultationModalProps(props);
    return props.isOpen ? <div data-testid="consultation-modal" /> : null;
  },
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

/** One walk-in OPD case, queued for this doctor and carrying no appointment. */
const QUEUED_WALK_IN = {
  id: 1,
  createdAt: '2026-09-05T09:00:00.000Z',
  opd: {
    id: 55,
    status: 'QUEUED',
    appointmentId: null,
    patient: { id: 7, name: 'Ravi Kumar' },
  },
};

const hospitalService = {
  getMyAppointments: vi.fn().mockResolvedValue({ content: [], totalElements: 0 }),
  getAppointmentStats: vi.fn().mockResolvedValue({}),
  getTodaysFollowUps: vi.fn().mockResolvedValue([]),
  getPatients: vi.fn().mockResolvedValue({ content: [] }),
  getDoctorQueue: vi.fn().mockResolvedValue([QUEUED_WALK_IN]),
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

/** The Overview grid that holds the left panel and the Queue. */
const overviewGrid = () => screen.getByRole('heading', { name: 'Queue' }).closest('.grid');

/**
 * The Queue panel itself. Assertions are scoped to it on purpose: the queue's first patient is
 * also mirrored into the "Current Patient" stat card, so an unscoped query would match twice and
 * would not prove the due list rendered.
 */
const queuePanel = () => screen.getByRole('heading', { name: 'Queue' }).closest('.bg-white');

const WALK_IN_ONLY = ['OPD', 'IPD'];
const WITH_APPOINTMENTS = ['OPD', 'IPD', 'APPOINTMENTS'];

beforeEach(() => {
  vi.clearAllMocks();
  hospitalService.getMyAppointments.mockResolvedValue({ content: [], totalElements: 0 });
  hospitalService.getAppointmentStats.mockResolvedValue({});
  hospitalService.getTodaysFollowUps.mockResolvedValue([]);
  hospitalService.getPatients.mockResolvedValue({ content: [] });
  hospitalService.getDoctorQueue.mockResolvedValue([QUEUED_WALK_IN]);
});

afterEach(() => vi.resetModules());

describe('DoctorDashboard Overview — OPD queue is independent of appointments', () => {
  it('renders the queued OPD patient and its Consult action when APPOINTMENTS is disabled', async () => {
    await renderDashboard(WALK_IN_ONLY);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    await waitFor(() => expect(within(queuePanel()).getByText('Ravi Kumar')).toBeInTheDocument());
    expect(within(queuePanel()).getByRole('button', { name: 'Consult' })).toBeInTheDocument();
  });

  it('renders the queued OPD patient and its Consult action when APPOINTMENTS is enabled', async () => {
    await renderDashboard(WITH_APPOINTMENTS);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    await waitFor(() => expect(within(queuePanel()).getByText('Ravi Kumar')).toBeInTheDocument());
    expect(within(queuePanel()).getByRole('button', { name: 'Consult' })).toBeInTheDocument();
  });

  it('opens the consultation for a walk-in OPD without any appointment', async () => {
    await renderDashboard(WALK_IN_ONLY);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    await waitFor(() =>
      expect(within(queuePanel()).getByRole('button', { name: 'Consult' })).toBeInTheDocument()
    );
    fireEvent.click(within(queuePanel()).getByRole('button', { name: 'Consult' }));

    await waitFor(() => expect(screen.getByTestId('consultation-modal')).toBeInTheDocument());
    const opened = consultationModalProps.mock.calls.map(([p]) => p).filter((p) => p.isOpen);
    expect(opened).not.toHaveLength(0);
    const props = opened[opened.length - 1];
    expect(props.appointment).toBeNull();
    expect(props.opd).toMatchObject({ id: 55, status: 'QUEUED' });
    expect(props.patient).toMatchObject({ name: 'Ravi Kumar' });
  });
});

describe('DoctorDashboard Overview — layout keeps the Queue beside the left panel', () => {
  it('keeps the desktop two-column grid when APPOINTMENTS is disabled', async () => {
    await renderDashboard(WALK_IN_ONLY);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    // A single column would stack a 650px panel above the Queue and push it below the fold.
    expect(overviewGrid()).toHaveClass('lg:grid-cols-2');
  });

  it('keeps the desktop two-column grid when APPOINTMENTS is enabled', async () => {
    await renderDashboard(WITH_APPOINTMENTS);

    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    expect(overviewGrid()).toHaveClass('lg:grid-cols-2');
  });

  it('places exactly two panels in that grid in both module states', async () => {
    const { unmount } = await renderDashboard(WALK_IN_ONLY);
    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    expect(overviewGrid().children).toHaveLength(2);
    unmount();

    await renderDashboard(WITH_APPOINTMENTS);
    await waitFor(() => expect(hospitalService.getDoctorQueue).toHaveBeenCalled());
    expect(overviewGrid().children).toHaveLength(2);
  });
});
