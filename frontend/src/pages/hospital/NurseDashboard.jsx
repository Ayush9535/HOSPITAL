import React, { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Navbar from '../../components/Navbar';
import NotificationBell from '../../components/NotificationBell';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import useWebSocket from '../../hooks/useWebSocket';
import authService from '../../services/authService';
import nurseScheduleService from '../../services/nurseScheduleService';
import nurseService from '../../services/nurseService';
import IcuBedBoard from './icu/IcuBedBoard';
import MyAttendanceView from './nurse/MyAttendanceView';
import MyPatientsView from './nurse/MyPatientsView';
import MyShiftsView from './nurse/MyShiftsView';
import MyTasksView from './nurse/MyTasksView';
import NurseFormsView from './nurse/NurseFormsView';
import NurseOverviewView from './nurse/NurseOverviewView';
import NursePatientDetail from './nurse/NursePatientDetail';

/**
 * NurseDashboard - Nurse portal shell.
 * Sidebar + Navbar layout (mirrors PharmacyDashboard) with view switching.
 * On-shift status is derived from the nurse's schedule (Phase B) — there is
 * no manual "Start/End Shift" gate; the dashboard is always accessible. A
 * status badge in the header reflects whether the current time falls inside
 * today's scheduled shift window.
 */
const NurseDashboard = () => {
  const [user, setUser] = useState(authService.getCurrentUser());
  const navigate = useNavigate();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  const [activeTab, setActiveTab] = useState('dashboard');
  const [selectedAdmissionId, setSelectedAdmissionId] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);

  // Schedule-derived on-shift status (read-only)
  const [onShift, setOnShift] = useState(false);
  const [shiftWindow, setShiftWindow] = useState(null); // "HH:mm–HH:mm" for today, if scheduled
  const [coverage, setCoverage] = useState([]); // active substitutions where I'm the replacement

  const handleRefresh = useCallback(() => setRefreshKey((k) => k + 1), []);
  useWebSocket(user, setUser, handleRefresh);

  useEffect(() => {
    let active = true;
    nurseService
      .getShiftStatus()
      .then((d) => {
        if (active) setOnShift(!!d?.onShift);
      })
      .catch(() => {
        if (active) setOnShift(false);
      });

    const today = new Date().toISOString().slice(0, 10);
    nurseScheduleService
      .getMine(today, today)
      .then((rows) => {
        if (!active || !Array.isArray(rows) || rows.length === 0) return;
        const s = rows[0];
        if (s?.startTime && s?.endTime) {
          setShiftWindow(`${s.startTime.slice(0, 5)}–${s.endTime.slice(0, 5)}`);
        }
      })
      .catch(() => {});

    nurseService
      .getMyCoverage()
      .then((rows) => {
        if (active) setCoverage(Array.isArray(rows) ? rows : []);
      })
      .catch(() => {
        if (active) setCoverage([]);
      });
    return () => {
      active = false;
    };
  }, [refreshKey]);

  const doLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  const handleTabChange = (tabId) => {
    setActiveTab(tabId);
    setSelectedAdmissionId(null);
  };

  const openPatient = (admissionId) => {
    setSelectedAdmissionId(admissionId);
    setActiveTab('patient-detail');
  };

  const sidebarTabs = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'my-patients', label: 'My Patients' },
    { id: 'my-tasks', label: 'My Tasks' },
    { id: 'my-shifts', label: 'My Shifts' },
    { id: 'my-attendance', label: 'My Attendance' },
    { id: 'forms', label: 'Forms' },
    ...(user?.modules?.includes('ICU') ? [{ id: 'icu-beds', label: 'ICU Beds' }] : []),
  ];

  const renderContent = () => {
    if (activeTab === 'patient-detail' && selectedAdmissionId) {
      return (
        <NursePatientDetail
          admissionId={selectedAdmissionId}
          onBack={() => handleTabChange('my-patients')}
          refreshKey={refreshKey}
        />
      );
    }
    switch (activeTab) {
      case 'my-patients':
        return <MyPatientsView onOpenPatient={openPatient} refreshKey={refreshKey} />;
      case 'my-tasks':
        return <MyTasksView refreshKey={refreshKey} />;
      case 'my-shifts':
        return <MyShiftsView refreshKey={refreshKey} />;
      case 'my-attendance':
        return <MyAttendanceView refreshKey={refreshKey} />;
      case 'forms':
        return <NurseFormsView />;
      case 'icu-beds':
        // Row content is narrowed server-side to this nurse's assigned patients.
        return <IcuBedBoard refreshKey={refreshKey} />;
      case 'dashboard':
      default:
        return <NurseOverviewView onOpenPatient={openPatient} refreshKey={refreshKey} />;
    }
  };

  const titleFor = () => {
    if (activeTab === 'patient-detail') return 'Patient Details';
    if (activeTab === 'my-patients') return 'My Patients';
    if (activeTab === 'my-tasks') return 'My Tasks';
    if (activeTab === 'my-shifts') return 'My Shifts';
    if (activeTab === 'my-attendance') return 'My Attendance';
    if (activeTab === 'forms') return 'Forms';
    return 'Nurse Dashboard';
  };

  return (
    <div className="flex h-screen bg-white overflow-hidden">
      <Sidebar
        title="Nurse Portal"
        tabs={sidebarTabs}
        activeTab={activeTab === 'patient-detail' ? 'my-patients' : activeTab}
        onTabChange={handleTabChange}
        footerTitle="Hospital"
        footerData={user?.hospitalName || 'HMS Medical Center'}
        variant="plain"
        isCollapsed={sidebarCollapsed}
      />

      <div className="flex-1 flex flex-col h-full relative overflow-hidden">
        <Navbar
          title={titleFor()}
          user={user}
          onLogout={doLogout}
          onProfile={() => setProfileOpen(true)}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
          actions={<NotificationBell refreshKey={refreshKey} />}
        />

        <main className="flex-1 overflow-x-hidden overflow-y-auto bg-[#fafafa] p-4 md:p-6">
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-3">
              <div className="w-1 h-6 bg-gray-900 rounded-full"></div>
              <h2 className="text-lg font-bold text-gray-800">{titleFor()}</h2>
            </div>
            <span
              className={`inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1 rounded-full ${
                onShift ? 'text-green-700 bg-green-50' : 'text-gray-500 bg-gray-100'
              }`}
            >
              <span
                className={`w-2 h-2 rounded-full ${onShift ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`}
              ></span>
              {onShift ? 'On Shift' : 'Off Shift'}
              {shiftWindow ? ` (${shiftWindow})` : ''}
            </span>
          </div>
          {coverage.length > 0 && (
            <div className="mb-6 rounded-xl border border-indigo-200 bg-indigo-50 px-4 py-3 text-sm text-indigo-800">
              <span className="font-semibold">
                Covering {coverage.length} nurse{coverage.length > 1 ? 's' : ''}
              </span>{' '}
              until{' '}
              {new Date(
                coverage.reduce((m, s) => (s.toDate > m ? s.toDate : m), coverage[0].toDate)
              ).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}
              . Their patients appear in your My Patients list.
            </div>
          )}
          {renderContent()}
        </main>
      </div>

      <ProfileModal isOpen={profileOpen} onClose={() => setProfileOpen(false)} />
    </div>
  );
};

export default NurseDashboard;
