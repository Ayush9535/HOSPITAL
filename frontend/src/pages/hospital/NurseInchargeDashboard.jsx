import React, { useState, useCallback, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import EmptyState from '../../components/EmptyState';
import LoadingSpinner from '../../components/LoadingSpinner';
import Navbar from '../../components/Navbar';
import NotificationBell from '../../components/NotificationBell';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import { useToast } from '../../context/ToastContext';
import useWebSocket from '../../hooks/useWebSocket';
import authService from '../../services/authService';
import nurseService from '../../services/nurseService';
import HospitalCalendar from './HospitalCalendar';
import IcuBedBoard from './icu/IcuBedBoard';
import NursePatientDetail from './nurse/NursePatientDetail';
import AttendanceView from './nurse-incharge/AttendanceView';
import CoverageView from './nurse-incharge/CoverageView';
import InchargeOverview from './nurse-incharge/InchargeOverview';
import MyNursesView from './nurse-incharge/MyNursesView';
import ShiftScheduleView from './nurse-incharge/ShiftScheduleView';
import WardBedsView from './nurse-incharge/WardBedsView';

/**
 * NurseInchargeDashboard - Phase A1 shell for the Nurse Incharge role.
 * Mirrors NurseDashboard's Sidebar + Navbar layout. Real content ("My Nurses"
 * roster management, "My Ward Patients" bedside views) lands in a later phase
 * — these tabs are placeholders for now.
 */
const NurseInchargeDashboard = () => {
  const [user, setUser] = useState(authService.getCurrentUser());
  const navigate = useNavigate();
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [activeTab, setActiveTab] = useState('dashboard');
  // The chart the incharge has open, if any. Ward-scoped: the list they opened it from
  // only contains their wards, and the server re-checks on every read and write.
  const [selectedAdmissionId, setSelectedAdmissionId] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const handleRefresh = useCallback(() => setRefreshKey((k) => k + 1), []);
  useWebSocket(user, setUser, handleRefresh);

  const doLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  const sidebarTabs = [
    { id: 'dashboard', label: 'Dashboard' },
    { id: 'my-nurses', label: 'My Nurses' },
    { id: 'my-ward-patients', label: 'My Ward Patients' },
    { id: 'unassigned-patients', label: 'Unassigned Patients' },
    { id: 'schedule', label: 'Schedule' },
    { id: 'attendance', label: 'Attendance' },
    { id: 'beds', label: 'Beds' },
    ...(user?.modules?.includes('ICU') ? [{ id: 'icu-beds', label: 'ICU Beds' }] : []),
    { id: 'coverage', label: 'Coverage' },
    { id: 'calendar', label: 'Calendar' },
  ];

  const titleFor = () => {
    if (activeTab === 'patient-detail') return 'Patient Chart';
    if (activeTab === 'dashboard') return 'Dashboard';
    if (activeTab === 'my-ward-patients') return 'My Ward Patients';
    if (activeTab === 'unassigned-patients') return 'Unassigned Patients';
    if (activeTab === 'schedule') return 'Schedule';
    if (activeTab === 'attendance') return 'Attendance';
    if (activeTab === 'beds') return 'Beds';
    if (activeTab === 'icu-beds') return 'ICU Beds';
    if (activeTab === 'coverage') return 'Coverage';
    if (activeTab === 'calendar') return 'Calendar';
    return 'My Nurses';
  };

  const renderContent = () => {
    if (activeTab === 'patient-detail' && selectedAdmissionId) {
      return (
        <NursePatientDetail
          admissionId={selectedAdmissionId}
          onBack={() => {
            setSelectedAdmissionId(null);
            setActiveTab('my-ward-patients');
          }}
          refreshKey={refreshKey}
        />
      );
    }
    switch (activeTab) {
      case 'dashboard':
        return <InchargeOverview onNavigate={setActiveTab} refreshKey={refreshKey} />;
      case 'my-ward-patients':
        return (
          <WardPatientsView
            refreshKey={refreshKey}
            onOpenPatient={(id) => {
              setSelectedAdmissionId(id);
              setActiveTab('patient-detail');
            }}
          />
        );
      case 'unassigned-patients':
        return <UnassignedPatientsView refreshKey={refreshKey} />;
      case 'schedule':
        return <ShiftScheduleView />;
      case 'attendance':
        return <AttendanceView />;
      case 'beds':
        return <WardBedsView />;
      case 'icu-beds':
        return <IcuBedBoard refreshKey={refreshKey} />;
      case 'coverage':
        return <CoverageView />;
      case 'calendar':
        return <HospitalCalendar />;
      case 'my-nurses':
        return <MyNursesView refreshKey={refreshKey} />;
      default:
        return (
          <div className="bg-white rounded-2xl border border-gray-200/80 p-8 text-center text-gray-500">
            Select a tab.
          </div>
        );
    }
  };

  return (
    <div className="flex h-screen bg-white overflow-hidden">
      <Sidebar
        title="Nurse Incharge"
        tabs={sidebarTabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
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
          </div>
          {renderContent()}
        </main>
      </div>

      <ProfileModal isOpen={profileOpen} onClose={() => setProfileOpen(false)} />
    </div>
  );
};

/**
 * WardPatientsView - active admissions across the incharge's wards, with an
 * inline "Assign Nurse" action per row (staff nurses from that patient's ward).
 */
const WardPatientsView = ({ refreshKey, onOpenPatient }) => {
  const { success: toastSuccess, error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [patients, setPatients] = useState([]);
  const [localRefresh, setLocalRefresh] = useState(0);
  const [assigningFor, setAssigningFor] = useState(null); // ipdAdmissionId or null
  const [wardNurses, setWardNurses] = useState({}); // wardId -> [{id,name}]
  const [nursesLoading, setNursesLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    nurseService
      .getWardPatients()
      .then((d) => {
        if (active) setPatients(Array.isArray(d) ? d : []);
      })
      .catch((e) => {
        if (active) toastError(e?.response?.data?.error || 'Failed to load ward patients');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refreshKey, localRefresh, toastError]);

  const openAssign = async (patient) => {
    setAssigningFor(patient.ipdAdmissionId);
    if (patient.wardId && !wardNurses[patient.wardId]) {
      setNursesLoading(true);
      try {
        const list = await nurseService.getWardStaffNurses(patient.wardId);
        setWardNurses((prev) => ({ ...prev, [patient.wardId]: Array.isArray(list) ? list : [] }));
      } catch (e) {
        toastError(e?.response?.data?.error || 'Failed to load nurses for this ward');
      } finally {
        setNursesLoading(false);
      }
    }
  };

  const handleAssign = async (ipdAdmissionId, nurseProfileId) => {
    if (!nurseProfileId) return;
    setSaving(true);
    try {
      await nurseService.assignPatientNurse(ipdAdmissionId, Number(nurseProfileId));
      toastSuccess('Nurse assigned');
      setAssigningFor(null);
      setLocalRefresh((k) => k + 1);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to assign nurse');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <LoadingSpinner />;

  if (patients.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No admitted patients"
        message="Patients admitted to your ward will show up here."
      />
    );
  }

  return (
    <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
            <th className="px-4 py-3">PATIENT</th>
            <th className="px-4 py-3">AGE/SEX</th>
            <th className="px-4 py-3">WARD</th>
            <th className="px-4 py-3">IPD NO.</th>
            <th className="px-4 py-3">STATUS</th>
            <th className="px-4 py-3 text-right">ACTIONS</th>
          </tr>
        </thead>
        <tbody>
          {patients.map((p) => (
            <tr key={p.ipdAdmissionId} className="border-b border-gray-100 hover:bg-gray-50">
              <td className="px-4 py-3 font-medium text-gray-900">{p.patientName || '—'}</td>
              <td className="px-4 py-3 text-gray-600">
                {p.age != null ? p.age : '—'}
                {p.gender ? ` / ${p.gender}` : ''}
              </td>
              <td className="px-4 py-3 text-gray-600">{p.wardName || '—'}</td>
              <td className="px-4 py-3 text-gray-600">{p.ipdNumber || '—'}</td>
              <td className="px-4 py-3">
                <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                  {p.status || '—'}
                </span>
              </td>
              <td className="px-4 py-3 text-right whitespace-nowrap">
                {assigningFor === p.ipdAdmissionId ? (
                  <div className="inline-flex items-center gap-2">
                    <select
                      className="px-2 py-1 text-xs border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
                      disabled={nursesLoading || saving}
                      defaultValue=""
                      onChange={(e) => handleAssign(p.ipdAdmissionId, e.target.value)}
                    >
                      <option value="" disabled>
                        {nursesLoading ? 'Loading…' : 'Select nurse'}
                      </option>
                      {(wardNurses[p.wardId] || []).map((n) => (
                        <option key={n.id} value={n.id}>
                          {n.name}
                        </option>
                      ))}
                    </select>
                    <button
                      onClick={() => setAssigningFor(null)}
                      className="px-2 py-1 text-xs font-semibold rounded-lg bg-gray-100 text-gray-600 hover:bg-gray-200"
                    >
                      Cancel
                    </button>
                  </div>
                ) : (
                  <div className="inline-flex items-center gap-2">
                    <button
                      onClick={() => onOpenPatient(p.ipdAdmissionId)}
                      className="px-3 py-1 text-xs font-semibold rounded-lg bg-primary-600 text-white hover:bg-primary-700"
                    >
                      Open Chart
                    </button>
                    <button
                      onClick={() => openAssign(p)}
                      className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800"
                    >
                      Assign Nurse
                    </button>
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export const UnassignedPatientsView = ({ refreshKey }) => {
  const { success: toastSuccess, error: toastError } = useToast();
  const [patients, setPatients] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null);
  const [nurses, setNurses] = useState([]);
  const [nurseProfileId, setNurseProfileId] = useState('');
  const [saving, setSaving] = useState(false);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    nurseService.getUnassignedPatients().then((data) => {
      if (active) setPatients(Array.isArray(data) ? data : []);
    }).catch((error) => {
      if (active) toastError(error?.response?.data?.error || 'Failed to load unassigned patients');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [refreshKey, refresh, toastError]);

  const openAssign = async (patient) => {
    setSelected(patient);
    setNurseProfileId('');
    try {
      const data = await nurseService.getWardStaffNurses(patient.wardId);
      setNurses(Array.isArray(data) ? data : []);
    } catch (error) {
      toastError(error?.response?.data?.error || 'Failed to load nurses for this ward');
    }
  };

  const assign = async () => {
    if (!selected || !nurseProfileId) return;
    setSaving(true);
    try {
      await nurseService.assignPatientNurse(selected.ipdAdmissionId, Number(nurseProfileId));
      toastSuccess('Primary nurse assigned');
      setSelected(null);
      setRefresh((value) => value + 1);
    } catch (error) {
      // Preserve the dialog and selection so an operator can correct/retry the action.
      toastError(error?.response?.data?.error || 'Failed to assign nurse');
    } finally { setSaving(false); }
  };

  if (loading) return <LoadingSpinner />;
  if (!patients.length) return <EmptyState icon={null} title="No unassigned patients" message="All active patients in your wards have a primary nurse." />;
  return (
    <div className="space-y-4">
      <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-4 py-3">These patients are visible to ward staff but need a primary nurse for worklist ownership.</p>
      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto"><table className="min-w-full text-sm"><thead><tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200"><th className="px-4 py-3">PATIENT</th><th className="px-4 py-3">IPD NO.</th><th className="px-4 py-3">WARD / BED</th><th className="px-4 py-3">ADMITTED</th><th className="px-4 py-3 text-right">ACTION</th></tr></thead><tbody>{patients.map((patient) => <tr key={patient.ipdAdmissionId} className="border-b border-gray-100"><td className="px-4 py-3 font-medium">{patient.patientName || '—'}</td><td className="px-4 py-3">{patient.ipdNumber || '—'}</td><td className="px-4 py-3">{patient.wardName || '—'} {patient.bedCode ? ` / ${patient.bedCode}` : ''}</td><td className="px-4 py-3">{patient.admissionDateTime ? new Date(patient.admissionDateTime).toLocaleString('en-IN') : '—'}</td><td className="px-4 py-3 text-right"><button onClick={() => openAssign(patient)} className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white">Assign nurse</button></td></tr>)}</tbody></table></div>
      {selected && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"><div className="bg-white rounded-xl p-5 w-full max-w-md space-y-4"><h3 className="font-bold">Assign primary nurse</h3><p className="text-sm text-gray-600">{selected.patientName || selected.ipdNumber}</p><select aria-label="Primary nurse" value={nurseProfileId} onChange={(event) => setNurseProfileId(event.target.value)} className="w-full border rounded-lg p-2"><option value="">Select nurse</option>{nurses.map((nurse) => <option key={nurse.id} value={nurse.id}>{nurse.name}</option>)}</select><div className="flex justify-end gap-2"><button onClick={() => setSelected(null)} disabled={saving} className="px-3 py-2 text-sm">Cancel</button><button onClick={assign} disabled={!nurseProfileId || saving} className="px-3 py-2 text-sm rounded-lg bg-gray-900 text-white">{saving ? 'Assigning…' : 'Assign'}</button></div></div></div>}
    </div>
  );
};

export default NurseInchargeDashboard;
