import { createColumnHelper } from '@tanstack/react-table';
import FollowUpPanel from '../../components/FollowUpPanel';
import { safeLoadMessage } from '../../utils/apiError';
import React, { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ActionMenu from '../../components/ActionMenu';
import AppointmentModal from '../../components/AppointmentModal';
import ConfirmationModal from '../../components/ConfirmationModal';
import ConsultationModal from '../../components/ConsultationModal';
import DataTable from '../../components/DataTable';
import DateSelect from '../../components/DateSelect';
import EmptyState from '../../components/EmptyState';
import HospitalInventoryTab from '../../components/HospitalInventoryTab';
import LowStockBanner from '../../components/LowStockBanner';
import MedicineInventoryTab from '../../components/MedicineInventoryTab';
import Navbar from '../../components/Navbar';
import OpdPaymentFields, {
  isPayFirst,
  validateOpdPayment,
} from '../../components/OpdPaymentFields';
// BUG-028: single source-of-truth for base URL
import PageHeader from '../../components/PageHeader';
import PatientDetailsModal from '../../components/PatientDetailsModal';
import PatientModal from '../../components/PatientModal';
import PrescriptionViewModal from '../../components/PrescriptionViewModal';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import { SkeletonStatsGrid, SkeletonOverviewDual, SkeletonTable } from '../../components/Skeleton';
import StatusBadge from '../../components/StatusBadge';
import { useToast } from '../../context/ToastContext';
import useDebounce from '../../hooks/useDebounce'; // BUG-017: standardised debounce hook
import useEnabledVitals from '../../hooks/useEnabledVitals';
import useWebSocket from '../../hooks/useWebSocket';
import apiClient from '../../services/apiService';
import authService from '../../services/authService';
import hospitalService from '../../services/hospitalService';
import otService from '../../services/otService';
import { extractApiError } from '../../utils/apiError';
import BillingTable from './BillingTable';
import IcuBedBoard from './icu/IcuBedBoard';
import IcuDashboard from './icu/IcuDashboard';
import OtBoard from './ot/OtBoard';
import { createOptionalModuleFetcher } from '../../utils/optionalModule';

/**
 * DoctorDashboard - Doctor dashboard
 *
 * This page allows Doctor to:
 * - View their own appointments
 * - Manage appointment status
 * - View patients
 *
 * @author HMS Team
 * @version Phase-1
 */
const DoctorDashboard = () => {
  const [user, setUser] = useState(() => authService.getCurrentUser());
  const modules = user?.modules || [];
  const hasIPD = modules.includes('IPD');
  const hasAppointments = modules.includes('APPOINTMENTS');
  const hasOT = modules.includes('OT');
  // Gates the tab only. Who may see which ICU row is decided server-side by IcuBoardService
  // (ICU-1 D6) — this never re-implements that rule.
  const hasICU = modules.includes('ICU');
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
  const [loading, setLoading] = useState(false);
  // A failed load is its own state. Blanking the list on failure told the doctor "no patients
  // waiting", which is indistinguishable from a quiet clinic and is the worst possible way for
  // this screen to fail. Previously loaded rows are kept so a transient blip does not wipe the
  // board the doctor is reading.
  const [loadError, setLoadError] = useState(null);

  /**
   * A message safe to show a clinician.
   *
   * extractApiError falls back to err.message, which for a network or runtime failure is a raw
   * technical string — a Java exception line reached this banner in testing. The server's own
   * structured message is meaningful to a user; anything else is not, so it is replaced rather
   * than displayed.
   */
  // Scoped loading indicator for just the Appointments card/table, so switching
  // the Today/Upcoming/History filter doesn't blow away the whole dashboard's
  // full-page skeleton (that's reserved for actual tab switches).
  const [appointmentsLoading, setAppointmentsLoading] = useState(false);

  // View Prescription Modal State
  const [viewPrescriptionModal, setViewPrescriptionModal] = useState({
    isOpen: false,
    patient: null,
  });

  // Stats State
  const [stats, setStats] = useState({ today: 0, pending: 0, total: 0 });
  // Queue / OPD state (doctor-specific)
  const [queueEntries, setQueueEntries] = useState([]);
  const [opds, setOpds] = useState([]);
  const [currentPatient, setCurrentPatient] = useState(null);
  const [nextPatient, setNextPatient] = useState(null);

  // Pagination & Search
  const [page, setPage] = useState(1);
  const ITEMS_PER_PAGE = 10;
  const [paginatedData, setPaginatedData] = useState([]);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [totalAppointments, setTotalAppointments] = useState(0);
  const [searchInput, setSearchInput] = useState('');
  const searchTerm = useDebounce(searchInput, 500); // BUG-017: debounce search input

  const { success, error: toastError, info } = useToast();
  const navigate = useNavigate();

  // Patient History Modal State (Removed in favor of HistoryDrawer)
  // const [historyModal, setHistoryModal] = useState({ isOpen: false, patient: null, history: [] });

  // Edit Appointment Modal
  const [editModal, setEditModal] = useState({
    isOpen: false,
    appointment: null,
    status: '',
    notes: '',
  });

  // Confirmation Modal
  const [confirmModal, setConfirmModal] = useState({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: null,
    showReasonInput: false,
    inputPlaceholder: '',
  });

  // Sidebar collapse state
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);

  // OPD date filter — default to today
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

  // Billing specific states
  const [billing, setBilling] = useState([]);
  const [billingStatus, setBillingStatus] = useState('PENDING');

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

  // SOLO Doctor specific states
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isAddPatientModalOpen, setIsAddPatientModalOpen] = useState(false);
  const [isOpdModalOpen, setIsOpdModalOpen] = useState(false);
  const [patientDetailsModal, setPatientDetailsModal] = useState({ isOpen: false, patient: null });
  const [isIpdAdmitOpen, setIsIpdAdmitOpen] = useState(false);
  const [ipdOpdForAdmit, setIpdOpdForAdmit] = useState(null);
  // State for admit-to-IPD directly from appointment action
  const [ipdFromAppointment, setIpdFromAppointment] = useState({
    isOpen: false,
    appointment: null,
  });

  // Payment Modals
  const [paymentModal, setPaymentModal] = useState({
    isOpen: false,
    billId: null,
    amount: null,
    patientName: '',
  });

  const [paymentSuccessModal, setPaymentSuccessModal] = useState({
    isOpen: false,
    billId: null,
    patientName: '',
    amount: 0,
  });

  // Resolved Doctor record state
  const [doctorRecord, setDoctorRecord] = useState(null);

  // --- OT (Operation Theatre) board — visible to all doctors ---
  const [otRows, setOtRows] = useState([]);
  const [otLoading, setOtLoading] = useState(false);

  useEffect(() => {
    if (activeTab !== 'ot' || !hasOT) return;
    setOtLoading(true);
    otService
      .getMyBoard()
      .then((d) => setOtRows(Array.isArray(d) ? d : []))
      .catch(() => setOtRows([]))
      .finally(() => setOtLoading(false));
  }, [activeTab, hasOT]);

  // Fetch doctor record corresponding to user email
  useEffect(() => {
    const fetchDoctorRecord = async () => {
      try {
        const docs = await hospitalService.getDoctors('', 0, 100);
        const docsList = Array.isArray(docs) ? docs : docs.content || [];
        const currentDoc = docsList.find(
          (d) => d.email?.toLowerCase() === user?.email?.toLowerCase()
        );
        if (currentDoc) {
          setDoctorRecord(currentDoc);
          setOpdForm((prev) => ({ ...prev, doctorId: currentDoc.id }));
        }
      } catch (err) {
        console.error('Failed to load doctor record', err);
      }
    };
    if (user?.email) {
      fetchDoctorRecord();
    }
  }, [user]);

  // OPD Form
  const [opdForm, setOpdForm] = useState({
    patientId: null,
    receptionistId: null,
    doctorId: user?.id || null,
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
  const [patientSearchText, setPatientSearchText] = useState('');
  const [showPatientDropdown, setShowPatientDropdown] = useState(false);

  // Reset OPD form on modal open/close
  useEffect(() => {
    if (!isOpdModalOpen) {
      setPatientSearchText('');
      setShowPatientDropdown(false);
      setOpdForm((prev) => ({
        ...prev,
        patientId: null,
        doctorId: doctorRecord?.id || user?.id || null,
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
  }, [isOpdModalOpen, doctorRecord]);

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

  const handleViewHistory = (patient) => {
    setPatientDetailsModal({ isOpen: true, patient });
  };

  // Appointment "View History" → the patient's full clinical history, shown in the same modal
  // the consultation/patient views use (opened on the Medical History tab). We resolve the full
  // patient by id first so every tab (Info / Medical History / Bills) loads correctly.
  const handleAppointmentHistory = async (appt) => {
    const pid = appt.patientId;
    if (!pid) {
      toastError('No patient is linked to this appointment.');
      return;
    }
    try {
      const patient = await hospitalService.getPatientById(pid);
      setPatientDetailsModal({ isOpen: true, patient, initialTab: 'medicalhistory' });
    } catch (e) {
      // Fallback: consultation-details also accepts the numeric id, so the history still loads
      // from the appointment's own patient fields even if the full patient fetch fails.
      setPatientDetailsModal({
        isOpen: true,
        patient: {
          id: pid,
          name: appt.patientName,
          phone: appt.patientPhone,
          email: appt.patientEmail,
          gender: appt.patientGender,
        },
        initialTab: 'medicalhistory',
      });
    }
  };

  const handleEditClick = (appointment) => {
    setEditModal({
      isOpen: true,
      appointment: appointment,
      status: appointment.status,
      notes: appointment.notes || '',
    });
  };

  const handleUpdateAppointment = async () => {
    try {
      await hospitalService.updateAppointment(
        editModal.appointment.id,
        editModal.status,
        editModal.notes
      );
      success('Appointment updated successfully');
      setEditModal({ isOpen: false, appointment: null, status: '', notes: '' });
      loadData();
    } catch (err) {
      toastError('Failed to update appointment');
    }
  };

  useEffect(() => {
    setSearchInput('');
    setPage(1);
    if (activeTab === 'opd') {
      setOpdTabView('Live');
      setOpdDateFilter(todayStr);
    }
    if (activeTab === 'patients') {
      setPatientTabView('All');
      setPatientDateFilter(todayStr);
    }
  }, [activeTab]);

  // Only an actual tab switch shows the full-page loading skeleton — a
  // dedicated effect keyed on just [activeTab], so it can't misfire on
  // filter/search/page changes within the same tab. (Deriving "is this a
  // tab switch" from a manually-mutated ref compared against activeTab
  // doesn't survive React 18 StrictMode's double-invocation of effects in
  // dev: the ref update from the first invocation makes the second one
  // think nothing changed, which can leave loading stuck true. Deriving
  // purely from React's own dependency-array diffing avoids that.)
  useEffect(() => {
    setLoading(true);
  }, [activeTab]);

  useEffect(() => {
    loadData(false);
  }, [
    activeTab,
    searchTerm,
    viewFilter,
    billingStatus,
    opdDateFilter,
    opdTabView,
    patientTabView,
    patientDateFilter,
    page,
  ]);

  // WebSocket connection will be initialized below loadData definition to avoid ReferenceError

  useEffect(() => {
    // Use local date instead of UTC
    const today = new Date();
    const todayStr =
      today.getFullYear() +
      '-' +
      String(today.getMonth() + 1).padStart(2, '0') +
      '-' +
      String(today.getDate()).padStart(2, '0');

    const todayCount = appointments.filter((a) => a.appointmentDate === todayStr).length;
    const pendingCount = appointments.filter((a) => a.status === 'SCHEDULED').length;

    setStats({
      today: todayCount,
      pending: pendingCount,
      total: totalAppointments,
    });
  }, [appointments, totalAppointments]);

  /**
   * Appointment reads that must never take the dashboard down — see utils/optionalModule.
   */
  const fetchAppointmentData = createOptionalModuleFetcher(hasAppointments);

  const loadData = async (showSpinner = true) => {
    if (showSpinner) setLoading(true);
    // Cleared up front so a successful retry removes the banner without extra bookkeeping.
    setLoadError(null);
    try {
      if (activeTab === 'appointments' && hasAppointments) {
        setAppointmentsLoading(true);
        try {
          const localPage = page - 1;
          try {
            const data = await fetchAppointmentData(
              () =>
                hospitalService.getMyAppointments(viewFilter, searchTerm, localPage, ITEMS_PER_PAGE),
              { content: [], totalElements: 0, totalPages: 1 }
            );
            // Handle both array and paginated response
            const appointmentsArray = Array.isArray(data) ? data : data.content || [];
            setAppointments(appointmentsArray);
            setTotalElements(data.totalElements || 0);
            setTotalAppointments(data.totalElements || 0);
            setTotalPages(data.totalPages || 1);
          } catch (err) {
            console.error('Failed to load appointments', err);
            toastError('Failed to load appointments');
          }
        } finally {
          setAppointmentsLoading(false);
        }
      } else if (activeTab === 'overview') {
        setAppointmentsLoading(true);
        try {
          // The appointment request is optional; report a failure without aborting the queue,
          // follow-up, and OPD data that the walk-in workflow needs.
          try {
            const data = await fetchAppointmentData(
              () => hospitalService.getMyAppointments(viewFilter, searchTerm, 0, 100),
              { content: [], totalElements: 0 }
            );
            const appointmentsArray = Array.isArray(data) ? data : data.content || [];
            setAppointments(appointmentsArray);
            setTotalAppointments(data.totalElements || appointmentsArray.length);
          } catch (err) {
            console.error('Failed to load overview appointments', err);
            toastError('Failed to load appointments');
          }

          // The old endpoint inferred this from the caller's role; it is now stated.
          const followUpsData = await hospitalService.getTodaysFollowUps({ mine: true });
          setTodaysFollowUps(followUpsData || []);

          // If Solo Mode is active, fetch patients for Overview's patient list as well
          if (user?.receptionMode === 'SOLO') {
            const patData = await hospitalService.getPatients('', 0, 100);
            const patientsArray = Array.isArray(patData) ? patData : patData.content || [];
            setPatients(patientsArray);
          }
        } finally {
          setAppointmentsLoading(false);
        }
      } else if (activeTab === 'patients') {
        const dateParam = patientTabView === 'Date' ? patientDateFilter : '';
        const data = await hospitalService.getPatients(
          searchTerm,
          page - 1,
          ITEMS_PER_PAGE,
          dateParam
        );
        const patientsArray = Array.isArray(data) ? data : data.content || [];
        setPatients(patientsArray);
        setTotalElements(data.totalElements || 0);
        setTotalPages(data.totalPages || 1);
      } else if (activeTab === 'billing') {
        const data = await hospitalService.getBills(
          searchTerm,
          page - 1,
          ITEMS_PER_PAGE,
          billingStatus
        );
        const billingArray = Array.isArray(data) ? data : data.content || [];
        setBilling(billingArray);
        setTotalElements(data.totalElements || billingArray.length);
        setTotalPages(data.totalPages || 1);
      }

      // Doctor-specific queue (loaded for overview, queue, and opd tabs to enable position mapping)
      if (activeTab === 'overview' || activeTab === 'queue' || activeTab === 'opd') {
        try {
          // For doctor dashboard use authenticated doctor's queue (backend maps user -> doctor)
          const q = await hospitalService.getDoctorQueue();
          setQueueEntries(q || []);
          if (q && q.length > 0) {
            const sorted = [...q].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
            setCurrentPatient(
              sorted[0]?.opd?.patient?.name || sorted[0]?.opd?.patientName || 'No Name'
            );
            setNextPatient(
              sorted[1]
                ? sorted[1]?.opd?.patient?.name || sorted[1]?.opd?.patientName || 'No Name'
                : null
            );
          } else {
            setCurrentPatient(null);
            setNextPatient(null);
          }
        } catch (err) {
          setLoadError(safeLoadMessage(err, 'Could not load your patient queue.'));
        }
      }

      // OPD list for doctor (read-only)
      if (activeTab === 'opd') {
        try {
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
            page - 1,
            ITEMS_PER_PAGE,
            dateParam,
            statusParam
          );
          let opdsArray = Array.isArray(opdsData) ? opdsData : opdsData.content || [];
          // In regular mode for Live view, we only show QUEUED anyway, but let's keep the local safety fallback
          if (user?.receptionMode !== 'SOLO' && opdTabView === 'Live') {
            opdsArray = opdsArray.filter((o) => o.status === 'QUEUED');
          }
          setOpds(opdsArray);
          setTotalElements(opdsData.totalElements || opdsArray.length);
          setTotalPages(opdsData.totalPages || 1);
        } catch (err) {
          setLoadError(safeLoadMessage(err, 'Could not load the OPD list.'));
        }
      }

      // IPD list for doctor (their own admissions)
      if (activeTab === 'ipd') {
        try {
          // use role-aware admitted list (server will filter to current doctor)
          const list = await hospitalService.getAdmittedIpdAdmissions();
          let arr = Array.isArray(list) ? list : list.content || [];
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
          setTotalElements(arr.length);
          setTotalPages(1);
        } catch (err) {
          setLoadError(safeLoadMessage(err, 'Could not load your admitted patients.'));
        }
      }
    } catch (err) {
      toastError('Failed to load data');
    } finally {
      // Unconditionally clear — the request that's finishing is the one
      // that should decide the final loading state, regardless of
      // whether it was the call that originally turned it on.
      setLoading(false);
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

  const handleStatusUpdate = (id, newStatus) => {
    const action = newStatus === 'COMPLETED' ? 'Complete' : 'Cancel';

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
            setAppointments(oldAppointments);
            toastError('Failed to cancel appointment');
          }
        },
        true, // Show reason input
        'Reason for cancellation (required)'
      );
    } else {
      openConfirmation(
        `${action} Appointment`,
        `Are you sure you want to mark this appointment as ${newStatus.toLowerCase()}?`,
        async () => {
          const oldAppointments = [...appointments];
          setAppointments((prev) =>
            prev.map((appt) => (appt.id === id ? { ...appt, status: newStatus } : appt))
          );
          try {
            await hospitalService.updateAppointmentStatus(id, newStatus);
            success(`Appointment ${newStatus.toLowerCase()} successfully`);
            loadData(false);
          } catch (err) {
            setAppointments(oldAppointments);
            toastError(`Failed to ${action.toLowerCase()} appointment`);
          }
        }
      );
    }
  };

  const handleBillStatus = async (id, status, billObj = null) => {
    // Open payment modal when marking PAID
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

  const [docPrintingId, setDocPrintingId] = useState(null);
  const handlePrintReceipt = (id) => {
    openPdfInNewTab(`/hospital/billing/${id}/pdf`);
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

  const [docPaymentProcessing, setDocPaymentProcessing] = useState(false);

  const handleProcessPayment = async (method) => {
    if (docPaymentProcessing) return;
    setDocPaymentProcessing(method);
    try {
      const pm = method === 'Online' ? 'UPI' : 'CASH';
      let reference = null;
      if (pm === 'UPI') {
        reference = window.prompt('Enter UTR / transaction reference (required for UPI):');
        if (!reference || !reference.trim()) {
          toastError('UTR / reference is required for UPI payments');
          setDocPaymentProcessing(false);
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
      setDocPaymentProcessing(false);
    }
  };

  const handleLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  const isSolo = user?.receptionMode === 'SOLO';
  const hasBilling = user?.billingHandler === 'DOCTOR' || user?.billingHandler === 'BOTH';
  const hasInClinic = user?.inClinic !== false;
  const hasMedicalInventory = modules.includes('MEDICAL_INVENTORY');
  const hasHospitalInventory = modules.includes('HOSPITAL_INVENTORY');
  // Tenant-aware label: clinic logins say "Clinic" wherever we'd otherwise say "Hospital".
  const tenantWord = user?.hospitalType === 'CLINIC' ? 'Clinic' : 'Hospital';

  const tabs = [
    { id: 'overview', label: 'Overview', icon: null },
    { id: 'patients', label: 'Patients', icon: null },
    ...(hasAppointments ? [{ id: 'appointments', label: 'Appointments', icon: null }] : []),
    { id: 'opd', label: 'OPD', icon: null },
    { id: 'follow-ups', label: 'Follow-ups', icon: null },
    ...(hasIPD ? [{ id: 'ipd', label: 'IPD', icon: null }] : []),
    ...(isSolo || hasBilling ? [{ id: 'billing', label: 'Billing', icon: null }] : []),
    ...(hasOT ? [{ id: 'ot', label: 'Operation Theatre', icon: null }] : []),
    ...(hasICU ? [{ id: 'icu-dashboard', label: 'ICU Dashboard', icon: null }] : []),
    ...(hasICU ? [{ id: 'icu-beds', label: 'ICU Bed Board', icon: null }] : []),
    ...(isSolo && hasInClinic && hasMedicalInventory
      ? [{ id: 'inventory', label: 'Medicine Inventory', icon: null }]
      : []),
    ...(isSolo && hasHospitalInventory
      ? [{ id: 'hospital-inventory', label: `${tenantWord} Inventory`, icon: null }]
      : []),
  ];

  // Fallback if the URL parameter tab is not currently valid/visible
  useEffect(() => {
    const isValidTab = tabs.some((t) => t.id === activeTab);
    if (!isValidTab) {
      setActiveTab('overview');
    }
  }, [user, activeTab, tabs]);

  const pagination = {
    pageIndex: page - 1, // DataTable expects 0-indexed
    pageSize: ITEMS_PER_PAGE,
    totalItems: totalElements,
    pageCount: totalPages,
    onPageChange: (newPage) => setPage(newPage + 1), // DataTable gives 0-indexed
  };

  // Client-side pagination logic for queueEntries
  const sortedQueue = [...queueEntries].sort(
    (a, b) => new Date(a.createdAt) - new Date(b.createdAt)
  );
  const queueTotalPages = Math.ceil(sortedQueue.length / ITEMS_PER_PAGE) || 1;
  const paginatedQueue = sortedQueue.slice((page - 1) * ITEMS_PER_PAGE, page * ITEMS_PER_PAGE);

  const queuePagination = {
    pageIndex: page - 1,
    pageSize: ITEMS_PER_PAGE,
    totalItems: sortedQueue.length,
    pageCount: queueTotalPages,
    onPageChange: (newPage) => setPage(newPage + 1),
  };

  // Consultation Modal
  const [consultationModal, setConsultationModal] = useState({
    isOpen: false,
    appointment: null,
    patient: null,
    opd: null,
  });
  const [checkoutPrintModal, setCheckoutPrintModal] = useState({
    isOpen: false,
    opdId: null,
    billId: null,
    appointmentId: null,
    patientName: '',
    patientId: '',
  });

  const handleConsultClick = (appointment) => {
    setConsultationModal({ isOpen: true, appointment });
  };

  // Print a server PDF the way the forms print: fetch it (auth + tenant rewrite via
  // apiClient), load it into a hidden iframe, and fire the print dialog. This avoids the
  // pop-up blocker entirely — opening several new tabs in a row (case paper, prescription,
  // bill) got blocked, which is why some documents silently never printed.
  const printPdf = (endpointPath) =>
    new Promise((resolve) => {
      apiClient
        .get(endpointPath, { responseType: 'blob' })
        .then((resp) => {
          const blobUrl = URL.createObjectURL(new Blob([resp.data], { type: 'application/pdf' }));
          const iframe = document.createElement('iframe');
          iframe.style.position = 'fixed';
          iframe.style.left = '-10000px';
          iframe.style.width = '210mm';
          iframe.style.height = '297mm';
          iframe.style.border = '0';
          iframe.onload = () => {
            setTimeout(() => {
              try {
                iframe.contentWindow.focus();
                iframe.contentWindow.print();
              } catch (e) {
                /* ignore */
              }
              resolve(true);
            }, 300);
            // Revoke well after the print dialog has had time to render the document.
            setTimeout(() => {
              URL.revokeObjectURL(blobUrl);
              iframe.remove();
            }, 120000);
          };
          iframe.src = blobUrl;
          document.body.appendChild(iframe);
        })
        .catch(() => resolve(false));
    });
  // Print server PDFs via the shared util (utils/printPdf): desktop uses a hidden iframe (avoids
  // the pop-up blocker when several documents print in a row); iOS/iPadOS opens the PDF in a real
  // foreground tab, because WebKit can't print a hidden iframe and would otherwise print a
  // "screenshot" of the visible page instead of the document.

  // Kept for the single-document print buttons: fire the print without waiting.
  const openPdfInNewTab = (endpointPath) => {
    printPdf(endpointPath);
    return true;
  };

  const handlePrintPrescription = (appointmentId) => {
    openPdfInNewTab(`/hospital/doctors/prescription/${appointmentId}/pdf`);
  };

  const handlePrintOpd = (opd) => {
    openPdfInNewTab(`/hospital/opd/${opd.id}/pdf`);
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

  const handlePrintPrescriptionOpd = (opd) => {
    openPdfInNewTab(`/hospital/doctors/prescription/opd/${opd.id}/pdf`);
  };

  const handleViewPrescriptionOpd = (opd) => {
    openPdfInNewTab(`/hospital/doctors/prescription/opd/${opd.id}/pdf`);
  };

  const [startingConsultationId, setStartingConsultationId] = useState(null);

  // Patient consultation handlers
  const handleStartConsultation = async (patient) => {
    if (startingConsultationId) return;
    setStartingConsultationId(patient.publicId || patient.id);
    try {
      // Update status to CONSULTING
      await hospitalService.startConsultation(patient.publicId);
      success('Consultation started');

      // Reload data to reflect status change
      await loadData();

      // Open consultation modal
      setConsultationModal({ isOpen: true, patient: { ...patient, status: 'CONSULTING' } });
    } catch (err) {
      console.error('Failed to start consultation', err);
      toastError('Failed to start consultation status update');
      // Still open modal to allow doctor to proceed even if status update fails?
      // Maybe safer to still open it
      setConsultationModal({ isOpen: true, patient });
    } finally {
      setStartingConsultationId(null);
    }
  };

  const handleStartOpdConsultation = async (opd) => {
    try {
      // Open consultation modal with OPD context
      setConsultationModal({ isOpen: true, appointment: null, patient: opd.patient, opd });
    } catch (err) {
      console.error('Failed to open OPD consultation modal', err);
      toastError('Failed to start OPD consultation');
      setConsultationModal({ isOpen: true, appointment: null, patient: opd.patient, opd });
    }
  };

  const handleCompleteConsultation = (patient) => {
    setConsultationModal({ isOpen: true, patient });
  };

  const handleViewPrescription = (patient) => {
    // Direct open View Prescription Modal
    setViewPrescriptionModal({ isOpen: true, patient });
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

      {/* Main Content Wrapper */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Navbar */}
        <Navbar
          title={tabs.find((t) => t.id === activeTab)?.label}
          user={user}
          onLogout={handleLogout}
          onProfile={() => setProfileOpen(true)}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
        />

        {/* Main Content Area */}
        <main className="flex-1 overflow-x-hidden overflow-y-auto bg-white p-8">
          {/* A failed load says so. It must never be shown as an empty clinic: "no patients
              waiting" and "we could not reach the server" call for opposite responses from a
              doctor, and only one of them is true. */}
          {loadError && (
            <div
              role="alert"
              className="mb-6 rounded-xl border border-red-200 bg-red-50 px-4 py-4 flex items-start justify-between gap-4"
            >
              <div>
                <p className="text-sm font-semibold text-red-800">Couldn&apos;t load your patients</p>
                <p className="mt-1 text-sm text-red-700">{loadError}</p>
                <p className="mt-1 text-xs text-red-600">
                  Anything shown below may be out of date.
                </p>
              </div>
              <button
                type="button"
                onClick={() => loadData()}
                className="shrink-0 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700"
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
                {user?.receptionMode === 'SOLO' && (
                  <div className="flex items-center gap-3">
                    <button
                      onClick={() => setIsAddPatientModalOpen(true)}
                      className="inline-flex items-center justify-center gap-2 px-4 py-2 bg-slate-900 hover:bg-slate-800 text-white text-sm font-semibold rounded-xl transition-all shadow-sm active:scale-95 cursor-pointer animate-fade-in"
                    >
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
                          d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
                        />
                      </svg>
                      Add Patient
                    </button>
                  </div>
                )}
              </div>
              {user?.hospitalType === 'CLINIC' && modules.includes('HOSPITAL_INVENTORY') && (
                <LowStockBanner />
              )}
              <div className="grid grid-cols-1 md:grid-cols-5 gap-6">
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div className="min-w-0 w-full">
                      <p className="text-gray-600 text-sm font-medium">Current Patient</p>
                      <h3
                        className="text-base font-bold text-gray-900 mt-1 truncate"
                        title={currentPatient}
                      >
                        {currentPatient ?? 'None'}
                      </h3>
                    </div>
                  </div>
                </div>
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div className="min-w-0 w-full">
                      <p className="text-gray-600 text-sm font-medium">Next Patient</p>
                      <h3
                        className="text-base font-bold text-gray-900 mt-1 truncate"
                        title={nextPatient}
                      >
                        {nextPatient ?? 'None'}
                      </h3>
                    </div>
                  </div>
                </div>
                {hasAppointments && (
                  <div className="bg-white rounded-lg border border-gray-200 p-6">
                    <div className="flex justify-between items-center">
                      <div>
                        <p className="text-gray-600 text-sm font-medium">
                          Today&apos;s Appointments
                        </p>
                        <h3 className="text-3xl font-bold text-gray-900 mt-1">{stats.today}</h3>
                      </div>
                    </div>
                  </div>
                )}
                <div className="bg-white rounded-lg border border-gray-200 p-6">
                  <div className="flex justify-between items-center">
                    <div>
                      <p className="text-gray-600 text-sm font-medium">Pending Action</p>
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
              </div>

              {/* Side-by-Side Lists: the left panel follows the module, the Queue is always
                  present. Two columns in BOTH module states, deliberately: collapsing to one
                  stacked a fixed 650px panel above the Queue and pushed the OPD due list and
                  its Consult button below the fold for a walk-in-only tenant. */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mt-8">
                {hasAppointments && (
                  <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                    <div className="p-6 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-gradient-to-r from-gray-50/50 to-white">
                      <div>
                        <h3 className="text-lg font-bold text-gray-955">Appointments</h3>
                        <p className="text-xs text-gray-500 mt-0.5">
                          Manage scheduled clinical slots
                        </p>
                      </div>
                      {user?.receptionMode === 'SOLO' && (
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
                      )}
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
                          onChange={(e) => setSearchInput(e.target.value)}
                          className="w-full pl-9 pr-4 py-1.5 text-xs bg-white border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-gray-900/10 focus:border-gray-900 transition-all"
                        />
                      </div>
                      <div className="flex bg-gray-100 rounded-xl p-1 border border-gray-200">
                        {['today', 'upcoming', 'history'].map((view) => (
                          <button
                            key={view}
                            onClick={() => setViewFilter(view)}
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

                    {/* Scrollable Container */}
                    <div className="flex-1 overflow-auto p-6 space-y-6">
                      <div>
                        <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-3 flex items-center gap-2">
                          <span className="w-1.5 h-3 bg-gray-950 rounded-full"></span>
                          Today&apos;s Appointments
                        </h4>
                        {appointmentsLoading ? (
                          <SkeletonTable rows={4} cols={5} />
                        ) : appointments.length > 0 ? (
                          <DoctorAppointmentsTable
                            appointments={appointments}
                            onStatusUpdate={handleStatusUpdate}
                            onEdit={handleEditClick}
                            onConsult={handleConsultClick}
                            onPrint={handlePrintPrescription}
                            onAuditHistory={(item) => handleAppointmentHistory(item)}
                            onAdmitToIpd={(appt) =>
                              setConsultationModal({ isOpen: true, appointment: appt })
                            }
                            hasIPD={hasIPD}
                            startIndex={0}
                            pagination={null}
                          />
                        ) : (
                          <EmptyState
                            icon={null}
                            title="No Appointments"
                            message="No appointments scheduled for today."
                          />
                        )}
                      </div>

                      <DoctorFollowUpsContent
                        followUps={todaysFollowUps}
                        className="border-t border-gray-100 pt-6"
                      />
                    </div>
                  </div>
                )}

                {!hasAppointments && (
                  <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                    <div className="p-6 border-b border-gray-100 bg-gradient-to-r from-gray-50/50 to-white">
                      <div>
                        <h3 className="text-lg font-bold text-gray-955">Follow-Ups</h3>
                        <p className="text-xs text-gray-500 mt-0.5">
                          Return visits and ongoing clinical work for today
                        </p>
                      </div>
                    </div>
                    <div className="flex-1 overflow-auto p-6 space-y-6">
                      <DoctorFollowUpsContent followUps={todaysFollowUps} />
                    </div>
                  </div>
                )}

                {/* Right Div: Queue */}
                <div className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col h-[650px] overflow-hidden">
                  <div className="p-6 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-gradient-to-r from-gray-50/50 to-white">
                    <div>
                      <h3 className="text-lg font-bold text-gray-955">Queue</h3>
                      <p className="text-xs text-gray-500 mt-0.5">Real-time OPD patient workflow</p>
                    </div>
                    {user?.receptionMode === 'SOLO' && (
                      <button
                        onClick={() => setIsOpdModalOpen(true)}
                        className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 bg-sky-600 hover:bg-sky-700 text-white text-xs font-semibold rounded-xl transition-all shadow-sm active:scale-95 cursor-pointer animate-fade-in"
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
                        OPD Intake
                      </button>
                    )}
                  </div>

                  {/* Queue Content */}
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
                                Token
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Patient Name
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Time
                              </th>
                              <th className="px-4 py-3 text-xs font-bold text-gray-600 uppercase tracking-wider">
                                Action
                              </th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-50">
                            {[...queueEntries]
                              .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
                              .slice(0, 10)
                              .map((q, idx) => (
                                <tr key={q.id} className="hover:bg-gray-50/40 transition-colors">
                                  <td className="px-4 py-3.5 text-sm text-gray-700 font-medium">
                                    {idx + 1}
                                  </td>
                                  <td className="px-4 py-3.5">
                                    <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-100/30">
                                      #{idx + 1}
                                    </span>
                                  </td>
                                  <td className="px-4 py-3.5 text-sm font-semibold text-gray-900">
                                    {q.opd?.patient?.name || q.opd?.patientName || '-'}
                                  </td>
                                  <td className="px-4 py-3.5 text-xs text-gray-400">
                                    {new Date(q.createdAt).toLocaleTimeString([], {
                                      hour: '2-digit',
                                      minute: '2-digit',
                                    })}
                                  </td>
                                  <td className="px-4 py-3.5 text-xs">
                                    {q.opd?.status === 'QUEUED' ? (
                                      <button
                                        onClick={() => handleStartOpdConsultation(q.opd)}
                                        disabled={!!startingConsultationId}
                                        className={`px-3 py-1 font-semibold rounded-lg transition-colors cursor-pointer ${startingConsultationId ? 'bg-gray-300 text-gray-500 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800 text-white'}`}
                                      >
                                        Consult
                                      </button>
                                    ) : (
                                      <span className="text-gray-400 italic">Consulting</span>
                                    )}
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

          {/* Standardized Header - Hide on overview, inventory and hospital-inventory tabs */}
          {activeTab !== 'overview' &&
            activeTab !== 'inventory' &&
            activeTab !== 'hospital-inventory' && (
              <PageHeader
                title={
                  activeTab === 'appointments'
                    ? 'My Appointments'
                    : activeTab === 'opd'
                      ? 'OPD Cases'
                      : activeTab === 'queue'
                        ? 'Patient Queue'
                        : activeTab === 'billing'
                          ? 'Billing Records'
                          : 'My Patients'
                }
                subtitle={`Manage your ${
                  activeTab === 'appointments'
                    ? 'schedule'
                    : activeTab === 'opd'
                      ? 'OPD cases'
                      : activeTab === 'queue'
                        ? 'patient queue'
                        : activeTab === 'billing'
                          ? 'bills'
                          : 'patients'
                } here.`}
                onSearch={(e) => setSearchInput(e.target.value)}
                searchValue={searchInput}
                searchPlaceholder={`Search ${activeTab}...`}
                onAdd={
                  user?.receptionMode === 'SOLO' &&
                  (activeTab === 'patients' ||
                    activeTab === 'opd' ||
                    (activeTab === 'appointments' && hasAppointments))
                    ? () => {
                        if (activeTab === 'patients') setIsAddPatientModalOpen(true);
                        if (activeTab === 'opd') setIsOpdModalOpen(true);
                        if (activeTab === 'appointments') setIsAddModalOpen(true);
                      }
                    : null
                }
                addLabel={
                  activeTab === 'patients'
                    ? 'Register Patient'
                    : activeTab === 'opd'
                      ? 'New OPD Case'
                      : 'Add Appointment'
                }
                filter={
                  activeTab === 'appointments' && hasAppointments ? (
                    <div className="flex items-center gap-4">
                      {/* View Filter Buttons */}
                      <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200">
                        {['today', 'upcoming', 'history'].map((view) => (
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
                    </div>
                  ) : activeTab === 'billing' ? (
                    <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200">
                      {['PENDING', 'PAID', 'PARTIAL'].map((status) => (
                        <button
                          key={status}
                          onClick={() => {
                            setBillingStatus(status);
                            setPage(1);
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
                  ) : activeTab === 'patients' ? (
                    <div className="flex items-center gap-2">
                      <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 h-[38px] items-center">
                        {['All', 'Date'].map((view) => (
                          <button
                            key={view}
                            type="button"
                            onClick={() => {
                              setPatientTabView(view);
                              setPage(1);
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
                            setPage(1);
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
                              setPage(1);
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
                            setPage(1);
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
                  ) : null
                }
              />
            )}

          {loading && (
            <div className="space-y-8 animate-fade-in-up">
              {activeTab === 'overview' ? (
                <>
                  <SkeletonStatsGrid count={5} gridCols="md:grid-cols-5" />
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
                  <DoctorAppointmentsTable
                    appointments={appointments}
                    onStatusUpdate={handleStatusUpdate}
                    onEdit={handleEditClick}
                    onConsult={handleConsultClick}
                    onPrint={handlePrintPrescription}
                    onAuditHistory={(item) => handleAppointmentHistory(item)}
                    onAdmitToIpd={(appt) =>
                      setConsultationModal({ isOpen: true, appointment: appt })
                    }
                    hasIPD={hasIPD}
                    startIndex={(page - 1) * ITEMS_PER_PAGE}
                    pagination={pagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title="No Appointments"
                    message="No appointments found for the selected filter."
                  />
                ))}

              {activeTab === 'follow-ups' && <FollowUpPanel role="DOCTOR" mine />}

              {activeTab === 'opd' &&
                (opds.length > 0 ? (
                  <DoctorOpdTable
                    opds={opds}
                    queueEntries={queueEntries}
                    onPrintOpd={handlePrintOpd}
                    onStartConsultation={handleStartOpdConsultation}
                    onPrintPrescription={handlePrintPrescriptionOpd}
                    onViewPrescription={handleViewPrescriptionOpd}
                    onAdmitIpd={(opd) => {
                      setIpdOpdForAdmit(opd);
                      setIsIpdAdmitOpen(true);
                    }}
                    user={user}
                    hasIPD={hasIPD}
                    startIndex={(page - 1) * ITEMS_PER_PAGE}
                    pagination={pagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title={
                      opdTabView === 'Live' ? 'No Active OPD Patients' : 'No OPD Records Found'
                    }
                    message={
                      opdTabView === 'Live'
                        ? 'No patients are currently in the queue or being consulted.'
                        : `No OPD registrations found on ${opdDateFilter}.`
                    }
                  />
                ))}

              {activeTab === 'ipd' &&
                (opds.length > 0 ? (
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
                          return (
                            <tr key={row.ipdId || row.id || ipdNumber || idx} className="border-t">
                              <td className="px-4 py-3">{(page - 1) * ITEMS_PER_PAGE + idx + 1}</td>
                              <td className="px-4 py-3">{ipdNumber || row.id}</td>
                              <td className="px-4 py-3">{patientName}</td>
                              <td className="px-4 py-3">{doctorName}</td>
                              <td className="px-4 py-3">{wardName}</td>
                              <td className="px-4 py-3">{bedNumber}</td>
                              <td className="px-4 py-3">
                                {admittedAt ? new Date(admittedAt).toLocaleString() : '-'}
                              </td>
                              <td className="px-4 py-3">
                                <div className="flex flex-col gap-1 items-start">
                                  {(row.admissionConfirmed ?? row.ipd?.admissionConfirmed) ? (
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
                                {(() => {
                                  const theId =
                                    row.ipdId || row.id || row.ipd?.id || row.ipd?.ipdId || null;
                                  return (
                                    <button
                                      className={`px-3 py-1 rounded ${theId ? 'bg-blue-500 text-white' : 'bg-gray-200 text-gray-500 cursor-not-allowed'}`}
                                      onClick={() => {
                                        if (theId) navigate(`/ipd/${theId}`);
                                      }}
                                      disabled={!theId}
                                      title={theId ? 'Open IPD case' : 'IPD id not available'}
                                    >
                                      Open Case
                                    </button>
                                  );
                                })()}
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
                    message="You have no active IPD admissions."
                  />
                ))}

              {activeTab === 'queue' &&
                (queueEntries.length > 0 ? (
                  <DoctorQueueTable
                    queueEntries={paginatedQueue}
                    startIndex={(page - 1) * ITEMS_PER_PAGE}
                    pagination={queuePagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title="Queue Empty"
                    message="No queued OPD cases for today."
                  />
                ))}

              {activeTab === 'patients' &&
                (patients.length > 0 ? (
                  <DoctorPatientsTable
                    patients={patients}
                    onViewHistory={handleViewHistory}
                    onStartConsultation={handleStartConsultation}
                    onCompleteConsultation={handleCompleteConsultation}
                    onViewPrescription={handleViewPrescription}
                    onStatusUpdate={handlePatientStatusUpdate}
                    startIndex={(page - 1) * ITEMS_PER_PAGE}
                    pagination={pagination}
                  />
                ) : (
                  <EmptyState
                    icon={null}
                    title={patientTabView === 'Date' ? 'No Registered Patients' : 'No Patients'}
                    message={
                      patientTabView === 'Date'
                        ? `No patients registered on ${patientDateFilter}.`
                        : 'No patients found.'
                    }
                  />
                ))}

              {activeTab === 'billing' &&
                (billing.length === 0 ? (
                  <EmptyState icon={null} title="No Billing Records" message="No bills found." />
                ) : (
                  <BillingTable
                    billing={billing}
                    startIndex={(page - 1) * ITEMS_PER_PAGE}
                    pagination={pagination}
                    onUpdateStatus={handleBillStatus}
                    onPrint={handlePrintReceipt}
                    printingBillId={docPrintingId}
                    onEditItems={handleOpenEditBillItems}
                  />
                ))}

              {activeTab === 'inventory' && <MedicineInventoryTab />}

              {activeTab === 'hospital-inventory' && <HospitalInventoryTab />}

              {activeTab === 'ot' &&
                (otLoading ? (
                  <div className="text-center text-gray-400 py-16">Loading…</div>
                ) : (
                  <OtBoard rows={otRows} mode="doctor" />
                ))}

              {activeTab === 'icu-dashboard' && (
                <IcuDashboard onOpenBedBoard={() => setActiveTab('icu-beds')} />
              )}
              {activeTab === 'icu-beds' && <IcuBedBoard />}
            </div>
          )}
        </main>
      </div>

      {/* Edit Appointment Modal */}
      {editModal.isOpen && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg border border-gray-200 w-full max-w-lg p-6 m-4 max-h-[90vh] overflow-y-auto">
            <h3 className="text-xl font-bold text-gray-900 mb-4">Edit Appointment Details</h3>

            <div className="mb-6">
              <label htmlFor="fld-46" className="block text-sm font-medium text-gray-700 mb-1">
                Notes
              </label>
              <textarea
                id="fld-46"
                value={editModal.notes}
                onChange={(e) => setEditModal({ ...editModal, notes: e.target.value })}
                className="w-full border border-gray-300 rounded-lg px-4 py-2 focus:ring-2 focus:ring-primary-500 h-32"
                placeholder="Enter clinical notes, diagnosis, or prescriptions..."
              />
            </div>

            <div className="flex justify-end gap-3">
              <button
                onClick={() =>
                  setEditModal({ isOpen: false, appointment: null, status: '', notes: '' })
                }
                className="px-4 py-2 bg-gray-100 text-gray-700 rounded-lg hover:bg-gray-200 transition"
              >
                Cancel
              </button>
              <button
                onClick={handleUpdateAppointment}
                className="px-4 py-2 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition"
              >
                Save Changes
              </button>
            </div>
          </div>
        </div>
      )}

      {/* History Modal Removed - Uses HistoryDrawer */}

      <>
        <ConfirmationModal
          isOpen={confirmModal.isOpen}
          title={confirmModal.title}
          message={confirmModal.message}
          onConfirm={confirmModal.onConfirm}
          onCancel={() => setConfirmModal((prev) => ({ ...prev, isOpen: false }))}
          showReasonInput={confirmModal.showReasonInput}
          inputPlaceholder={confirmModal.inputPlaceholder}
        />

        <ConsultationModal
          isOpen={consultationModal.isOpen}
          appointment={consultationModal.appointment}
          patient={consultationModal.patient}
          opd={consultationModal.opd}
          onClose={() =>
            setConsultationModal({ isOpen: false, appointment: null, patient: null, opd: null })
          }
          onSuccess={async (res) => {
            setConsultationModal({ isOpen: false, appointment: null, patient: null, opd: null });
            loadData();

            if (res && typeof res === 'object') {
              const opdId = res.opdId || res.opd?.id;
              const billId = res.billId;

              if (opdId) {
                // One combined PDF, printed as a single job so one print dialog outputs
                // every page (firing a dialog per document dropped pages):
                //   1. case paper (always)
                //   2. bill (always)
                //   3. prescription — only when medicines were prescribed
                //   4. in-clinic medicines slip — only when items were administered
                const printed = await printPdf(`/hospital/opd/${opdId}/documents/pdf`);

                if (!printed) {
                  toastError(
                    'The consultation documents could not be printed. Please try printing them again from the patient record.'
                  );
                } else {
                  success('Consultation completed successfully! Documents sent to print.');
                }
              } else {
                success('Consultation completed successfully!');
              }
            } else {
              success(res || 'Consultation completed successfully!');
            }
          }}
        />

        <PrescriptionViewModal
          isOpen={viewPrescriptionModal.isOpen}
          patient={viewPrescriptionModal.patient}
          onClose={() => setViewPrescriptionModal({ isOpen: false, patient: null })}
        />

        {isAddModalOpen && hasAppointments && (
          <AppointmentModal
            isOpen={isAddModalOpen}
            onClose={() => setIsAddModalOpen(false)}
            onSuccess={loadData}
            doctors={doctorRecord ? [doctorRecord] : [{ id: user?.id, name: user?.name }]}
            patients={patients}
          />
        )}

        {isAddPatientModalOpen && (
          <PatientModal
            isOpen={isAddPatientModalOpen}
            onClose={() => setIsAddPatientModalOpen(false)}
            onSuccess={loadData}
          />
        )}

        {patientDetailsModal.isOpen && (
          <PatientDetailsModal
            patient={patientDetailsModal.patient}
            initialTab={patientDetailsModal.initialTab}
            onClose={() => setPatientDetailsModal({ isOpen: false, patient: null })}
          />
        )}

        {/* Admit to IPD directly from Appointment - opens consultation modal so doctor fills data first */}
        {/* (ipdFromAppointment state opens the existing consultationModal with the appointment) */}

        {isOpdModalOpen && (
          <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-3xl animate-scale-in overflow-hidden max-h-[90vh]">
              <div className="bg-white px-8 py-6 border-b border-gray-200">
                <div className="flex justify-between items-center">
                  <div>
                    <h3 className="text-2xl font-bold text-neutral-800">OPD Queue Intake</h3>
                    <p className="text-sm text-neutral-600 mt-1">
                      Capture vitals and check-in patient to queue
                    </p>
                  </div>
                  <button
                    onClick={() => setIsOpdModalOpen(false)}
                    className="w-10 h-10 rounded-xl bg-white/80 hover:bg-white flex items-center justify-center text-neutral-400 hover:text-neutral-600 cursor-pointer border-0"
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
                  if (!opdForm.patientId) {
                    toastError('Please select a valid patient from the suggestions');
                    return;
                  }
                  // Validate vitals
                  if (opdForm.bp) {
                    const bpVal = opdForm.bp.trim();
                    const bpMatch = bpVal.match(/^(\d{2,3})\s*\/\s*(\d{2,3})$/);
                    if (!bpMatch) {
                      toastError(
                        'Blood pressure must be in format Systolic/Diastolic, e.g., 120/80'
                      );
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
                  const payErr = validateOpdPayment(
                    opdForm.paymentMethod,
                    opdForm.paymentReference
                  );
                  if (payErr) {
                    toastError(payErr);
                    return;
                  }

                  try {
                    const payload = {
                      patientId: opdForm.patientId,
                      doctorId: opdForm.doctorId || doctorRecord?.id || user?.id,
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
                    setIsOpdModalOpen(false);
                    success('OPD Case created successfully — ID: ' + res.caseId);
                    loadData();
                  } catch (err) {
                    console.error('Failed to create OPD', err);
                    toastError('Failed to create OPD');
                  }
                }}
                className="p-6 space-y-4 max-h-[76vh] overflow-auto"
              >
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
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
                        className="w-full border border-gray-300 rounded-xl pl-10 pr-4 py-2 focus:ring-2 focus:ring-primary-500 text-sm focus:border-transparent text-slate-800"
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
                        placeholder="Type patient name to search..."
                        autoComplete="off"
                      />
                    </div>

                    {showPatientDropdown && patientSearchText.trim().length >= 2 && (
                      <div className="absolute left-0 right-0 mt-1 bg-white border border-neutral-200 rounded-xl shadow-xl z-50 max-h-60 overflow-y-auto divide-y divide-neutral-100">
                        {patients.filter((p) =>
                          p.name?.toLowerCase().includes(patientSearchText.toLowerCase())
                        ).length > 0 ? (
                          patients
                            .filter((p) =>
                              p.name?.toLowerCase().includes(patientSearchText.toLowerCase())
                            )
                            .map((p) => (
                              <button
                                type="button"
                                key={p.id}
                                onMouseDown={() => {
                                  setOpdForm((prev) => ({ ...prev, patientId: p.id }));
                                  setPatientSearchText(
                                    `${p.name}${p.phone ? ` (${p.phone})` : ''}`
                                  );
                                  setShowPatientDropdown(false);
                                }}
                                className="w-full px-4 py-3 hover:bg-neutral-50 cursor-pointer transition-colors duration-150 flex flex-col gap-0.5 text-left border-0"
                              >
                                <span className="font-semibold text-neutral-800 text-sm">
                                  {p.name}
                                </span>
                                <span className="text-xs text-neutral-500">
                                  {p.phone ? `📞 ${p.phone}` : ''} | {p.age} Yrs | {p.gender}
                                </span>
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
                  <div>
                    <span className="block text-sm font-semibold text-neutral-700 mb-2">
                      Doctor
                    </span>
                    <div className="w-full px-4 py-2.5 bg-neutral-50 border border-neutral-200 text-neutral-850 rounded-xl text-sm font-semibold flex items-center justify-between">
                      <span>{user?.name || 'Self'}</span>
                      <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                        Assigned
                      </span>
                    </div>
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  {isOn('BP') && (
                    <div>
                      <label
                        htmlFor="fld-45"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        BP
                      </label>
                      <input
                        id="fld-45"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
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
                        htmlFor="fld-44"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        Temperature (°F)
                      </label>
                      <input
                        id="fld-44"
                        type="number"
                        step="0.1"
                        min="0"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
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
                        htmlFor="fld-43"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        Pulse
                      </label>
                      <input
                        id="fld-43"
                        type="number"
                        min="0"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                        value={opdForm.pulse}
                        onChange={(e) => setOpdForm((prev) => ({ ...prev, pulse: e.target.value }))}
                      />
                    </div>
                  )}
                  {isOn('HEIGHT') && (
                    <div>
                      <label
                        htmlFor="fld-42"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        Height (cm)
                      </label>
                      <input
                        id="fld-42"
                        type="number"
                        step="0.1"
                        min="0"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                        value={opdForm.height}
                        onChange={(e) =>
                          setOpdForm((prev) => ({ ...prev, height: e.target.value }))
                        }
                      />
                    </div>
                  )}
                  {isOn('WEIGHT') && (
                    <div>
                      <label
                        htmlFor="fld-41"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        Weight (kg)
                      </label>
                      <input
                        id="fld-41"
                        type="number"
                        step="0.1"
                        min="0"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                        value={opdForm.weight}
                        onChange={(e) =>
                          setOpdForm((prev) => ({ ...prev, weight: e.target.value }))
                        }
                      />
                    </div>
                  )}
                  {isOn('SPO2') && (
                    <div>
                      <label
                        htmlFor="fld-40"
                        className="block text-sm font-semibold text-neutral-700 mb-2"
                      >
                        SpO2 (%)
                      </label>
                      <input
                        id="fld-40"
                        type="number"
                        min="0"
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
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
                        className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
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
                    htmlFor="fld-39"
                    className="block text-sm font-semibold text-neutral-700 mb-2"
                  >
                    Problem / Reason
                  </label>
                  <textarea
                    id="fld-39"
                    rows={3}
                    className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800 resize-none"
                    value={opdForm.problem}
                    onChange={(e) => setOpdForm((prev) => ({ ...prev, problem: e.target.value }))}
                  />
                </div>

                <div className="flex items-center gap-4">
                  <label htmlFor="fld-10021" className="text-sm font-medium">
                    Visit Type:
                  </label>
                  <label className="inline-flex items-center gap-2 cursor-pointer">
                    <input
                      id="fld-10021"
                      type="radio"
                      name="visitType"
                      value="NEW"
                      checked={opdForm.visitType === 'NEW'}
                      onChange={() => setOpdForm((prev) => ({ ...prev, visitType: 'NEW' }))}
                    />{' '}
                    New
                  </label>
                  <label className="inline-flex items-center gap-2 cursor-pointer">
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
                    className="flex-1 py-2.5 rounded-xl border border-gray-300 font-semibold text-gray-700 hover:bg-gray-50 transition"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="flex-1 py-2.5 rounded-xl bg-slate-900 text-white font-semibold hover:bg-slate-850 transition"
                  >
                    Create OPD Case
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

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
              <span
                className="hidden sm:inline-block sm:align-middle sm:h-screen"
                aria-hidden="true"
              >
                &#8203;
              </span>
              <div className="inline-block align-bottom bg-white rounded-2xl text-left max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-lg sm:w-full">
                <div className="bg-white px-8 py-6 border-b border-gray-100">
                  <h3 className="text-xl font-bold text-gray-900">Process Payment</h3>
                  <p className="text-xs text-gray-500 mt-1">
                    Settle invoice for patient {paymentModal.patientName}
                  </p>
                </div>
                <div className="p-6 space-y-4">
                  <div className="flex justify-between items-center bg-gray-50 p-4 rounded-xl">
                    <span className="text-sm font-semibold text-gray-700">Amount Due:</span>
                    <span className="text-xl font-bold text-gray-900">
                      ₹{paymentModal.amount?.toFixed(2)}
                    </span>
                  </div>
                  <div className="grid grid-cols-2 gap-4">
                    <button
                      onClick={() => handleProcessPayment('Cash')}
                      disabled={!!docPaymentProcessing}
                      className={`px-4 py-3 rounded-xl border transition-all font-bold text-sm flex flex-col items-center gap-1.5 ${docPaymentProcessing ? 'opacity-50 cursor-not-allowed border-gray-200' : 'border-gray-300 hover:bg-gray-50 text-gray-800'}`}
                    >
                      {docPaymentProcessing === 'Cash' ? '⏳ Processing...' : '💵 Pay Cash'}
                    </button>
                    <button
                      onClick={() => handleProcessPayment('Online')}
                      disabled={!!docPaymentProcessing}
                      className={`px-4 py-3 rounded-xl text-white font-bold text-sm transition-all flex flex-col items-center gap-1.5 ${docPaymentProcessing ? 'bg-gray-400 cursor-not-allowed' : 'bg-sky-600 hover:bg-sky-700'}`}
                    >
                      {docPaymentProcessing === 'Online'
                        ? '⏳ Processing...'
                        : '📱 Pay Online (UPI)'}
                    </button>
                  </div>
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
              <span
                className="hidden sm:inline-block sm:align-middle sm:h-screen"
                aria-hidden="true"
              >
                &#8203;
              </span>
              <div className="inline-block align-bottom bg-white rounded-2xl text-center max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-md sm:w-full p-8">
                <div className="w-16 h-16 bg-green-100 text-green-600 rounded-full flex items-center justify-center mx-auto mb-4">
                  <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={3}
                      d="M5 13l4 4L19 7"
                    />
                  </svg>
                </div>
                <h3 className="text-xl font-bold text-gray-900 mb-1">Payment Successful!</h3>
                <p className="text-sm text-gray-500 mb-4">
                  Received ₹{paymentSuccessModal.amount?.toFixed(2)} from{' '}
                  {paymentSuccessModal.patientName}
                </p>
                <div className="flex gap-4">
                  <button
                    onClick={() => {
                      setPaymentSuccessModal({
                        isOpen: false,
                        billId: null,
                        patientName: '',
                        amount: 0,
                      });
                    }}
                    className="flex-1 py-2.5 rounded-xl border border-gray-300 font-semibold text-gray-750 hover:bg-gray-50 transition border-0 bg-neutral-100"
                  >
                    Close
                  </button>
                  <button
                    onClick={() => {
                      handlePrintReceipt(paymentSuccessModal.billId);
                      setPaymentSuccessModal({
                        isOpen: false,
                        billId: null,
                        patientName: '',
                        amount: 0,
                      });
                    }}
                    className="flex-1 py-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-white font-semibold transition"
                  >
                    Print Receipt
                  </button>
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
              <span
                className="hidden sm:inline-block sm:align-middle sm:h-screen"
                aria-hidden="true"
              >
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
                    className="px-4 py-2 bg-gray-900 text-white rounded-xl text-sm font-semibold hover:bg-slate-800 transition disabled:opacity-50"
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

        {/* Checkout Print Action Center Modal removed in favor of automatic printing */}
      </>
    </div>
  );
};

export default DoctorDashboard;

const DoctorFollowUpsContent = ({ followUps, className = '' }) => (
  <div className={className}>
    <h4 className="text-xs font-bold text-indigo-500 uppercase tracking-wider mb-3 flex items-center gap-2">
      <span className="w-1.5 h-3 bg-indigo-600 rounded-full"></span>
      Today&apos;s Follow-Ups
    </h4>
    {followUps && followUps.length > 0 ? (
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
                Diagnosis / Reason
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {followUps.map((record) => (
              <tr key={record.id} className="hover:bg-gray-50/40 transition-colors">
                <td className="px-4 py-3.5 text-xs font-bold text-indigo-600">
                  {record.patientCustomId || record.patientPublicId || '-'}
                </td>
                <td className="px-4 py-3.5 text-sm font-semibold text-gray-900">
                  {record.patientName || '-'}
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
      <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl">
        <p className="text-sm text-gray-400 font-medium">No follow-ups scheduled for today.</p>
      </div>
    )}
  </div>
);

// Doctor Appointments Table
const DoctorAppointmentsTable = ({
  appointments,
  onStatusUpdate,
  onEdit,
  onConsult,
  onPrint,
  onAuditHistory,
  onAdmitToIpd,
  hasIPD,
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
    columnHelper.accessor((row) => row.patientName || row.patientId, {
      id: 'patient',
      header: 'PATIENT',
    }),
    columnHelper.accessor('appointmentDate', {
      header: 'DATE',
    }),
    columnHelper.accessor('appointmentTime', {
      header: 'TIME',
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
      header: 'STATUS',
      cell: (info) => {
        const status = info.getValue();
        const isFinal = ['CANCELLED', 'COMPLETED'].includes(status);
        return (
          <StatusBadge
            status={status}
            options={isFinal ? [] : ['SCHEDULED', 'COMPLETED', 'CANCELLED']}
            onUpdate={
              isFinal ? null : (newStatus) => onStatusUpdate(info.row.original.id, newStatus)
            }
            type="dropdown"
          />
        );
      },
    }),
    columnHelper.accessor('notes', {
      header: 'NOTES',
      cell: (info) => (
        <span className="text-gray-600 max-w-xs truncate block">{info.getValue()}</span>
      ),
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">ACTIONS</div>,
      cell: (info) => (
        <div className="text-right">
          <ActionMenu
            actions={[
              {
                label: 'View History',
                icon: (
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="h-4 w-4"
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z"
                      clipRule="evenodd"
                    />
                  </svg>
                ),
                onClick: () => onAuditHistory(info.row.original),
              },
              // Only show status actions if not final
              ...(info.row.original.status === 'SCHEDULED'
                ? [
                    {
                      label: 'Start Consultation',
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M10 2a4 4 0 00-4 4v1H5a1 1 0 00-.994.89l-1 9A1 1 0 004 18h12a1 1 0 00.994-1.11l-1-9A1 1 0 0015 7h-1V6a4 4 0 00-4-4zM8 6a2 2 0 114 0v1H8V6zm.707 7.293a1 1 0 00-1.414 1.414L9.586 17a1 1 0 001.414 0l2.293-2.293a1 1 0 00-1.414-1.414L10 15.172l-1.293-1.879z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      onClick: () => onConsult(info.row.original),
                    },
                    {
                      label: 'Cancel Appointment',
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
                      onClick: () => onStatusUpdate(info.row.original.id, 'CANCELLED'),
                      variant: 'danger',
                    },
                    {
                      label: 'Edit Details',
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
                      onClick: () => onEdit(info.row.original),
                    },
                    ...(hasIPD
                      ? [
                          {
                            label: 'Admit to IPD',
                            icon: (
                              <svg
                                xmlns="http://www.w3.org/2000/svg"
                                className="h-4 w-4"
                                viewBox="0 0 20 20"
                                fill="currentColor"
                              >
                                <path
                                  fillRule="evenodd"
                                  d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z"
                                  clipRule="evenodd"
                                />
                              </svg>
                            ),
                            onClick: () => onAdmitToIpd(info.row.original),
                          },
                        ]
                      : []),
                  ]
                : []),
              ...(info.row.original.status === 'COMPLETED'
                ? [
                    {
                      label: 'Print Prescription',
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M5 4v3H4a2 2 0 00-2 2v3a2 2 0 002 2h1v2a2 2 0 002 2h6a2 2 0 002-2v-2h1a2 2 0 002-2V9a2 2 0 00-2-2h-1V4a2 2 0 00-2-2H7a2 2 0 00-2 2zm8 0H7v3h6V4zm0 8H7v4h6v-4z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      onClick: () => onPrint(info.row.original.id),
                    },
                  ]
                : []),
            ]}
          />
        </div>
      ),
    }),
  ];

  return <DataTable data={appointments} columns={columns} pagination={pagination} />;
};

// Doctor Patients Table (Moved to end to keep context clear)
const DoctorPatientsTable = ({
  patients,
  onViewHistory,
  onStartConsultation,
  onCompleteConsultation,
  onViewPrescription,
  onStatusUpdate,
  startIndex = 0,
  pagination,
}) => {
  // ... existing table code ...
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
      header: 'AGE/GENDER',
      cell: (info) => (
        <span>
          {info.row.original.age} / {info.row.original.gender}
        </span>
      ),
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
        const status = patient.status || 'REGISTERED';

        // Secondary actions for three-dot menu
        const secondaryActions = [
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
            onClick: () => onViewHistory(patient),
          },
        ];

        return (
          <div className="flex items-center justify-end gap-2">
            <ActionMenu actions={secondaryActions} />
          </div>
        );
      },
    }),
  ];

  return <DataTable data={patients} columns={columns} pagination={pagination} />;
};

// Doctor OPD Table
const DoctorOpdTable = ({
  opds,
  queueEntries = [],
  onPrintOpd,
  onStartConsultation,
  onPrintPrescription,
  onViewPrescription,
  onAdmitIpd,
  user,
  hasIPD,
  startIndex = 0,
  pagination,
}) => {
  const columnHelper = createColumnHelper();

  const getQueuePosition = (opdId) => {
    if (!queueEntries || queueEntries.length === 0) return null;
    const sorted = [...queueEntries].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
    const index = sorted.findIndex((q) => q.opd?.id === opdId);
    return index !== -1 ? index + 1 : null;
  };

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.NO.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.accessor('caseId', {
      header: 'CASE ID',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor((row) => row.patient?.name, {
      id: 'patient',
      header: 'PATIENT',
    }),
    columnHelper.accessor((row) => row.doctor?.name || '-', {
      id: 'doctor',
      header: 'DOCTOR',
    }),
    columnHelper.accessor('status', {
      id: 'position',
      header: 'QUEUE POSITION',
      cell: (info) => {
        const opd = info.row.original;
        if (opd.status === 'QUEUED') {
          const pos = getQueuePosition(opd.id);
          return pos ? `Position #${pos}` : 'Queued';
        }
        return '—';
      },
    }),
    columnHelper.accessor('visitType', {
      header: 'VISIT',
    }),
    columnHelper.accessor('createdAt', {
      header: 'CREATED',
      cell: (info) => new Date(info.getValue()).toLocaleString(),
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">ACTIONS</div>,
      cell: (info) => {
        const opd = info.row.original;
        const actions = [];

        // Print Case Paper - always available
        actions.push({
          label: 'Print Case Paper',
          icon: (
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="h-4 w-4"
              viewBox="0 0 20 20"
              fill="currentColor"
            >
              <path
                fillRule="evenodd"
                d="M5 4v3H4a2 2 0 00-2 2v3a2 2 0 002 2h1v2a2 2 0 002 2h6a2 2 0 002-2v-2h1a2 2 0 002-2V9a2 2 0 00-2-2h-1V4a2 2 0 00-2-2H7a2 2 0 00-2 2zm8 0H7v3h6V4zm0 8H7v4h6v-4z"
                clipRule="evenodd"
              />
            </svg>
          ),
          onClick: () => onPrintOpd(opd),
        });

        // Start Consultation - only for QUEUED status
        if (opd.status === 'QUEUED') {
          actions.push({
            label: 'Start Consultation',
            icon: (
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path
                  fillRule="evenodd"
                  d="M10 2a4 4 0 00-4 4v1H5a1 1 0 00-.994.89l-1 9A1 1 0 004 18h12a1 1 0 00.994-1.11l-1-9A1 1 0 0015 7h-1V6a4 4 0 00-4-4zM8 6a2 2 0 114 0v1H8V6zm.707 7.293a1 1 0 00-1.414 1.414L9.586 17a1 1 0 001.414 0l2.293-2.293a1 1 0 00-1.414-1.414L10 15.172l-1.293-1.879z"
                  clipRule="evenodd"
                />
              </svg>
            ),
            onClick: () => onStartConsultation(opd),
            disabled: false,
          });
        }

        // Prescription actions - only for CONSULTED or COMPLETED status
        if (opd.status === 'CONSULTED' || opd.status === 'COMPLETED') {
          actions.push({
            label: 'Print Prescription',
            icon: (
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path
                  fillRule="evenodd"
                  d="M5 4v3H4a2 2 0 00-2 2v3a2 2 0 002 2h1v2a2 2 0 002 2h6a2 2 0 002-2v-2h1a2 2 0 002-2V9a2 2 0 00-2-2h-1V4a2 2 0 00-2-2H7a2 2 0 00-2 2zm8 0H7v3h6V4zm0 8H7v4h6v-4z"
                  clipRule="evenodd"
                />
              </svg>
            ),
            onClick: () => onPrintPrescription(opd),
          });
          actions.push({
            label: 'View Prescription',
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
            onClick: () => onViewPrescription(opd),
          });

          // Solo Doctor Mode + IPD module enabled: can admit patient to IPD
          if (user?.receptionMode === 'SOLO' && hasIPD && onAdmitIpd) {
            actions.push({
              label: 'Admit to IPD',
              icon: (
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-4 w-4"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path
                    fillRule="evenodd"
                    d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z"
                    clipRule="evenodd"
                  />
                </svg>
              ),
              onClick: () => onAdmitIpd(opd),
            });
          }
        }

        return (
          <div className="text-right">
            <ActionMenu actions={actions} />
          </div>
        );
      },
    }),
  ];

  return <DataTable data={opds} columns={columns} pagination={pagination} />;
};

// Doctor Queue Table (Client-side Paginated)
const DoctorQueueTable = ({ queueEntries, startIndex = 0, pagination }) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.NO.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.display({
      id: 'position',
      header: 'QUEUE POSITION',
      cell: (info) => (
        <span className="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-bold bg-indigo-50 text-indigo-700 border border-indigo-100/30">
          Position #{startIndex + info.row.index + 1}
        </span>
      ),
    }),
    columnHelper.accessor((row) => row.opd?.patient?.name || row.opd?.patientName || '-', {
      id: 'patient',
      header: 'PATIENT',
    }),
    columnHelper.accessor((row) => row.opd?.doctor?.name || row.opd?.doctorName || '-', {
      id: 'doctor',
      header: 'DOCTOR',
    }),
    columnHelper.accessor('createdAt', {
      header: 'CREATED AT',
      cell: (info) =>
        new Date(info.getValue()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    }),
  ];

  return <DataTable data={queueEntries} columns={columns} pagination={pagination} />;
};
