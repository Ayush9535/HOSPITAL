import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('../../../services/otService', () => ({
  default: {
    getWhoChecklist: vi.fn(),
    getMilestones: vi.fn(),
    signWhoPhase: vi.fn(),
    recordMilestone: vi.fn(),
    saveOperativeNote: vi.fn(),
    getMyOtPermissions: vi.fn(),
  },
}));

vi.mock('../../../context/ToastContext', () => ({
  useToast: () => ({ success: vi.fn(), error: vi.fn() }),
}));

vi.mock('../../../services/authService', () => ({
  default: { getCurrentUser: () => ({ modules: ['OT'] }) },
}));

import otService from '../../../services/otService';
import SurgeryExecutionModal from './SurgeryExecutionModal';

const surgery = { surgeryId: 6, patientName: 'A Patient', procedureName: 'Appendicectomy' };

/**
 * The reported staging failure, at its source.
 *
 * <p>POST .../who-checklist/SIGN_IN/sign returned Access Denied. This modal is mounted on the
 * reception dashboard and rendered every control enabled for anyone who could open it, but
 * signing the WHO checklist requires OT_TIME_OUT — a theatre-team permission reception does not
 * hold. The action was offered and then refused.
 *
 * <p>The invariant these tests hold: if a control is visible and enabled, the caller can execute
 * it. Where the permission is missing, the control is not offered at all.
 */
describe('SurgeryExecutionModal — offers only what the caller may do', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    otService.getWhoChecklist.mockResolvedValue({ signInAt: null, timeOutAt: null, signOutAt: null });
    otService.getMilestones.mockResolvedValue([]);
    otService.signWhoPhase.mockResolvedValue({ signInAt: '2026-01-01T10:00:00' });
    otService.recordMilestone.mockResolvedValue({ milestone: 'INCISION' });
    otService.saveOperativeNote.mockResolvedValue({});
  });

  const openAs = async (permissions) => {
    otService.getMyOtPermissions.mockResolvedValue(permissions);
    render(<SurgeryExecutionModal surgery={surgery} onClose={vi.fn()} />);
    await waitFor(() =>
      expect(screen.getByText('WHO Surgical Safety Checklist')).toBeInTheDocument()
    );
  };

  /** Reception: can run the board, start and complete — but does not sign the checklist. */
  it('does not offer WHO signing to a caller without OT_TIME_OUT', async () => {
    await openAs(['OT_VIEW', 'OT_START', 'OT_COMPLETE', 'OT_SCHEDULE']);

    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument();
    expect(screen.getAllByText('Signed by the theatre team').length).toBeGreaterThan(0);
    expect(otService.signWhoPhase).not.toHaveBeenCalled();
  });

  /** The theatre team holds OT_TIME_OUT, and for them the action really works. */
  it('offers WHO signing to a caller with OT_TIME_OUT, and it executes', async () => {
    const user = userEvent.setup();
    await openAs(['OT_VIEW', 'OT_TIME_OUT', 'OT_PRE_OP']);

    const signButtons = screen.getAllByRole('button', { name: 'Sign' });
    expect(signButtons.length).toBeGreaterThan(0);
    expect(signButtons[0]).not.toBeDisabled();

    await user.click(signButtons[0]);
    await waitFor(() => expect(otService.signWhoPhase).toHaveBeenCalledWith(6, 'SIGN_IN', expect.anything()));
  });

  /** Milestones need OT_START, OT_COMPLETE or OT_PRE_OP — the same set the endpoint accepts. */
  it('disables milestones for a caller who holds none of the milestone permissions', async () => {
    await openAs(['OT_VIEW', 'OT_TIME_OUT']);

    expect(screen.getByText(/Recorded by the theatre team/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ Incision' })).toBeDisabled();
  });

  it('enables milestones for a caller who holds one of them', async () => {
    await openAs(['OT_VIEW', 'OT_START']);

    expect(screen.getByRole('button', { name: '+ Incision' })).not.toBeDisabled();
  });

  /** The operative note is the surgeon's, gated on OT_COMPLETE exactly as its endpoint is. */
  it('makes the operative note read-only without OT_COMPLETE', async () => {
    await openAs(['OT_VIEW', 'OT_TIME_OUT']);

    expect(screen.getByPlaceholderText('Written by the operating surgeon.')).toHaveAttribute('readonly');
    expect(screen.queryByRole('button', { name: 'Save note' })).not.toBeInTheDocument();
  });

  it('allows writing the operative note with OT_COMPLETE', async () => {
    await openAs(['OT_VIEW', 'OT_COMPLETE']);

    const box = screen.getByPlaceholderText(/Findings, procedure performed/);
    expect(box).not.toHaveAttribute('readonly');
  });

  /**
   * Nothing may be offered before the server has said what the caller holds — otherwise a
   * control flashes into view, is clicked, and 403s.
   */
  it('shows nothing actionable until permissions have loaded', async () => {
    let resolvePerms;
    otService.getMyOtPermissions.mockImplementation(
      () => new Promise((resolve) => { resolvePerms = resolve; })
    );

    render(<SurgeryExecutionModal surgery={surgery} onClose={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument();
    expect(screen.getByText('Loading…')).toBeInTheDocument();

    resolvePerms(['OT_VIEW', 'OT_TIME_OUT']);
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Sign' }).length).toBeGreaterThan(0));
  });

  /** A caller with only OT_VIEW gets a readable case and no dead controls at all. */
  it('gives a view-only caller no enabled write controls', async () => {
    await openAs(['OT_VIEW']);

    expect(screen.queryByRole('button', { name: 'Sign' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ Incision' })).toBeDisabled();
    expect(screen.getByPlaceholderText('Written by the operating surgeon.')).toHaveAttribute('readonly');
  });
});
