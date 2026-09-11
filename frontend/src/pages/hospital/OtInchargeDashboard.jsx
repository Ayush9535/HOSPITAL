import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Navbar from '../../components/Navbar';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import { useToast } from '../../context/ToastContext';
import useOtPermissions from '../../hooks/useOtPermissions';
import useWebSocket from '../../hooks/useWebSocket';
import authService from '../../services/authService';
import otService from '../../services/otService';
import { extractApiError } from '../../utils/apiError';
import OtAnalyticsStrip from './ot/OtAnalyticsStrip';
import OtBoard from './ot/OtBoard';
import OtDayBoard from './ot/OtDayBoard';
import AnaesthesiaClearanceModal from './ot/AnaesthesiaClearanceModal';
import RecoveryModal from './ot/RecoveryModal';
import ScheduleSurgeryModal from './ot/ScheduleSurgeryModal';
import SurgeryExecutionModal from './ot/SurgeryExecutionModal';
import SurgeryTeamModal from './ot/SurgeryTeamModal';

/**
 * The theatre incharge's own dashboard.
 *
 * <p>OT_INCHARGE is a role a hospital can create, and the backend grants it the full clinical OT
 * set — request, approve, schedule, assign team, pre-op, WHO checklist, start, complete,
 * recovery, transfer, close. It had no route and no post-login destination, so the login switch
 * fell through to its default and sent the user back to the login page: the role that runs the
 * theatre could not get into the product at all.
 *
 * <p>Deliberately only theatre. Reception's dashboard carries patients, appointments, OPD, IPD
 * and billing, none of which is this role's work, so this is not a copy of it — it is the OT
 * board, the day list, recovery, and the case modals, all of which already exist and are reused
 * as they are.
 *
 * <p>Every action is offered through the same permission gate the rest of OT now uses: a handler
 * is passed to the board only when this user holds the permission that endpoint requires. A
 * hospital that narrows OT_INCHARGE in its own permission matrix gets a dashboard that narrows
 * with it, rather than buttons that fail when pressed.
 */
const OtInchargeDashboard = () => {
  const [user, setUser] = useState(authService.getCurrentUser());
  const navigate = useNavigate();
  const { success, error: toastError } = useToast();
  const { can, loaded: permsLoaded } = useOtPermissions();

  const [sidebarCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('board');

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);

  const [teamTarget, setTeamTarget] = useState(null);
  const [execTarget, setExecTarget] = useState(null);
  const [recoveryTarget, setRecoveryTarget] = useState(null);
  const [scheduleTarget, setScheduleTarget] = useState(null);
  const [anaesthesiaTarget, setAnaesthesiaTarget] = useState(null);

  const today = new Date();
  const todayString = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;

  const load = useCallback(async () => {
    if (activeTab === 'day') return;
    setLoading(true);
    setLoadError(null);
    try {
      const data = activeTab === 'requests' ? await otService.getRequests() : await otService.getBoard();
      setRows(Array.isArray(data) ? data : []);
    } catch (e) {
      // A failed read is its own state. Showing an empty board would say "no surgeries today",
      // which in a theatre is a materially different and dangerous claim.
      setLoadError(extractApiError(e, 'Could not load the OT board.'));
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, [activeTab]);

  useEffect(() => {
    load();
  }, [load]);

  useWebSocket(user, setUser, load);

  const handleLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  const act = (verb, fn) => async (row) => {
    try {
      await fn(row.publicId);
      success(`Surgery ${verb}`);
      load();
    } catch (e) {
      toastError(extractApiError(e, `Could not ${verb.replace(/d$/, '')} this surgery.`));
    }
  };

  const tabs = [
    { id: 'board', label: 'OT Board' },
    { id: 'day', label: "Today's List" },
    ...(can('OT_SCHEDULE') ? [{ id: 'requests', label: 'Requests' }] : []),
  ];

  return (
    <div className="flex h-screen bg-white overflow-hidden">
      <Sidebar
        title="Operation Theatre"
        tabs={tabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        footerTitle="Theatre"
        footerData={user?.hospitalName || 'HMS Medical Center'}
        variant="plain"
        isCollapsed={sidebarCollapsed}
      />

      <div className="flex-1 flex flex-col h-full relative overflow-hidden">
        <Navbar
          user={user}
          onLogout={handleLogout}
          onProfile={() => setProfileOpen(true)}
          title="Operation Theatre"
        />

        <main className="flex-1 overflow-y-auto p-6 space-y-6 bg-gray-50/50">
          <OtAnalyticsStrip />

          {!permsLoaded ? (
            <div className="text-center text-gray-400 py-16">Loading…</div>
          ) : !can('OT_VIEW') ? (
            <div
              role="alert"
              className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-5 text-center"
            >
              <p className="text-sm font-semibold text-amber-900">No theatre access</p>
              <p className="mt-1 text-sm text-amber-800">
                Your hospital has not granted this account access to the operation theatre. Ask
                your administrator to review the OT permission matrix.
              </p>
            </div>
          ) : activeTab === 'day' ? (
            <OtDayBoard date={todayString} onSelect={(c) => setExecTarget(c)} />
          ) : loadError ? (
            <div
              role="alert"
              className="rounded-lg border border-red-200 bg-red-50 px-4 py-5 text-center"
            >
              <p className="text-sm font-semibold text-red-800">Couldn&apos;t load the OT board</p>
              <p className="mt-1 text-sm text-red-700">{loadError}</p>
              <button
                type="button"
                onClick={load}
                className="mt-3 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700"
              >
                Retry
              </button>
            </div>
          ) : loading ? (
            <div className="text-center text-gray-400 py-16">Loading…</div>
          ) : (
            <OtBoard
              rows={rows}
              mode={activeTab === 'requests' ? 'requests' : 'board'}
              onSchedule={can('OT_SCHEDULE') ? (r) => setScheduleTarget(r) : undefined}
              onCancel={can('OT_CANCEL') ? act('cancelled', otService.cancel) : undefined}
              onStart={can('OT_START') ? act('started', otService.start) : undefined}
              onComplete={can('OT_COMPLETE') ? act('completed', otService.complete) : undefined}
              onTeam={can('OT_ASSIGN_TEAM') ? (r) => setTeamTarget(r) : undefined}
              onExecute={can('OT_VIEW') ? (r) => setExecTarget(r) : undefined}
              onRecovery={
                can('OT_RECOVERY') || can('OT_TRANSFER') ? (r) => setRecoveryTarget(r) : undefined
              }
              onClose={can('OT_CLOSE') ? act('closed', otService.close) : undefined}
              onApprove={can('OT_APPROVE') ? act('approved', otService.approve) : undefined}
              onPreOp={can('OT_PRE_OP') ? act('moved to pre-op', otService.preOp) : undefined}
              onAnaesthesia={
                can('OT_ANAESTHESIA_CLEARANCE') ? (r) => setAnaesthesiaTarget(r) : undefined
              }
              onPostpone={can('OT_RESCHEDULE') ? act('postponed', otService.postpone) : undefined}
            />
          )}
        </main>
      </div>

      {teamTarget && (
        <SurgeryTeamModal surgery={teamTarget} onClose={() => setTeamTarget(null)} />
      )}
      {execTarget && (
        <SurgeryExecutionModal
          surgery={execTarget}
          onClose={() => {
            setExecTarget(null);
            load();
          }}
        />
      )}
      {recoveryTarget && (
        <RecoveryModal
          surgery={recoveryTarget}
          onClose={() => {
            setRecoveryTarget(null);
            load();
          }}
        />
      )}
      {anaesthesiaTarget && (
        <AnaesthesiaClearanceModal
          surgery={anaesthesiaTarget}
          onClose={() => setAnaesthesiaTarget(null)}
          onRecorded={load}
        />
      )}
      {scheduleTarget && (
        <ScheduleSurgeryModal
          surgery={scheduleTarget}
          onClose={() => setScheduleTarget(null)}
          onScheduled={() => {
            setScheduleTarget(null);
            load();
          }}
        />
      )}

      <ProfileModal isOpen={profileOpen} onClose={() => setProfileOpen(false)} user={user} />
    </div>
  );
};

export default OtInchargeDashboard;
