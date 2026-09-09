import { createColumnHelper } from '@tanstack/react-table';
import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ActionMenu from '../../components/ActionMenu';
import AppointmentModal from '../../components/AppointmentModal';
import ConfirmationModal from '../../components/ConfirmationModal';
import DataTable from '../../components/DataTable';
import DateSelect from '../../components/DateSelect';
import EmptyState from '../../components/EmptyState';
import FollowUpPanel from '../../components/FollowUpPanel';
import HistoryDrawer from '../../components/HistoryDrawer';
import HospitalInventoryTab from '../../components/HospitalInventoryTab';
import IpdAdmitModal from '../../components/IpdAdmitModal';
import LowStockBanner from '../../components/LowStockBanner';
import MedicineInventoryTab from '../../components/MedicineInventoryTab';
import Navbar from '../../components/Navbar';
import OpdPaymentFields, {
  isPayFirst,
  validateOpdPayment,
} from '../../components/OpdPaymentFields';
import PageHeader from '../../components/PageHeader';
import PatientDetailsModal from '../../components/PatientDetailsModal';
import PatientFormFields, {
  patientFormRules,
  stripPatientPayload,
} from '../../components/PatientFormFields';
import PatientModal from '../../components/PatientModal';
import PrescriptionModal from '../../components/PrescriptionModal';
import PrescriptionViewModal from '../../components/PrescriptionViewModal';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import { SkeletonStatsGrid, SkeletonOverviewDual, SkeletonTable } from '../../components/Skeleton';
import StatusBadge from '../../components/StatusBadge';
import { useToast } from '../../context/ToastContext';
import useDebounce from '../../hooks/useDebounce'; // BUG-017: standardised debounce hook
import useEnabledVitals from '../../hooks/useEnabledVitals';
import useOtPermissions from '../../hooks/useOtPermissions';
import useWebSocket from '../../hooks/useWebSocket';
import authService from '../../services/authService';
import hospitalService from '../../services/hospitalService';
// BUG-028: single source-of-truth for base URL
import otService from '../../services/otService';
import { extractApiError, safeLoadMessage } from '../../utils/apiError';
import { formatDateTime, formatTime } from '../../utils/date';
import { createOptionalModuleFetcher } from '../../utils/optionalModule';
import { printPdf } from '../../utils/printPdf';
import { validateForm } from '../../utils/validation';
import BillingTable from './BillingTable';
import IcuBedBoard from './icu/IcuBedBoard';
import IcuDashboard from './icu/IcuDashboard';
import AnaesthesiaClearanceModal from './ot/AnaesthesiaClearanceModal';
import DayCareSurgeryModal from './ot/DayCareSurgeryModal';
import OtAnalyticsStrip from './ot/OtAnalyticsStrip';
import OtBoard from './ot/OtBoard';
import OtDayBoard from './ot/OtDayBoard';
import OtListPrint from './ot/OtListPrint';
import RecoveryModal from './ot/RecoveryModal';
import ScheduleSurgeryModal from './ot/ScheduleSurgeryModal';
import SurgeryExecutionModal from './ot/SurgeryExecutionModal';
import SurgeryTeamModal from './ot/SurgeryTeamModal';

const ReceptionistDashboard = () => {
  const [user, setUser] = useState(() => authService.getCurrentUser());
  const modules = user?.modules || [];
  const hasOPD = modules.includes('OPD');
  const hasIPD = modules.includes('IPD');
  const hasBilling = modules.includes('BILLING');
  const hasAppointments = modules.includes('APPOINTMENTS');
  const hasMedicalInventory = modules.includes('MEDICAL_INVENTORY');
  const hasHospitalInventory = modules.includes('HOSPITAL_INVENTORY');
  const hasOT = modules.includes('OT');
  // Reception admits and moves patients, so it needs live ICU capacity. IcuBoardService
  // withholds diagnosis and vitals for this role (ICU-1 D6); the tab flag does not.
  const hasICU = modules.includes('ICU');
  // Tenant-aware label: clinic logins say "Clinic" wherever we'd otherwise say "Hospital".
  const tenantWord = user?.hospitalType === 'CLINIC' ? 'Clinic' : 'Hospital';
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') || 'overview';
  const viewFilter = searchParams.get('appointmentFilter') || 'today';

  // Helper to switch tabs
  const setActiveTab = (tab) => {
    const newParams = { tab };
    if (tab === 'appointments' && viewFilter) newParams.appointmentFilter = viewFilter;
    setSearchParams(newParams);
  };

  // Helper to set appointment filter
  const setViewFilter = (filter) => {
    setSearchParams({ tab: activeTab, appointmentFilter: filter });
  };

  const [appointments, setAppointments] = useState([]);
  const [todaysFollowUps, setTodaysFollowUps] = useState([]);
  const [patients, setPatients] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [opds, setOpds] = useState([]);
  const [queueEntries, setQueueEntries] = useState([]);
  const [currentPatientName, setCurrentPatientName] = useState(null);
  const [nextPatientName, setNextPatientName] = useState(null);
  const [selectedDoctorForQueue, setSelectedDoctorForQueue] = useState('');
  const [billing, setBilling] = useState([]);
  const [billingStatus, setBillingStatus] = useState('PENDING');
  const [recPrintingId, setRecPrintingId] = useState(null);
  const [loading, setLoading] = useState(false);
  // A failed load is its own state. Without it the screen falls back to whatever it already
  // held -- [] on a first load -- and an unreachable server draws a desk with no patients
  // booked, which is a sentence the software has no business saying.
  const [loadError, setLoadError] = useState('');
  // Scoped loading indicator for just the Appointments list, so switching the
  // Today/Upcoming filter doesn't blow away the whole dashboard's full-page
  // skeleton (that's reserved for actual tab switches).
  const [appointmentsLoading, setAppointmentsLoading] = useState(false);

  const [customFees, setCustomFees] = useState([]);
  const [standardFees, setStandardFees] = useState({ consultationFee: '0', casePaperFee: '0' });
  const [editBillItemsModal, setEditBillItemsModal] = useState({
    isOpen: false,
    billId: null,
    items: [],
    medicines: [],
    patientName: '',
    billNumber: '',
  });
  const [editBillItemsSubmitting, setEditBillItemsSubmitting] = useState(false);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isAddPatientModalOpen, setIsAddPatientModalOpen] = useState(false);
  const [isOpdModalOpen, setIsOpdModalOpen] = useState(false);
  const [opdSubmitting, setOpdSubmitting] = useState(false);
  const [isIpdAdmitOpen, setIsIpdAdmitOpen] = useState(false);
  const [ipdOpdForAdmit, setIpdOpdForAdmit] = useState(null);
  const [ipdSubTab, setIpdSubTab] = useState('current'); // 'current' | 'requested'
  const [opdForm, setOpdForm] = useState({
    patientId: null,
    receptionistId: user?.id || null,
    doctorId: null,
    bp: '',
    temperature: '',
    pulse: '',
    weight: '',
    height: '',
    customVitals: {},
    spo2: '',
    problem: '',
    visitType: 'NEW',
    // Only used when Bill Payment = First (fee collected at OPD entry).
    paymentMethod: 'CASH',
    paymentReference: '',
  });
  const { isOn, customs } = useEnabledVitals();
  const [createdOpd, setCreatedOpd] = useState(null);
  const [selectedPatient, setSelectedPatient] = useState(null);
  const [patientSearchText, setPatientSearchText] = useState('');
  const [showPatientDropdown, setShowPatientDropdown] = useState(false);
  // OPD "New Patient": the patient fields are rendered inline in this same OPD form, so one
  // submit registers the patient and opens their OPD. Same fields, same client rules and the
  // same endpoint as the Patients tab - the existing-patient search path below is untouched.
  const [opdPatientMode, setOpdPatientMode] = useState('existing'); // 'existing' | 'new'
  const [newPatientForm, setNewPatientForm] = useState({ insurance: 'NO' });
  const [newPatientErrors, setNewPatientErrors] = useState({});
  // POST /hospital/patients allows only these roles, so the option is hidden for
  // anyone else rather than letting them walk into a 403.
  const canCreatePatient = user?.role === 'HOSPITAL_ADMIN' || user?.role === 'RECEPTIONIST';

  // Reset OPD patient search state when modal is opened or closed
  useEffect(() => {
    if (!isOpdModalOpen) {
      setPatientSearchText('');
      setShowPatientDropdown(false);
      setOpdPatientMode('existing');
      setNewPatientForm({ insurance: 'NO' });
      setNewPatientErrors({});
      setOpdForm((prev) => ({
        ...prev,
        patientId: null,
        bp: '',
        temperature: '',
        pulse: '',
        weight: '',
        height: '',
        customVitals: {},
        spo2: '',
        problem: '',
        visitType: 'NEW',
      }));
    }
  }, [isOpdModalOpen]);

  // Prescription Modal State
  const [prescriptionModal, setPrescriptionModal] = useState({
    isOpen: false,
    data: null,
    appointmentId: null,
  });

  // View Prescription Modal State
  const [viewPrescriptionModal, setViewPrescriptionModal] = useState({
    isOpen: false,
    patient: null,
  });

  // Payment Modal State
  const [paymentModal, setPaymentModal] = useState({
    isOpen: false,
    billId: null,
    amount: null,
    patientName: '',
  });

  // Payment Success Modal State
  const [paymentSuccessModal, setPaymentSuccessModal] = useState({
    isOpen: false,
    billId: null,
    patientName: '',
    amount: 0,
  });

  // Pagination
  const [page, setPage] = useState(0); // 0-indexed for Spring Boot
  const [pageSize, setPageSize] = useState(10);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [searchInput, setSearchInput] = useState('');
  const searchTerm = useDebounce(searchInput, 500); // BUG-017: debounce search to prevent per-keystroke API calls
  const activeRequestRef = useRef(0);

  // Clear search when switching tabs
  useEffect(() => {
    setSearchInput('');
    setPage(0);
  }, [activeTab]);
  const todayStr = (() => {
    const t = new Date();
    return (
      t.getFullYear() +
      '-' +
      String(t.getMonth() + 1).padStart(2, '0') +
      '-' +
      String(t.getDate()).padStart(2, '0')
    );
  })();
  const [opdDateFilter, setOpdDateFilter] = useState(todayStr);
  const [opdTabView, setOpdTabView] = useState('Live');

  // Patient tab: All / Date toggle
  const [patientTabView, setPatientTabView] = useState('All');
  const [patientDateFilter, setPatientDateFilter] = useState(todayStr);

  const [confirmModal, setConfirmModal] = useState({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: null,
    showReasonInput: false,
    inputPlaceholder: '',
  });

  const [historyDrawer, setHistoryDrawer] = useState({
    isOpen: false,
    entityType: '',
    entityId: null,
    entityName: '',
  });

  // Sidebar collapse state
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  // Patient Details Modal
  const [patientDetailsModal, setPatientDetailsModal] = useState({ isOpen: false, patient: null });

  // OT (Operation Theatre) tab
  const [otFilter, setOtFilter] = useState('board'); // 'board' | 'day' | 'requests'
  const [isDayCareModalOpen, setIsDayCareModalOpen] = useState(false);
  const [isOtListOpen, setIsOtListOpen] = useState(false);
  const [otTeamTarget, setOtTeamTarget] = useState(null);
  const [otExecTarget, setOtExecTarget] = useState(null);
  const [otRecoveryTarget, setOtRecoveryTarget] = useState(null);
  const { can: canOt } = useOtPermissions();
  const [otRows, setOtRows] = useState([]);
  const [otLoading, setOtLoading] = useState(false);
  const [otScheduleTarget, setOtScheduleTarget] = useState(null);
  const [otAnaesthesiaTarget, setOtAnaesthesiaTarget] = useState(null);

  const { success, error: toastError, info } = useToast();
  const navigate = useNavigate();

  // Stats
  const [stats, setStats] = useState({ today: 0, pending: 0, total: 0 });

  // Only an actual tab switch shows the full-page loading skeleton — a
  // dedicated effect keyed on just [activeTab], so it can't misfire on
  // filter/search/page changes within the same tab. (An earlier version
  // tracked "is this a tab switch" via a manually-mutated ref compared
  // against activeTab, which doesn't survive React 18 StrictMode's
  // double-invocation of effects in dev: the ref update from the first
  // invocation made the second one think nothing had changed, so the
  // request that "won" the stale-response race never had showSpinner set
  // and loading got stuck true forever. Deriving purely from React's own
  // dependency-array diffing avoids that class of bug entirely.)
  useEffect(() => {
    setLoading(true);
  }, [activeTab]);

  useEffect(() => {
    loadData(false);
  }, [
    activeTab,
    page,
    searchTerm,
    viewFilter,
    pageSize,
    selectedDoctorForQueue,
    billingStatus,
    opdDateFilter,
    opdTabView,
    patientTabView,
    patientDateFilter,
    ipdSubTab,
  ]); // Add pageSize & doctor filter to dependencies

  // WebSocket connection will be initialized below loadData definition to avoid ReferenceError

  // Auto-select doctor in OPD form if only one is available
  useEffect(() => {
    if (isOpdModalOpen && doctors && doctors.length === 1) {
      setOpdForm((prev) => ({ ...prev, doctorId: doctors[0].id }));
    }
  }, [isOpdModalOpen, doctors]);

  const loadOt = React.useCallback(() => {
    if (!hasOT) return;
    setOtLoading(true);
    const p = otFilter === 'requests' ? otService.getRequests() : otService.getBoard();
    p.then((d) => setOtRows(Array.isArray(d) ? d : []))
      .catch(() => setOtRows([]))
      .finally(() => setOtLoading(false));
  }, [hasOT, otFilter]);

  useEffect(() => {
    if (activeTab === 'ot') loadOt();
  }, [activeTab, otFilter, loadOt]);

  /**
   * Appointment reads that must never take the desk down — see utils/optionalModule. This is the
   * role that suffered most: the appointment stats call was the FIRST statement of loadData, so a
   * 403 there skipped the OPD queue, the patient lookup, the doctor list, the IPD requests and
   * today's follow-ups behind one "Failed to load data" toast.
   */
  const fetchAppointmentData = createOptionalModuleFetcher(hasAppointments);

  const loadData = async (showSpinner = true) => {
    const requestId = ++activeRequestRef.current;
    if (showSpinner) setLoading(true);
    try {
      let appointmentLoadFailed = false;
      let appointmentLoadError = '';
      const reportAppointmentFailure = (message, err) => {
        console.error(message, err);
        appointmentLoadError = safeLoadMessage(err, "Couldn't load this screen. Please try again.");
        if (!appointmentLoadFailed) {
          appointmentLoadFailed = true;
          toastError('Failed to load appointments');
        }
      };

      // Appointment stats are optional. A failure remains visible, but cannot prevent the desk
      // from loading its queue, doctors, patients, or follow-ups.
      let statsData = {};
      try {
        statsData = await fetchAppointmentData(() => hospitalService.getAppointmentStats(), {});
      } catch (err) {
        reportAppointmentFailure('[ReceptionistDashboard] Failed to load appointment stats', err);
      }
      if (requestId !== activeRequestRef.current) return;

      let ipdRequestsCount = null;
      if (hasIPD) {
        try {
          ipdRequestsCount = await hospitalService.getPendingIpdRequestCount();
        } catch (e) {
          // Leave the count null so the tile can say it failed. Showing 0 here would be
          // indistinguishable from having no pending admissions.
          console.error('Failed to load IPD request count for stats', e);
        }
      }
      setStats({ ...statsData, ipdRequests: ipdRequestsCount });

      if (
        activeTab === 'overview' ||
        (activeTab === 'appointments' && hasAppointments) ||
        activeTab === 'opd' ||
        activeTab === 'queue' ||
        activeTab === 'ipd'
      ) {
        if (activeTab === 'overview' || activeTab === 'appointments') setAppointmentsLoading(true);
        // Mandatory desk data is deliberately separate from the optional appointments call.
        const [docData, patData, followUpsData] = await Promise.all([
          hospitalService.getDoctors('', 0, 100), // Fetch doctors for lookup
          hospitalService.getPatients('', 0, 1000), // Fetch ALL patients (up to 1000) for lookup dropdown
          activeTab === 'overview' ? hospitalService.getTodaysFollowUps() : Promise.resolve([]),
        ]);
        if (requestId !== activeRequestRef.current) return;

        if (activeTab === 'appointments' || activeTab === 'overview') {
          try {
            const apptData = await fetchAppointmentData(
              () => hospitalService.getAppointments(searchTerm, page, pageSize, viewFilter),
              { content: [] }
            );
            if (requestId !== activeRequestRef.current) return;

            if (apptData?.content) {
              setAppointments(apptData.content);
              setTotalPages(apptData.totalPages);
              setTotalElements(apptData.totalElements);
            } else if (Array.isArray(apptData)) {
              setAppointments(apptData);
              setTotalPages(1);
              setTotalElements(apptData.length);
            } else if (!hasAppointments) {
              setAppointments([]);
              setTotalPages(1);
              setTotalElements(0);
            }
          } catch (err) {
            reportAppointmentFailure('[ReceptionistDashboard] Failed to load appointments', err);
          }
        }

        if (activeTab === 'overview') {
          setTodaysFollowUps(followUpsData || []);
        }

        if (activeTab === 'opd') {
          // OPD tab: fetch opds separately
          let dateParam = '';
          let statusParam = '';
          if (opdTabView === 'Live') {
            dateParam = todayStr;
            statusParam = 'QUEUED';
          } else {
            dateParam = opdDateFilter;
            statusParam = ''; // all statuses on that date
          }
          const opdsData = await hospitalService.getOpds(
            searchTerm,
            page,
            pageSize,
            dateParam,
            statusParam
          );
          if (requestId !== activeRequestRef.current) return;
          let opdsArray = opdsData.content ? opdsData.content : opdsData;
          if (opdTabView === 'Live') {
            opdsArray = (opdsArray || []).filter((o) => o.status === 'QUEUED');
          }
          setOpds(opdsArray || []);
          setTotalPages(opdsData.totalPages || 1);
          setTotalElements(opdsData.totalElements || (opdsArray ? opdsArray.length : 0));
        } else if (activeTab === 'ipd') {
          if (ipdSubTab === 'current') {
            // IPD tab: fetch role-based admitted IPD admissions
            const ipdList = await hospitalService.getAdmittedIpdAdmissions();
            if (requestId !== activeRequestRef.current) return;
            let arr = ipdList || [];
            if (searchTerm && searchTerm.trim()) {
              const q = searchTerm.trim().toLowerCase();
              arr = arr.filter((item) => {
                const row = item.ipd || item;
                const ipdNumber = (row.ipdNumber || row.ipd?.ipdNumber || '')
                  .toString()
                  .toLowerCase();
                const patient = (item.patient?.name || row.patient?.name || item.patientName || '')
                  .toString()
                  .toLowerCase();
                const doctor = (item.doctor?.name || row.doctor?.name || item.doctorName || '')
                  .toString()
                  .toLowerCase();
                const ward = (item.ward?.wardName || row.wardName || row.ward || '')
                  .toString()
                  .toLowerCase();
                const bed = (item.bed?.bedCode || row.bedNumber || row.bed?.bedCode || '')
                  .toString()
                  .toLowerCase();
                const status = (row.status || '').toString().toLowerCase();
                return (
                  ipdNumber.includes(q) ||
                  patient.includes(q) ||
                  doctor.includes(q) ||
                  ward.includes(q) ||
                  bed.includes(q) ||
                  status.includes(q)
                );
              });
            }
            setOpds(arr);
            setTotalPages(1);
            setTotalElements((arr && arr.length) || 0);
          } else {
            // Requested patients: pending IPD requests, resolved server-side. The predicate
            // (ipdAdmitRecommended AND status != IN_IPD) used to be a client-side filter over one
            // 1000-row page of ALL OPDs -- a request past that page was invisible, and admitting a
            // patient only disappeared from the list once that same page happened to be refetched
            // with the now-updated status. The server now derives "pending" the same way on every
            // read, so a refetch after admit always excludes it, at any tenant size.
            const pending = await hospitalService.getPendingIpdRequests(page, pageSize);
            if (requestId !== activeRequestRef.current) return;
            let arr = pending.content || pending || [];
            if (searchTerm && searchTerm.trim()) {
              const q = searchTerm.trim().toLowerCase();
              arr = arr.filter((o) => {
                const patient = (o.patient?.name || o.patientName || '').toString().toLowerCase();
                const doctor = (o.doctor?.name || o.doctorName || '').toString().toLowerCase();
                const caseId = (o.caseId || '').toString().toLowerCase();
                return patient.includes(q) || doctor.includes(q) || caseId.includes(q);
              });
            }
            setOpds(arr);
            setTotalPages(pending.totalPages || 1);
            setTotalElements(pending.totalElements || arr.length);
          }
        }

        if (activeTab === 'queue' || activeTab === 'overview' || activeTab === 'opd') {
          // Fetch hospital queue or doctor's queue based on filter and compute current/next tokens
          let q = [];
          if (selectedDoctorForQueue) {
            q = await hospitalService.getDoctorQueue(selectedDoctorForQueue);
          } else {
            q = await hospitalService.getHospitalQueue();
          }
          if (requestId !== activeRequestRef.current) return;
          setQueueEntries(q || []);
          if (q && q.length > 0) {
            const sorted = [...q].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
            setCurrentPatientName(
              sorted[0]?.opd?.patient?.name || sorted[0]?.opd?.patientName || 'No Name'
            );
            setNextPatientName(
              sorted[1]
                ? sorted[1]?.opd?.patient?.name || sorted[1]?.opd?.patientName || 'No Name'
                : null
            );
          } else {
            setCurrentPatientName(null);
            setNextPatientName(null);
          }
        }

        // Set Doctors for lookup/dropdown
        if (docData?.content) setDoctors(docData.content);
        else setDoctors(docData || []);

        // Set Patients for lookup/dropdown in specific modal
        if (patData?.content) setPatients(patData.content);
        else setPatients(patData || []);
      } else if (activeTab === 'patients') {
        const dateParam = patientTabView === 'Date' ? patientDateFilter : '';
        const data = await hospitalService.getPatients(searchTerm, page, pageSize, dateParam);
        if (requestId !== activeRequestRef.current) return;
        if (data.content) {
          setPatients(data.content);
          setTotalPages(data.totalPages);
          setTotalElements(data.totalElements);
        } else {
          setPatients(data);
          setTotalPages(1);
          setTotalElements(data.length);
        }
      } else if (activeTab === 'billing') {
        const data = await hospitalService.getBills(searchTerm, page, pageSize, billingStatus);
        if (requestId !== activeRequestRef.current) return;
        if (data.content) {
          setBilling(data.content);
          setTotalPages(data.totalPages);
          setTotalElements(data.totalElements);
        } else {
          setBilling(data);
          setTotalPages(1);
          setTotalElements(data.length);
        }
      }
      setLoadError(appointmentLoadError);
    } catch (err) {
      if (requestId !== activeRequestRef.current) return;
      console.error(`[ReceptionistDashboard] Failed to load ${activeTab}:`, err);
      // safeLoadMessage, not extractApiError: this reaches a banner, and err.message carries
      // whatever the transport or the JVM threw. Rows already on screen are left alone -- a
      // failed refresh should not blank a list that was fine a moment ago.
      setLoadError(safeLoadMessage(err, "Couldn't load this screen. Please try again."));
      // Only show toast error if it was a user-initiated action (spinner shown)
      if (showSpinner) toastError('Failed to load data');
    } finally {
      if (requestId === activeRequestRef.current) {
        // Unconditionally clear — whichever request is current when it
        // finishes is the one that should decide the final loading
        // state, regardless of whether *this* call was the one that
        // originally turned it on.
        setLoading(false);
        setAppointmentsLoading(false);
      }
    }
  };

  // WebSocket real-time live sync (defined after loadData to avoid ReferenceError)
  useWebSocket(user, setUser, loadData);

  // Fetch fresh profile on mount to sync sessionStorage settings
  useEffect(() => {
    const fetchProfileOnMount = async () => {
      try {
        const profile = await authService.getProfile();
        const updatedUser = authService.updateCurrentUser(profile);
        if (updatedUser) {
          setUser(updatedUser);
        }
      } catch (err) {
        console.error('Failed to fetch profile on mount', err);
      }
    };
    fetchProfileOnMount();

    // 15-second background synchronization fallback
    const interval = setInterval(async () => {
      try {
        const profile = await authService.getProfile();
        const updatedUser = authService.updateCurrentUser(profile);
        if (updatedUser) {
          setUser(updatedUser);
        }
      } catch (err) {
        console.error('Background profile sync failed', err);
      }
    }, 15000);

    return () => clearInterval(interval);
  }, []);

  const openConfirmation = (
    title,
    message,
    action,
    showReasonInput = false,
    inputPlaceholder = 'Please provide a reason...'
  ) => {
    setConfirmModal({
      isOpen: true,
      title,
      message,
      onConfirm: action,
      showReasonInput,
      inputPlaceholder,
    });
  };

  const handleStatusUpdate = async (id, newStatus) => {
    if (!id) {
      toastError('Cannot update status: Invalid Appointment ID.');
      return;
    }

    if (newStatus === 'CANCELLED') {
      openConfirmation(
        'Cancel Appointment',
        'Are you sure you want to cancel this appointment? This action cannot be undone.',
        async (reason) => {
          const oldAppointments = [...appointments];
          setAppointments((prev) =>
            prev.map((appt) => (appt.id === id ? { ...appt, status: newStatus } : appt))
          );
          try {
            await hospitalService.updateAppointmentStatus(id, newStatus, reason);
            success('Appointment cancelled successfully');
            loadData(false);
          } catch (err) {
            console.error('Failed to cancel appointment:', err);
            setAppointments(oldAppointments);
            toastError('Failed to cancel appointment');
          }
        },
        true, // Show reason input
        'Reason for cancellation (required)'
      );
    } else {
      const oldAppointments = [...appointments];
      setAppointments((prev) =>
        prev.map((appt) => (appt.id === id ? { ...appt, status: newStatus } : appt))
      );
      try {
        await hospitalService.updateAppointmentStatus(id, newStatus);
        success(`Appointment status updated to ${newStatus}`);
        loadData(false);
      } catch (err) {
        console.error('Failed to update status:', err);
        setAppointments(oldAppointments);
        toastError('Failed to update status');
      }
    }
  };

  const handleBillStatus = async (id, status, billObj = null) => {
    // Open payment modal when marking PAID from receptionist
    if (status === 'PAID') {
      setPaymentModal({
        isOpen: true,
        billId: id,
        amount: billObj?.balance ?? billObj?.amount ?? null,
        patientName: billObj?.patientName || '',
      });
      return;
    }
    try {
      await hospitalService.updateBillStatus(id, status);
      success('Bill status updated');
      loadData();
    } catch (err) {
      toastError('Failed to update bill status');
    }
  };

  const confirmPayment = async (id, method, reference) => {
    try {
      await hospitalService.updateBillStatus(id, 'PAID', method, reference);
      success('Bill marked as PAID');
      loadData();
    } catch (err) {
      console.error(err);
      toastError('Failed to mark bill as paid');
    }
  };

  // Print server PDFs on the current page (hidden iframe), like the bill/case-paper prints.
  const openPdfInNewTab = (endpointPath) => {
    printPdf(endpointPath);
  };

  /**
   * Print the receipt on this page (hidden iframe), like the consultation documents and the
   * clinical forms — not in a stray new tab. The Payment Successful dialog has done its job
   * once print is invoked, so it closes itself rather than leaving "Print Receipt" / "Close"
   * sitting behind the print preview.
   */
  const handlePrintReceipt = async (id) => {
    setPaymentSuccessModal((prev) => ({ ...prev, isOpen: false }));
    const printed = await printPdf(`/hospital/billing/${id}/pdf`);
    if (!printed) toastError('Failed to open the receipt for printing');
  };

  const handleDownloadPatientsReport = () => {
    let endpoint = `/hospital/patients/report/pdf`;
    if (patientTabView === 'Date' && patientDateFilter) {
      endpoint += `?date=${patientDateFilter}`;
    }
    openPdfInNewTab(endpoint);
  };

  const handleDownloadOpdReport = () => {
    let endpoint = `/hospital/opd/report/pdf`;
    const params = [];
    if (opdTabView === 'Live') {
      params.push(`date=${todayStr}`);
      params.push(`status=QUEUED`);
      params.push(`reportType=LIVE`);
    } else {
      if (opdDateFilter) {
        params.push(`date=${opdDateFilter}`);
      }
      params.push(`reportType=DATE`);
    }
    if (params.length > 0) {
      endpoint += `?${params.join('&')}`;
    }
    openPdfInNewTab(endpoint);
  };

  const handleOpenEditBillItems = async (billObj) => {
    try {
      const [stdData, customData] = await Promise.all([
        hospitalService.getHospitalFees(),
        hospitalService.getCustomFees(),
      ]);
      setStandardFees({
        consultationFee: stdData.consultationFee != null ? stdData.consultationFee : '0',
        casePaperFee: stdData.casePaperFee != null ? stdData.casePaperFee : '0',
      });
      setCustomFees(customData || []);

      const mappedItems = (billObj.items || []).map((it) => ({
        id: it.id,
        name: it.description,
        defaultAmount: it.amount,
      }));

      setEditBillItemsModal({
        isOpen: true,
        billId: billObj.id,
        items: mappedItems,
        medicines: billObj.medicines || [],
        patientName: billObj.patientName || '',
        billNumber: billObj.customId || billObj.id,
      });
    } catch (err) {
      toastError('Failed to open bill editor');
    }
  };

  const handleSaveBillItems = async () => {
    setEditBillItemsSubmitting(true);
    try {
      const filteredItems = editBillItemsModal.items.filter(
        (it) => it.name && it.name.trim() !== ''
      );
      await hospitalService.updateBillItems(editBillItemsModal.billId, filteredItems);
      success('Bill items updated successfully');
      setEditBillItemsModal({
        isOpen: false,
        billId: null,
        items: [],
        medicines: [],
        patientName: '',
        billNumber: '',
      });
      loadData();
    } catch (err) {
      const msg = extractApiError(err, 'Failed to update bill items');
      toastError(msg);
    } finally {
      setEditBillItemsSubmitting(false);
    }
  };

  const updateBillItem = (index, field, value) => {
    setEditBillItemsModal((prev) => {
      const updated = [...prev.items];
      updated[index] = { ...updated[index], [field]: value };
      return { ...prev, items: updated };
    });
  };

  const removeBillItem = (index) => {
    setEditBillItemsModal((prev) => {
      const updated = prev.items.filter((_, i) => i !== index);
      return { ...prev, items: updated };
    });
  };

  const addBillItem = () => {
    setEditBillItemsModal((prev) => ({
      ...prev,
      items: [...prev.items, { name: '', defaultAmount: '' }],
    }));
  };

  /**
   * Open the prescription for one consultation, whichever way the patient arrived.
   *
   * Reception's prescription viewer used to be appointment-keyed end to end — it read
   * /doctors/consultation/{appointmentId} and printed /doctors/prescription/{appointmentId}/pdf.
   * A walk-in consultation has no appointment, so for a hospital without the APPOINTMENTS module
   * (and for the majority of visits at one with it) there was nothing to open. The doctor's side
   * never had this problem; it has read by OPD for a while.
   *
   * Both keys are supported rather than one replacing the other: an appointment-origin
   * consultation is still addressed by its appointment exactly as before.
   */
  const openPrescription = async ({ appointmentId = null, opdId = null }) => {
    try {
      setLoading(true);
      const data = appointmentId
        ? await hospitalService.getConsultationDetails(appointmentId)
        : await hospitalService.getConsultationDetailsByOpd(opdId);
      setPrescriptionModal({ isOpen: true, data, appointmentId, opdId });
    } catch (err) {
      console.error(err);
      toastError('Prescription not available yet.');
    } finally {
      setLoading(false);
    }
  };

  const handleViewPrescription = (appointmentId) => openPrescription({ appointmentId });

  const handleViewOpdPrescription = (opd) => openPrescription({ opdId: opd.id });

  const handleViewPatientPrescription = (patient) => {
    setViewPrescriptionModal({ isOpen: true, patient });
  };

  const handlePrintPrescription = () => {
    // Print through whichever key opened the modal. The OPD route already existed and is what
    // the doctor's dashboard has always used; reception simply never reached it.
    if (prescriptionModal.appointmentId) {
      openPdfInNewTab(`/hospital/doctors/prescription/${prescriptionModal.appointmentId}/pdf`);
      return;
    }
    if (prescriptionModal.opdId) {
      openPdfInNewTab(`/hospital/doctors/prescription/opd/${prescriptionModal.opdId}/pdf`);
    }
  };

  const handleHistory = (type, id, name) => {
    setHistoryDrawer({
      isOpen: true,
      entityType: type,
      entityId: id,
      entityName: name,
    });
  };

  const handleLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  const handlePatientStatusUpdate = async (patientId, newStatus) => {
    try {
      await hospitalService.updatePatientStatus(patientId, newStatus);
      success(`Patient status updated to ${newStatus}`);
      loadData(); // Reload to show updated status
    } catch (err) {
      toastError('Failed to update patient status');
      console.error(err);
    }
  };

  const handleOpenPayment = (patient) => {
    if (!patient.latestBill) {
      toastError('No bill found for this patient.');
      return;
    }
    setPaymentModal({
      isOpen: true,
      billId: patient.latestBill.id,
      amount: patient.latestBill.amount,
      patientName: patient.name,
    });
  };

  const [paymentProcessing, setPaymentProcessing] = useState(false);

  const handleProcessPayment = async (method) => {
    if (paymentProcessing) return;
    setPaymentProcessing(method);
    try {
      const pm = method === 'Online' ? 'UPI' : 'CASH';
      let reference = null;
      if (pm === 'UPI') {
        reference = window.prompt('Enter UTR / transaction reference (required for UPI):');
        if (!reference || !reference.trim()) {
          toastError('UTR / reference is required for UPI payments');
          setPaymentProcessing(false);
          return;
        }
      }
      await hospitalService.updateBillStatus(paymentModal.billId, 'PAID', pm, reference);
      // Close Payment Modal
      setPaymentModal({ isOpen: false, billId: null, amount: null, patientName: '' });

      // Open Success Modal immediately
      setPaymentSuccessModal({
        isOpen: true,
        billId: paymentModal.billId,
        patientName: paymentModal.patientName,
        amount: paymentModal.amount,
      });

      loadData();
    } catch (err) {
      toastError('Failed to process payment');
    } finally {
      setPaymentProcessing(false);
    }
  };

  const handleViewPatient = (patient) => {
    setPatientDetailsModal({ isOpen: true, patient });
  };

  const handleEditPatient = (patient) => {
    setSelectedPatient(patient);
    setIsAddModalOpen(true);
  };

  const handlePrintOpd = (opd) => {
    openPdfInNewTab(`/hospital/opd/${opd.id}/pdf`);
  };

  const tabs = [
    { id: 'overview', label: 'Overview', icon: null },
    ...(hasOPD ? [{ id: 'patients', label: 'Patients', icon: null }] : []),
    ...(hasAppointments ? [{ id: 'appointments', label: 'Appointments', icon: null }] : []),
    ...(hasOPD ? [{ id: 'opd', label: 'OPD', icon: null }] : []),
    ...(hasOPD ? [{ id: 'follow-ups', label: 'Follow-ups', icon: null }] : []),
    ...(hasIPD ? [{ id: 'ipd', label: 'IPD', icon: null }] : []),
    ...(hasBilling ? [{ id: 'billing', label: 'Billing', icon: null }] : []),
    ...(hasMedicalInventory && user?.inClinic !== false
      ? [{ id: 'inventory', label: 'Medicine Inventory', icon: null }]
      : []),
    // Reception manages the hospital/clinic inventory (stock, purchases, service lookup)
    // with the same rights as the admin — the backend already allows RECEPTIONIST on every
    // hospital-inventory and service endpoint.
    ...(hasHospitalInventory
      ? [{ id: 'hospital-inventory', label: `${tenantWord} Inventory`, icon: null }]
      : []),
    ...(hasOT ? [{ id: 'ot', label: 'Operation Theatre', icon: null }] : []),
    ...(hasICU ? [{ id: 'icu-dashboard', label: 'ICU Dashboard', icon: null }] : []),
    ...(hasICU ? [{ id: 'icu-beds', label: 'ICU Bed Board', icon: null }] : []),
  ].filter((tab) => tab.id !== 'billing' || user?.billingHandler !== 'DOCTOR');

  // Fallback if the URL parameter tab is not currently valid/visible
  useEffect(() => {
    const isValidTab = tabs.some((t) => t.id === activeTab);
    if (!isValidTab) {
      setActiveTab('overview');
    }
  }, [user, activeTab, tabs]);

  const pagination = {
    pageIndex: page,
    pageSize: pageSize,
    totalItems: totalElements,
    pageCount: totalPages,
    onPageChange: (newPage) => setPage(newPage),
  };

  const getQueuePosition = (opdId) => {
    if (!queueEntries || queueEntries.length === 0) return null;
    const sorted = [...queueEntries].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    const index = sorted.findIndex((q) => q.opd?.id === opdId);
    return index !== -1 ? index + 1 : null;
  };

  const filteredPatientsForOpd =
    patientSearchText.trim().length >= 3
      ? patients.filter((p) => {
          const query = patientSearchText.toLowerCase();
          return (
            p.name?.toLowerCase().includes(query) ||
            p.phone?.includes(query) ||
            (p.customId || p.id?.toString())?.toLowerCase().includes(query)
          );
        })
      : [];

  const showPatientSuggestions = showPatientDropdown && patientSearchText.trim().length >= 3;

  // True while the OPD form is collecting a brand new patient inline instead of searching.
  const isNewOpdPatient = canCreatePatient && opdPatientMode === 'new';

  const handleNewPatientChange = (field, value) => {
    setNewPatientForm((prev) => ({ ...prev, [field]: value }));
    setNewPatientErrors((prev) => (prev[field] ? { ...prev, [field]: null } : prev));
  };

  return (
    <div className="flex h-screen bg-white">
      {/* Sidebar */}
      <Sidebar
        title="HMS Portal"
        tabs={tabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        footerTitle={tenantWord}
        footerData={user?.hospitalName}
        variant="plain"
        isCollapsed={sidebarCollapsed}
        showOnMobile={true}
      />

      {/* Main Content */}
      <div className="flex-1 flex flex-col overflow-hidden">
        <Navbar
          title={`${tabs.find((t) => t.id === activeTab)?.label || activeTab.charAt(0).toUpperCase() + activeTab.slice(1)} Dashboard`}
          user={user}
          onLogout={handleLogout}
          onProfile={() => setProfileOpen(true)}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
        />

        <main className="flex-1 overflow-x-hidden overflow-y-auto bg-white p-8">
          {loadError && (
            <div
              role="alert"
              className="mb-6 border border-red-200 bg-red-50 rounded-lg p-4 text-sm text-red-800"
            >
              <p className="font-semibold">Couldn&apos;t load this screen</p>
              <p className="mt-0.5">{loadError}</p>
              <p className="mt-1 text-xs text-red-700">Anything shown below may be out of date.</p>
              <button
                type="button"
                onClick={() => loadData(true)}
                className="mt-2 px-3 py-1 text-xs font-semibold text-red-800 border border-red-300 rounded-md hover:bg-red-100"
              >
                Retry
              </button>
            </div>
          )}

          {/* Overview Tab */}
          {activeTab === 'overview' && !loading && (
            <div className="space-y-6">
              <div className="flex items-center justify-between">
                <h2 className="text-2xl font-bold text-gray-900">Overview</h2>
                <button
                  onClick={() => setIsAddPatientModalOpen(true)}
                  className="inline-flex items-center justify-center gap-2 px-4 py-2 bg-gray-900 hover:bg-gray-800 text-white text-sm font-semibold rounded-xl transition-all shadow-sm active:scale-95 cursor-pointer animate-fade-in"
                >
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
                    />
                  </svg>
                  Add Patient
                </button>
              </div>
              {modules.includes('HOSPITAL_INVENTORY') && <LowStockBanner />}
              <div
                className={`grid grid-cols-1 ${hasIPD ? 'md:grid-cols-5' : 'md:grid-cols-4'} gap-6`}
              >
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div>
                      <p className="text-gray-600 text-sm font-medium">Today&apos;s Appointments</p>
                      <h3 className="text-3xl font-bold text-gray-900 mt-1">{stats.today}</h3>
                    </div>
                  </div>
                </div>
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div>
                      <p className="text-gray-600 text-sm font-medium">Pending</p>
                      <h3 className="text-3xl font-bold text-gray-900 mt-1">{stats.pending}</h3>
                    </div>
                  </div>
                </div>
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div>
                      <p className="text-gray-600 text-sm font-medium">Total Appointments</p>
                      <h3 className="text-3xl font-bold text-gray-900 mt-1">{stats.total}</h3>
                    </div>
                  </div>
                </div>
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div>
                    <p className="text-gray-600 text-sm font-medium">Active / Next Patient</p>
                    <div className="mt-2 flex items-baseline gap-2 flex-wrap">
                      <h3
                        className="text-base font-bold text-gray-900 truncate max-w-[150px]"
                        title={currentPatientName}
                      >
                        {currentPatientName ?? 'None'}
                      </h3>
                      <span
                        className="text-xs text-gray-500 truncate max-w-[120px]"
                        title={nextPatientName}
                      >
                        / {nextPatientName ?? 'None'}
                      </span>
                    </div>
                  </div>
                </div>
                {hasIPD && (
                  <div className="bg-white rounded-lg border border-gray-200 p-6 flex flex-col justify-between">
                    <div>
                      <p className="text-gray-600 text-sm font-medium">IPD Requests</p>
                      {stats.ipdRequests === null || stats.ipdRequests === undefined ? (
                        <h3 className="text-base font-semibold text-red-600 mt-1" role="status">
                          Couldn&apos;t load
                        </h3>
                      ) : (
                        <h3 className="text-3xl font-bold text-gray-900 mt-1">
                          {stats.ipdRequests}
                        </h3>
                      )}
                    </div>
                    <div className="mt-2">
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('ipd');
                          setIpdSubTab('requested');
                        }}
                        className="text-xs font-semibold text-sky-600 hover:text-sky-800 hover:underline flex items-center gap-1 cursor-pointer bg-transparent border-0 p-0"
                      >
                        <span>View Requests</span>
                        <span>→</span>
                      </button>
                    </div>
                  </div>
                )}
              </div>

              {/* Follow-ups remain available for walk-in hospitals, independent of appointments. */}
              <div
                className={`${hasAppointments ? 'grid grid-cols-1 lg:grid-cols-3' : 'grid grid-cols-1 lg:grid-cols-2'} gap-8 mt-8`}
              >
                {hasAppointments && (
                  <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                    <div className="p-6 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-gradient-to-r from-gray-50/50 to-white">
                      <div>
                        <h3 className="text-lg font-bold text-gray-955">Appointments</h3>
                        <p className="text-xs text-gray-500 mt-0.5">
                          Manage scheduled clinical slots
                        </p>
                      </div>
                      <button
                        onClick={() => setIsAddModalOpen(true)}
                        className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 bg-gray-900 hover:bg-gray-800 text-white text-xs font-semibold rounded-xl transition-all shadow-sm active:scale-95 cursor-pointer animate-fade-in"
                      >
                        <svg
                          className="w-4 h-4"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M12 4v16m8-8H4"
                          />
                        </svg>
                        Add Appointment
                      </button>
                    </div>

                    {/* Appointment Controls */}
                    <div className="px-6 py-3 bg-gray-50/30 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                      <div className="relative flex-1 max-w-xs">
                        <span className="absolute inset-y-0 left-0 flex items-center pl-3 pointer-events-none text-gray-400">
                          <svg
                            className="w-4 h-4"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                            />
                          </svg>
                        </span>
                        <input
                          type="text"
                          placeholder="Search appointments..."
                          value={searchInput}
                          onChange={(e) => {
                            setSearchInput(e.target.value);
                            setPage(0);
                          }}
                          className="w-full pl-9 pr-4 py-1.5 text-xs bg-white border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900/10 focus:border-gray-900 transition-all"
                        />
                      </div>
                      <div className="flex bg-gray-100 rounded-xl p-1 border border-gray-200">
                        {['today', 'upcoming'].map((view) => (
                          <button
                            key={view}
                            onClick={() => {
                              setViewFilter(view);
                              setPage(0);
                            }}
                            className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all cursor-pointer ${
                              viewFilter === view
                                ? 'bg-white text-gray-900 shadow-sm border border-gray-100'
                                : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                            }`}
                          >
                            {view.charAt(0).toUpperCase() + view.slice(1)}
                          </button>
                        ))}
                      </div>
                    </div>

                    {/* Appointment List Content */}
                    <div className="flex-1 overflow-auto p-6 space-y-6">
                      <div>
                        <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-3 flex items-center gap-2">
                          <span className="w-1.5 h-3 bg-gray-900 rounded-full"></span>
                          Active Appointments
                        </h4>
                        {appointmentsLoading ? (
                          <SkeletonTable rows={4} cols={5} />
                        ) : appointments.length > 0 ? (
                          <AppointmentsTable
                            appointments={appointments}
                            doctors={doctors}
                            onStatusUpdate={handleStatusUpdate}
                            onHistory={(item) =>
                              handleHistory('APPOINTMENT', item.publicId || item.id, 'Appointment')
                            }
                            onViewPrescription={handleViewPrescription}
                            startIndex={page * pageSize}
                            pagination={pagination}
                          />
                        ) : (
                          <EmptyState
                            icon={null}
                            title="No Appointments Found"
                            message="Schedule appointments for your patients."
                            actionLabel="Schedule Appointment"
                            onAction={() => setIsAddModalOpen(true)}
                          />
                        )}
                      </div>
                    </div>
                  </div>
                )}

                <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                  <div className="p-6 border-b border-gray-100 bg-gradient-to-r from-gray-50/50 to-white">
                    <h3 className="text-lg font-bold text-gray-955">Today&apos;s Follow-Ups</h3>
                    <p className="text-xs text-gray-500 mt-0.5">
                      Patients due for a follow-up today
                    </p>
                  </div>
                  <div className="flex-1 overflow-auto p-6">
                    {todaysFollowUps.length > 0 ? (
                      <div className="overflow-x-auto border border-gray-100 rounded-xl">
                        <table className="w-full text-sm text-left">
                          <thead>
                            <tr className="bg-gray-50 border-b border-gray-100">
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Patient ID
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Patient Name
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Assigned Doctor
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Diagnosis / Reason
                              </th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-50">
                            {todaysFollowUps.map((record) => (
                              <tr key={record.id} className="hover:bg-gray-50/40 transition-colors">
                                <td className="px-4 py-3.5 text-xs font-bold text-indigo-600">
                                  {record.patientCustomId || record.patientPublicId || '-'}
                                </td>
                                <td className="px-4 py-3.5 text-sm font-semibold text-gray-900">
                                  {record.patientName || '-'}
                                </td>
                                <td className="px-4 py-3.5 text-sm text-gray-600">
                                  {record.doctorName || '-'}
                                </td>
                                <td className="px-4 py-3.5 text-sm text-gray-500 italic">
                                  {record.diagnosis || 'Follow-up'}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Follow-Ups Today"
                        message="No follow-ups scheduled for today."
                      />
                    )}
                  </div>
                </div>

                {/* Right Div: Queue */}
                <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                  <div className="p-6 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-gradient-to-r from-gray-50/50 to-white">
                    <div>
                      <h3 className="text-lg font-bold text-gray-955">Queue</h3>
                      <p className="text-xs text-gray-500 mt-0.5">Real-time OPD patient workflow</p>
                    </div>
                    <div className="flex items-center gap-2">
                      {doctors && doctors.length > 1 && (
                        <div className="relative">
                          <select
                            value={selectedDoctorForQueue}
                            onChange={(e) => {
                              setSelectedDoctorForQueue(e.target.value);
                              setPage(0);
                            }}
                            className="pl-3 pr-8 py-1.5 text-xs bg-white border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900/10 focus:border-gray-900 appearance-none font-semibold text-gray-700 cursor-pointer"
                          >
                            <option value="">All Doctors</option>
                            {doctors.map((d) => (
                              <option key={d.id} value={d.id}>
                                {d.name}
                              </option>
                            ))}
                          </select>
                        </div>
                      )}
                      <button
                        onClick={() => setIsOpdModalOpen(true)}
                        className="inline-flex items-center gap-1.5 px-4 py-2 bg-gray-900 text-white text-xs font-bold rounded-xl hover:bg-gray-800 transition-colors shadow-sm whitespace-nowrap"
                      >
                        <span className="text-base leading-none">+</span>
                        Add OPD
                      </button>
                    </div>
                  </div>

                  {/* Queue List Content */}
                  <div className="flex-1 overflow-auto p-6">
                    {queueEntries.length > 0 ? (
                      <div className="overflow-x-auto border border-gray-100 rounded-xl">
                        <table className="w-full text-sm text-left">
                          <thead>
                            <tr className="bg-gray-50 border-b border-gray-100">
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                S.No.
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Position
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Patient
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Doctor
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Time
                              </th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-50">
                            {[...queueEntries]
                              .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
                              .slice(0, 10)
                              .map((q, idx) => (
                                <tr key={q.id} className="hover:bg-gray-50/40 transition-colors">
                                  <td className="px-4 py-3.5 text-sm text-gray-955 font-medium">
                                    {idx + 1}
                                  </td>
                                  <td className="px-4 py-3.5">
                                    <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-100/30">
                                      Position #{idx + 1}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3.5 text-sm text-gray-955 font-semibold">
                                    {q.opd?.patient?.name || q.opd?.patientName || '-'}
                                  </td>
                                  <td className="px-4 py-3.5 text-sm text-gray-600">
                                    {q.opd?.doctor?.name || q.opd?.doctorName || '-'}
                                  </td>
                                  <td className="px-4 py-3.5 text-xs text-gray-400">
                                    {formatTime(q.createdAt)}
                                  </td>
                                </tr>
                              ))}
                          </tbody>
                        </table>
                      </div>
                    ) : (
                      <EmptyState
                        icon={null}
                        title="Queue Empty"
                        message="No queued OPD cases for today."
                      />
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Standardized Header */}
          {activeTab !== 'overview' && (
            <PageHeader
              title={
                tabs.find((t) => t.id === activeTab)?.label ||
                activeTab.charAt(0).toUpperCase() + activeTab.slice(1)
              }
              subtitle={`Manage ${activeTab} records`}
              onSearch={
                activeTab === 'queue' || activeTab === 'hospital-inventory'
                  ? null
                  : (e) => setSearchInput(e.target.value)
              }
              searchValue={searchInput}
              searchPlaceholder={
                activeTab === 'queue' || activeTab === 'hospital-inventory'
                  ? ''
                  : `Search ${activeTab}...`
              }
              onAdd={
                activeTab === 'queue' ||
                activeTab === 'billing' ||
                activeTab === 'ipd' ||
                activeTab === 'inventory' ||
                activeTab === 'hospital-inventory' ||
                activeTab === 'ot'
                  ? null
                  : () => {
                      if (activeTab === 'opd') setIsOpdModalOpen(true);
                      else setIsAddModalOpen(true);
                    }
              }
              addLabel={activeTab === 'opd' ? 'New OPD / Case' : `Add ${activeTab.slice(0, -1)}`}
              filter={
                activeTab === 'appointments' && hasAppointments ? (
                  <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200">
                    {['today', 'upcoming'].map((view) => (
                      <button
                        key={view}
                        onClick={() => setViewFilter(view)}
                        className={`px-4 py-1.5 text-sm font-medium rounded-md transition-all ${
                          viewFilter === view
                            ? 'bg-white text-primary-600 shadow-sm border border-gray-100'
                            : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                        }`}
                      >
                        {view.charAt(0).toUpperCase() + view.slice(1)}
                      </button>
                    ))}
                  </div>
                ) : activeTab === 'billing' ? (
                  <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200">
                    {['PENDING', 'PAID', 'PARTIAL'].map((status) => (
                      <button
                        key={status}
                        onClick={() => {
                          setBillingStatus(status);
                          setPage(0);
                        }}
                        className={`px-4 py-1.5 text-sm font-medium rounded-md transition-all ${
                          billingStatus === status
                            ? 'bg-white text-primary-600 shadow-sm border border-gray-100'
                            : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                        }`}
                      >
                        {status.charAt(0) + status.slice(1).toLowerCase()}
                      </button>
                    ))}
                  </div>
                ) : activeTab === 'queue' ? (
                  doctors && doctors.length > 1 ? (
                    <div className="flex items-center gap-3">
                      <select
                        value={selectedDoctorForQueue}
                        onChange={(e) => {
                          setSelectedDoctorForQueue(e.target.value);
                          setPage(0);
                        }}
                        className="input-field"
                      >
                        <option value="">All Doctors</option>
                        {doctors.map((d) => (
                          <option key={d.id} value={d.id}>
                            {d.name}
                          </option>
                        ))}
                      </select>
                    </div>
                  ) : null
                ) : activeTab === 'patients' ? (
                  <div className="flex items-center gap-2">
                    <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 h-[38px] items-center">
                      {['All', 'Date'].map((view) => (
                        <button
                          key={view}
                          type="button"
                          onClick={() => {
                            setPatientTabView(view);
                            setPage(0);
                            setSearchInput('');
                          }}
                          className={`px-4 py-1 text-sm font-medium rounded-md transition-all ${
                            patientTabView === view
                              ? 'bg-white text-sky-600 shadow-sm border border-gray-100 font-semibold'
                              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                          }`}
                        >
                          {view}
                        </button>
                      ))}
                    </div>
                    {patientTabView === 'Date' && (
                      <DateSelect
                        value={patientDateFilter}
                        onChange={(v) => {
                          setPatientDateFilter(v);
                          setPage(0);
                        }}
                      />
                    )}
                    <button
                      type="button"
                      onClick={handleDownloadPatientsReport}
                      disabled={loading}
                      className="bg-sky-600 hover:bg-sky-700 disabled:bg-gray-300 disabled:cursor-not-allowed text-white px-4 py-1.5 rounded-lg text-sm font-semibold shadow-sm transition flex items-center gap-1.5 h-[38px]"
                    >
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="h-4 w-4"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                        />
                      </svg>
                      <span>Download PDF</span>
                    </button>
                  </div>
                ) : activeTab === 'opd' ? (
                  <div className="flex items-center gap-2">
                    <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 h-[38px] items-center">
                      {['Live', 'Date'].map((view) => (
                        <button
                          key={view}
                          type="button"
                          onClick={() => {
                            setOpdTabView(view);
                            setPage(0);
                            setSearchInput('');
                          }}
                          className={`px-4 py-1 text-sm font-medium rounded-md transition-all ${
                            opdTabView === view
                              ? 'bg-white text-sky-600 shadow-sm border border-gray-100 font-semibold'
                              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                          }`}
                        >
                          {view}
                        </button>
                      ))}
                    </div>
                    {opdTabView === 'Date' && (
                      <DateSelect
                        value={opdDateFilter}
                        onChange={(v) => {
                          setOpdDateFilter(v);
                          setPage(0);
                        }}
                      />
                    )}
                    <button
                      type="button"
                      onClick={handleDownloadOpdReport}
                      disabled={loading}
                      className="bg-sky-600 hover:bg-sky-700 disabled:bg-gray-300 disabled:cursor-not-allowed text-white px-4 py-1.5 rounded-lg text-sm font-semibold shadow-sm transition flex items-center gap-1.5 h-[38px]"
                    >
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="h-4 w-4"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                        />
                      </svg>
                      <span>Download PDF</span>
                    </button>
                  </div>
                ) : activeTab === 'ipd' ? (
                  <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200">
                    {[
                      { value: 'current', label: 'Current Patients' },
                      { value: 'requested', label: 'Requested Patients' },
                    ].map((sub) => (
                      <button
                        key={sub.value}
                        type="button"
                        onClick={() => {
                          setIpdSubTab(sub.value);
                          setPage(0);
                          setSearchInput('');
                        }}
                        className={`px-4 py-1.5 text-sm font-medium rounded-md transition-all ${
                          ipdSubTab === sub.value
                            ? 'bg-white text-primary-600 shadow-sm border border-gray-100 font-semibold'
                            : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                        }`}
                      >
                        {sub.label}
                      </button>
                    ))}
                  </div>
                ) : null
              }
            />
          )}

          {loading && (
            <div className="space-y-8 animate-fade-in-up">
              {activeTab === 'overview' ? (
                <>
                  <SkeletonStatsGrid
                    count={hasIPD ? 5 : 4}
                    gridCols={hasIPD ? 'md:grid-cols-5' : 'md:grid-cols-4'}
                  />
                  <SkeletonOverviewDual />
                </>
              ) : (
                <SkeletonTable rows={6} cols={5} />
              )}
            </div>
          )}

          {!loading && activeTab !== 'overview' && (
            <div className="bg-white rounded-lg border border-gray-200 overflow-hidden mb-4">
              {activeTab === 'appointments' &&
                hasAppointments &&
                (appointmentsLoading ? (
                  <SkeletonTable rows={6} cols={5} />
                ) : appointments.length > 0 ? (
                  <AppointmentsTable
                    appointments={appointments}
                    doctors={doctors}
                    onStatusUpdate={handleStatusUpdate}
                    onHistory={(item) =>
                      handleHistory('APPOINTMENT', item.publicId || item.id, 'Appointment')
                    }
                    onViewPrescription={handleViewPrescription}
                    startIndex={page * pageSize}
                    pagination={pagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title="No Appointments Found"
                    message="Schedule appointments for your patients."
                    actionLabel="Schedule Appointment"
                    onAction={() => setIsAddModalOpen(true)}
                  />
                ))}
              {/* eslint-disable-next-line jsx-a11y/aria-role -- `role` is FollowUpPanel's own prop, not an ARIA role */}
              {activeTab === 'follow-ups' && <FollowUpPanel role="RECEPTIONIST" />}
              {activeTab === 'opd' &&
                (opds.length > 0 ? (
                  <div className="p-4 overflow-x-auto">
                    <table className="w-full text-sm text-left">
                      <thead>
                        <tr>
                          <th className="px-4 py-2">S.No.</th>
                          <th className="px-4 py-2">Case ID</th>
                          <th className="px-4 py-2">Patient</th>
                          <th className="px-4 py-2">Doctor</th>
                          <th className="px-4 py-2">Visit</th>
                          <th className="px-4 py-2">Created</th>
                          <th className="px-4 py-2">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {opds.map((o, idx) => (
                          <tr key={o.id} className="border-t">
                            <td className="px-4 py-3">{page * pageSize + idx + 1}</td>
                            <td className="px-4 py-3">{o.caseId}</td>
                            <td className="px-4 py-3">{o.patient?.name}</td>
                            <td className="px-4 py-3">{o.doctor?.name || '-'}</td>
                            <td className="px-4 py-3">{o.visitType}</td>
                            <td className="px-4 py-3">{formatDateTime(o.createdAt)}</td>
                            <td className="px-4 py-3">
                              <div className="flex items-center gap-2">
                                <button
                                  onClick={() => handlePrintOpd(o)}
                                  className="px-3 py-1 bg-gray-900 text-white rounded"
                                >
                                  Print
                                </button>
                                <button
                                  onClick={() => handleViewOpdPrescription(o)}
                                  className="px-3 py-1 border border-gray-300 text-gray-700 rounded hover:bg-gray-50"
                                >
                                  Prescription
                                </button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <EmptyState
                    icon={null}
                    title={opdTabView === 'Live' ? 'No Active OPD Cases' : 'No OPD Records Found'}
                    message={
                      opdTabView === 'Live'
                        ? 'No patients are currently in the queue or being consulted.'
                        : `No OPD registrations found on ${opdDateFilter}.`
                    }
                    actionLabel={opdTabView === 'Live' ? 'New OPD / Case' : null}
                    onAction={opdTabView === 'Live' ? () => setIsOpdModalOpen(true) : null}
                  />
                ))}
              {activeTab === 'ipd' &&
                (ipdSubTab === 'current' ? (
                  opds.length > 0 ? (
                    <div className="p-4 overflow-x-auto">
                      <table className="w-full text-sm text-left">
                        <thead>
                          <tr>
                            <th className="px-4 py-2">S.No.</th>
                            <th className="px-4 py-2">IPD No.</th>
                            <th className="px-4 py-2">Patient</th>
                            <th className="px-4 py-2">Doctor</th>
                            <th className="px-4 py-2">Ward</th>
                            <th className="px-4 py-2">Bed</th>
                            <th className="px-4 py-2">Admitted</th>
                            <th className="px-4 py-2">Status</th>
                            <th className="px-4 py-2">Actions</th>
                          </tr>
                        </thead>
                        <tbody>
                          {opds.map((o, idx) => {
                            const row = o.ipd || o;
                            const ipdNumber = row.ipdNumber || row.ipd?.ipdNumber || row.ipdNumber;
                            const patientName = row.patientName || row.patient?.name || '-';
                            const doctorName = row.doctorName || row.doctor?.name || '-';
                            const wardName = row.wardName || row.ward?.name || '-';
                            const bedNumber =
                              row.bedNumber ||
                              row.bed?.bedNumber ||
                              row.bed?.bedCode ||
                              row.bed?.name ||
                              '-';
                            const admittedAt =
                              row.admissionDateTime ||
                              row.admissionDatetime ||
                              row.ipd?.admissionDatetime;
                            const status = row.status || row.ipd?.status || 'ADMITTED';
                            const ipdId =
                              row.ipdId || row.id || row.ipd?.id || row.ipd?.ipdId || ipdNumber;
                            return (
                              <tr key={idx} className="border-t">
                                <td className="px-4 py-3">{page * pageSize + idx + 1}</td>
                                <td className="px-4 py-3">{ipdNumber || row.id}</td>
                                <td className="px-4 py-3">{patientName}</td>
                                <td className="px-4 py-3">{doctorName}</td>
                                <td className="px-4 py-3">{wardName}</td>
                                <td className="px-4 py-3">{bedNumber}</td>
                                <td className="px-4 py-3">{formatDateTime(admittedAt)}</td>
                                <td className="px-4 py-3">
                                  <div className="flex flex-col gap-1 items-start">
                                    {(o.admissionConfirmed ?? row.admissionConfirmed) ? (
                                      <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                                        ADMITTED
                                      </span>
                                    ) : (
                                      <span className="px-2 py-0.5 text-[10px] font-bold rounded-full bg-amber-100 text-amber-700">
                                        ADMISSION PENDING
                                      </span>
                                    )}
                                    {status && status !== 'ADMITTED' && (
                                      <span className="text-[10px] text-gray-500">{status}</span>
                                    )}
                                  </div>
                                </td>
                                <td className="px-4 py-3">
                                  <button
                                    className={`px-3 py-1 rounded ${ipdId ? 'bg-blue-500 text-white' : 'bg-gray-200 text-gray-500 cursor-not-allowed'}`}
                                    onClick={() => {
                                      if (ipdId) window.location.href = `/ipd/${ipdId}`;
                                    }}
                                    disabled={!ipdId}
                                    title={ipdId ? 'View IPD details' : 'IPD id not available'}
                                  >
                                    View
                                  </button>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <EmptyState
                      icon={null}
                      title="No IPD Admissions"
                      message="No IPD admissions found."
                    />
                  )
                ) : opds.length > 0 ? (
                  <div className="p-4 overflow-x-auto">
                    <table className="w-full text-sm text-left">
                      <thead>
                        <tr>
                          <th className="px-4 py-2">S.No.</th>
                          <th className="px-4 py-2">OPD Case No.</th>
                          <th className="px-4 py-2">Patient</th>
                          <th className="px-4 py-2">Doctor</th>
                          <th className="px-4 py-2">Problem / Diagnosis</th>
                          <th className="px-4 py-2">Date Recommended</th>
                          <th className="px-4 py-2">Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {opds.map((o, idx) => (
                          <tr key={o.id} className="border-t">
                            <td className="px-4 py-3">{page * pageSize + idx + 1}</td>
                            <td className="px-4 py-3">{o.caseId}</td>
                            <td className="px-4 py-3">{o.patient?.name}</td>
                            <td className="px-4 py-3">{o.doctor?.name || '-'}</td>
                            <td className="px-4 py-3">{o.problem || '-'}</td>
                            <td className="px-4 py-3">{formatDateTime(o.createdAt)}</td>
                            <td className="px-4 py-3">
                              <button
                                onClick={() => {
                                  setIpdOpdForAdmit(o);
                                  setIsIpdAdmitOpen(true);
                                }}
                                className="px-3 py-1 bg-green-600 text-white rounded text-xs font-semibold hover:bg-green-700 transition"
                              >
                                Admit to IPD
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <EmptyState
                    icon={null}
                    title="No Admission Requests"
                    message="No pending IPD admission requests from doctors found."
                  />
                ))}
              {activeTab === 'queue' &&
                (queueEntries.length > 0 ? (
                  <div className="p-4 overflow-x-auto">
                    <table className="w-full text-sm text-left">
                      <thead>
                        <tr>
                          <th className="px-4 py-2">S.No.</th>
                          <th className="px-4 py-2">Position</th>
                          <th className="px-4 py-2">Patient</th>
                          <th className="px-4 py-2">Doctor</th>
                          <th className="px-4 py-2">Created</th>
                        </tr>
                      </thead>
                      <tbody>
                        {[...queueEntries]
                          .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
                          .slice(0, 10)
                          .map((q, idx) => (
                            <tr key={q.id} className="border-t">
                              <td className="px-4 py-3">{idx + 1}</td>
                              <td className="px-4 py-3">Position #{idx + 1}</td>
                              <td className="px-4 py-3">
                                {q.opd?.patient?.name || q.opd?.patientName || '-'}
                              </td>
                              <td className="px-4 py-3">
                                {q.opd?.doctor?.name || q.opd?.doctorName || '-'}
                              </td>
                              <td className="px-4 py-3">{formatDateTime(q.createdAt)}</td>
                            </tr>
                          ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <EmptyState
                    icon={null}
                    title="Queue Empty"
                    message="No queued OPD cases for today."
                  />
                ))}
              {activeTab === 'patients' &&
                (patients.length > 0 ? (
                  <PatientsTable
                    patients={patients}
                    onStatusUpdate={handlePatientStatusUpdate}
                    onViewPrescription={handleViewPatientPrescription}
                    onViewDetails={handleViewPatient}
                    onEdit={handleEditPatient}
                    onOpenPayment={handleOpenPayment}
                    onPrintReceipt={handlePrintReceipt}
                    startIndex={page * pageSize}
                    pagination={pagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title={
                      patientTabView === 'Date' ? 'No Registered Patients' : 'No Patients Found'
                    }
                    message={
                      patientTabView === 'Date'
                        ? `No patients registered on ${patientDateFilter}.`
                        : 'There are no patients registered in the system yet.'
                    }
                    actionLabel={patientTabView === 'Date' ? null : 'Add Patient'}
                    onAction={patientTabView === 'Date' ? null : () => setIsAddModalOpen(true)}
                  />
                ))}
              {activeTab === 'billing' && billing.length === 0 && (
                <EmptyState icon={null} title="No Billing Records" message="No bills found." />
              )}
              {activeTab === 'billing' && billing.length > 0 && (
                <BillingTable
                  billing={billing}
                  startIndex={page * pageSize}
                  pagination={pagination}
                  onUpdateStatus={handleBillStatus}
                  onPrint={handlePrintReceipt}
                  printingBillId={recPrintingId}
                  onEditItems={handleOpenEditBillItems}
                />
              )}
              {activeTab === 'inventory' && <MedicineInventoryTab hidePrescribingColumns />}
              {activeTab === 'hospital-inventory' && <HospitalInventoryTab />}
              {activeTab === 'icu-dashboard' && (
                <IcuDashboard onOpenBedBoard={() => setActiveTab('icu-beds')} />
              )}
              {activeTab === 'icu-beds' && <IcuBedBoard />}
              {activeTab === 'ot' && (
                <div>
                  <div className="flex items-center justify-between mb-4">
                    {/* Rendered by capability, not by role: a hospital grants OT_CREATE
                                            to whoever books its day-care lists. */}
                    <div className="flex items-center gap-2">
                      {canOt('OT_CREATE') && (
                        <button
                          onClick={() => setIsDayCareModalOpen(true)}
                          className="px-4 py-2 rounded-lg text-sm font-semibold bg-gray-900 text-white hover:bg-gray-800"
                        >
                          + Day-Care Surgery
                        </button>
                      )}
                      <button
                        onClick={() => setIsOtListOpen(true)}
                        className="px-4 py-2 rounded-lg text-sm font-semibold border border-gray-300 text-gray-700 hover:bg-gray-50"
                      >
                        OT List
                      </button>
                    </div>
                    <div className="inline-flex rounded-lg border border-gray-200 overflow-hidden">
                      <button
                        onClick={() => setOtFilter('board')}
                        className={`px-4 py-1.5 text-sm font-semibold ${otFilter === 'board' ? 'bg-gray-900 text-white' : 'bg-white text-gray-600'}`}
                      >
                        Scheduled / Live
                      </button>
                      <button
                        onClick={() => setOtFilter('day')}
                        className={`px-4 py-1.5 text-sm font-semibold ${otFilter === 'day' ? 'bg-gray-900 text-white' : 'bg-white text-gray-600'}`}
                      >
                        Day Board
                      </button>
                      <button
                        onClick={() => setOtFilter('requests')}
                        className={`px-4 py-1.5 text-sm font-semibold ${otFilter === 'requests' ? 'bg-gray-900 text-white' : 'bg-white text-gray-600'}`}
                      >
                        Requests
                      </button>
                    </div>
                  </div>
                  <OtAnalyticsStrip />
                  {otFilter === 'day' ? (
                    <OtDayBoard
                      date={new Date().toISOString().slice(0, 10)}
                      onSelect={(c) => setOtExecTarget(c)}
                    />
                  ) : otLoading ? (
                    <div className="text-center text-gray-400 py-16">Loading…</div>
                  ) : (
                    /* Each handler is supplied only when this user holds the permission its
                       endpoint requires, so the board never draws a button that would 403. */
                    <OtBoard
                      rows={otRows}
                      mode={otFilter}
                      onSchedule={canOt('OT_SCHEDULE') ? (r) => setOtScheduleTarget(r) : undefined}
                      onCancel={
                        canOt('OT_CANCEL')
                          ? async (r) => {
                              try {
                                await otService.cancel(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to cancel');
                              }
                            }
                          : undefined
                      }
                      onStart={
                        canOt('OT_START')
                          ? async (r) => {
                              try {
                                await otService.start(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to start');
                              }
                            }
                          : undefined
                      }
                      onComplete={
                        canOt('OT_COMPLETE')
                          ? async (r) => {
                              try {
                                await otService.complete(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to complete');
                              }
                            }
                          : undefined
                      }
                      onTeam={canOt('OT_ASSIGN_TEAM') ? (r) => setOtTeamTarget(r) : undefined}
                      onExecute={canOt('OT_VIEW') ? (r) => setOtExecTarget(r) : undefined}
                      onRecovery={
                        canOt('OT_RECOVERY') || canOt('OT_TRANSFER')
                          ? (r) => setOtRecoveryTarget(r)
                          : undefined
                      }
                      onClose={
                        canOt('OT_CLOSE')
                          ? async (r) => {
                              try {
                                await otService.close(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to close');
                              }
                            }
                          : undefined
                      }
                      onApprove={
                        canOt('OT_APPROVE')
                          ? async (r) => {
                              try {
                                await otService.approve(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to approve');
                              }
                            }
                          : undefined
                      }
                      onPreOp={
                        canOt('OT_PRE_OP')
                          ? async (r) => {
                              try {
                                await otService.preOp(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to enter pre-op');
                              }
                            }
                          : undefined
                      }
                      onAnaesthesia={
                        canOt('OT_ANAESTHESIA_CLEARANCE')
                          ? (r) => setOtAnaesthesiaTarget(r)
                          : undefined
                      }
                      onPostpone={
                        canOt('OT_RESCHEDULE')
                          ? async (r) => {
                              try {
                                await otService.postpone(r.publicId);
                                loadOt();
                              } catch (e) {
                                toastError(e?.response?.data?.error || 'Failed to postpone');
                              }
                            }
                          : undefined
                      }
                    />
                  )}
                  {otAnaesthesiaTarget && (
                    <AnaesthesiaClearanceModal
                      surgery={otAnaesthesiaTarget}
                      onClose={() => setOtAnaesthesiaTarget(null)}
                      onRecorded={loadOt}
                    />
                  )}
                  {otTeamTarget && (
                    <SurgeryTeamModal
                      surgery={otTeamTarget}
                      onClose={() => setOtTeamTarget(null)}
                    />
                  )}
                  {otExecTarget && (
                    <SurgeryExecutionModal
                      surgery={otExecTarget}
                      onClose={() => {
                        setOtExecTarget(null);
                        loadOt();
                      }}
                    />
                  )}
                  {otRecoveryTarget && (
                    <RecoveryModal
                      surgery={otRecoveryTarget}
                      onClose={() => {
                        setOtRecoveryTarget(null);
                        loadOt();
                      }}
                    />
                  )}
                  {otScheduleTarget && (
                    <ScheduleSurgeryModal
                      surgery={otScheduleTarget}
                      onClose={() => setOtScheduleTarget(null)}
                      onScheduled={loadOt}
                    />
                  )}
                </div>
              )}
            </div>
          )}
        </main>
      </div>

      {/* Appointment Modal - Using Shared Component */}
      {hasAppointments && (activeTab === 'appointments' || activeTab === 'overview') && (
        <AppointmentModal
          isOpen={isAddModalOpen}
          onClose={() => setIsAddModalOpen(false)}
          onSuccess={() => loadData(false)}
          doctors={doctors}
          patients={patients}
        />
      )}

      {/* Patient Modal - Using Shared Component */}
      {((activeTab === 'patients' && isAddModalOpen) ||
        (activeTab === 'overview' && isAddPatientModalOpen)) && (
        <PatientModal
          isOpen={activeTab === 'patients' ? isAddModalOpen : isAddPatientModalOpen}
          onClose={() => {
            if (activeTab === 'patients') setIsAddModalOpen(false);
            else setIsAddPatientModalOpen(false);
            setSelectedPatient(null);
          }}
          initialData={selectedPatient}
          onSuccess={() => {
            if (activeTab === 'patients') setIsAddModalOpen(false);
            else setIsAddPatientModalOpen(false);
            setSelectedPatient(null);
            loadData(); // Reload patients list
          }}
        />
      )}

      {/* IPD Admit Modal */}
      {isIpdAdmitOpen && (
        <IpdAdmitModal
          isOpen={isIpdAdmitOpen}
          onClose={() => {
            setIsIpdAdmitOpen(false);
            setIpdOpdForAdmit(null);
          }}
          opd={ipdOpdForAdmit}
          onSuccess={() => {
            loadData();
          }}
        />
      )}

      {/* OPD Modal / Form for Receptionist */}
      {isOpdModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-organic w-full max-w-3xl animate-scale-in overflow-hidden max-h-[90vh]">
            <div className="bg-white px-8 py-6 border-b border-gray-200">
              <div className="flex justify-between items-center">
                <div>
                  <h3 className="text-2xl font-bold text-neutral-800">New OPD / Case</h3>
                  <p className="text-sm text-neutral-600 mt-1">Capture vitals and assign doctor</p>
                </div>
                <button
                  onClick={() => setIsOpdModalOpen(false)}
                  className="w-10 h-10 rounded-xl bg-white/80 hover:bg-white flex items-center justify-center text-neutral-400 hover:text-neutral-600"
                >
                  <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>
            </div>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                if (opdSubmitting) return;
                // Every check runs before any request, so a bad vitals value can never leave a
                // patient created with no OPD behind it.
                if (isNewOpdPatient) {
                  const patientErrors = validateForm(newPatientForm, patientFormRules);
                  setNewPatientErrors(patientErrors);
                  if (Object.keys(patientErrors).length > 0) {
                    toastError('Please correct the highlighted patient details');
                    return;
                  }
                } else if (!opdForm.patientId) {
                  toastError('Please select a valid patient from the suggestions');
                  return;
                }
                // Validate vitals
                if (opdForm.bp) {
                  const bpVal = opdForm.bp.trim();
                  const bpMatch = bpVal.match(/^(\d{2,3})\s*\/\s*(\d{2,3})$/);
                  if (!bpMatch) {
                    toastError('Blood pressure must be in format Systolic/Diastolic, e.g., 120/80');
                    return;
                  }
                  const systolic = parseInt(bpMatch[1], 10);
                  const diastolic = parseInt(bpMatch[2], 10);
                  if (systolic <= diastolic) {
                    toastError(
                      'Systolic blood pressure must be greater than diastolic blood pressure'
                    );
                    return;
                  }
                }
                if (opdForm.temperature) {
                  const temp = parseFloat(opdForm.temperature);
                  if (isNaN(temp) || temp < 0) {
                    toastError('Temperature cannot be negative');
                    return;
                  }
                }
                if (opdForm.pulse) {
                  const pulse = parseInt(opdForm.pulse, 10);
                  if (isNaN(pulse) || pulse < 0) {
                    toastError('Pulse cannot be negative');
                    return;
                  }
                }
                if (opdForm.weight) {
                  const weight = parseFloat(opdForm.weight);
                  if (isNaN(weight) || weight < 0) {
                    toastError('Weight cannot be negative');
                    return;
                  }
                }
                if (opdForm.spo2) {
                  const spo2 = parseInt(opdForm.spo2, 10);
                  if (isNaN(spo2) || spo2 < 0) {
                    toastError('SpO2 cannot be negative');
                    return;
                  }
                }

                // Bill Payment = First: the fee is collected here, so the method is required.
                const payErr = validateOpdPayment(opdForm.paymentMethod, opdForm.paymentReference);
                if (payErr) {
                  toastError(payErr);
                  return;
                }

                setOpdSubmitting(true);

                // Two sequential calls to the two existing endpoints. The OPD is only
                // attempted once the patient exists.
                let patientId = opdForm.patientId;
                if (isNewOpdPatient) {
                  try {
                    const created = await hospitalService.addPatient(
                      stripPatientPayload(newPatientForm)
                    );
                    patientId = created?.id;
                    if (!patientId) throw new Error('Patient was saved without an id');
                  } catch (err) {
                    console.error('Failed to create patient', err);
                    toastError(extractApiError(err, 'Failed to create patient'));
                    setOpdSubmitting(false);
                    return;
                  }
                }

                try {
                  const payload = {
                    patientId,
                    doctorId: opdForm.doctorId,
                    bp: opdForm.bp ? opdForm.bp : null,
                    temperature: opdForm.temperature ? parseFloat(opdForm.temperature) : null,
                    pulse: opdForm.pulse ? parseInt(opdForm.pulse) : null,
                    weight: opdForm.weight ? parseFloat(opdForm.weight) : null,
                    height: opdForm.height ? parseFloat(opdForm.height) : null,
                    customVitals: opdForm.customVitals || {},
                    spo2: opdForm.spo2 ? parseInt(opdForm.spo2) : null,
                    problem: opdForm.problem,
                    visitType: opdForm.visitType,
                    ...(isPayFirst()
                      ? {
                          paymentMethod: opdForm.paymentMethod,
                          paymentReference: opdForm.paymentReference || null,
                        }
                      : {}),
                  };
                  const res = await hospitalService.createOpd(payload);
                  setCreatedOpd(res);
                  setIsOpdModalOpen(false);
                  success('OPD Case created successfully — ID: ' + res.caseId);
                  loadData(); // Refresh the data after OPD creation
                } catch (err) {
                  console.error('Failed to create OPD', err);
                  toastError('Failed to create OPD');
                } finally {
                  setOpdSubmitting(false);
                }
              }}
              className="p-6 space-y-4 max-h-[76vh] overflow-auto"
            >
              {canCreatePatient && (
                <div
                  className="inline-flex p-1 bg-neutral-100 rounded-xl"
                  role="group"
                  aria-label="Patient source"
                >
                  <button
                    type="button"
                    aria-pressed={opdPatientMode === 'existing'}
                    onClick={() => setOpdPatientMode('existing')}
                    className={`px-4 py-1.5 text-xs font-bold rounded-lg transition-colors ${
                      opdPatientMode === 'existing'
                        ? 'bg-white text-neutral-900 shadow-sm'
                        : 'text-neutral-500 hover:text-neutral-700'
                    }`}
                  >
                    Existing Patient
                  </button>
                  <button
                    type="button"
                    aria-pressed={opdPatientMode === 'new'}
                    onClick={() => {
                      setOpdPatientMode('new');
                      setShowPatientDropdown(false);
                      // Drop any patient picked by search, so only one of the two
                      // sources is ever in play.
                      setPatientSearchText('');
                      setOpdForm((prev) => ({ ...prev, patientId: null }));
                    }}
                    className={`px-4 py-1.5 text-xs font-bold rounded-lg transition-colors ${
                      opdPatientMode === 'new'
                        ? 'bg-white text-neutral-900 shadow-sm'
                        : 'text-neutral-500 hover:text-neutral-700'
                    }`}
                  >
                    New Patient
                  </button>
                </div>
              )}
              {isNewOpdPatient && (
                <div className="border border-neutral-200 rounded-xl p-4 space-y-4 bg-neutral-50/40">
                  <p className="text-sm font-semibold text-neutral-800">New Patient Details</p>
                  <PatientFormFields
                    values={newPatientForm}
                    errors={newPatientErrors}
                    onChange={handleNewPatientChange}
                  />
                </div>
              )}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {!isNewOpdPatient && (
                  <div className="relative">
                    <span className="block text-sm font-semibold text-neutral-700 mb-2">
                      Patient <span className="text-red-600">*</span>
                    </span>
                    <div className="relative">
                      <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-neutral-400">
                        <svg
                          className="w-5 h-5"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                          />
                        </svg>
                      </span>
                      <input
                        type="text"
                        className="input-field pl-10"
                        value={patientSearchText}
                        onChange={(e) => {
                          const val = e.target.value;
                          setPatientSearchText(val);
                          setShowPatientDropdown(true);
                          setOpdForm((prev) => ({ ...prev, patientId: null }));
                        }}
                        onFocus={() => setShowPatientDropdown(true)}
                        onBlur={() => {
                          setTimeout(() => {
                            setShowPatientDropdown(false);
                          }, 250);
                        }}
                        placeholder="Type at least 3 letters to search patient..."
                        autoComplete="off"
                      />
                    </div>

                    {showPatientSuggestions && (
                      <div className="absolute left-0 right-0 mt-1 bg-white border border-neutral-200 rounded-xl shadow-xl z-50 max-h-60 overflow-y-auto divide-y divide-neutral-100 animate-scale-in">
                        {filteredPatientsForOpd.length > 0 ? (
                          filteredPatientsForOpd.map((p) => (
                            <button
                              type="button"
                              key={p.id}
                              onMouseDown={() => {
                                setOpdForm((prev) => ({ ...prev, patientId: p.id }));
                                setPatientSearchText(
                                  `${p.name}${p.phone ? ` (${p.phone})` : ''}${p.customId ? ` [${p.customId}]` : ''}`
                                );
                                setShowPatientDropdown(false);
                              }}
                              className="w-full px-4 py-3 hover:bg-neutral-50 cursor-pointer transition-colors duration-150 flex flex-col gap-0.5 text-left"
                            >
                              <div className="flex justify-between items-center">
                                <span className="font-semibold text-neutral-800 text-sm">
                                  {p.name}
                                </span>
                                {p.customId && (
                                  <span className="text-[10px] font-bold px-2 py-0.5 bg-neutral-100 text-neutral-600 rounded-full tracking-wide">
                                    {p.customId}
                                  </span>
                                )}
                              </div>
                              <div className="flex gap-4 text-xs text-neutral-500">
                                {p.phone && <span>📞 {p.phone}</span>}
                                {p.gender && <span>👤 {p.gender}</span>}
                                {p.age && <span>🎂 {p.age} Yrs</span>}
                              </div>
                            </button>
                          ))
                        ) : (
                          <div className="px-4 py-4 text-sm text-neutral-500 text-center">
                            No matching patients found
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}
                <div>
                  <span className="block text-sm font-semibold text-neutral-700 mb-2">Doctor</span>
                  {doctors && doctors.length === 1 ? (
                    <div className="w-full px-4 py-2.5 bg-neutral-50 border border-neutral-200 text-neutral-800 rounded-xl text-sm font-semibold flex items-center justify-between">
                      <span>{doctors[0].name}</span>
                      <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                        Assigned
                      </span>
                    </div>
                  ) : (
                    <select
                      className="input-field"
                      value={opdForm.doctorId || ''}
                      onChange={(e) =>
                        setOpdForm((prev) => ({ ...prev, doctorId: e.target.value }))
                      }
                    >
                      <option value="">Unassigned</option>
                      {doctors.map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                {isOn('BP') && (
                  <div>
                    <label
                      htmlFor="fld-140"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      BP
                    </label>
                    <input
                      id="fld-140"
                      className="input-field"
                      value={opdForm.bp}
                      onChange={(e) =>
                        setOpdForm((prev) => ({
                          ...prev,
                          bp: e.target.value.replace(/[^0-9/]/g, ''),
                        }))
                      }
                      placeholder="120/80"
                    />
                  </div>
                )}
                {isOn('TEMPERATURE') && (
                  <div>
                    <label
                      htmlFor="fld-139"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Temperature (°F)
                    </label>
                    <input
                      id="fld-139"
                      type="number"
                      step="0.1"
                      min="0"
                      className="input-field"
                      value={opdForm.temperature}
                      onChange={(e) =>
                        setOpdForm((prev) => ({ ...prev, temperature: e.target.value }))
                      }
                    />
                  </div>
                )}
              </div>
              <div className="grid grid-cols-3 gap-4">
                {isOn('PULSE') && (
                  <div>
                    <label
                      htmlFor="fld-138"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Pulse
                    </label>
                    <input
                      id="fld-138"
                      type="number"
                      min="0"
                      className="input-field"
                      value={opdForm.pulse}
                      onChange={(e) => setOpdForm((prev) => ({ ...prev, pulse: e.target.value }))}
                    />
                  </div>
                )}
                {isOn('HEIGHT') && (
                  <div>
                    <label
                      htmlFor="fld-137"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Height (cm)
                    </label>
                    <input
                      id="fld-137"
                      type="number"
                      step="0.1"
                      min="0"
                      className="input-field"
                      value={opdForm.height}
                      onChange={(e) => setOpdForm((prev) => ({ ...prev, height: e.target.value }))}
                    />
                  </div>
                )}
                {isOn('WEIGHT') && (
                  <div>
                    <label
                      htmlFor="fld-136"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Weight (kg)
                    </label>
                    <input
                      id="fld-136"
                      type="number"
                      step="0.1"
                      min="0"
                      className="input-field"
                      value={opdForm.weight}
                      onChange={(e) => setOpdForm((prev) => ({ ...prev, weight: e.target.value }))}
                    />
                  </div>
                )}
                {isOn('SPO2') && (
                  <div>
                    <label
                      htmlFor="fld-135"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      SpO2 (%)
                    </label>
                    <input
                      id="fld-135"
                      type="number"
                      min="0"
                      className="input-field"
                      value={opdForm.spo2}
                      onChange={(e) => setOpdForm((prev) => ({ ...prev, spo2: e.target.value }))}
                    />
                  </div>
                )}
                {customs.map((v) => (
                  <div key={v.key}>
                    <label className="block text-sm font-semibold text-neutral-700 mb-2">
                      {v.label}
                      {v.unit ? ` (${v.unit})` : ''}
                    </label>
                    <input
                      className="input-field"
                      value={opdForm.customVitals?.[v.key] || ''}
                      onChange={(e) =>
                        setOpdForm((prev) => ({
                          ...prev,
                          customVitals: { ...(prev.customVitals || {}), [v.key]: e.target.value },
                        }))
                      }
                    />
                  </div>
                ))}
              </div>

              <div>
                <label
                  htmlFor="fld-134"
                  className="block text-sm font-semibold text-neutral-700 mb-2"
                >
                  Problem / Reason
                </label>
                <textarea
                  id="fld-134"
                  rows={3}
                  className="input-field resize-none"
                  value={opdForm.problem}
                  onChange={(e) => setOpdForm((prev) => ({ ...prev, problem: e.target.value }))}
                />
              </div>

              <div className="flex items-center gap-4">
                <label htmlFor="fld-10025" className="text-sm font-medium">
                  Visit Type:
                </label>
                <label className="inline-flex items-center gap-2">
                  <input
                    id="fld-10025"
                    type="radio"
                    name="visitType"
                    value="NEW"
                    checked={opdForm.visitType === 'NEW'}
                    onChange={() => setOpdForm((prev) => ({ ...prev, visitType: 'NEW' }))}
                  />{' '}
                  New
                </label>
                <label className="inline-flex items-center gap-2">
                  <input
                    type="radio"
                    name="visitType"
                    value="FOLLOWUP"
                    checked={opdForm.visitType === 'FOLLOWUP'}
                    onChange={() => setOpdForm((prev) => ({ ...prev, visitType: 'FOLLOWUP' }))}
                  />{' '}
                  Follow-up
                </label>
              </div>

              <OpdPaymentFields
                method={opdForm.paymentMethod}
                reference={opdForm.paymentReference}
                onChange={(patch) => setOpdForm((prev) => ({ ...prev, ...patch }))}
              />

              <div className="flex gap-4 pt-4">
                <button
                  type="button"
                  onClick={() => setIsOpdModalOpen(false)}
                  disabled={opdSubmitting}
                  className={`flex-1 py-2 rounded-lg border ${opdSubmitting ? 'opacity-50 cursor-not-allowed' : ''}`}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={opdSubmitting}
                  className={`flex-1 py-2 rounded-lg text-white flex items-center justify-center gap-2 ${opdSubmitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900'}`}
                >
                  {opdSubmitting && (
                    <svg
                      className="animate-spin h-4 w-4 text-white"
                      xmlns="http://www.w3.org/2000/svg"
                      fill="none"
                      viewBox="0 0 24 24"
                    >
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      ></circle>
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                      ></path>
                    </svg>
                  )}
                  {opdSubmitting ? 'Creating...' : 'Create OPD'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Confirmation Modal */}
      <ConfirmationModal
        isOpen={confirmModal.isOpen}
        onCancel={() => setConfirmModal({ ...confirmModal, isOpen: false })}
        title={confirmModal.title}
        message={confirmModal.message}
        onConfirm={confirmModal.onConfirm}
        showReasonInput={confirmModal.showReasonInput}
        inputPlaceholder={confirmModal.inputPlaceholder}
      />

      {/* History Drawer */}
      <HistoryDrawer
        isOpen={historyDrawer.isOpen}
        onClose={() => setHistoryDrawer((prev) => ({ ...prev, isOpen: false }))}
        entityType={historyDrawer.entityType}
        entityId={historyDrawer.entityId}
        entityName={historyDrawer.entityName}
      />

      {/* Patient Details Modal */}
      {patientDetailsModal.isOpen && (
        <PatientDetailsModal
          patient={patientDetailsModal.patient}
          onClose={() => setPatientDetailsModal({ isOpen: false, patient: null })}
        />
      )}

      {/* Prescription Modal */}
      <PrescriptionModal
        isOpen={prescriptionModal.isOpen}
        onClose={() => setPrescriptionModal({ ...prescriptionModal, isOpen: false })}
        data={prescriptionModal.data}
        onPrint={handlePrintPrescription}
      />

      {/* View Patient Prescription Modal */}
      <PrescriptionViewModal
        isOpen={viewPrescriptionModal.isOpen}
        patient={viewPrescriptionModal.patient}
        onClose={() => setViewPrescriptionModal({ isOpen: false, patient: null })}
      />

      {/* Payment Modal */}
      {paymentModal.isOpen && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          <div className="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
            <div className="fixed inset-0 transition-opacity" aria-hidden="true">
              <button
                type="button"
                aria-label="Close"
                className="absolute inset-0 bg-gray-500 opacity-75"
                onClick={() => setPaymentModal({ ...paymentModal, isOpen: false })}
              />
            </div>
            <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">
              &#8203;
            </span>
            <div className="inline-block align-bottom bg-white rounded-lg text-left max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full">
              <div className="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
                <div className="sm:flex sm:items-start">
                  <div className="mx-auto flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-full bg-gray-100 sm:mx-0 sm:h-10 sm:w-10"></div>
                  <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
                    <h3 className="text-lg leading-6 font-medium text-gray-900">Collect Payment</h3>
                    <div className="mt-2">
                      <p className="text-sm text-gray-500 mb-4">
                        Collect payment for <strong>{paymentModal.patientName}</strong>.
                      </p>
                      <div className="bg-gray-50 p-4 rounded-lg mb-4 text-center">
                        <span className="block text-xs text-gray-500 uppercase">Total Amount</span>
                        <span className="text-2xl font-bold text-gray-900">
                          ₹{paymentModal.amount}
                        </span>
                      </div>
                      <p className="text-sm font-medium text-gray-700 mb-3">
                        Select Payment Method:
                      </p>
                      <div className="grid grid-cols-2 gap-3">
                        <button
                          onClick={() => handleProcessPayment('Cash')}
                          disabled={paymentProcessing}
                          className={`flex items-center justify-center gap-2 p-3 border rounded-lg transition-colors group ${paymentProcessing ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50 hover:border-gray-300'}`}
                        >
                          <span className="font-medium text-gray-700">
                            {paymentProcessing === 'Cash' ? 'Processing...' : 'Cash'}
                          </span>
                        </button>
                        <button
                          onClick={() => handleProcessPayment('Online')}
                          disabled={paymentProcessing}
                          className={`flex items-center justify-center gap-2 p-3 border rounded-lg transition-colors group ${paymentProcessing ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50 hover:border-gray-300'}`}
                        >
                          <span className="font-medium text-gray-700">
                            {paymentProcessing === 'Online' ? 'Processing...' : 'Online/UPI'}
                          </span>
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
              <div className="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse">
                <button
                  type="button"
                  onClick={() => setPaymentModal({ ...paymentModal, isOpen: false })}
                  className="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Payment Success Modal */}
      {paymentSuccessModal.isOpen && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          <div className="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
            <div className="fixed inset-0 transition-opacity" aria-hidden="true">
              <button
                type="button"
                aria-label="Close"
                className="absolute inset-0 bg-gray-500 opacity-75"
                onClick={() => setPaymentSuccessModal({ ...paymentSuccessModal, isOpen: false })}
              />
            </div>
            <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">
              &#8203;
            </span>
            <div className="inline-block align-bottom bg-white rounded-lg text-left max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-sm sm:w-full">
              <div className="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
                <div className="sm:flex sm:items-start">
                  <div className="mx-auto flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-full bg-gray-100 sm:mx-0 sm:h-10 sm:w-10"></div>
                  <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
                    <h3 className="text-lg leading-6 font-medium text-gray-900">
                      Payment Successful
                    </h3>
                    <div className="mt-2">
                      <p className="text-sm text-gray-500 mb-4">
                        Payment recorded for <strong>{paymentSuccessModal.patientName}</strong>.
                      </p>
                      <div className="bg-gray-50 p-3 rounded-lg mb-4 text-center">
                        <span className="block text-xs text-gray-500 uppercase">
                          Total Received
                        </span>
                        <span className="text-2xl font-bold text-gray-900">
                          ₹{paymentSuccessModal.amount}
                        </span>
                      </div>
                      <button
                        onClick={() => handlePrintReceipt(paymentSuccessModal.billId)}
                        className="w-full flex items-center justify-center gap-2 p-3 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors mb-2"
                      >
                        <span className="font-medium">Print Receipt</span>
                      </button>
                      <button
                        onClick={() =>
                          setPaymentSuccessModal({ ...paymentSuccessModal, isOpen: false })
                        }
                        className="w-full p-2 text-gray-500 hover:text-gray-700 text-sm font-medium"
                      >
                        Close
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Profile Settings Modal */}
      <ProfileModal isOpen={profileOpen} onClose={() => setProfileOpen(false)} />

      {/* Edit Bill Items Modal */}
      {editBillItemsModal.isOpen && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          <div className="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
            <div className="fixed inset-0 transition-opacity" aria-hidden="true">
              <button
                type="button"
                aria-label="Close"
                className="absolute inset-0 bg-gray-500 opacity-75"
                onClick={() => setEditBillItemsModal({ ...editBillItemsModal, isOpen: false })}
              />
            </div>
            <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">
              &#8203;
            </span>
            <div className="inline-block align-bottom bg-white rounded-2xl text-left max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full border border-gray-200">
              <div className="bg-white px-6 pt-6 pb-4">
                <h3 className="text-lg leading-6 font-bold text-gray-900 mb-1">
                  Edit Bill Charges
                </h3>
                <p className="text-xs text-gray-500 mb-4">
                  Patient:{' '}
                  <span className="font-semibold text-gray-800">
                    {editBillItemsModal.patientName}
                  </span>{' '}
                  | Bill No:{' '}
                  <span className="font-semibold text-gray-800">
                    {editBillItemsModal.billNumber}
                  </span>
                </p>

                <div className="space-y-4">
                  <div className="flex justify-between items-center">
                    <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wide">
                      Clinic Charges & Services
                    </h4>
                    <button
                      type="button"
                      onClick={addBillItem}
                      className="text-xs font-semibold text-sky-600 hover:text-sky-800"
                    >
                      + Add Charge Item
                    </button>
                  </div>

                  <div className="space-y-3 max-h-[250px] overflow-y-auto pr-1">
                    {editBillItemsModal.items.map((item, index) => (
                      <div
                        key={index}
                        className="flex gap-2 items-center bg-gray-50 p-3 rounded-xl border border-gray-100"
                      >
                        <div className="flex-1 space-y-2">
                          <div className="flex gap-2">
                            <select
                              onChange={(e) => {
                                const val = e.target.value;
                                if (val === 'consultation') {
                                  updateBillItem(index, 'name', 'Consultation Fee');
                                  updateBillItem(
                                    index,
                                    'defaultAmount',
                                    standardFees.consultationFee || '0'
                                  );
                                } else if (val === 'case_paper') {
                                  updateBillItem(index, 'name', 'Case Paper Fee');
                                  updateBillItem(
                                    index,
                                    'defaultAmount',
                                    standardFees.casePaperFee || '0'
                                  );
                                } else if (val.startsWith('custom_')) {
                                  const customId = parseInt(val.replace('custom_', ''));
                                  const found = customFees.find((f) => f.id === customId);
                                  if (found) {
                                    updateBillItem(index, 'name', found.name);
                                    updateBillItem(
                                      index,
                                      'defaultAmount',
                                      found.defaultAmount || '0'
                                    );
                                  }
                                } else if (val === 'injections') {
                                  updateBillItem(index, 'name', 'Injections');
                                  updateBillItem(index, 'defaultAmount', '0');
                                } else if (val === 'medicines_by_hospital') {
                                  updateBillItem(index, 'name', 'Medicines by Hospital');
                                  updateBillItem(index, 'defaultAmount', '0');
                                }
                              }}
                              className="text-xs border border-gray-200 rounded-lg p-1 bg-white focus:ring-1 focus:ring-sky-500"
                              defaultValue=""
                            >
                              <option value="" disabled>
                                -- Select Charge Preset --
                              </option>
                              <option value="consultation">
                                Consultation Fee (₹{standardFees.consultationFee || 0})
                              </option>
                              <option value="case_paper">
                                Case Paper Fee (₹{standardFees.casePaperFee || 0})
                              </option>
                              <option value="injections">Injections</option>
                              <option value="medicines_by_hospital">Medicines by Hospital</option>
                              {customFees.map((f) => (
                                <option key={f.id} value={`custom_${f.id}`}>
                                  {f.name} (₹{f.defaultAmount || 0})
                                </option>
                              ))}
                              <option value="manual">Manual Entry</option>
                            </select>
                          </div>
                          <div className="grid grid-cols-3 gap-2">
                            <input
                              type="text"
                              placeholder="Description"
                              value={item.name}
                              onChange={(e) => updateBillItem(index, 'name', e.target.value)}
                              className="col-span-2 px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-1 focus:ring-sky-500 focus:border-transparent bg-white font-medium text-gray-900"
                            />
                            <input
                              type="number"
                              placeholder="Amount"
                              value={item.defaultAmount}
                              onChange={(e) =>
                                updateBillItem(index, 'defaultAmount', e.target.value)
                              }
                              className="px-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:ring-1 focus:ring-sky-500 focus:border-transparent bg-white text-gray-900 font-semibold"
                            />
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => removeBillItem(index)}
                          className="text-red-500 hover:text-red-700 p-2 text-lg font-bold"
                        >
                          ✕
                        </button>
                      </div>
                    ))}
                    {editBillItemsModal.items.length === 0 && (
                      <div className="text-center py-6 border border-dashed border-gray-200 rounded-xl bg-gray-50 text-xs text-gray-400">
                        No charges added yet. Click &quot;+ Add Charge Item&quot; to begin.
                      </div>
                    )}
                  </div>

                  {editBillItemsModal.medicines && editBillItemsModal.medicines.length > 0 && (
                    <div className="border-t border-gray-100 pt-3">
                      <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wide mb-2">
                        Administered In-Clinic Medicines (Read-Only)
                      </h4>
                      <div className="space-y-2 max-h-[120px] overflow-y-auto pr-1">
                        {editBillItemsModal.medicines.map((med, index) => (
                          <div
                            key={index}
                            className="flex justify-between items-center text-xs text-gray-600 bg-teal-50/50 border border-teal-100/50 p-2 rounded-lg"
                          >
                            <div>
                              <span className="font-semibold text-gray-800">
                                {med.medicineName}
                              </span>
                              <span className="text-gray-500 ml-2">Qty: {med.quantity}</span>
                            </div>
                            <span className="font-semibold text-teal-700">₹{med.amount}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
              <div className="bg-gray-50 px-6 py-4 flex flex-row-reverse gap-3">
                <button
                  type="button"
                  onClick={handleSaveBillItems}
                  disabled={editBillItemsSubmitting}
                  className="px-4 py-2 bg-gray-900 text-white rounded-xl text-sm font-semibold hover:bg-gray-800 transition disabled:opacity-50"
                >
                  {editBillItemsSubmitting ? 'Saving...' : 'Save Changes'}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setEditBillItemsModal({
                      isOpen: false,
                      billId: null,
                      items: [],
                      medicines: [],
                      patientName: '',
                      billNumber: '',
                    })
                  }
                  className="px-4 py-2 bg-white border border-gray-200 text-gray-700 rounded-xl text-sm font-semibold hover:bg-gray-50 transition"
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      <DayCareSurgeryModal
        isOpen={isDayCareModalOpen}
        onClose={() => setIsDayCareModalOpen(false)}
        onSuccess={loadOt}
      />
      {isOtListOpen && <OtListPrint onClose={() => setIsOtListOpen(false)} />}
    </div>
  );
};

export default ReceptionistDashboard;

// Appointments Table Component (Standardized with Admin)
const AppointmentsTable = ({
  appointments,
  doctors,
  onStatusUpdate,
  onHistory,
  onViewPrescription,
  startIndex = 0,
  pagination,
}) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.No.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.accessor((row) => row.customId || row.id, {
      id: 'id',
      header: 'ID',
      cell: (info) => <span title="Serial Number">{info.getValue()}</span>,
    }),
    columnHelper.accessor((row) => row.patientName || row.patientId, {
      id: 'patient',
      header: 'Patient',
    }),
    columnHelper.accessor(
      (row) => row.doctorName || doctors?.find((d) => d.id === row.doctorId)?.name || row.doctorId,
      {
        id: 'doctor',
        header: 'Doctor',
      }
    ),
    columnHelper.accessor('appointmentDate', {
      header: 'Date',
    }),
    columnHelper.accessor('appointmentTime', {
      header: 'Time',
      cell: (info) =>
        info.getValue() ? (
          <span className="text-sm font-medium bg-gray-100 px-2 py-1 rounded">
            {info.getValue().substring(0, 5)}
          </span>
        ) : (
          '-'
        ),
    }),
    columnHelper.accessor('status', {
      header: 'Status',
      cell: (info) => {
        const status = info.getValue();
        const isFinal = ['CANCELLED', 'COMPLETED'].includes(status);
        return (
          <StatusBadge
            status={status}
            options={isFinal ? [] : ['SCHEDULED', 'COMPLETED', 'CANCELLED']}
            onUpdate={
              isFinal
                ? null
                : (newStatus) =>
                    onStatusUpdate(info.row.original.publicId || info.row.original.id, newStatus)
            }
            type="dropdown"
          />
        );
      },
    }),
    columnHelper.accessor('notes', {
      header: 'Notes',
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">Actions</div>,
      cell: (info) => (
        <div className="text-right">
          <ActionMenu
            actions={[
              {
                label: 'View Prescription',
                icon: (
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="h-4 w-4"
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zm0 4a1 1 0 011-1h6a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h6a1 1 0 110 2H4a1 1 0 01-1-1z"
                      clipRule="evenodd"
                    />
                  </svg>
                ),
                onClick: () =>
                  onViewPrescription(info.row.original.publicId || info.row.original.id),
                hidden: info.row.original.status !== 'COMPLETED',
              },
              {
                label: 'Cancel',
                icon: (
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="h-4 w-4"
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
                      clipRule="evenodd"
                    />
                  </svg>
                ),
                onClick: () =>
                  onStatusUpdate(info.row.original.publicId || info.row.original.id, 'CANCELLED'),
                variant: 'danger',
                // Only show cancel if not already cancelled/completed
                disabled: ['CANCELLED', 'COMPLETED'].includes(info.row.original.status),
                hidden: ['CANCELLED', 'COMPLETED'].includes(info.row.original.status),
              },
            ]}
          />
        </div>
      ),
    }),
  ];

  // DEBUG: Log first appointment structure to verify publicId
  if (appointments.length > 0) {
    // console.log("DEBUG: First Appointment Record:", appointments[0]);
    const corruptAppointments = appointments.filter((a) => !a.publicId);
    if (corruptAppointments.length > 0) {
      console.error(
        'CRITICAL: The following appointments are missing publicId:',
        JSON.stringify(corruptAppointments, null, 2)
      );
      // Optional: fallback display or warning
    }
  }

  return <DataTable data={appointments} columns={columns} pagination={pagination} />;
};

// Patients Table Component (Standardized with Admin)
const PatientsTable = ({
  patients,
  onStatusUpdate,
  onViewPrescription,
  onViewDetails,
  onEdit,
  onOpenPayment,
  onPrintReceipt,
  startIndex = 0,
  pagination,
}) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.NO.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.accessor((row) => row.customId || row.id, {
      id: 'id',
      header: 'ID',
      cell: (info) => <span title="Serial Number">{info.getValue()}</span>,
    }),
    columnHelper.accessor('name', {
      header: 'NAME',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('age', {
      header: 'AGE',
    }),
    columnHelper.accessor('gender', {
      header: 'GENDER',
    }),
    columnHelper.accessor('phone', {
      header: 'PHONE',
    }),
    columnHelper.accessor('address', {
      header: 'ADDRESS',
      cell: (info) => <span className="truncate max-w-xs block">{info.getValue()}</span>,
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">ACTIONS</div>,
      cell: (info) => {
        const patient = info.row.original;

        const actions = [
          {
            label: 'View Details',
            icon: (
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path d="M10 12a2 2 0 100-4 2 2 0 000 4z" />
                <path
                  fillRule="evenodd"
                  d="M.458 10C1.732 5.943 5.522 3 10 3s8.268 2.943 9.542 7c-1.274 4.057-5.064 7-9.542 7S1.732 14.057.458 10zM14 10a4 4 0 11-8 0 4 4 0 018 0z"
                  clipRule="evenodd"
                />
              </svg>
            ),
            onClick: () => onViewDetails(patient),
          },
          {
            label: 'Edit',
            icon: (
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
              </svg>
            ),
            onClick: () => onEdit(patient),
          },
        ];

        return (
          <div className="text-right">
            <ActionMenu actions={actions} />
          </div>
        );
      },
    }),
  ];

  return <DataTable data={patients} columns={columns} pagination={pagination} />;
};
