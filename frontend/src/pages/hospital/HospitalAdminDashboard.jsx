import { createColumnHelper } from '@tanstack/react-table';
import FollowUpPanel from '../../components/FollowUpPanel';
import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  BarChart,
  Bar,
  Legend,
  PieChart,
  Pie,
  Cell,
} from 'recharts';
import ActionMenu from '../../components/ActionMenu';
import AppointmentModal from '../../components/AppointmentModal';
import ConfirmationModal from '../../components/ConfirmationModal';
import DataTable from '../../components/DataTable';
import DateSelect from '../../components/DateSelect';
import EmptyState from '../../components/EmptyState';
import HistoryDrawer from '../../components/HistoryDrawer';
import HospitalInventoryTab from '../../components/HospitalInventoryTab';
import InClinicPresetsManager from '../../components/InClinicPresetsManager';
import IpdAdmitModal from '../../components/IpdAdmitModal';
import LowStockBanner from '../../components/LowStockBanner';
import MedicineInventoryTab from '../../components/MedicineInventoryTab';
import Navbar from '../../components/Navbar';
import NotePresetsManager from '../../components/NotePresetsManager';
import OpdPaymentFields, {
  isPayFirst,
  validateOpdPayment,
} from '../../components/OpdPaymentFields';
import PageHeader from '../../components/PageHeader';
import PatientDetailsModal from '../../components/PatientDetailsModal';
import PatientModal from '../../components/PatientModal';
import PrescriptionPresetsManager from '../../components/PrescriptionPresetsManager';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import {
  SkeletonDashboard,
  SkeletonFormCard,
  SkeletonSettingsCard,
  SkeletonTable,
  SkeletonStatsGrid,
  SkeletonOverviewDual,
} from '../../components/Skeleton';
import StaffDetailsModal from '../../components/StaffDetailsModal';
import StatusBadge from '../../components/StatusBadge';
import WardModal from '../../components/WardModal';
import { useToast } from '../../context/ToastContext';
import useDebounce from '../../hooks/useDebounce'; // BUG-017: standardised debounce hook
import useEnabledVitals from '../../hooks/useEnabledVitals';
import useWebSocket from '../../hooks/useWebSocket';
import authService from '../../services/authService';
import hospitalService from '../../services/hospitalService';
import branchesApi from '../../services/pharmacy/branchesApi';
import inventoryApi from '../../services/pharmacy/inventoryApi';
import reportsApi from '../../services/pharmacy/reportsApi';
import salesApi from '../../services/pharmacy/salesApi';
import timeSlotService from '../../services/timeSlotService';
import wardService from '../../services/wardService';
import { extractApiError } from '../../utils/apiError';
import { describeShift, isOnShiftNow } from '../../utils/nurseShift';
import { backdropProps } from '../../utils/modalA11y';
import { printHtml } from '../../utils/printHtml';
import { printPdf, printBlob } from '../../utils/printPdf';
import { validateForm } from '../../utils/validation';
import AlertThresholdsCard from './AlertThresholdsCard';
import BillingTable from './BillingTable';
// BUG-028: single source-of-truth for base URL
import FilesAndAccessCard from './FilesAndAccessCard';
import HospitalCalendar from './HospitalCalendar';
import IcuBedBoard from './icu/IcuBedBoard';
import IcuDashboard from './icu/IcuDashboard';
import OtAnalyticsCard from './OtAnalyticsCard';
import OtPermissionsCard from './OtPermissionsCard';
import OtPoliciesCard from './OtPoliciesCard';
import OtRoomsCard from './OtRoomsCard';
import BillingHistoryView from './pharmacy/BillingHistoryView';
import SuppliersView from './pharmacy/SuppliersView';
import { createOptionalModuleFetcher } from '../../utils/optionalModule';
import PrintPaymentSettingsCard from './PrintPaymentSettingsCard';
import ScoreSettingsCard from './ScoreSettingsCard';
import TimeSlotsView from './TimeSlotsView';
import VentilatorSettingsCard from './VentilatorSettingsCard';
import VitalsSettingsCard from './VitalsSettingsCard';
import WardsAndBeds from './WardsAndBeds';
/**
 * HospitalAdminDashboard - Hospital Admin dashboard
 *
 * This page allows Hospital Admin to:
 * - Manage patients
 * - Manage doctors
 * - Manage appointments
 * - Manage billing
 *
 * @author HMS Team
 * @version Phase-1
 */
/**
 * Shown when a list failed to load, in place of the empty state.
 *
 * <p>An empty table and a failed request look identical to the person reading the screen, and the
 * difference matters: one means there are no patients, the other means we do not know. Only text
 * the API deliberately returned is displayed -- never a transport or exception string.
 */
const LoadFailureNotice = ({ message, onRetry }) => (
  <div className="p-8 text-center" role="alert">
    <p className="text-sm font-bold text-gray-900">Couldn't load this list</p>
    <p className="mt-1 text-sm text-gray-600">{message}</p>
    <p className="mt-1 text-xs text-gray-500">This is not the same as having no records.</p>
    <button
      type="button"
      onClick={onRetry}
      className="mt-4 px-4 py-2 text-xs font-black uppercase tracking-widest bg-gray-900 text-white rounded"
    >
      Retry
    </button>
  </div>
);

const HospitalAdminDashboard = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [user, setUser] = useState(authService.getCurrentUser());
  const modules = user?.modules || [];
  const hasOPD = modules.includes('OPD');
  const hasIPD = modules.includes('IPD');
  const hasAppointments = modules.includes('APPOINTMENTS');
  // Tenant-aware label: clinic logins say "Clinic" wherever we'd otherwise say "Hospital".
  const tenantWord = user?.hospitalType === 'CLINIC' ? 'Clinic' : 'Hospital';
  const isPharmacyTenant = user?.hospitalType === 'PHARMACY';
  const pharmacyMode = modules.includes('MULTI_PHARMACY')
    ? 'MULTI'
    : modules.includes('SINGLE_PHARMACIST_ADMIN')
      ? 'SOLO'
      : 'SINGLE'; // default single pharmacy
  const defaultTab = 'overview';
  const activeTab = searchParams.get('tab') || defaultTab;

  // Helper to switch tabs
  const setActiveTab = (tab) => {
    const newParams = { tab };
    setSearchParams(newParams);
    setSearchInput(''); // Clear search input on tab switch
    setPage(0); // Reset page to 0
  };

  const [patients, setPatients] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [receptionists, setReceptionists] = useState([]);
  const [nurses, setNurses] = useState([]);
  const [nurseAssignments, setNurseAssignments] = useState([]);
  const [nurseOptions, setNurseOptions] = useState([]);
  const [assignNurseModal, setAssignNurseModal] = useState({ isOpen: false, row: null });
  const [nurseTasks, setNurseTasks] = useState([]);
  const [createTaskModal, setCreateTaskModal] = useState(false);
  const [pharmacists, setPharmacists] = useState([]);
  const [otIncharges, setOtIncharges] = useState([]);
  const [appointments, setAppointments] = useState([]);
  const [billing, setBilling] = useState([]);
  const [ipds, setIpds] = useState([]);
  const [opds, setOpds] = useState([]);
  const [billingStatus, setBillingStatus] = useState('PENDING');
  const [auditLogs, setAuditLogs] = useState([]);
  const [stats, setStats] = useState({ today: 0, pending: 0, total: 0 });
  const [loading, setLoading] = useState(false);
  // A failed load is not an empty ward. Kept separate from `loading` so the three outcomes --
  // still loading, genuinely empty, failed to load -- stay distinguishable in the render.
  const [loadError, setLoadError] = useState(null);
  const [pharmacyStats, setPharmacyStats] = useState(null);
  const [pharmacyStatsLoading, setPharmacyStatsLoading] = useState(false);
  const [analyticsData, setAnalyticsData] = useState(null);
  const [analyticsTimePeriod, setAnalyticsTimePeriod] = useState('Last 6 Months');

  // Pharmacy Branch filter state (multi pharmacy)
  const [filterBranches, setFilterBranches] = useState([]);
  const [selectedFilterBranchId, setSelectedFilterBranchId] = useState('ALL');
  const [pharmacyRefreshKey, setPharmacyRefreshKey] = useState(0);
  // ICU views own their fetch, so they re-read on the tenant's existing REFRESH_DATA push.
  const [icuRefreshKey, setIcuRefreshKey] = useState(0);

  // Pharmacy Admin Overview Dashboard States
  const [pharmacyRecentBills, setPharmacyRecentBills] = useState([]);
  const [pharmacyLowStock, setPharmacyLowStock] = useState([]);
  const [pharmacyRightTab, setPharmacyRightTab] = useState('low-stock');

  // Dashboard state
  const [dashboardStats, setDashboardStats] = useState({
    totalPatients: 0,
    totalDoctors: 0,
    todaysAppointments: 0,
  });
  const [todaysAppointments, setTodaysAppointments] = useState([]);
  const [isNewPatient, setIsNewPatient] = useState(false);
  const [fees, setFees] = useState({ consultationFee: '', casePaperFee: '' });
  const [origFees, setOrigFees] = useState(null);
  const [feesLoading, setFeesLoading] = useState(false);
  const [feesEditing, setFeesEditing] = useState(false);

  const [customFees, setCustomFees] = useState([]);
  const [customFeeModal, setCustomFeeModal] = useState({
    isOpen: false,
    mode: 'add',
    feeId: null,
    name: '',
    defaultAmount: '',
  });
  const [customFeeSubmitting, setCustomFeeSubmitting] = useState(false);

  const [editBillItemsModal, setEditBillItemsModal] = useState({
    isOpen: false,
    billId: null,
    items: [],
    medicines: [],
    patientName: '',
    billNumber: '',
  });
  const [editBillItemsSubmitting, setEditBillItemsSubmitting] = useState(false);
  const [operationsSettings, setOperationsSettings] = useState({
    receptionMode: 'HAS_RECEPTIONIST',
    billingHandler: 'RECEPTIONIST',
    inClinic: true,
    separateNurseLogin: false,
    otInchargeEnabled: false,
  });
  const [origOperationsSettings, setOrigOperationsSettings] = useState(null);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsEditing, setSettingsEditing] = useState(false);
  // Which Settings box is open: null (show the grid) | 'operations' | 'vitals' | 'ventilator'
  // | 'ot-forms' | 'nursing' | 'permissions' | 'policies'.
  const [settingsView, setSettingsView] = useState(null);
  // Leaving Settings resets to the box grid, so returning never lands mid-section.
  useEffect(() => {
    if (activeTab !== 'settings') setSettingsView(null);
  }, [activeTab]);
  const [auditLogRoleFilter, setAuditLogRoleFilter] = useState('ALL');
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const ITEMS_PER_PAGE = 10;
  const [paginatedData, setPaginatedData] = useState([]);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);

  const { success, error: toastError, info } = useToast();

  // Confirmation Modal State
  const [searchInput, setSearchInput] = useState('');
  const searchTerm = useDebounce(searchInput, 500); // BUG-017: debounce main search input

  // Modal tracking state instead of activeTab
  const [modalType, setModalType] = useState(null);

  // Patients state for Overview tab
  const [patientsSearchInput, setPatientsSearchInput] = useState('');
  const patientsSearchTerm = useDebounce(patientsSearchInput, 500); // BUG-017: debounce overview patients search
  const [patientsPage, setPatientsPage] = useState(0);
  const [patientsTotalPages, setPatientsTotalPages] = useState(1);
  const [patientsTotalElements, setPatientsTotalElements] = useState(0);

  // Appointments search state for Today's Appointments under Overview tab
  const [appointmentsSearchTerm, setAppointmentsSearchTerm] = useState('');

  // History Drawer State
  const [historyDrawer, setHistoryDrawer] = useState({
    isOpen: false,
    entityType: null,
    entityId: null,
    entityName: null,
  });

  // Confirmation Modal State
  const [confirmModal, setConfirmModal] = useState({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  const [showModal, setShowModal] = useState(false);
  const [editData, setEditData] = useState(null);
  const [patientDetailsModal, setPatientDetailsModal] = useState({ isOpen: false, patient: null });
  const [staffDetailsModal, setStaffDetailsModal] = useState({
    isOpen: false,
    staff: null,
    role: null,
  });
  const [resetPasswordModal, setResetPasswordModal] = useState({
    isOpen: false,
    staff: null,
    role: '',
  });
  const [resetPasswordForm, setResetPasswordForm] = useState({
    newPassword: '',
    confirmPassword: '',
    showNew: false,
    showConfirm: false,
    submitting: false,
    error: '',
  });
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  // Manually-expanded sidebar groups (Hospital Admin's grouped sidebar only —
  // see groupedSidebarTabs below). The group containing the active tab is
  // always shown expanded regardless of this set; this only tracks groups
  // the user has opened in addition to that.
  const [expandedSidebarGroups, setExpandedSidebarGroups] = useState(() => new Set());

  // OPD Modal State (Admin)
  const [isAdminOpdModalOpen, setIsAdminOpdModalOpen] = useState(false);
  const [adminOpdForm, setAdminOpdForm] = useState({
    patientId: null,
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
    paymentMethod: 'CASH',
    paymentReference: '',
  });
  const { isOn, customs } = useEnabledVitals();
  const [adminOpdPatientSearch, setAdminOpdPatientSearch] = useState('');
  const [adminOpdShowDropdown, setAdminOpdShowDropdown] = useState(false);

  // Auto-assign the doctor in the Admin OPD form when the hospital has exactly one
  // (mirrors the receptionist OPD flow, which already does this).
  useEffect(() => {
    if (isAdminOpdModalOpen && doctors && doctors.length === 1) {
      setAdminOpdForm((prev) => ({ ...prev, doctorId: doctors[0].id }));
    }
  }, [isAdminOpdModalOpen, doctors]);

  // IPD Admit from OPD (Admin)
  const [isAdminIpdAdmitOpen, setIsAdminIpdAdmitOpen] = useState(false);
  const [adminIpdOpdForAdmit, setAdminIpdOpdForAdmit] = useState(null);
  const [profileOpen, setProfileOpen] = useState(false);

  // Patient tab: Date / All toggle
  const [patientTabView, setPatientTabView] = useState('All');
  const getISTDateString = () => {
    const now = new Date();
    const istOffset = 330; // IST = UTC+5:30 in minutes
    const istDate = new Date(now.getTime() + istOffset * 60000);
    return istDate.toISOString().split('T')[0];
  };
  const [patientDateFilter, setPatientDateFilter] = useState(getISTDateString);
  const [datePatients, setDatePatients] = useState([]);
  const [datePatientsLoading, setDatePatientsLoading] = useState(false);

  // OPD tab: Live / Date toggle
  const [opdTabView, setOpdTabView] = useState('Live');
  const [opdDateFilter, setOpdDateFilter] = useState(getISTDateString);

  // Appointments tab: Today / Upcoming toggle (mirrors Receptionist dashboard)
  const [appointmentsViewFilter, setAppointmentsViewFilter] = useState('today');

  // Help & Support state variables
  const [faqs, setFaqs] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [supportLoading, setSupportLoading] = useState(false);
  const [supportSubmitting, setSupportSubmitting] = useState(false);
  const [expandedFaqId, setExpandedFaqId] = useState(null);
  const [faqSearch, setFaqSearch] = useState('');
  const [ticketForm, setTicketForm] = useState({ subject: '', message: '', priority: 'LOW' });

  const navigate = useNavigate();

  // Reset patientDateFilter at midnight IST
  useEffect(() => {
    const msUntilMidnightIST = () => {
      const now = new Date();
      const istOffset = 330;
      const nowIST = new Date(now.getTime() + istOffset * 60000);
      const nextMidnight = new Date(nowIST);
      nextMidnight.setUTCHours(0, 0, 0, 0);
      nextMidnight.setUTCDate(nextMidnight.getUTCDate() + 1);
      return nextMidnight.getTime() - istOffset * 60000 - now.getTime();
    };
    const timer = setTimeout(() => {
      setPatientDateFilter(getISTDateString());
    }, msUntilMidnightIST());
    return () => clearTimeout(timer);
  }, []);

  // Real-time WebSocket sync
  useWebSocket(user, setUser, (silent) => {
    setIcuRefreshKey((k) => k + 1);
    if (
      activeTab !== 'fees' &&
      activeTab !== 'settings' &&
      activeTab !== 'support' &&
      activeTab !== 'audit-logs'
    ) {
      loadData(page, pageSize, silent);
    }
  });

  // Clear searchInput when tab changes
  useEffect(() => {
    setSearchInput('');
    setPage(0);
  }, [activeTab]);

  // Effect for loading data
  useEffect(() => {
    // Prevent double fetch when searchInput was just cleared but debounced searchTerm hasn't updated yet
    if (searchInput === '' && searchTerm !== '') return;
    loadData(page, pageSize);
  }, [
    activeTab,
    searchTerm,
    page,
    billingStatus,
    auditLogRoleFilter,
    patientTabView,
    patientDateFilter,
    opdTabView,
    opdDateFilter,
    appointmentsViewFilter,
    pharmacyRefreshKey,
  ]);

  // Periodic background polling replaced with WebSocket real-time sync

  // Effect for Patients list loading (Overview Tab specific)
  useEffect(() => {
    if (activeTab !== 'overview') return;
    if (patientsSearchInput === '' && patientsSearchTerm !== '') return;
    loadPatients(patientsSearchTerm, patientsPage);
  }, [activeTab, patientsSearchTerm, patientsPage, pageSize]);

  // Load hospital fees when Fees tab is active
  useEffect(() => {
    const loadFees = async () => {
      if (activeTab !== 'fees') return;
      setFeesLoading(true);
      try {
        const [data, customData] = await Promise.all([
          hospitalService.getHospitalFees(),
          hospitalService.getCustomFees(),
        ]);
        const loaded = {
          consultationFee: data.consultationFee != null ? data.consultationFee : '',
          casePaperFee: data.casePaperFee != null ? data.casePaperFee : '',
        };
        setFees(loaded);
        setOrigFees(loaded);
        setFeesEditing(false);
        setCustomFees(customData || []);
      } catch (err) {
        toastError('Failed to load fees');
      } finally {
        setFeesLoading(false);
      }
    };
    loadFees();
  }, [activeTab]);

  const handleSaveFees = async () => {
    try {
      setFeesLoading(true);
      const payload = {
        consultationFee: fees.consultationFee === '' ? null : parseFloat(fees.consultationFee),
        casePaperFee: fees.casePaperFee === '' ? null : parseFloat(fees.casePaperFee),
      };
      await hospitalService.updateHospitalFees(payload);
      success('Fees updated successfully');
      setOrigFees({ ...fees });
      setFeesEditing(false);
    } catch (err) {
      const msg = extractApiError(err, 'Failed to update fees');
      toastError(msg);
    } finally {
      setFeesLoading(false);
    }
  };

  const handleSaveCustomFee = async (e) => {
    e.preventDefault();
    if (!customFeeModal.name.trim()) {
      toastError('Fee name is required');
      return;
    }
    setCustomFeeSubmitting(true);
    try {
      const payload = {
        name: customFeeModal.name.trim(),
        defaultAmount:
          customFeeModal.defaultAmount === '' ? 0 : parseFloat(customFeeModal.defaultAmount),
      };
      if (customFeeModal.mode === 'add') {
        const newFee = await hospitalService.addCustomFee(payload);
        success('Custom fee added successfully');
        setCustomFees((prev) => [...prev, newFee]);
      } else {
        const updatedFee = await hospitalService.updateCustomFee(customFeeModal.feeId, payload);
        success('Custom fee updated successfully');
        setCustomFees((prev) => prev.map((f) => (f.id === customFeeModal.feeId ? updatedFee : f)));
      }
      setCustomFeeModal({ isOpen: false, mode: 'add', feeId: null, name: '', defaultAmount: '' });
    } catch (err) {
      const msg = extractApiError(err, 'Failed to save custom fee');
      toastError(msg);
    } finally {
      setCustomFeeSubmitting(false);
    }
  };

  const handleDeleteCustomFee = (id) => {
    openConfirmation(
      'Delete Custom Fee',
      'Are you sure you want to delete this custom fee? It will no longer be available for new billing items.',
      async () => {
        try {
          await hospitalService.deleteCustomFee(id);
          success('Custom fee deleted successfully');
          setCustomFees((prev) => prev.filter((f) => f.id !== id));
        } catch (err) {
          toastError('Failed to delete custom fee');
        }
      }
    );
  };

  const handleOpenEditBillItems = async (billObj) => {
    try {
      // Make sure custom fees are loaded
      let currentCustomFees = customFees;
      if (currentCustomFees.length === 0) {
        const customData = await hospitalService.getCustomFees();
        setCustomFees(customData || []);
        currentCustomFees = customData || [];
      }

      // Map the billing items to DTO format
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

  // Load hospital operations settings when Settings or Audit Logs tab is active
  useEffect(() => {
    const loadOperationsSettings = async () => {
      if (activeTab !== 'settings' && activeTab !== 'audit-logs') return;
      const showSpinner = activeTab === 'settings';
      if (showSpinner) setSettingsLoading(true);
      try {
        const data = await hospitalService.getHospitalOperationsSettings();
        const loaded = {
          receptionMode: data.receptionMode || 'HAS_RECEPTIONIST',
          billingHandler: data.billingHandler || 'RECEPTIONIST',
          inClinic: data.inClinic !== false,
          barcodeEnabled: data.barcodeEnabled !== false,
          separateNurseLogin: data.separateNurseLogin === true,
          otInchargeEnabled: data.otInchargeEnabled === true,
        };
        setOperationsSettings(loaded);
        setOrigOperationsSettings(loaded);
        if (activeTab === 'settings') setSettingsEditing(false);
      } catch (err) {
        if (activeTab === 'settings') toastError('Failed to load settings');
      } finally {
        if (showSpinner) setSettingsLoading(false);
      }
    };
    loadOperationsSettings();
  }, [activeTab]);

  // Load Support data (FAQs and Tickets) when support tab is active
  useEffect(() => {
    const loadSupportData = async () => {
      if (activeTab !== 'support') return;
      setSupportLoading(true);
      try {
        const [faqData, ticketData] = await Promise.all([
          hospitalService.getPublicFaqs(),
          hospitalService.getTickets(),
        ]);
        setFaqs(faqData || []);
        setTickets(ticketData || []);
      } catch (err) {
        console.error('Failed to load Help & Support data', err);
        toastError('Failed to load FAQs or support tickets');
      } finally {
        setSupportLoading(false);
      }
    };
    loadSupportData();
  }, [activeTab]);

  // Fetch branches for Multi-Pharmacy admin filter dropdown
  useEffect(() => {
    if (isPharmacyTenant && pharmacyMode === 'MULTI') {
      branchesApi
        .getAll()
        .then((data) => {
          setFilterBranches(Array.isArray(data) ? data : []);
        })
        .catch((err) => {
          console.error('Failed to fetch branches for filter:', err);
        });
    }
  }, [isPharmacyTenant, pharmacyMode]);

  // Load Pharmacy Dashboard Analytics when Pharmacy tab is active
  useEffect(() => {
    const loadPharmacyDashboard = async () => {
      if (activeTab !== 'pharmacy') return;
      setPharmacyStatsLoading(true);
      try {
        const data = await reportsApi.getDashboardData();
        setPharmacyStats(data);
      } catch (err) {
        console.error('Failed to load pharmacy dashboard analytics', err);
        toastError('Failed to load pharmacy dashboard analytics');
      } finally {
        setPharmacyStatsLoading(false);
      }
    };
    loadPharmacyDashboard();
  }, [activeTab, pharmacyRefreshKey]);

  const handleExportLedger = async () => {
    try {
      const blob = await reportsApi.exportLedgerCsv();
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `pharmacy_tax_ledger.csv`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      success('Pharmacy tax ledger CSV exported successfully');
    } catch (err) {
      toastError('Failed to export ledger CSV');
    }
  };

  const handleCreateTicket = async (e) => {
    e.preventDefault();
    if (!ticketForm.subject.trim() || !ticketForm.message.trim()) {
      toastError('Subject and Message are required');
      return;
    }
    setSupportSubmitting(true);
    try {
      const newTicket = await hospitalService.createTicket(ticketForm);
      success('Support ticket submitted successfully');
      setTickets((prev) => [newTicket, ...prev]);
      setTicketForm({ subject: '', message: '', priority: 'LOW' });
    } catch (err) {
      console.error('Failed to submit support ticket', err);
      const msg = extractApiError(err, 'Failed to submit ticket');
      toastError(msg);
    } finally {
      setSupportSubmitting(false);
    }
  };

  const toggleReceptionMode = () => {
    const isCurrentlySolo = operationsSettings.receptionMode === 'SOLO';
    const nextValue = isCurrentlySolo ? 'HAS_RECEPTIONIST' : 'SOLO';
    const title = isCurrentlySolo ? 'Enable Reception Mode' : 'Switch to Self Manage Mode';
    const message = isCurrentlySolo
      ? 'Are you sure you want to enable Reception Mode? Receptionists will be allowed to log in and manage the clinic workflow.'
      : 'Are you sure you want to activate Self Manage mode? Receptionist accounts will be blocked from logging in, and Billing will automatically switch to the Doctor.';

    openConfirmation(
      title,
      message,
      async () => {
        try {
          setSettingsLoading(true);
          const updated = {
            receptionMode: nextValue,
            billingHandler: nextValue === 'SOLO' ? 'DOCTOR' : operationsSettings.billingHandler,
            inClinic: operationsSettings.inClinic,
          };
          await hospitalService.updateHospitalOperationsSettings(updated);
          setOperationsSettings(updated);
          setOrigOperationsSettings(updated);
          success('Operational settings updated successfully.');

          // Refresh local user profile session
          const profile = await authService.getProfile();
          authService.updateCurrentUser(profile);
          setUser(profile);
        } catch (err) {
          const msg = extractApiError(err, 'Failed to update operations mode');
          toastError(msg);
        } finally {
          setSettingsLoading(false);
        }
      },
      false
    );
  };

  const handleBillingHandlerChange = async (e) => {
    const nextValue = e.target.value;
    if (operationsSettings.receptionMode === 'SOLO' && nextValue !== 'DOCTOR') {
      info('Billing must be managed by Doctor in Self Manage Mode.');
      return;
    }

    try {
      setSettingsLoading(true);
      const updated = {
        receptionMode: operationsSettings.receptionMode,
        billingHandler: nextValue,
        inClinic: operationsSettings.inClinic,
      };
      const data = await hospitalService.updateHospitalOperationsSettings(updated);
      const loaded = {
        receptionMode: data.receptionMode || 'HAS_RECEPTIONIST',
        billingHandler: data.billingHandler || 'RECEPTIONIST',
        inClinic: data.inClinic !== false,
      };
      setOperationsSettings(loaded);
      setOrigOperationsSettings(loaded);
      success('Operational settings updated successfully.');

      // Refresh local user profile session
      const profile = await authService.getProfile();
      authService.updateCurrentUser(profile);
      setUser(profile);
    } catch (err) {
      const errData = err.response?.data;
      const msg =
        typeof errData === 'string'
          ? errData
          : errData?.message || errData?.error || errData?.billingHandler
            ? errData.message || errData.error || JSON.stringify(errData)
            : 'Failed to update billing responsibility';
      toastError(msg);
    } finally {
      setSettingsLoading(false);
    }
  };

  const toggleInClinic = () => {
    const isCurrentlyInClinic = operationsSettings.inClinic !== false;
    const nextValue = !isCurrentlyInClinic;
    const title = isCurrentlyInClinic ? 'Disable In-Clinic Mode' : 'Enable In-Clinic Mode';
    const message = isCurrentlyInClinic
      ? 'Are you sure you want to disable In-Clinic medicine flow? The Medicine Inventory management tabs will be hidden from the Receptionist and Doctor dashboards.'
      : 'Are you sure you want to enable In-Clinic medicine flow? This will allow Receptionists and Doctors to manage active stock inventory and administer direct stock charges.';

    openConfirmation(
      title,
      message,
      async () => {
        try {
          setSettingsLoading(true);
          const updated = {
            receptionMode: operationsSettings.receptionMode,
            billingHandler: operationsSettings.billingHandler,
            inClinic: nextValue,
          };
          const data = await hospitalService.updateHospitalOperationsSettings(updated);
          const loaded = {
            receptionMode: data.receptionMode || 'HAS_RECEPTIONIST',
            billingHandler: data.billingHandler || 'RECEPTIONIST',
            inClinic: data.inClinic !== false,
            barcodeEnabled: operationsSettings.barcodeEnabled,
            separateNurseLogin: operationsSettings.separateNurseLogin,
            otInchargeEnabled: operationsSettings.otInchargeEnabled,
          };
          setOperationsSettings(loaded);
          setOrigOperationsSettings(loaded);
          success('Operational settings updated successfully.');

          // Refresh local user profile session
          const profile = await authService.getProfile();
          authService.updateCurrentUser(profile);
          setUser(profile);
        } catch (err) {
          const msg = extractApiError(err, 'Failed to update In-Clinic mode');
          toastError(msg);
        } finally {
          setSettingsLoading(false);
        }
      },
      false
    );
  };

  const toggleBarcode = () => {
    const isCurrentlyEnabled = operationsSettings.barcodeEnabled !== false;
    const nextValue = !isCurrentlyEnabled;
    const title = isCurrentlyEnabled ? 'Disable Barcode Workflow' : 'Enable Barcode Workflow';
    const message = isCurrentlyEnabled
      ? 'Disable barcode scanning and label printing? The barcode mode at the Billing Counter and barcode label printing in Inventory will be hidden.'
      : 'Enable barcode scanning and label printing across the Billing Counter and Inventory?';

    openConfirmation(
      title,
      message,
      async () => {
        try {
          setSettingsLoading(true);
          const data = await hospitalService.updateBarcodeSetting(nextValue);
          setOperationsSettings((prev) => ({
            ...prev,
            barcodeEnabled: data.barcodeEnabled !== false,
          }));
          setOrigOperationsSettings((prev) => ({
            ...prev,
            barcodeEnabled: data.barcodeEnabled !== false,
          }));
          success('Barcode setting updated successfully.');

          // Refresh local user profile so the pharmacist views pick up the change
          const profile = await authService.getProfile();
          authService.updateCurrentUser(profile);
          setUser(profile);
        } catch (err) {
          const msg = extractApiError(err, 'Failed to update barcode setting');
          toastError(msg);
        } finally {
          setSettingsLoading(false);
        }
      },
      false
    );
  };

  const toggleSeparateNurseLogin = () => {
    const isCurrentlyEnabled = operationsSettings.separateNurseLogin === true;
    const nextValue = !isCurrentlyEnabled;
    const title = isCurrentlyEnabled
      ? 'Disable Separate Nurse Login'
      : 'Enable Separate Nurse Login';
    const message = isCurrentlyEnabled
      ? 'Disable the separate Nurse login page? Nurses and Nurse Incharges will log in through the shared hospital login page.'
      : 'Enable a separate login page for Nurses and Nurse Incharges?';

    openConfirmation(
      title,
      message,
      async () => {
        try {
          setSettingsLoading(true);
          const data = await hospitalService.updateSeparateNurseLoginSetting(nextValue);
          setOperationsSettings((prev) => ({
            ...prev,
            separateNurseLogin: data.separateNurseLogin === true,
          }));
          setOrigOperationsSettings((prev) => ({
            ...prev,
            separateNurseLogin: data.separateNurseLogin === true,
          }));
          success('Nurse login setting updated successfully.');
        } catch (err) {
          const msg = extractApiError(err, 'Failed to update nurse login setting');
          toastError(msg);
        } finally {
          setSettingsLoading(false);
        }
      },
      false
    );
  };

  const toggleOtIncharge = () => {
    const isCurrentlyEnabled = operationsSettings.otInchargeEnabled === true;
    const nextValue = !isCurrentlyEnabled;
    const title = isCurrentlyEnabled ? 'Disable OT Incharge' : 'Enable OT Incharge';
    const message = isCurrentlyEnabled
      ? 'Disable the OT Incharge role? This will hide the OT Incharge management tab.'
      : 'Enable the OT Incharge role? This will show the OT Incharge management tab under Staff.';

    openConfirmation(
      title,
      message,
      async () => {
        try {
          setSettingsLoading(true);
          const data = await hospitalService.updateOtInchargeSetting(nextValue);
          setOperationsSettings((prev) => ({
            ...prev,
            otInchargeEnabled: data.otInchargeEnabled === true,
          }));
          setOrigOperationsSettings((prev) => ({
            ...prev,
            otInchargeEnabled: data.otInchargeEnabled === true,
          }));
          success('OT Incharge setting updated successfully.');
        } catch (err) {
          const msg = extractApiError(err, 'Failed to update OT Incharge setting');
          toastError(msg);
        } finally {
          setSettingsLoading(false);
        }
      },
      false
    );
  };

  const loadPatients = async (searchTermVal = patientsSearchTerm, pageNum = patientsPage) => {
    try {
      const patData = await hospitalService.getPatients(searchTermVal, pageNum, pageSize);
      if (patData.content) {
        setPatients(patData.content);
        setPatientsTotalPages(patData.totalPages);
        setPatientsTotalElements(patData.totalElements);
      } else {
        setPatients(patData);
        setPatientsTotalPages(1);
        setPatientsTotalElements(patData.length);
      }
    } catch (err) {
      console.error('Failed to load patients', err);
    }
  };

  const loadDatePatients = async (dateVal = patientDateFilter) => {
    setDatePatientsLoading(true);
    try {
      const data = await hospitalService.getPatientActivityByDate(dateVal);
      setDatePatients(data || []);
    } catch (err) {
      console.error('Failed to load patient activity for date', err);
      toastError('Failed to load patient activities');
    } finally {
      setDatePatientsLoading(false);
    }
  };

  const renderBranchFilterBar = () => {
    if (!isPharmacyTenant || pharmacyMode !== 'MULTI') return null;
    return (
      <div className="flex items-center gap-3 bg-slate-50 border border-slate-200/60 p-4 rounded-2xl mb-6 shadow-sm">
        <div className="flex items-center gap-2">
          <svg
            className="w-4 h-4 text-slate-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
            />
          </svg>
          <span className="text-xs font-bold text-slate-700 uppercase tracking-wide">
            Select Branch:
          </span>
        </div>
        <select
          value={selectedFilterBranchId}
          onChange={(e) => {
            const val = e.target.value;
            setSelectedFilterBranchId(val);
            if (val === 'ALL') {
              sessionStorage.removeItem('selectedBranchId');
            } else {
              sessionStorage.setItem('selectedBranchId', val);
            }
            // Trigger a refetch
            setPharmacyRefreshKey((k) => k + 1);
          }}
          className="px-3.5 py-1.5 border border-slate-300 rounded-xl text-xs font-semibold outline-none focus:border-slate-900 bg-white transition-all cursor-pointer hover:border-slate-400"
        >
          <option value="ALL">All Branches (Aggregated)</option>
          {filterBranches.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
            </option>
          ))}
        </select>
      </div>
    );
  };

  /**
   * Appointment reads that must never take the dashboard down — see utils/optionalModule.
   * Every tab of this page used to blank out for a tenant without the APPOINTMENTS module,
   * because the stats call was awaited bare ahead of each tab's own load.
   */
  const fetchAppointmentData = createOptionalModuleFetcher(hasAppointments);

  const loadData = async (pageNum = page, sizeNum = pageSize, showSpinner = true) => {
    if (showSpinner) setLoading(true);
    setLoadError(null);
    try {
      if (activeTab === 'overview') {
        if (isPharmacyTenant) {
          const [dashboardData, recentBillsData, lowStockData] = await Promise.all([
            reportsApi.getDashboardData(),
            salesApi.getHistory(0, 5),
            inventoryApi.getLowStock(0, 5),
          ]);
          setPharmacyStats(dashboardData);
          setPharmacyRecentBills(recentBillsData.content || recentBillsData || []);
          setPharmacyLowStock(lowStockData.content || lowStockData || []);
        } else {
          // Mandatory data first, on its own. The two appointment calls used to share this
          // Promise.all, so one 403 rejected the global stats, the doctor list and the patient
          // load with it and left Overview completely empty.
          const [globalStatsData, docData] = await Promise.all([
            hospitalService.getGlobalStats(),
            hospitalService.getDoctors('', 0, 100),
          ]);
          const [statsData, todaysAppts] = await Promise.all([
            fetchAppointmentData(() => hospitalService.getAppointmentStats(), {}),
            fetchAppointmentData(() => hospitalService.getTodaysAppointments(), []),
          ]);
          setStats({ ...statsData, ...globalStatsData });
          setTodaysAppointments(Array.isArray(todaysAppts) ? todaysAppts : []);
          if (docData.content) {
            setDoctors(docData.content);
          } else {
            setDoctors(docData);
          }
          await loadPatients(patientsSearchTerm, patientsPage);
        }
      } else if (activeTab === 'dashboard') {
        // Load dashboard data
        const [statsData, todaysAppts] = await Promise.all([
          hospitalService.getGlobalStats(),
          fetchAppointmentData(() => hospitalService.getTodaysAppointments(), []),
        ]);
        setDashboardStats(statsData);
        setTodaysAppointments(Array.isArray(todaysAppts) ? todaysAppts : []);
      } else {
        // Refresh the header stat counters. This runs before EVERY other tab's own load, so it
        // is the single most dangerous call on the page: awaited bare, it used to throw 403 for a
        // tenant without APPOINTMENTS and drop straight into the outer catch, leaving Patients,
        // Doctors, OPD, IPD, Billing, Pharmacy, Nurses, OT, Audit and Settings all empty behind a
        // "Failed to load data" toast. It is optional data and must degrade, not abort.
        if (!isPharmacyTenant) {
          const statsData = await fetchAppointmentData(
            () => hospitalService.getAppointmentStats(),
            null
          );
          if (statsData) {
            setStats(statsData);
          }
        }

        if (activeTab === 'patients') {
          const dateParam = patientTabView === 'Date' ? patientDateFilter : '';
          const data = await hospitalService.getPatients(searchTerm, page, pageSize, dateParam);
          if (data.content) {
            setPatients(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            // Fallback for list
            setPatients(data);
            setTotalPages(1);
            setTotalElements(data.length);
          }
        } else if (activeTab === 'doctors') {
          const data = await hospitalService.getDoctors(searchTerm, page, pageSize);
          if (data.content) {
            setDoctors(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            setDoctors(data);
            setTotalPages(1);
            setTotalElements(data.length);
          }
        } else if (activeTab === 'receptionists') {
          const data = await hospitalService.getReceptionists(searchTerm, page, pageSize);
          if (data.content) {
            setReceptionists(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            setReceptionists(data);
            setTotalPages(1);
            setTotalElements(data.length);
          }
        } else if (activeTab === 'nurses') {
          const data = await hospitalService.getNurses(searchTerm, page, pageSize);
          if (data.content) {
            setNurses(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            setNurses(data);
            setTotalPages(1);
            setTotalElements(data.length);
          }
        } else if (activeTab === 'nurse-assignments') {
          // Overview of active admissions + their nurse, plus the nurse
          // list for the assign dropdown (fetch a large page = "all").
          const [overview, nurseList] = await Promise.all([
            hospitalService.getNurseAssignmentsOverview(),
            hospitalService.getNurses('', 0, 500),
          ]);
          setNurseAssignments(Array.isArray(overview) ? overview : []);
          setNurseOptions(nurseList.content || nurseList || []);
          setTotalPages(1);
          setTotalElements(Array.isArray(overview) ? overview.length : 0);
        } else if (activeTab === 'nurse-tasks') {
          const [tasks, nurseList, overview] = await Promise.all([
            hospitalService.getNurseTasks(),
            hospitalService.getNurses('', 0, 500),
            hospitalService.getNurseAssignmentsOverview(),
          ]);
          setNurseTasks(Array.isArray(tasks) ? tasks : []);
          setNurseOptions(nurseList.content || nurseList || []);
          setNurseAssignments(Array.isArray(overview) ? overview : []);
          setTotalPages(1);
          setTotalElements(Array.isArray(tasks) ? tasks.length : 0);
        } else if (activeTab === 'pharmacists') {
          const data = await hospitalService.getPharmacists(searchTerm, page, pageSize);
          if (data.content) {
            setPharmacists(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            setPharmacists(data); // Fallback if API returns list directly
            setTotalPages(1);
            setTotalElements(data.length);
          }
        } else if (activeTab === 'ot-incharges') {
          const data = await hospitalService.getOtIncharges(searchTerm, page, pageSize);
          if (data.content) {
            setOtIncharges(data.content);
            setTotalPages(data.totalPages);
            setTotalElements(data.totalElements);
          } else {
            setOtIncharges(data || []);
            setTotalPages(1);
            setTotalElements((data || []).length);
          }
        } else if (activeTab === 'appointments' && hasAppointments) {
          // Guarded on the module, not just on the tab being visible: activeTab is read from the
          // query string, so ?tab=appointments reaches this branch even when the sidebar entry
          // has been filtered out.
          // Fetch both appointments and doctors (for name lookup)
          // Note: getAppointments now supports page/size, getDoctors might not return all if paginated
          // For now, we fetch paginated appointments and maybe "all" doctors for lookup if possible or handle missing names
          const [apptData, docData, patData] = await Promise.all([
            hospitalService.getAppointments(searchTerm, page, pageSize, appointmentsViewFilter),
            hospitalService.getDoctors('', 0, 100), // Attempt to get more doctors for lookup
            hospitalService.getPatients('', 0, 1000), // Fetch ALL patients for lookup
          ]);

          if (apptData.content) {
            setAppointments(apptData.content);
            setTotalPages(apptData.totalPages);
            setTotalElements(apptData.totalElements);
          } else {
            setAppointments(apptData);
            setTotalPages(1);
            setTotalElements(apptData.length);
          }

          if (docData.content) {
            setDoctors(docData.content);
          } else {
            setDoctors(docData);
          }

          if (patData.content) {
            setPatients(patData.content);
          } else {
            setPatients(patData);
          }
        } else if (activeTab === 'billing') {
          const data = await hospitalService.getBills(searchTerm, page, pageSize, billingStatus);
          if (data && data.content) {
            setBilling(Array.isArray(data.content) ? data.content : []);
            setTotalPages(data.totalPages || 1);
            setTotalElements(data.totalElements || 0);
          } else if (Array.isArray(data)) {
            setBilling(data);
            setTotalPages(1);
            setTotalElements(data.length);
          } else {
            setBilling([]);
            setTotalPages(1);
            setTotalElements(0);
          }
        } else if (activeTab === 'ipd') {
          try {
            // Use admissions endpoint to show only currently admitted patients (same as receptionist)
            const arr = await hospitalService.getAdmittedIpdAdmissions();
            const filtered = (arr || []).filter((o) => {
              if (!searchTerm) return true;
              const q = searchTerm.toLowerCase();
              const row = o.ipd || o;
              const ipdNumber = (row.ipdNumber || '').toString().toLowerCase();
              const patient = (row.patientName || row.patient?.name || '').toLowerCase();
              const doctor = (row.doctorName || row.doctor?.name || '').toLowerCase();
              return ipdNumber.includes(q) || patient.includes(q) || doctor.includes(q);
            });
            setIpds(filtered);
            setTotalPages(1);
            setTotalElements(filtered.length);
          } catch (err) {
            console.error('Failed to load IPD admissions', err);
            setLoadError(safeLoadMessage(err, "Couldn't load admitted patients."));
          }
        } else if (activeTab === 'opd') {
          try {
            let dateParam = '';
            let statusParam = '';
            if (opdTabView === 'Live') {
              dateParam = getISTDateString();
              statusParam = 'QUEUED';
            } else {
              dateParam = opdDateFilter;
              statusParam = ''; // show all statuses
            }
            const [data, docData, patData] = await Promise.all([
              hospitalService.getOpds(searchTerm, page, pageSize, dateParam, statusParam),
              hospitalService.getDoctors('', 0, 100),
              hospitalService.getPatients('', 0, 1000),
            ]);
            if (data.content) {
              setOpds(data.content);
              setTotalPages(data.totalPages);
              setTotalElements(data.totalElements);
            } else {
              const arr = Array.isArray(data) ? data : [];
              setOpds(arr);
              setTotalPages(1);
              setTotalElements(arr.length);
            }
            // Also set doctors and patients for the modal dropdowns
            setDoctors(docData.content || (Array.isArray(docData) ? docData : []));
            setPatients(patData.content || (Array.isArray(patData) ? patData : []));
          } catch (err) {
            console.error('Failed to load OPD cases', err);
            setLoadError(safeLoadMessage(err, "Couldn't load OPD cases."));
          }
        } else if (activeTab === 'audit-logs') {
          const data = isPharmacyTenant
            ? await hospitalService.getPharmacyAuditLogs(
                searchTerm,
                auditLogRoleFilter === 'ALL' ? null : auditLogRoleFilter
              )
            : await hospitalService.getAuditLogs(searchTerm, auditLogRoleFilter);
          setAuditLogs(data);
          setTotalPages(1); // Audit logs don't have pagination yet
        } else if (activeTab === 'analytics') {
          const data = await hospitalService.getAnalyticsStats();
          setAnalyticsData(data);
        }
      }
    } catch (err) {
      toastError('Failed to load data');
    } finally {
      if (showSpinner) setLoading(false);
    }
  };

  const handleLogout = () => {
    const loginUrl = authService.getLoginUrl();
    authService.logout();
    navigate(loginUrl);
  };

  // Generic confirmation handler
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
      onConfirm: action, // Action now accepts optional reason arg
      showReasonInput,
      inputPlaceholder,
    });
  };

  /**
   * Handlers for Actions
   */

  const handleDeletePatient = (id) => {
    openConfirmation(
      'Delete Patient',
      'Are you sure you want to delete this patient?',
      async (reason) => {
        try {
          await hospitalService.deletePatient(id);
          success('Patient deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete patient');
        }
      },
      false // Patient deletion might not need strict reason logging yet per requirements, but good to have. User said "deleting/editing key entities". let's enable it.
      // Actually, keep it false unless strictly required to avoid friction.
      // User: "Super Admin or Hospital Admin... deleting/editing key entities". Patients are key.
      // But let's stick to Doctor/Receptionist first as they are staff.
    );
  };

  const handleDeleteDoctor = (id) => {
    openConfirmation(
      'Delete Doctor',
      'Are you sure you want to delete this doctor? This action cannot be undone.',
      async (reason) => {
        try {
          await hospitalService.deleteDoctor(id, reason);
          success('Doctor deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete doctor');
        }
      },
      true, // Require reason
      'Why are you deleting this doctor?'
    );
  };

  const handleDeleteReceptionist = (id) => {
    openConfirmation(
      'Delete Receptionist',
      'Are you sure you want to delete this receptionist?',
      async (reason) => {
        try {
          await hospitalService.deleteReceptionist(id, reason);
          success('Receptionist deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete receptionist');
        }
      },
      true, // Require reason
      'Why are you deleting this receptionist?'
    );
  };

  const handleDeletePharmacist = (id) => {
    openConfirmation(
      'Delete Pharmacist',
      'Are you sure you want to delete this pharmacist?',
      async (reason) => {
        try {
          await hospitalService.deletePharmacist(id, reason);
          success('Pharmacist deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete pharmacist');
        }
      },
      true,
      'Reason for deletion?'
    );
  };

  const handleDeleteOtIncharge = (id) => {
    openConfirmation(
      'Delete OT Incharge',
      'Are you sure you want to delete this OT Incharge?',
      async (reason) => {
        try {
          await hospitalService.deleteOtIncharge(id, reason);
          success('OT Incharge deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete OT Incharge');
        }
      },
      true,
      'Reason for deletion?'
    );
  };

  const handleDeleteNurse = (id) => {
    openConfirmation(
      'Delete Nurse',
      'Are you sure you want to delete this nurse?',
      async (reason) => {
        try {
          await hospitalService.deleteNurse(id, reason);
          success('Nurse deleted successfully');
          loadData();
        } catch (err) {
          toastError('Failed to delete nurse');
        }
      },
      true,
      'Why are you deleting this nurse?'
    );
  };

  const handlePromoteNurse = (nurse) => {
    const id = nurse.publicId || nurse.id;
    openConfirmation(
      'Promote to Nurse Incharge',
      `Promote ${nurse.name} to Nurse Incharge? They will be eligible to be assigned as a ward incharge.`,
      async () => {
        try {
          await hospitalService.promoteNurse(id);
          success('Nurse promoted to incharge');
          loadData();
        } catch (err) {
          toastError(err?.response?.data?.error || 'Failed to promote nurse');
        }
      }
    );
  };

  const handleDemoteNurse = (nurse) => {
    const id = nurse.publicId || nurse.id;
    openConfirmation(
      'Demote to Nurse',
      `Demote ${nurse.name} back to a plain nurse? Reassign their ward(s) first if they are currently a ward incharge.`,
      async () => {
        try {
          await hospitalService.demoteNurse(id);
          success('Incharge demoted to nurse');
          loadData();
        } catch (err) {
          toastError(err?.response?.data?.error || 'Failed to demote nurse');
        }
      }
    );
  };

  const handleToggleNurseActive = (nurse) => {
    const id = nurse.publicId || nurse.id;
    const nextActive = nurse.isActive === false;
    openConfirmation(
      nextActive ? 'Activate Nurse' : 'Deactivate Nurse',
      nextActive
        ? `Activate ${nurse.name}? They will be able to log in again.`
        : `Deactivate ${nurse.name}? They will no longer be able to log in.`,
      async () => {
        try {
          await hospitalService.setNurseActive(id, nextActive);
          success(nextActive ? 'Nurse activated' : 'Nurse deactivated');
          loadData();
        } catch (err) {
          toastError(err?.response?.data?.error || 'Failed to update nurse status');
        }
      }
    );
  };

  const handleUnassignNurse = (assignmentPublicId) => {
    openConfirmation(
      'Unassign Nurse',
      "Remove this nurse from the patient? The patient will drop off the nurse's list.",
      async () => {
        try {
          await hospitalService.unassignNurse(assignmentPublicId);
          success('Nurse unassigned');
          loadData();
        } catch (err) {
          toastError('Failed to unassign nurse');
        }
      }
    );
  };

  const handleCreateNurseTask = async (payload) => {
    try {
      await hospitalService.createNurseTask(payload);
      success('Task created successfully');
      setCreateTaskModal(false);
      loadData();
    } catch (err) {
      const msg = err.response?.data?.message || err.response?.data || 'Failed to create task';
      toastError(typeof msg === 'string' ? msg : 'Failed to create task');
    }
  };

  const handleCancelNurseTask = async (publicId) => {
    openConfirmation('Cancel Task', 'Are you sure you want to cancel this task?', async () => {
      try {
        await hospitalService.updateNurseTaskStatus(publicId, { status: 'CANCELLED' });
        success('Task cancelled');
        loadData();
      } catch (err) {
        const msg = err.response?.data?.message || err.response?.data || 'Failed to cancel task';
        toastError(typeof msg === 'string' ? msg : 'Failed to cancel task');
      }
    });
  };

  const handleSubmitAssignNurse = async (nurseUserId, notes) => {
    const row = assignNurseModal.row;
    if (!row || !nurseUserId) return;
    try {
      if (row.assignmentPublicId) {
        await hospitalService.reassignNurse(row.assignmentPublicId, { nurseUserId, notes });
        success('Nurse reassigned');
      } else {
        await hospitalService.assignNurse({
          ipdAdmissionId: row.ipdAdmissionId,
          nurseUserId,
          notes,
        });
        success('Nurse assigned');
      }
      setAssignNurseModal({ isOpen: false, row: null });
      loadData();
    } catch (err) {
      const msg = err.response?.data?.message || err.response?.data || 'Failed to assign nurse';
      toastError(typeof msg === 'string' ? msg : 'Failed to assign nurse');
    }
  };

  const handlePrintOpd = async (opd) => {
    try {
      const blob = await hospitalService.downloadCasePaper(opd.id);
      printBlob(blob); // print on this page (hidden iframe), not a new tab
    } catch (err) {
      console.error('Failed to download case paper', err);
      toastError('Failed to download case paper');
    }
  };

  const handleDownloadActivityReport = async () => {
    try {
      const blob = await hospitalService.downloadPatientActivityPdf(patientDateFilter);
      printBlob(blob);
    } catch (err) {
      console.error('Failed to generate report', err);
      toastError('Failed to generate PDF report');
    }
  };

  // Print server PDFs on the current page (hidden iframe), like the bill/case-paper prints.
  const openPdfInNewTab = (endpointPath) => {
    printPdf(endpointPath);
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
      params.push(`date=${getISTDateString()}`);
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

  const handleDeleteAppointment = (id) => {
    openConfirmation(
      'Delete Appointment',
      'Are you sure you want to delete this appointment?',
      async (reason) => {
        console.log(`Deleting appointment ${id}. Reason: ${reason}`);
        try {
          await hospitalService.deleteAppointment(id);
          success('Appointment deleted successfully');
          loadData(); // Reload all or specific tab?
        } catch (err) {
          toastError('Failed to delete appointment');
        }
      },
      true,
      'Why are you deleting this appointment?'
    );
  };

  // Original status update logic without reason input
  const handleAppointmentStatusUpdate = async (id, newStatus) => {
    try {
      await hospitalService.updateAppointmentStatus(id, newStatus);
      success(`Appointment ${newStatus.toLowerCase()} successfully`);
      if (activeTab === 'appointments' || activeTab === 'overview') loadData();
      else if (activeTab === 'dashboard') loadData();
    } catch (err) {
      toastError(`Failed to update appointment status`);
    }
  };

  // For Cancellation (Status Update to CANCELLED)
  const onAppointmentStatusUpdate = (id, newStatus) => {
    if (newStatus === 'CANCELLED') {
      openConfirmation(
        'Cancel Appointment',
        'Are you sure you want to cancel this appointment?',
        async (reason) => {
          console.log(`Cancelling appointment ${id}. Reason: ${reason}`);
          try {
            // Pass reason to updateAppointment if supported, or just log
            // For now we assume updateAppointmentStatus just takes status
            await hospitalService.updateAppointmentStatus(id, newStatus);
            success('Appointment cancelled successfully');
            // refresh
            if (activeTab === 'appointments' || activeTab === 'overview') loadData();
            else if (activeTab === 'dashboard') loadData();
          } catch (err) {
            toastError('Failed to cancel appointment');
          }
        },
        true,
        'Reason for cancellation?'
      );
    } else {
      // Normal update
      handleAppointmentStatusUpdate(id, newStatus);
    }
  };

  const handleEdit = (item, type = null) => {
    setEditData(item);
    const modalTypeStr = type && typeof type === 'string' ? type : activeTab;
    setModalType(modalTypeStr);
    setShowModal(true);
  };

  const handleViewDetails = (patient) => {
    setPatientDetailsModal({ isOpen: true, patient });
  };

  const handleViewStaffDetails = (staff, role) => {
    setStaffDetailsModal({ isOpen: true, staff, role });
  };

  const handleResetStaffPassword = (staff, role) => {
    setResetPasswordModal({ isOpen: true, staff, role });
    setResetPasswordForm({
      newPassword: '',
      confirmPassword: '',
      showNew: false,
      showConfirm: false,
      submitting: false,
      error: '',
    });
  };

  const handleResetPasswordSubmit = async () => {
    const { newPassword, confirmPassword } = resetPasswordForm;
    if (!newPassword || newPassword.length < 6) {
      setResetPasswordForm((f) => ({ ...f, error: 'Password must be at least 6 characters.' }));
      return;
    }
    if (newPassword !== confirmPassword) {
      setResetPasswordForm((f) => ({ ...f, error: 'Passwords do not match.' }));
      return;
    }
    setResetPasswordForm((f) => ({ ...f, submitting: true, error: '' }));
    try {
      const { staff, role } = resetPasswordModal;
      const id = staff.publicId || staff.id;
      if (role === 'doctor') {
        await hospitalService.resetDoctorPassword(id, newPassword);
      } else if (role === 'receptionist') {
        await hospitalService.resetReceptionistPassword(id, newPassword);
      } else if (role === 'pharmacist') {
        await hospitalService.resetPharmacistPassword(id, newPassword);
      } else if (role === 'nurse') {
        await hospitalService.resetNursePassword(id, newPassword);
      } else if (role === 'ot-incharge') {
        await hospitalService.resetOtInchargePassword(id, newPassword);
      }
      success('Password reset successfully');
      setResetPasswordModal({ isOpen: false, staff: null, role: '' });
    } catch (err) {
      const errorMsg =
        err.response?.data?.message ||
        err.response?.data?.error ||
        err.response?.data ||
        'Failed to reset password';
      setResetPasswordForm((f) => ({
        ...f,
        submitting: false,
        error: typeof errorMsg === 'string' ? errorMsg : 'Failed to reset password',
      }));
    }
  };

  const handleAdd = (type = null) => {
    setEditData(null); // Clear previous edit data
    const modalTypeStr = type && typeof type === 'string' ? type : activeTab;
    setModalType(modalTypeStr);
    setShowModal(true);
  };

  const handleHistory = (type, id, name) => {
    setHistoryDrawer({
      isOpen: true,
      entityType: type,
      entityId: id,
      entityName: name,
    });
  };

  const handleExportCSV = () => {
    if (!analyticsData || !analyticsData.monthlyTrends) return;
    let csvContent = 'data:text/csv;charset=utf-8,';
    csvContent +=
      'Month,OPD Consultations,IPD Admissions,Billing Revenue (INR),Pharmacy Revenue (INR),Total Revenue (INR)\r\n';
    analyticsData.monthlyTrends.forEach((item) => {
      csvContent += `"${item.month}",${item.opdCount},${item.ipdCount},${item.billingRevenue},${item.pharmacyRevenue},${item.totalRevenue}\r\n`;
    });
    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute(
      'download',
      `Hospital_Analytics_Report_${new Date().toISOString().split('T')[0]}.csv`
    );
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleExportPDF = () => {
    if (!analyticsData) return;
    printHtml(`
            <html>
            <head>
                <title>Hospital Analytics Report</title>
                <style>
                    body { font-family: system-ui, -apple-system, sans-serif; padding: 40px; color: #111; }
                    .header { display: flex; align-items: center; justify-content: space-between; border-b: 2px solid #eaeaea; padding-bottom: 20px; margin-bottom: 30px; }
                    .hospital-name { font-size: 24px; font-weight: bold; }
                    .report-title { font-size: 16px; color: #666; text-transform: uppercase; letter-spacing: 1px; font-weight: 600; }
                    .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin-bottom: 40px; }
                    .card { border: 1px solid #eaeaea; padding: 20px; border-radius: 12px; background: #fafafa; }
                    .card-title { font-size: 11px; color: #666; text-transform: uppercase; margin-bottom: 5px; font-weight: 600; }
                    .card-value { font-size: 24px; font-weight: 800; color: #111; }
                    table { width: 100%; border-collapse: collapse; margin-bottom: 40px; }
                    th, td { padding: 12px 15px; text-align: left; border-bottom: 1px solid #eaeaea; }
                    th { background-color: #f9fafb; font-weight: 700; color: #374151; font-size: 13px; text-transform: uppercase; }
                    td { font-size: 14px; color: #4B5563; }
                    .section-title { font-size: 16px; font-weight: bold; margin-bottom: 15px; border-bottom: 1px solid #eee; padding-bottom: 8px; color: #111; text-transform: uppercase; letter-spacing: 0.5px; }
                </style>
            </head>
            <body>
                <div class="header">
                    <div>
                        <div class="hospital-name">${user?.hospitalName || 'Hospital'} Analytics Summary</div>
                        <div style="font-size: 12px; color: #666; margin-top: 4px;">Run Date: ${new Date().toLocaleDateString()}</div>
                    </div>
                    <div class="report-title">Executive Summary</div>
                </div>
                <div class="section-title">Key Performance Indicators</div>
                <div class="grid">
                    <div class="card">
                        <div class="card-title">Total Patients</div>
                        <div class="card-value">${analyticsData.totalPatients}</div>
                    </div>
                    <div class="card">
                        <div class="card-title">OPD Consultations</div>
                        <div class="card-value">${analyticsData.totalOPDConsultations}</div>
                    </div>
                    <div class="card">
                        <div class="card-title">Bed Occupancy Rate</div>
                        <div class="card-value">${analyticsData.bedOccupancyRate}%</div>
                    </div>
                    <div class="card">
                        <div class="card-title">Total Revenue</div>
                        <div class="card-value">₹${analyticsData.totalRevenue?.toLocaleString()}</div>
                    </div>
                </div>
                
                <div class="section-title">Monthly Volume & Revenue Trends</div>
                <table>
                    <thead>
                        <tr>
                            <th>Month</th>
                            <th>OPD Consultations</th>
                            <th>IPD Admissions</th>
                            <th>Billing Revenue</th>
                            <th>Pharmacy Sales</th>
                            <th>Total Revenue</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${analyticsData.monthlyTrends
                          .map(
                            (t) =>
                              '<tr>' +
                              '<td>' +
                              t.month +
                              '</td>' +
                              '<td>' +
                              t.opdCount +
                              '</td>' +
                              '<td>' +
                              t.ipdCount +
                              '</td>' +
                              '<td>₹' +
                              (t.billingRevenue ? t.billingRevenue.toLocaleString() : '0') +
                              '</td>' +
                              '<td>₹' +
                              (t.pharmacyRevenue ? t.pharmacyRevenue.toLocaleString() : '0') +
                              '</td>' +
                              '<td><strong>₹' +
                              (t.totalRevenue ? t.totalRevenue.toLocaleString() : '0') +
                              '</strong></td>' +
                              '</tr>'
                          )
                          .join('')}
                    </tbody>
                </table>

                <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 30px;">
                    <div>
                        <div class="section-title">Doctor Consultation Load</div>
                        <table>
                            <thead>
                                <tr>
                                    <th>Doctor</th>
                                    <th>OPD Volume</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${analyticsData.doctorWorkload
                                  .map(
                                    (d) =>
                                      '<tr>' +
                                      '<td>' +
                                      d.doctorName +
                                      '</td>' +
                                      '<td>' +
                                      d.consultations +
                                      ' cases</td>' +
                                      '</tr>'
                                  )
                                  .join('')}
                            </tbody>
                        </table>
                    </div>
                    <div>
                        <div class="section-title">Ward Occupancy Utilization</div>
                        <table>
                            <thead>
                                <tr>
                                    <th>Ward</th>
                                    <th>Capacity</th>
                                    <th>Occupied</th>
                                    <th>Available</th>
                                </tr>
                            </thead>
                            <tbody>
                                ${analyticsData.wardOccupancy
                                  .map(
                                    (w) =>
                                      '<tr>' +
                                      '<td>' +
                                      w.wardName +
                                      '</td>' +
                                      '<td>' +
                                      w.totalBeds +
                                      '</td>' +
                                      '<td>' +
                                      w.occupiedBeds +
                                      '</td>' +
                                      '<td>' +
                                      w.availableBeds +
                                      '</td>' +
                                      '</tr>'
                                  )
                                  .join('')}
                            </tbody>
                        </table>
                    </div>
                </div>

                <script>
                    window.onload = function() { window.print(); }
                </script>
            </body>
            </html>
        `);
  };

  const allTabs = [
    { id: 'overview', label: 'Overview', icon: null, requiredModule: null },
    // Clinical workflow
    { id: 'patients', label: 'Patients', icon: null, requiredModule: 'OPD' },
    { id: 'appointments', label: 'Appointments', icon: null, requiredModule: 'APPOINTMENTS' },
    { id: 'opd', label: 'OPD', icon: null, requiredModule: 'OPD' },
    { id: 'follow-ups', label: 'Follow-ups', icon: null, requiredModule: 'OPD' },
    { id: 'ipd', label: 'IPD', icon: null, requiredModule: 'IPD' },
    { id: 'wards', label: 'Wards & Beds', icon: null, requiredModule: 'IPD' },
    { id: 'icu-dashboard', label: 'ICU Dashboard', icon: null, requiredModule: 'ICU' },
    { id: 'icu-beds', label: 'ICU Bed Board', icon: null, requiredModule: 'ICU' },
    { id: 'ot', label: 'Operation Theatre', icon: null, requiredModule: 'OT' },
    { id: 'pathology', label: 'Pathology', icon: null, requiredModule: 'PATHOLOGY' },
    // Pharmacy & inventory
    { id: 'pharmacy', label: 'Pharmacy', icon: null, requiredModule: 'PHARMACY' },
    { id: 'pharmacists', label: 'Pharmacists', icon: null, requiredModule: 'PHARMACY' },
    {
      id: 'inventory',
      label: 'Medicine Inventory',
      icon: null,
      requiredModule: 'MEDICAL_INVENTORY',
    },
    {
      id: 'hospital-inventory',
      label: `${tenantWord} Inventory`,
      icon: null,
      requiredModule: 'HOSPITAL_INVENTORY',
    },
    // Financial
    { id: 'billing', label: 'Billing', icon: null, requiredModule: 'BILLING' },
    { id: 'fees', label: 'Fees', icon: null, requiredModule: 'BILLING' },
    // Staff
    { id: 'doctors', label: 'Doctors', icon: null, requiredModule: 'OPD' },
    { id: 'receptionists', label: 'Receptionists', icon: null, requiredModule: 'OPD' },
    { id: 'ot-incharges', label: 'OT Incharge', icon: null, requiredModule: 'OT' },
    { id: 'ot-theatres', label: 'OT Theatres', icon: null, requiredModule: 'OT' },
    { id: 'ot-analytics', label: 'OT Analytics', icon: null, requiredModule: 'OT' },
    { id: 'nurses', label: 'Nurses', icon: null, requiredModule: 'NURSING' },
    { id: 'nurse-assignments', label: 'Nurse Assignments', icon: null, requiredModule: 'NURSING' },
    { id: 'nurse-tasks', label: 'Nurse Tasks', icon: null, requiredModule: 'NURSING' },
    { id: 'time-slots', label: 'Time Slots', icon: null, requiredModule: 'NURSING' },
    { id: 'calendar', label: 'Calendar', icon: null, requiredModule: 'NURSING' },
    // Admin & meta
    { id: 'analytics', label: 'Reports & Analytics', icon: null, requiredModule: 'REPORTS' },
    { id: 'audit-logs', label: 'Audit Logs', icon: null, requiredModule: null },
    { id: 'settings', label: 'Settings', icon: null, requiredModule: null },
    { id: 'support', label: 'Support', icon: null, requiredModule: null },
    { id: 'quick-notes', label: 'Quick Notes', icon: null, requiredModule: null },
    { id: 'symptom-presets', label: 'Symptom Presets', icon: null, requiredModule: null },
    { id: 'diagnosis-presets', label: 'Diagnosis Presets', icon: null, requiredModule: null },
    { id: 'prescription-presets', label: 'Prescription Presets', icon: null, requiredModule: null },
    // In-Clinic presets are bundles of stock medicines administered in the clinic, so they
    // only make sense while the In-Clinic flow is on (gated below, like Medicine Inventory).
    { id: 'in-clinic-presets', label: 'In-Clinic Presets', icon: null, requiredModule: null },
  ];

  // In-Clinic medicine flow gates the Medicine Inventory tab. When an admin
  // turns In-Clinic off, the tab is hidden (updated live via the SETTINGS_UPDATED
  // WebSocket message, which refreshes user.inClinic — see useWebSocket).
  const hasInClinic = user?.inClinic !== false;
  // Standalone pharmacy tenants get a dedicated sidebar driven by the pharmacy plan tier.

  let tabs;
  if (isPharmacyTenant) {
    const pick = (id) => allTabs.find((t) => t.id === id);
    tabs = [
      pick('overview'),
      // SINGLE Pharmacy manages pharmacists; SOLO is a one-person shop (no staff tab);
      // MULTI manages branches (Pharmacies tab).
      ...(pharmacyMode === 'SINGLE'
        ? [{ id: 'pharmacists', label: 'Pharmacists', icon: null, requiredModule: null }]
        : []),
      ...(pharmacyMode === 'MULTI'
        ? [
            { id: 'pharmacies', label: 'Pharmacies', icon: null, requiredModule: null },
            { id: 'suppliers', label: 'Suppliers', icon: null, requiredModule: null },
          ]
        : []),
      { id: 'pharmacy-billing', label: 'Billing', icon: null, requiredModule: null },
      { id: 'pharmacy', label: 'Analytics', icon: null, requiredModule: null },
      pick('audit-logs'),
      pick('settings'),
      pick('support'),
    ].filter(Boolean);
  } else {
    tabs = allTabs.filter((tab) => {
      if (tab.requiredModule && !modules.includes(tab.requiredModule)) return false;
      if (tab.id === 'inventory' && !hasInClinic) return false;
      if (tab.id === 'in-clinic-presets' && !hasInClinic) return false;
      if (tab.id === 'ot-incharges' && !operationsSettings.otInchargeEnabled) return false;
      return true;
    });
  }

  // If the active tab is no longer visible (e.g. In-Clinic just got disabled
  // while viewing Medicine Inventory), fall back to the overview tab.
  useEffect(() => {
    if (!tabs.some((t) => t.id === activeTab)) {
      setActiveTab(defaultTab);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasInClinic, activeTab]);

  // Grouped sidebar — Hospital AND Clinic admins (both tenant types run this dashboard).
  // Pharmacy tenants keep the flat, plan-driven list built above, because their tabs come
  // from the pharmacy tier rather than from these groups.
  // This is purely a presentational regrouping of the same tab ids/labels/routes computed
  // above, so navigation, module gating, and permissions are unaffected.
  // The same groups serve both tenants: a group whose tabs are all absent (a clinic with no
  // IPD/OT/Nursing on its plan) has no subItems and is dropped, so a clinic naturally shows
  // only the groups it actually has — no separate clinic group list to keep in sync.
  // Dashboard/Overview is deliberately NOT one of these groups — it's a
  // single item, so it stays a plain top-level link instead of a
  // one-item dropdown (see groupedSidebarTabs below).
  const SIDEBAR_GROUPS = [
    {
      id: 'group-patient-management',
      label: 'Patient Management',
      tabIds: ['patients', 'appointments', 'opd', 'ipd', 'ot', 'pathology'],
    },
    { id: 'group-rooms', label: 'Rooms', tabIds: ['wards'] },
    {
      id: 'group-critical-care',
      label: 'Critical Care',
      tabIds: ['icu-dashboard', 'icu-beds'],
    },
    {
      id: 'group-staff',
      label: 'Staff',
      tabIds: ['doctors', 'pharmacists', 'receptionists', 'ot-incharges', 'ot-theatres'],
    },
    {
      id: 'group-nursing',
      label: 'Nursing',
      tabIds: ['nurses', 'nurse-assignments', 'nurse-tasks', 'time-slots', 'calendar'],
    },
    { id: 'group-pharmacy', label: 'Pharmacy', tabIds: ['pharmacy'] },
    { id: 'group-inventory', label: 'Inventory', tabIds: ['inventory', 'hospital-inventory'] },
    { id: 'group-finance', label: 'Finance', tabIds: ['billing', 'fees'] },
    { id: 'group-reports', label: 'Reports', tabIds: ['analytics', 'ot-analytics', 'audit-logs'] },
    {
      id: 'group-presets',
      label: 'Presets',
      tabIds: [
        'quick-notes',
        'symptom-presets',
        'diagnosis-presets',
        'prescription-presets',
        'in-clinic-presets',
      ],
    },
    { id: 'group-administration', label: 'Administration', tabIds: ['settings', 'support'] },
  ];

  const usesGroupedSidebar = user?.hospitalType === 'HOSPITAL' || user?.hospitalType === 'CLINIC';

  const groupedSidebarTabs = usesGroupedSidebar
    ? [
        ...tabs.filter((t) => t.id === 'overview'),
        ...SIDEBAR_GROUPS.map((group) => ({
          id: group.id,
          label: group.label,
          subItems: group.tabIds.map((id) => tabs.find((t) => t.id === id)).filter(Boolean),
          // Expansion is driven ONLY by state. It used to also OR in
          // `group.tabIds.includes(activeTab)`, which force-expanded the group owning
          // the open tab — so clicking that group's header could never close it.
          // The active tab's group is instead auto-expanded once, when the tab changes
          // (see the effect below), exactly like the platform sidebar.
          isExpanded: expandedSidebarGroups.has(group.id),
        })).filter((group) => group.subItems.length > 0),
      ]
    : tabs;

  // Keep the group that owns the active tab visible — on first render and whenever the tab
  // changes. Because this only runs on a tab change, a manual collapse of that group sticks.
  useEffect(() => {
    const owning = SIDEBAR_GROUPS.find((g) => g.tabIds.includes(activeTab));
    if (!owning) return;
    setExpandedSidebarGroups((prev) => (prev.has(owning.id) ? prev : new Set(prev).add(owning.id)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab]);

  const handleToggleSidebarGroup = (groupId) => {
    setExpandedSidebarGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupId)) next.delete(groupId);
      else next.add(groupId);
      return next;
    });
  };

  // Pagination Object
  const pagination = {
    pageIndex: page,
    pageSize: pageSize,
    totalItems: totalElements,
    pageCount: totalPages,
    onPageChange: (newPage) => setPage(newPage),
  };

  // Payment Modal States (Admin Dashboard consistent implementation)
  const [paymentModal, setPaymentModal] = useState({
    isOpen: false,
    billId: null,
    amount: null,
    patientName: '',
  });
  const [paymentSuccessModal, setPaymentSuccessModal] = useState({
    isOpen: false,
    billId: null,
    amount: null,
    patientName: '',
  });
  const [paymentProcessing, setPaymentProcessing] = useState(false);

  const [billStatusUpdating, setBillStatusUpdating] = useState(null);
  const handleBillStatus = async (id, status, billObj = null) => {
    if (status === 'PAID') {
      setPaymentModal({
        isOpen: true,
        billId: id,
        amount: billObj?.balance ?? billObj?.amount ?? null,
        patientName: billObj?.patientName || '',
      });
      return;
    }
    if (billStatusUpdating) return;
    setBillStatusUpdating(id);
    try {
      await hospitalService.updateBillStatus(id, status);
      success('Bill status updated');
      loadData();
    } catch (err) {
      toastError('Failed to update bill status');
    } finally {
      setBillStatusUpdating(null);
    }
  };

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

  const [printingReceiptId, setPrintingReceiptId] = useState(null);
  /**
   * Print the receipt on this page (hidden iframe), like the consultation documents and the
   * clinical forms — not in a stray new tab that the user then has to close. The Payment
   * Successful dialog has done its job once print is invoked, so it dismisses itself instead
   * of leaving "Print Receipt" / "Close" sitting behind the print preview.
   */
  const handlePrintReceipt = async (id) => {
    if (printingReceiptId) return;
    setPrintingReceiptId(id);
    setPaymentSuccessModal((prev) => ({ ...prev, isOpen: false }));
    try {
      const printed = await printPdf(`/hospital/billing/${id}/pdf`);
      if (!printed) toastError('Failed to load receipt for printing');
    } finally {
      setPrintingReceiptId(null);
    }
  };

  const patientsPagination = {
    pageIndex: patientsPage,
    pageSize: pageSize,
    totalItems: patientsTotalElements,
    pageCount: patientsTotalPages,
    onPageChange: (newPage) => setPatientsPage(newPage),
  };

  const filteredTodaysAppointments = todaysAppointments.filter((appt) => {
    if (!appointmentsSearchTerm) return true;
    const term = appointmentsSearchTerm.toLowerCase();
    return (
      (appt.patientName && appt.patientName.toLowerCase().includes(term)) ||
      (appt.doctorName && appt.doctorName.toLowerCase().includes(term)) ||
      (appt.customId && appt.customId.toLowerCase().includes(term)) ||
      (appt.id && appt.id.toString().includes(term)) ||
      (appt.notes && appt.notes.toLowerCase().includes(term))
    );
  });

  const appointmentsPagination = {
    pageIndex: 0,
    pageSize: filteredTodaysAppointments.length || 10,
    totalItems: filteredTodaysAppointments.length,
    pageCount: 1,
    onPageChange: () => {},
  };

  return (
    <div className="flex h-screen bg-white">
      {/* Sidebar */}
      <Sidebar
        title="HMS Portal"
        tabs={groupedSidebarTabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        onToggleGroup={usesGroupedSidebar ? handleToggleSidebarGroup : undefined}
        footerTitle={tenantWord}
        footerData={user?.hospitalName}
        variant="plain"
        isCollapsed={sidebarCollapsed}
      />

      {/* Main Content Wrapper */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Navbar */}
        <Navbar
          title={activeTab}
          user={user}
          onLogout={handleLogout}
          onProfile={() => setProfileOpen(true)}
          onSupport={() => setActiveTab('support')}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
        />

        {/* Main Content Area */}
        <main className="flex-1 overflow-x-hidden overflow-y-auto bg-white p-8">
          {/* Low Stock Warning Banner */}
          {modules.includes('HOSPITAL_INVENTORY') && <LowStockBanner />}

          {/* Overview Tab - Stats & Inline Tables Split Grid */}
          {activeTab === 'overview' && !loading && isPharmacyTenant ? (
            <div className="space-y-6 animate-in fade-in duration-300">
              {renderBranchFilterBar()}

              {/* KPI Metrics Cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {/* Total Sales Card */}
                <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm relative overflow-hidden group">
                  <div className="absolute top-0 right-0 w-20 h-20 bg-blue-50/50 rounded-bl-full -z-10"></div>
                  <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                    Gross Sales
                  </p>
                  <h3 className="text-2xl font-black text-gray-900 mt-2">
                    ₹
                    {(pharmacyStats?.kpis?.totalSales || 0).toLocaleString('en-IN', {
                      minimumFractionDigits: 2,
                      maximumFractionDigits: 2,
                    })}
                  </h3>
                  <p className="text-[10px] text-gray-400 mt-1 font-semibold">
                    Total processed invoices
                  </p>
                </div>

                {/* Net Sales Card */}
                <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm relative overflow-hidden group">
                  <div className="absolute top-0 right-0 w-20 h-20 bg-green-50/50 rounded-bl-full -z-10"></div>
                  <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                    Net Revenue
                  </p>
                  <h3 className="text-2xl font-black text-emerald-600 mt-2">
                    ₹
                    {(pharmacyStats?.kpis?.netRevenue || 0).toLocaleString('en-IN', {
                      minimumFractionDigits: 2,
                      maximumFractionDigits: 2,
                    })}
                  </h3>
                  <p className="text-[10px] text-emerald-600/80 mt-1 font-semibold">
                    Net profit after customer returns
                  </p>
                </div>

                {/* Inventory Valuation Card */}
                <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm relative overflow-hidden group">
                  <div className="absolute top-0 right-0 w-20 h-20 bg-indigo-50/50 rounded-bl-full -z-10"></div>
                  <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                    Stock Valuation
                  </p>
                  <h3 className="text-2xl font-black text-indigo-600 mt-2">
                    ₹
                    {(pharmacyStats?.kpis?.inventoryValue || 0).toLocaleString('en-IN', {
                      minimumFractionDigits: 2,
                      maximumFractionDigits: 2,
                    })}
                  </h3>
                  <p className="text-[10px] text-indigo-500/80 mt-1 font-semibold">
                    Cash value of active shelf stock
                  </p>
                </div>

                {/* Profit Margin Card */}
                <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm relative overflow-hidden group">
                  <div className="absolute top-0 right-0 w-20 h-20 bg-amber-50/50 rounded-bl-full -z-10"></div>
                  <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                    Profit Margin
                  </p>
                  <h3 className="text-2xl font-black text-amber-600 mt-2">
                    {pharmacyStats?.kpis?.profitMargin || 0}%
                  </h3>
                  <p className="text-[10px] text-amber-600/80 mt-1 font-semibold">
                    Average mark-up profit margin
                  </p>
                </div>
              </div>

              {/* Split Grid */}
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mt-6">
                {/* Left Panel: Recent Invoices */}
                <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden shadow-sm flex flex-col">
                  <div className="px-6 py-5 border-b border-neutral-100 bg-neutral-50/50 flex flex-row justify-between items-center">
                    <div>
                      <h3 className="text-base font-bold text-slate-800">Recent Sales Invoices</h3>
                      <p className="text-xs text-slate-500 mt-0.5">
                        Summary of the latest customer sales counter bills
                      </p>
                    </div>
                  </div>
                  <div className="p-6 flex-1 overflow-x-auto">
                    {pharmacyRecentBills.length > 0 ? (
                      <table className="w-full text-left text-xs">
                        <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
                          <tr>
                            <th className="px-4 py-3">Bill Number</th>
                            <th className="px-4 py-3">Patient Name</th>
                            <th className="px-4 py-3 text-right">Net Amount</th>
                            <th className="px-4 py-3 text-center">Status</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                          {pharmacyRecentBills.map((bill) => (
                            <tr key={bill.id} className="hover:bg-gray-50/50 transition-colors">
                              <td className="px-4 py-3.5 font-bold text-gray-900">
                                {bill.billNumber}
                              </td>
                              <td className="px-4 py-3.5 text-gray-600 font-medium">
                                {bill.patientName || 'Walk-in'}
                              </td>
                              <td className="px-4 py-3.5 text-right font-bold text-gray-900">
                                ₹{bill.netAmount}
                              </td>
                              <td className="px-4 py-3.5 text-center">
                                <span
                                  className={`px-2 py-0.5 rounded text-[9px] font-black uppercase border ${
                                    bill.paymentStatus === 'PAID'
                                      ? 'bg-green-50 text-green-700 border-green-200'
                                      : 'bg-amber-50 text-amber-700 border-amber-200'
                                  }`}
                                >
                                  {bill.paymentStatus}
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <div className="text-center py-12 text-gray-400 italic">
                        No recent bills found.
                      </div>
                    )}
                  </div>
                </div>

                {/* Right Panel: Tabbed widget for Low Stock & Fast Movers */}
                <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden shadow-sm flex flex-col">
                  <div className="px-6 py-4 border-b border-neutral-100 bg-neutral-50/50 flex flex-row justify-between items-center">
                    <div className="flex bg-gray-100 rounded-lg p-0.5 border border-gray-200 items-center">
                      <button
                        onClick={() => setPharmacyRightTab('low-stock')}
                        className={`px-3 py-1 text-xs font-bold rounded-md transition-all ${
                          pharmacyRightTab === 'low-stock'
                            ? 'bg-white text-gray-900 shadow-sm border border-gray-100'
                            : 'text-gray-500 hover:text-gray-700'
                        }`}
                      >
                        Low Stock Alerts
                      </button>
                      <button
                        onClick={() => setPharmacyRightTab('fast-movers')}
                        className={`px-3 py-1 text-xs font-bold rounded-md transition-all ${
                          pharmacyRightTab === 'fast-movers'
                            ? 'bg-white text-gray-900 shadow-sm border border-gray-100'
                            : 'text-gray-500 hover:text-gray-700'
                        }`}
                      >
                        Best Selling Items
                      </button>
                    </div>
                  </div>

                  <div className="p-6 flex-1 overflow-x-auto">
                    {pharmacyRightTab === 'low-stock' ? (
                      pharmacyLowStock.length > 0 ? (
                        <table className="w-full text-left text-xs">
                          <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
                            <tr>
                              <th className="px-4 py-3">Medicine</th>
                              <th className="px-4 py-3">Batch</th>
                              <th className="px-4 py-3 text-right">Available Qty</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-100">
                            {pharmacyLowStock.map((item, idx) => (
                              <tr
                                key={item.id || idx}
                                className="hover:bg-gray-50/50 transition-colors"
                              >
                                <td className="px-4 py-3.5">
                                  <div className="flex flex-col">
                                    <span className="font-bold text-gray-900">
                                      {item.medicine?.medicineName || 'Unknown Medicine'}
                                    </span>
                                    <span className="text-[10px] text-gray-400 font-bold uppercase">
                                      {item.medicine?.genericName || '-'}
                                    </span>
                                  </div>
                                </td>
                                <td className="px-4 py-3.5 text-gray-600 font-bold">
                                  {item.batchNumber || '-'}
                                </td>
                                <td className="px-4 py-3.5 text-right font-black text-red-650 bg-red-50/30 rounded-lg">
                                  {item.currentQuantity}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      ) : (
                        <div className="text-center py-12 text-green-600 font-bold text-xs flex items-center justify-center gap-1">
                          <svg
                            className="w-4.5 h-4.5"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2.5}
                              d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                            />
                          </svg>
                          All medicine stocks are healthy!
                        </div>
                      )
                    ) : (pharmacyStats?.fastMoving || []).length > 0 ? (
                      <table className="w-full text-left text-xs">
                        <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
                          <tr>
                            <th className="px-4 py-3">Medicine</th>
                            <th className="px-4 py-3 text-right">Sold Qty</th>
                            <th className="px-4 py-3 text-right">Total Revenue</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-100">
                          {(pharmacyStats?.fastMoving || []).map((item, idx) => (
                            <tr key={idx} className="hover:bg-gray-50/50 transition-colors">
                              <td className="px-4 py-3.5 font-bold text-gray-900">{item.name}</td>
                              <td className="px-4 py-3.5 text-right font-bold text-gray-700">
                                {item.quantity}
                              </td>
                              <td className="px-4 py-3.5 text-right font-black text-emerald-600">
                                ₹{item.revenue}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <div className="text-center py-12 text-gray-400 italic">
                        No sales data recorded yet.
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          ) : (
            activeTab === 'overview' &&
            !loading && (
              <div className="space-y-6">
                <h2 className="text-2xl font-bold text-gray-900">Overview</h2>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                  <div className="bg-white rounded-lg border border-gray-200 p-6">
                    <div className="flex justify-between items-center">
                      <div>
                        <p className="text-gray-600 text-sm font-medium">
                          Total Registered Patients
                        </p>
                        <h3 className="text-3xl font-bold text-gray-900 mt-1">
                          {stats.totalRegisteredPatients || stats.totalPatients || 0}
                        </h3>
                      </div>
                    </div>
                  </div>
                  <div className="bg-white rounded-lg border border-gray-200 p-6">
                    <div className="flex justify-between items-center">
                      <div>
                        <p className="text-gray-600 text-sm font-medium">Patients This Month</p>
                        <h3 className="text-3xl font-bold text-gray-900 mt-1">
                          {stats.patientsThisMonth || 0}
                        </h3>
                      </div>
                    </div>
                  </div>
                  <div className="bg-white rounded-lg border border-gray-200 p-6">
                    <div className="flex justify-between items-center">
                      <div>
                        <p className="text-gray-600 text-sm font-medium">Patients Today</p>
                        <h3 className="text-3xl font-bold text-gray-900 mt-1">
                          {stats.patientsToday || 0}
                        </h3>
                      </div>
                    </div>
                  </div>
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 mt-8">
                  {/* Left Div: Patients */}
                  <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden shadow-sm flex flex-col">
                    {/* Head */}
                    <div className="px-6 py-5 border-b border-neutral-100 bg-neutral-50/50 flex flex-row justify-between items-center">
                      <div>
                        <h3 className="text-lg font-bold text-slate-800">Patients</h3>
                        <p className="text-xs text-slate-500 mt-0.5">
                          Manage registered hospital patients
                        </p>
                      </div>
                      {user?.role === 'HOSPITAL_ADMIN' && (
                        <button
                          onClick={() => handleAdd('patients')}
                          className="bg-sky-600 hover:bg-sky-700 text-white px-4 py-2 rounded-xl text-sm font-semibold shadow-sm transform hover:-translate-y-0.5 transition-all flex items-center gap-1.5"
                        >
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
                          <span>Add Patient</span>
                        </button>
                      )}
                    </div>
                    {/* Body */}
                    <div className="p-6 flex-1">
                      {/* Search Input for patients */}
                      <div className="relative mb-4">
                        <input
                          type="text"
                          placeholder="Search patients..."
                          value={patientsSearchInput}
                          onChange={(e) => setPatientsSearchInput(e.target.value)}
                          className="pl-9 pr-4 py-2 border border-neutral-300 rounded-xl text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent w-full transition-all bg-neutral-50 focus:bg-white text-slate-800 placeholder-slate-400"
                        />
                        <span className="absolute left-3 top-2.5 text-slate-400">
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
                              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                            />
                          </svg>
                        </span>
                      </div>
                      {patients.length > 0 ? (
                        <PatientsTable
                          patients={patients}
                          onEdit={(item) => handleEdit(item, 'patients')}
                          onViewDetails={handleViewDetails}
                          onDelete={handleDeletePatient}
                          onHistory={(p) => setPatientDetailsModal({ isOpen: true, patient: p })}
                          startIndex={patientsPage * pageSize}
                          pagination={patientsPagination}
                          isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        />
                      ) : (
                        <EmptyState
                          icon={null}
                          title="No Patients Found"
                          message="There are no patients registered in the system yet."
                          actionLabel="Add Patient"
                          onAction={
                            user?.role === 'HOSPITAL_ADMIN' ? () => handleAdd('patients') : null
                          }
                        />
                      )}
                    </div>
                  </div>

                  {/* Today's Appointments — operational appointment UI, so it renders only for a
                      tenant that holds the APPOINTMENTS module. Historical clinical data is a
                      different thing and is NOT hidden here: past appointments stay readable
                      through patient history, billing provenance and reports. */}
                  {hasAppointments && (
                    <div className="bg-white rounded-2xl border border-neutral-200 overflow-hidden shadow-sm flex flex-col">
                      {/* Head */}
                      <div className="px-6 py-5 border-b border-neutral-100 bg-neutral-50/50 flex flex-row justify-between items-center">
                        <div>
                          <h3 className="text-lg font-bold text-slate-800">
                            Today&apos;s Appointments
                          </h3>
                          <p className="text-xs text-slate-500 mt-0.5">
                            Quick overview of appointments for today
                          </p>
                        </div>
                        {user?.role === 'HOSPITAL_ADMIN' && (
                          <button
                            onClick={() => handleAdd('appointments')}
                            className="bg-sky-600 hover:bg-sky-700 text-white px-4 py-2 rounded-xl text-sm font-semibold shadow-sm transform hover:-translate-y-0.5 transition-all flex items-center gap-1.5"
                          >
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
                            <span>Add Appointment</span>
                          </button>
                        )}
                      </div>
                      {/* Body */}
                      <div className="p-6 flex-1">
                        {/* Search Input for appointments */}
                        <div className="relative mb-4">
                          <input
                            type="text"
                            placeholder="Search today's appointments by patient / doctor name..."
                            value={appointmentsSearchTerm}
                            onChange={(e) => setAppointmentsSearchTerm(e.target.value)}
                            className="pl-9 pr-4 py-2 border border-neutral-300 rounded-xl text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent w-full transition-all bg-neutral-50 focus:bg-white text-slate-800 placeholder-slate-400"
                          />
                          <span className="absolute left-3 top-2.5 text-slate-400">
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
                                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                              />
                            </svg>
                          </span>
                        </div>
                        {filteredTodaysAppointments.length > 0 ? (
                          <AppointmentsTable
                            appointments={filteredTodaysAppointments}
                            doctors={doctors}
                            isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                            onDelete={handleDeleteAppointment}
                            onStatusUpdate={onAppointmentStatusUpdate}
                            onHistory={(item) =>
                              handleHistory('APPOINTMENT', item.publicId || item.id, 'Appointment')
                            }
                            startIndex={0}
                            pagination={appointmentsPagination}
                          />
                        ) : (
                          <EmptyState
                            icon={null}
                            title="No Appointments Found"
                            message="There are no appointments matching your search today."
                            actionLabel="Schedule Appointment"
                            onAction={
                              user?.role === 'HOSPITAL_ADMIN' ? () => handleAdd('appointments') : null
                            }
                          />
                        )}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )
          )}

          {/* Standardized Header */}
          {activeTab !== 'overview' &&
            activeTab !== 'pharmacy' &&
            activeTab !== 'ipd' &&
            activeTab !== 'pathology' &&
            activeTab !== 'support' &&
            activeTab !== 'inventory' &&
            activeTab !== 'hospital-inventory' && (
              <PageHeader
                title={tabs.find((t) => t.id === activeTab)?.label}
                subtitle={
                  activeTab === 'settings'
                    ? 'Configure operational settings and permissions'
                    : activeTab === 'opd'
                      ? 'Active patients currently in queue or being consulted'
                      : `Manage hospital ${activeTab} records`
                }
                onSearch={
                  activeTab === 'fees' || activeTab === 'settings'
                    ? null
                    : (e) => setSearchInput(e.target.value)
                }
                searchValue={activeTab === 'fees' || activeTab === 'settings' ? '' : searchInput}
                searchPlaceholder={
                  activeTab === 'fees' || activeTab === 'settings' ? '' : `Search ${activeTab}...`
                }
                onAdd={
                  activeTab === 'opd'
                    ? () => setIsAdminOpdModalOpen(true)
                    : activeTab === 'nurse-tasks'
                      ? () => setCreateTaskModal(true)
                      : activeTab !== 'billing' &&
                          activeTab !== 'audit-logs' &&
                          activeTab !== 'fees' &&
                          activeTab !== 'settings' &&
                          activeTab !== 'ot' &&
                          activeTab !== 'time-slots' &&
                          activeTab !== 'calendar' &&
                          user?.role === 'HOSPITAL_ADMIN'
                        ? handleAdd
                        : null
                }
                addLabel={
                  activeTab === 'opd'
                    ? 'New OPD'
                    : activeTab === 'nurse-tasks'
                      ? 'New Task'
                      : activeTab === 'fees' || activeTab === 'settings'
                        ? ''
                        : `Add ${activeTab === 'patients' ? 'Patient' : activeTab === 'doctors' ? 'Doctor' : activeTab === 'receptionists' ? 'Receptionist' : activeTab === 'nurses' ? 'Nurse' : activeTab === 'pharmacists' ? 'Pharmacist' : activeTab === 'ot-incharges' ? 'OT Incharge' : activeTab === 'appointments' && hasAppointments ? 'Appointment' : activeTab === 'wards' ? 'Ward' : ''}`
                }
                filter={
                  activeTab === 'patients' ? (
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
                          onChange={(v) => setPatientDateFilter(v)}
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
                        <DateSelect value={opdDateFilter} onChange={(v) => setOpdDateFilter(v)} />
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
                  ) : activeTab === 'appointments' && hasAppointments ? (
                    <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 h-[38px] items-center">
                      {['today', 'upcoming'].map((view) => (
                        <button
                          key={view}
                          type="button"
                          onClick={() => {
                            setAppointmentsViewFilter(view);
                            setPage(0);
                          }}
                          className={`px-4 py-1 text-sm font-medium rounded-md transition-all ${
                            appointmentsViewFilter === view
                              ? 'bg-white text-sky-600 shadow-sm border border-gray-100 font-semibold'
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
                          onClick={() => setBillingStatus(status)}
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
                  ) : activeTab === 'audit-logs' ? (
                    <div className="flex bg-gray-100 rounded-lg p-1 border border-gray-200 gap-1">
                      {(isPharmacyTenant
                        ? [
                            { id: 'ALL', label: 'All Pharmacy Users' },
                            { id: 'HOSPITAL_ADMIN', label: 'Pharmacy Admin' },
                            { id: 'PHARMACIST', label: 'Pharmacists' },
                          ]
                        : [
                            { id: 'ALL', label: 'All' },
                            { id: 'DOCTOR', label: 'Doctor' },
                            { id: 'HOSPITAL_ADMIN', label: 'Admin' },
                            ...(operationsSettings.receptionMode !== 'SOLO'
                              ? [{ id: 'RECEPTIONIST', label: 'Reception' }]
                              : []),
                          ]
                      ).map((item) => (
                        <button
                          key={item.id}
                          onClick={() => setAuditLogRoleFilter(item.id)}
                          className={`px-4 py-1.5 text-sm font-medium rounded-md transition-all ${
                            auditLogRoleFilter === item.id
                              ? 'bg-white text-blue-600 shadow-sm border border-gray-100 font-semibold'
                              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-200'
                          }`}
                        >
                          {item.label}
                        </button>
                      ))}
                    </div>
                  ) : null
                }
              />
            )}

          {/* Error Banner Removed - Using Toasts now */}

          {loading && activeTab !== 'fees' && activeTab !== 'settings' ? (
            activeTab === 'overview' ? (
              <div className="space-y-8 animate-fade-in-up">
                <SkeletonStatsGrid count={3} />
                <SkeletonOverviewDual />
              </div>
            ) : (
              <SkeletonTable rows={6} cols={5} />
            )
          ) : (
            <>
              {/* Overview tab content already rendered above */}

              {(activeTab === 'patients' ||
                activeTab === 'doctors' ||
                activeTab === 'pharmacists' ||
                activeTab === 'receptionists' ||
                activeTab === 'nurses' ||
                activeTab === 'nurse-assignments' ||
                activeTab === 'nurse-tasks' ||
                activeTab === 'wards' ||
                activeTab === 'icu-dashboard' ||
                activeTab === 'icu-beds' ||
                activeTab === 'billing' ||
                activeTab === 'fees' ||
                activeTab === 'opd' ||
                (activeTab === 'appointments' && hasAppointments) ||
                activeTab === 'ot-incharges') && (
                <div className="bg-white rounded-lg border border-gray-200 overflow-hidden mb-4">
                  {activeTab === 'patients' &&
                    (patients.length > 0 ? (
                      <PatientsTable
                        patients={patients}
                        onEdit={(item) => handleEdit(item, 'patients')}
                        onViewDetails={handleViewDetails}
                        onDelete={handleDeletePatient}
                        onHistory={(p) => setPatientDetailsModal({ isOpen: true, patient: p })}
                        startIndex={page * pageSize}
                        pagination={pagination}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
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
                            : 'Add patients to start scheduling appointments.'
                        }
                        actionLabel={
                          patientTabView === 'Date'
                            ? null
                            : user?.role === 'HOSPITAL_ADMIN'
                              ? 'Add Patient'
                              : null
                        }
                        onAction={
                          patientTabView === 'Date'
                            ? null
                            : user?.role === 'HOSPITAL_ADMIN'
                              ? handleAdd
                              : null
                        }
                      />
                    ))}

                  {activeTab === 'doctors' &&
                    (doctors.length > 0 ? (
                      <DoctorsTable
                        doctors={doctors}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onEdit={handleEdit}
                        onDelete={handleDeleteDoctor}
                        onViewDetails={(doc) => handleViewStaffDetails(doc, 'doctor')}
                        onResetPassword={(doc) => handleResetStaffPassword(doc, 'doctor')}
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Doctors Found"
                        message="Add doctors to start scheduling appointments."
                        actionLabel="Add Doctor"
                        onAction={user?.role === 'HOSPITAL_ADMIN' ? handleAdd : null}
                      />
                    ))}

                  {activeTab === 'appointments' &&
                    hasAppointments &&
                    (appointments.length > 0 ? (
                      <AppointmentsTable
                        appointments={appointments}
                        doctors={doctors}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onDelete={handleDeleteAppointment}
                        onStatusUpdate={onAppointmentStatusUpdate}
                        onHistory={(item) =>
                          handleHistory('APPOINTMENT', item.publicId || item.id, 'Appointment')
                        }
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Appointments Found"
                        message="No appointment records match your search."
                        actionLabel="Schedule Appointment"
                        onAction={
                          user?.role === 'HOSPITAL_ADMIN' ? () => handleAdd('appointments') : null
                        }
                      />
                    ))}

                  {activeTab === 'pharmacists' &&
                    (pharmacists.length > 0 ? (
                      <PharmacistsTable
                        pharmacists={pharmacists}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onDelete={handleDeletePharmacist}
                        onEdit={(pharm) => handleEdit(pharm, 'pharmacists')}
                        onViewDetails={(pharm) => handleViewStaffDetails(pharm, 'pharmacist')}
                        onResetPassword={(pharm) => handleResetStaffPassword(pharm, 'pharmacist')}
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Pharmacists Found"
                        message="Add pharmacists to manage inventory and dispensing."
                        actionLabel="Add Pharmacist"
                        onAction={user?.role === 'HOSPITAL_ADMIN' ? handleAdd : null}
                      />
                    ))}
                  {activeTab === 'receptionists' &&
                    (receptionists.length > 0 ? (
                      <ReceptionistsTable
                        receptionists={receptionists}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onDelete={handleDeleteReceptionist}
                        onEdit={(rec) => handleEdit(rec, 'receptionists')}
                        onViewDetails={(rec) => handleViewStaffDetails(rec, 'receptionist')}
                        onResetPassword={(rec) => handleResetStaffPassword(rec, 'receptionist')}
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Receptionists Found"
                        message="Add receptionists to help manage your hospital operations."
                        actionLabel="Add Receptionist"
                        onAction={user?.role === 'HOSPITAL_ADMIN' ? handleAdd : null}
                      />
                    ))}

                  {activeTab === 'ot-incharges' &&
                    (otIncharges.length > 0 ? (
                      <OtInchargesTable
                        otIncharges={otIncharges}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onDelete={handleDeleteOtIncharge}
                        onEdit={(ot) => handleEdit(ot, 'ot-incharges')}
                        onViewDetails={(ot) => handleViewStaffDetails(ot, 'ot-incharge')}
                        onResetPassword={(ot) => handleResetStaffPassword(ot, 'ot-incharge')}
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No OT Incharges Found"
                        message="Add OT Incharges to help manage your operation theatre operations."
                        actionLabel="Add OT Incharge"
                        onAction={user?.role === 'HOSPITAL_ADMIN' ? handleAdd : null}
                      />
                    ))}

                  {activeTab === 'nurses' &&
                    (nurses.length > 0 ? (
                      <NursesTable
                        nurses={nurses}
                        isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                        onDelete={handleDeleteNurse}
                        onEdit={(n) => handleEdit(n, 'nurses')}
                        onViewDetails={(n) => handleViewStaffDetails(n, 'nurse')}
                        onResetPassword={(n) => handleResetStaffPassword(n, 'nurse')}
                        onPromote={handlePromoteNurse}
                        onDemote={handleDemoteNurse}
                        onToggleActive={handleToggleNurseActive}
                        startIndex={page * pageSize}
                        pagination={pagination}
                      />
                    ) : (
                      <EmptyState
                        icon={null}
                        title="No Nurses Found"
                        message="Add nurses to record vitals, medication, and nursing notes for admitted patients."
                        actionLabel="Add Nurse"
                        onAction={user?.role === 'HOSPITAL_ADMIN' ? handleAdd : null}
                      />
                    ))}

                  {activeTab === 'nurse-assignments' && (
                    <NurseAssignmentsTable
                      rows={nurseAssignments}
                      isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                      onAssign={(row) => setAssignNurseModal({ isOpen: true, row })}
                      onUnassign={handleUnassignNurse}
                    />
                  )}

                  {activeTab === 'nurse-tasks' && (
                    <NurseTasksTable
                      rows={nurseTasks}
                      nurseOptions={nurseOptions}
                      onCancel={handleCancelNurseTask}
                      isAdmin={user?.role === 'HOSPITAL_ADMIN'}
                    />
                  )}

                  {activeTab === 'wards' && (
                    <div className="p-6">
                      <WardsAndBeds />
                    </div>
                  )}
                  {activeTab === 'icu-dashboard' && (
                    <div className="p-6">
                      <IcuDashboard
                        refreshKey={icuRefreshKey}
                        onOpenBedBoard={() => setActiveTab('icu-beds')}
                      />
                    </div>
                  )}
                  {activeTab === 'icu-beds' && (
                    <div className="p-6">
                      <IcuBedBoard refreshKey={icuRefreshKey} />
                    </div>
                  )}
                  {activeTab === 'billing' && billing.length === 0 && (
                    <EmptyState
                      icon={null}
                      title="No Billing Records"
                      message="Billing module is active. Records will appear here."
                    />
                  )}
                  {activeTab === 'billing' && billing.length > 0 && (
                    <BillingTable
                      billing={billing}
                      startIndex={page * pageSize}
                      pagination={pagination}
                      onUpdateStatus={handleBillStatus}
                      onPrint={handlePrintReceipt}
                      updatingBillId={billStatusUpdating}
                      printingBillId={printingReceiptId}
                      onEditItems={handleOpenEditBillItems}
                    />
                  )}
                  {activeTab === 'fees' && (
                    <div className="p-6 max-w-6xl mx-auto">
                      <h2 className="text-2xl font-bold mb-6 text-gray-900">
                        {tenantWord} Fees & Charges
                      </h2>
                      {feesLoading ? (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
                          <SkeletonFormCard fields={2} />
                          <SkeletonTable rows={5} cols={3} />
                        </div>
                      ) : (
                        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 items-start">
                          {/* Standard Fees Card */}
                          <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
                            <div>
                              <h3 className="text-lg font-semibold text-gray-900 mb-1">
                                Standard Fees
                              </h3>
                              <p className="text-xs text-gray-500 mb-4">
                                Default fees automatically applied to new patients and case papers.
                              </p>
                            </div>
                            <div className="space-y-4">
                              <div>
                                <label
                                  htmlFor="fld-115"
                                  className="block text-sm font-medium text-gray-700 mb-2"
                                >
                                  Consultation Fee (₹)
                                </label>
                                <input
                                  id="fld-115"
                                  type="number"
                                  step="0.01"
                                  value={fees.consultationFee}
                                  onChange={(e) =>
                                    setFees((prev) => ({
                                      ...prev,
                                      consultationFee: e.target.value,
                                    }))
                                  }
                                  className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${!feesEditing ? 'bg-gray-50 text-gray-600' : ''}`}
                                  placeholder="0.00"
                                  disabled={!feesEditing}
                                />
                              </div>
                              <div>
                                <label
                                  htmlFor="fld-114"
                                  className="block text-sm font-medium text-gray-700 mb-2"
                                >
                                  Case Paper Fee (₹)
                                </label>
                                <input
                                  id="fld-114"
                                  type="number"
                                  step="0.01"
                                  value={fees.casePaperFee}
                                  onChange={(e) =>
                                    setFees((prev) => ({ ...prev, casePaperFee: e.target.value }))
                                  }
                                  className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${!feesEditing ? 'bg-gray-50 text-gray-600' : ''}`}
                                  placeholder="0.00"
                                  disabled={!feesEditing}
                                />
                              </div>
                              <div className="flex gap-3 pt-4">
                                {!feesEditing ? (
                                  user?.role === 'HOSPITAL_ADMIN' ? (
                                    <button
                                      onClick={() => setFeesEditing(true)}
                                      className="flex-1 bg-gray-950 text-white px-4 py-2 rounded-lg font-medium hover:bg-gray-800 transition"
                                    >
                                      Edit Standard Fees
                                    </button>
                                  ) : (
                                    <div className="text-sm text-gray-500 text-center w-full">
                                      Only {tenantWord} Admin can edit standard fees
                                    </div>
                                  )
                                ) : (
                                  <>
                                    <button
                                      onClick={() => {
                                        setFees(
                                          origFees || { consultationFee: '', casePaperFee: '' }
                                        );
                                        setFeesEditing(false);
                                      }}
                                      className="flex-1 bg-gray-200 text-gray-700 px-4 py-2 rounded-lg font-medium hover:bg-gray-300 transition"
                                    >
                                      Cancel
                                    </button>
                                    <button
                                      onClick={handleSaveFees}
                                      disabled={feesLoading}
                                      className="flex-1 bg-gray-950 text-white px-4 py-2 rounded-lg font-medium hover:bg-gray-800 transition"
                                    >
                                      Save Changes
                                    </button>
                                  </>
                                )}
                              </div>
                            </div>
                          </div>

                          {/* Custom Fees Card */}
                          <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
                            <div className="flex justify-between items-center">
                              <div>
                                <h3 className="text-lg font-semibold text-gray-900 mb-1">
                                  Custom Charges
                                </h3>
                                <p className="text-xs text-gray-500">
                                  Custom rates available for selection when editing hospital billing
                                  items.
                                </p>
                              </div>
                              {user?.role === 'HOSPITAL_ADMIN' && (
                                <button
                                  onClick={() =>
                                    setCustomFeeModal({
                                      isOpen: true,
                                      mode: 'add',
                                      feeId: null,
                                      name: '',
                                      defaultAmount: '',
                                    })
                                  }
                                  className="bg-gray-950 text-white text-xs font-semibold px-3 py-2 rounded-lg hover:bg-gray-800 transition"
                                >
                                  + Add Fee
                                </button>
                              )}
                            </div>

                            {customFees.length === 0 ? (
                              <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
                                <p className="text-sm text-gray-500">No custom fees added yet.</p>
                              </div>
                            ) : (
                              <div className="overflow-x-auto">
                                <table className="min-w-full divide-y divide-gray-200">
                                  <thead>
                                    <tr>
                                      <th className="px-4 py-2 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                                        Fee Name
                                      </th>
                                      <th className="px-4 py-2 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                                        Default Rate
                                      </th>
                                      {user?.role === 'HOSPITAL_ADMIN' && (
                                        <th className="px-4 py-2 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                                          Actions
                                        </th>
                                      )}
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-gray-200">
                                    {customFees.map((fee) => (
                                      <tr key={fee.id}>
                                        <td className="px-4 py-3 text-sm font-medium text-gray-950">
                                          {fee.name}
                                        </td>
                                        <td className="px-4 py-3 text-sm text-gray-600">
                                          ₹
                                          {fee.defaultAmount != null
                                            ? fee.defaultAmount.toFixed(2)
                                            : '0.00'}
                                        </td>
                                        {user?.role === 'HOSPITAL_ADMIN' && (
                                          <td className="px-4 py-3 text-right text-sm space-x-2">
                                            <button
                                              onClick={() =>
                                                setCustomFeeModal({
                                                  isOpen: true,
                                                  mode: 'edit',
                                                  feeId: fee.id,
                                                  name: fee.name,
                                                  defaultAmount: fee.defaultAmount || '',
                                                })
                                              }
                                              className="text-indigo-600 hover:text-indigo-900 font-medium"
                                            >
                                              Edit
                                            </button>
                                            <button
                                              onClick={() => handleDeleteCustomFee(fee.id)}
                                              className="text-red-600 hover:text-red-900 font-medium"
                                            >
                                              Delete
                                            </button>
                                          </td>
                                        )}
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              </div>
                            )}
                          </div>
                        </div>
                      )}
                    </div>
                  )}

                  {activeTab === 'follow-ups' && <FollowUpPanel role="HOSPITAL_ADMIN" />}

                  {activeTab === 'opd' &&
                    (loadError ? (
                      <LoadFailureNotice message={loadError} onRetry={() => loadData()} />
                    ) : opds.length > 0 ? (
                      <AdminOpdTable
                        opds={opds}
                        onPrintOpd={handlePrintOpd}
                        onAdmitToIpd={
                          hasIPD
                            ? (o) => {
                                setAdminIpdOpdForAdmit(o);
                                setIsAdminIpdAdmitOpen(true);
                              }
                            : null
                        }
                        startIndex={page * pageSize}
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
                </div>
              )}

              {activeTab === 'inventory' && <MedicineInventoryTab />}

              {activeTab === 'hospital-inventory' && <HospitalInventoryTab />}

              {activeTab === 'quick-notes' && (
                <div className="max-w-2xl mx-auto my-4">
                  <NotePresetsManager fieldType="TREATMENT_NOTES" isAdmin />
                </div>
              )}

              {activeTab === 'ot-theatres' && (
                <div className="max-w-3xl mx-auto my-4">
                  <OtRoomsCard />
                </div>
              )}

              {activeTab === 'ot-analytics' && (
                <div className="max-w-4xl mx-auto my-4">
                  <OtAnalyticsCard />
                </div>
              )}

              {activeTab === 'symptom-presets' && (
                <div className="max-w-2xl mx-auto my-4">
                  <NotePresetsManager
                    fieldType="SYMPTOMS"
                    isAdmin
                    title="Symptom Presets"
                    noun="symptom preset"
                    placeholder="e.g. Fever x 3 days"
                    description="Common complaints that appear as one-click buttons under Symptoms during a consultation."
                  />
                </div>
              )}

              {activeTab === 'diagnosis-presets' && (
                <div className="max-w-2xl mx-auto my-4">
                  <NotePresetsManager
                    fieldType="DIAGNOSIS"
                    isAdmin
                    title="Diagnosis Presets"
                    noun="diagnosis preset"
                    placeholder="e.g. Essential Hypertension"
                    description="Common diagnoses that appear as one-click buttons under Diagnosis during a consultation."
                  />
                </div>
              )}

              {activeTab === 'prescription-presets' && (
                <div className="max-w-3xl mx-auto my-4">
                  <PrescriptionPresetsManager isAdmin />
                </div>
              )}

              {activeTab === 'in-clinic-presets' && (
                <div className="max-w-3xl mx-auto my-4">
                  <InClinicPresetsManager />
                </div>
              )}
              {activeTab === 'settings' && (
                <>
                  {/* Non-pharmacy: Settings is a grid of boxes; each opens its section on click. */}
                  {!isPharmacyTenant && !settingsView && (
                    <div className="max-w-4xl mx-auto my-4 grid grid-cols-1 md:grid-cols-2 gap-4">
                      {[
                        {
                          view: 'operations',
                          title: 'Operations Settings',
                          desc: 'Reception mode, billing responsibility, nurse login and OT incharge.',
                          iconClass: 'bg-sky-50 text-sky-600',
                        },
                        {
                          view: 'vitals',
                          title: 'Vitals',
                          desc: 'Which vitals are captured at OPD entry; add your own.',
                          iconClass: 'bg-rose-50 text-rose-600',
                        },
                        ...(modules.includes('ICU')
                          ? [
                              {
                                view: 'ventilator',
                                title: 'Ventilator Parameters',
                                desc: 'Which ventilator settings and measurements the ICU chart captures; add your own.',
                                iconClass: 'bg-teal-50 text-teal-600',
                              },
                              {
                                view: 'scores',
                                title: 'Severity Scores',
                                desc: 'Which severity scores the ICU chart records. GCS is part of Vitals.',
                                iconClass: 'bg-indigo-50 text-indigo-600',
                              },
                              {
                                view: 'alerts',
                                title: 'ICU Alert Thresholds',
                                desc: 'Notify the nurse and ward incharge when an ICU vitals observation falls outside your range.',
                                iconClass: 'bg-amber-50 text-amber-600',
                              },
                            ]
                          : []),
                        {
                          view: 'print-payment',
                          title: 'Print & Payment',
                          desc: 'Which pages print at consultation, and whether the bill is taken before or after the OPD.',
                          iconClass: 'bg-violet-50 text-violet-600',
                        },
                        ...(modules.includes('NURSING')
                          ? [
                              {
                                view: 'nursing',
                                title: 'Nursing Records',
                                desc: 'Turn nursing forms on or off and choose who edits each.',
                                iconClass: 'bg-emerald-50 text-emerald-600',
                              },
                            ]
                          : []),
                        // Without nursing there is no "Nursing Records" card, but the IPD still needs
                        // its clinical sub-divs (Vitals, Medication, Notes, …). This gives the same
                        // on/off + who-edits control, with Reception as the editor instead of Nurse.
                        ...(modules.includes('IPD') && !modules.includes('NURSING')
                          ? [
                              {
                                view: 'ipd-forms',
                                title: 'IPD Forms',
                                desc: 'Turn IPD sub-divisions on or off and choose who edits each (Doctor / Reception).',
                                iconClass: 'bg-emerald-50 text-emerald-600',
                              },
                            ]
                          : []),
                        ...(modules.includes('OT')
                          ? [
                              {
                                view: 'ot-forms',
                                title: 'OT / Surgery Forms',
                                desc: 'Turn OT and NABH forms on or off and choose who edits each.',
                                iconClass: 'bg-amber-50 text-amber-600',
                              },
                              {
                                view: 'permissions',
                                title: 'OT Permissions',
                                desc: 'Choose which role may do what in the Operation Theatre.',
                                iconClass: 'bg-indigo-50 text-indigo-600',
                              },
                              {
                                view: 'policies',
                                title: 'OT Policies',
                                desc: 'Which workflow steps your theatre requires; one-click presets.',
                                iconClass: 'bg-teal-50 text-teal-600',
                              },
                            ]
                          : []),
                      ].map((b) => (
                        <button
                          key={b.view}
                          onClick={() => setSettingsView(b.view)}
                          className="text-left bg-slate-50/50 rounded-2xl border border-gray-200 p-6 hover:shadow-md transition-all duration-300"
                        >
                          <div className={`p-3 rounded-xl w-fit mb-4 ${b.iconClass}`}>
                            <svg
                              xmlns="http://www.w3.org/2000/svg"
                              className="h-6 w-6"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                              />
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                              />
                            </svg>
                          </div>
                          <h3 className="text-lg font-bold text-gray-900 mb-1">{b.title}</h3>
                          <p className="text-sm text-gray-600 leading-relaxed">{b.desc}</p>
                        </button>
                      ))}
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView && (
                    <div className="max-w-4xl mx-auto mt-4">
                      <button
                        onClick={() => setSettingsView(null)}
                        className="inline-flex items-center gap-1 text-sm font-semibold text-gray-600 hover:text-gray-900"
                      >
                        ← Back to Settings
                      </button>
                    </div>
                  )}
                  {(isPharmacyTenant || settingsView === 'operations') && (
                    <div className="p-6 bg-white rounded-2xl border border-gray-200/80 shadow-sm max-w-4xl mx-auto my-4">
                      <h2 className="text-xl font-bold mb-1 text-gray-900">
                        {isPharmacyTenant ? 'Pharmacy Settings' : 'Operations Settings'}
                      </h2>
                      <p className="text-sm text-gray-500 mb-8">
                        {isPharmacyTenant
                          ? 'Configure your pharmacy workflow options.'
                          : 'Configure operational scenarios, staff access permissions, and billing responsibilities.'}
                      </p>

                      {settingsLoading ? (
                        <SkeletonSettingsCard />
                      ) : (
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
                          {/* Barcode Workflow Card — pharmacy tenants only */}
                          {isPharmacyTenant && (
                            <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                              <div>
                                <div className="flex items-center justify-between mb-4">
                                  <div className="p-3 bg-violet-50 rounded-xl text-violet-600">
                                    <svg
                                      xmlns="http://www.w3.org/2000/svg"
                                      className="h-6 w-6"
                                      fill="none"
                                      viewBox="0 0 24 24"
                                      stroke="currentColor"
                                    >
                                      <path
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                        strokeWidth={2}
                                        d="M4 5h1v14H4V5zm3 0h1v14H7V5zm3 0h2v14h-2V5zm4 0h1v14h-1V5zm3 0h2v14h-2V5z"
                                      />
                                    </svg>
                                  </div>
                                  <span
                                    className={`px-3 py-1 text-xs font-semibold rounded-full ${operationsSettings.barcodeEnabled !== false ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}`}
                                  >
                                    {operationsSettings.barcodeEnabled !== false
                                      ? 'Barcode Enabled'
                                      : 'Barcode Disabled'}
                                  </span>
                                </div>
                                <h3 className="text-lg font-bold text-gray-900 mb-2">
                                  Barcode Workflow
                                </h3>
                                <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                  Enable barcode scanning at the Billing Counter and barcode label
                                  printing in Inventory. Turn this off if your pharmacy does not use
                                  barcodes.
                                </p>
                              </div>
                              <div className="flex items-center justify-between border-t border-gray-100 pt-4">
                                <span className="text-sm font-medium text-gray-700">
                                  {operationsSettings.barcodeEnabled !== false
                                    ? 'Barcode Active'
                                    : 'Barcode Off'}
                                </span>
                                <button
                                  onClick={toggleBarcode}
                                  className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500 focus:ring-offset-2 ${
                                    operationsSettings.barcodeEnabled !== false
                                      ? 'bg-sky-600'
                                      : 'bg-gray-200'
                                  }`}
                                >
                                  <span className="sr-only">Toggle Barcode Workflow</span>
                                  <span
                                    className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                      operationsSettings.barcodeEnabled !== false
                                        ? 'translate-x-5'
                                        : 'translate-x-0'
                                    }`}
                                  />
                                </button>
                              </div>
                            </div>
                          )}
                          {/* Clinic-oriented cards — hidden for standalone pharmacy tenants */}
                          {!isPharmacyTenant && (
                            <>
                              {/* Receptionist Access Card */}
                              <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                                <div>
                                  <div className="flex items-center justify-between mb-4">
                                    <div className="p-3 bg-indigo-50 rounded-xl text-indigo-600">
                                      <svg
                                        xmlns="http://www.w3.org/2000/svg"
                                        className="h-6 w-6"
                                        fill="none"
                                        viewBox="0 0 24 24"
                                        stroke="currentColor"
                                      >
                                        <path
                                          strokeLinecap="round"
                                          strokeLinejoin="round"
                                          strokeWidth={2}
                                          d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
                                        />
                                      </svg>
                                    </div>
                                    <span
                                      className={`px-3 py-1 text-xs font-semibold rounded-full ${operationsSettings.receptionMode === 'SOLO' ? 'bg-amber-100 text-amber-800' : 'bg-emerald-100 text-emerald-800'}`}
                                    >
                                      {operationsSettings.receptionMode === 'SOLO'
                                        ? 'Self Manage'
                                        : 'Reception Mode'}
                                    </span>
                                  </div>
                                  <h3 className="text-lg font-bold text-gray-900 mb-2">
                                    Operations Mode
                                  </h3>
                                  <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                    Configure practice operations. Under Self Manage, billing and
                                    scheduling are managed directly by the doctor. Under Reception
                                    Mode, receptionists have system access.
                                  </p>
                                </div>
                                <div className="flex items-center justify-between border-t border-gray-100 pt-4">
                                  <span className="text-sm font-medium text-gray-700">
                                    Enable Reception Mode
                                  </span>
                                  <button
                                    onClick={toggleReceptionMode}
                                    className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500 focus:ring-offset-2 ${
                                      operationsSettings.receptionMode === 'HAS_RECEPTIONIST'
                                        ? 'bg-sky-600'
                                        : 'bg-gray-200'
                                    }`}
                                  >
                                    <span className="sr-only">Toggle Reception Mode</span>
                                    <span
                                      className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                        operationsSettings.receptionMode === 'HAS_RECEPTIONIST'
                                          ? 'translate-x-5'
                                          : 'translate-x-0'
                                      }`}
                                    />
                                  </button>
                                </div>
                              </div>

                              {/* Billing Responsibility Card */}
                              <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                                <div>
                                  <div className="flex items-center justify-between mb-4">
                                    <div className="p-3 bg-emerald-50 rounded-xl text-emerald-600">
                                      <svg
                                        xmlns="http://www.w3.org/2000/svg"
                                        className="h-6 w-6"
                                        fill="none"
                                        viewBox="0 0 24 24"
                                        stroke="currentColor"
                                      >
                                        <path
                                          strokeLinecap="round"
                                          strokeLinejoin="round"
                                          strokeWidth={2}
                                          d="M9 7h6m0 10v-3m-3 3h.01M9 17h.01M9 14h.01M12 14h.01M15 11h.01M12 11h.01M9 11h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z"
                                        />
                                      </svg>
                                    </div>
                                    <span
                                      className={`px-3 py-1 text-xs font-semibold rounded-full ${
                                        operationsSettings.receptionMode === 'SOLO'
                                          ? 'bg-amber-100 text-amber-800'
                                          : operationsSettings.billingHandler === 'RECEPTIONIST'
                                            ? 'bg-emerald-100 text-emerald-800'
                                            : operationsSettings.billingHandler === 'DOCTOR'
                                              ? 'bg-blue-100 text-blue-800'
                                              : 'bg-purple-100 text-purple-800'
                                      }`}
                                    >
                                      {operationsSettings.receptionMode === 'SOLO'
                                        ? 'Doctor Managed (Forced)'
                                        : operationsSettings.billingHandler === 'RECEPTIONIST'
                                          ? 'Receptionist Managed'
                                          : operationsSettings.billingHandler === 'DOCTOR'
                                            ? 'Doctor Managed'
                                            : 'Both Managed'}
                                    </span>
                                  </div>
                                  <h3 className="text-lg font-bold text-gray-900 mb-2">
                                    Billing Responsibility
                                  </h3>
                                  <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                    Determine who handles billing and payment collection. In Self
                                    Manage mode, billing responsibility is restricted to the Doctor.
                                  </p>
                                </div>
                                <div className="flex flex-col gap-2 border-t border-gray-100 pt-4">
                                  <span className="text-sm font-medium text-gray-700">
                                    Billing Managed By
                                  </span>
                                  <select
                                    disabled={operationsSettings.receptionMode === 'SOLO'}
                                    value={operationsSettings.billingHandler}
                                    onChange={handleBillingHandlerChange}
                                    className={`w-full border border-gray-300 rounded-lg px-3 py-1.5 focus:ring-2 focus:ring-sky-500 focus:border-transparent outline-none bg-white text-sm ${
                                      operationsSettings.receptionMode === 'SOLO'
                                        ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                                        : 'text-gray-700'
                                    }`}
                                  >
                                    <option value="RECEPTIONIST">Receptionist</option>
                                    <option value="DOCTOR">Doctor</option>
                                    <option value="BOTH">Both (Doctor & Receptionist)</option>
                                  </select>
                                </div>
                              </div>

                              {/* In-Clinic Mode Card — only shown when IN_CLINIC module is in plan */}
                              {modules.includes('IN_CLINIC') && (
                                <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                                  <div>
                                    <div className="flex items-center justify-between mb-4">
                                      <div className="p-3 bg-teal-50 rounded-xl text-teal-600">
                                        <svg
                                          xmlns="http://www.w3.org/2000/svg"
                                          className="h-6 w-6"
                                          fill="none"
                                          viewBox="0 0 24 24"
                                          stroke="currentColor"
                                        >
                                          <path
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                            strokeWidth={2}
                                            d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 9.172V5L8 4z"
                                          />
                                        </svg>
                                      </div>
                                      <span
                                        className={`px-3 py-1 text-xs font-semibold rounded-full ${operationsSettings.inClinic !== false ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}`}
                                      >
                                        {operationsSettings.inClinic !== false
                                          ? 'In-Clinic Enabled'
                                          : 'In-Clinic Disabled'}
                                      </span>
                                    </div>
                                    <h3 className="text-lg font-bold text-gray-900 mb-2">
                                      In-Clinic Operations
                                    </h3>
                                    <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                      Enable or disable active in-clinic stock inventory, low-stock
                                      warnings, and direct administered stock charges during OPD/IPD
                                      visits.
                                    </p>
                                  </div>
                                  <div className="flex items-center justify-between border-t border-gray-100 pt-4">
                                    <span className="text-sm font-medium text-gray-700">
                                      {operationsSettings.inClinic !== false
                                        ? 'In-Clinic Flow Active'
                                        : 'In-Clinic Flow Halted'}
                                    </span>
                                    <button
                                      onClick={toggleInClinic}
                                      className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500 focus:ring-offset-2 ${
                                        operationsSettings.inClinic !== false
                                          ? 'bg-sky-600'
                                          : 'bg-gray-200'
                                      }`}
                                    >
                                      <span className="sr-only">Toggle In-Clinic Mode</span>
                                      <span
                                        className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                          operationsSettings.inClinic !== false
                                            ? 'translate-x-5'
                                            : 'translate-x-0'
                                        }`}
                                      />
                                    </button>
                                  </div>
                                </div>
                              )}

                              {/* Separate Nurse Login Card — only shown when NURSING module is in plan */}
                              {modules.includes('NURSING') && (
                                <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                                  <div>
                                    <div className="flex items-center justify-between mb-4">
                                      <div className="p-3 bg-rose-50 rounded-xl text-rose-600">
                                        <svg
                                          xmlns="http://www.w3.org/2000/svg"
                                          className="h-6 w-6"
                                          fill="none"
                                          viewBox="0 0 24 24"
                                          stroke="currentColor"
                                        >
                                          <path
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                            strokeWidth={2}
                                            d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                                          />
                                        </svg>
                                      </div>
                                      <span
                                        className={`px-3 py-1 text-xs font-semibold rounded-full ${operationsSettings.separateNurseLogin === true ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}`}
                                      >
                                        {operationsSettings.separateNurseLogin === true
                                          ? 'Separate Login Enabled'
                                          : 'Shared Login'}
                                      </span>
                                    </div>
                                    <h3 className="text-lg font-bold text-gray-900 mb-2">
                                      Separate Nurse Login
                                    </h3>
                                    <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                      ON: individual nurses and nurse incharges may sign in through
                                      the dedicated nurse login page. OFF: individual nurse login is
                                      disabled; this does not change ward or patient access rules.
                                    </p>
                                  </div>
                                  <div className="flex items-center justify-between border-t border-gray-100 pt-4">
                                    <span className="text-sm font-medium text-gray-700">
                                      {operationsSettings.separateNurseLogin === true
                                        ? 'Separate Login Active'
                                        : 'Using Shared Login'}
                                    </span>
                                    <button
                                      onClick={toggleSeparateNurseLogin}
                                      className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500 focus:ring-offset-2 ${
                                        operationsSettings.separateNurseLogin === true
                                          ? 'bg-sky-600'
                                          : 'bg-gray-200'
                                      }`}
                                    >
                                      <span className="sr-only">Toggle Separate Nurse Login</span>
                                      <span
                                        className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                          operationsSettings.separateNurseLogin === true
                                            ? 'translate-x-5'
                                            : 'translate-x-0'
                                        }`}
                                      />
                                    </button>
                                  </div>
                                </div>
                              )}

                              {/* OT Incharge Setting Card — only shown when OT module is in plan */}
                              {modules.includes('OT') && (
                                <div className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 flex flex-col justify-between hover:shadow-md transition-all duration-300">
                                  <div>
                                    <div className="flex items-center justify-between mb-4">
                                      <div className="p-3 bg-indigo-50 rounded-xl text-indigo-600">
                                        <svg
                                          xmlns="http://www.w3.org/2000/svg"
                                          className="h-6 w-6"
                                          fill="none"
                                          viewBox="0 0 24 24"
                                          stroke="currentColor"
                                        >
                                          <path
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                            strokeWidth={2}
                                            d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"
                                          />
                                        </svg>
                                      </div>
                                      <span
                                        className={`px-3 py-1 text-xs font-semibold rounded-full ${operationsSettings.otInchargeEnabled === true ? 'bg-emerald-100 text-emerald-800' : 'bg-rose-100 text-rose-800'}`}
                                      >
                                        {operationsSettings.otInchargeEnabled === true
                                          ? 'OT Incharge Enabled'
                                          : 'Disabled'}
                                      </span>
                                    </div>
                                    <h3 className="text-lg font-bold text-gray-900 mb-2">
                                      OT Incharge Staff Option
                                    </h3>
                                    <p className="text-sm text-gray-600 leading-relaxed mb-6">
                                      When enabled, an OT Incharge profile can be managed under the
                                      Staff tab.
                                    </p>
                                  </div>
                                  <div className="flex items-center justify-between border-t border-gray-100 pt-4">
                                    <span className="text-sm font-medium text-gray-700">
                                      {operationsSettings.otInchargeEnabled === true
                                        ? 'OT Incharge Tab Active'
                                        : 'OT Incharge Tab Hidden'}
                                    </span>
                                    <button
                                      onClick={toggleOtIncharge}
                                      className={`relative inline-flex h-6 w-11 flex-shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500 focus:ring-offset-2 ${
                                        operationsSettings.otInchargeEnabled === true
                                          ? 'bg-sky-600'
                                          : 'bg-gray-200'
                                      }`}
                                    >
                                      <span className="sr-only">Toggle OT Incharge Option</span>
                                      <span
                                        className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out ${
                                          operationsSettings.otInchargeEnabled === true
                                            ? 'translate-x-5'
                                            : 'translate-x-0'
                                        }`}
                                      />
                                    </button>
                                  </div>
                                </div>
                              )}
                            </>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView === 'vitals' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <VitalsSettingsCard />
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView === 'ventilator' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <VentilatorSettingsCard refreshKey={icuRefreshKey} />
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView === 'scores' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <ScoreSettingsCard refreshKey={icuRefreshKey} />
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView === 'alerts' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <AlertThresholdsCard refreshKey={icuRefreshKey} />
                    </div>
                  )}
                  {!isPharmacyTenant && settingsView === 'print-payment' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <PrintPaymentSettingsCard />
                    </div>
                  )}
                  {!isPharmacyTenant &&
                    modules.includes('NURSING') &&
                    settingsView === 'nursing' && (
                      <div className="max-w-4xl mx-auto my-4">
                        <FilesAndAccessCard
                          category="NURSING"
                          title="Nursing Records"
                          description="Turn nursing forms on or off for this hospital and choose who can edit each. Off forms are hidden everywhere."
                        />
                      </div>
                    )}
                  {/* IPD Forms — same engine as Nursing Records, shown only when nursing is off.
                                            Reception is the editor instead of a nurse. */}
                  {!isPharmacyTenant &&
                    modules.includes('IPD') &&
                    !modules.includes('NURSING') &&
                    settingsView === 'ipd-forms' && (
                      <div className="max-w-4xl mx-auto my-4">
                        <FilesAndAccessCard
                          category="NURSING"
                          title="IPD Forms"
                          accessContext="RECEPTION"
                          description="Turn IPD sub-divisions on or off and choose who can edit each. Forms stay visible to everyone in the IPD; only the chosen role (and admin) can edit. Off forms are hidden."
                        />
                      </div>
                    )}
                  {!isPharmacyTenant && modules.includes('OT') && settingsView === 'ot-forms' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <FilesAndAccessCard
                        category="OT"
                        title="OT / Surgery Forms"
                        description="Turn OT and NABH forms on or off for this hospital and choose who can edit each. Off forms are hidden everywhere."
                      />
                    </div>
                  )}
                  {modules.includes('OT') && settingsView === 'permissions' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <OtPermissionsCard />
                    </div>
                  )}
                  {modules.includes('OT') && settingsView === 'policies' && (
                    <div className="max-w-4xl mx-auto my-4">
                      <OtPoliciesCard />
                    </div>
                  )}
                </>
              )}
              {activeTab === 'pharmacy-billing' && (
                <div className="space-y-6">
                  {renderBranchFilterBar()}
                  <BillingHistoryView refreshKey={pharmacyRefreshKey} />
                </div>
              )}
              {activeTab === 'pharmacies' && <PharmaciesTab />}
              {activeTab === 'suppliers' && (
                <div className="space-y-6">
                  {renderBranchFilterBar()}
                  <SuppliersView key={pharmacyRefreshKey} />
                </div>
              )}
              {activeTab === 'pharmacy' && (
                <div className="space-y-6 animate-in fade-in duration-300">
                  {renderBranchFilterBar()}

                  {/* Header Controls Panel */}
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-gradient-to-r from-emerald-600 to-teal-600 p-6 rounded-2xl text-white shadow-lg relative overflow-hidden">
                    <div className="absolute inset-0 bg-[radial-gradient(circle_at_30%_107%,rgba(255,255,255,0.1)_0%,rgba(255,255,255,0)_50%)]"></div>
                    <div className="relative z-10">
                      <h2 className="text-2xl font-bold tracking-tight">Pharmacy Command Center</h2>
                      <p className="text-emerald-100 text-sm mt-1">
                        A centralized financial, tax, and inventory risk control panel for
                        administrators.
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-3 relative z-10">
                      <button
                        onClick={handleExportLedger}
                        className="px-4 py-2.5 bg-white/10 hover:bg-white/20 border border-white/20 text-white rounded-xl text-xs font-bold transition-all flex items-center gap-2 active:scale-95 shadow-inner"
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
                            d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                          />
                        </svg>
                        <span>Export Tax Ledger CSV</span>
                      </button>
                    </div>
                  </div>

                  {pharmacyStatsLoading ? (
                    <SkeletonDashboard statCount={4} tableRows={5} tableCols={4} />
                  ) : (
                    <div className="space-y-6">
                      {/* KPI Financial Overview Grid */}
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                        {/* Revenue Card */}
                        <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm hover:shadow-md transition-all duration-300 relative overflow-hidden group">
                          <div className="absolute top-0 right-0 w-24 h-24 bg-emerald-50 rounded-bl-full -z-10 group-hover:scale-110 transition-transform duration-300"></div>
                          <div className="flex items-center justify-between mb-4">
                            <span className="p-3 bg-emerald-100/80 rounded-xl text-emerald-600">
                              <svg
                                className="w-6 h-6"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                                />
                              </svg>
                            </span>
                          </div>
                          <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                            Net Sales Revenue
                          </p>
                          <h3 className="text-3xl font-black text-slate-800 mt-2">
                            ₹{(pharmacyStats?.kpis?.netRevenue || 0).toLocaleString()}
                          </h3>
                          <div className="flex items-center gap-1.5 mt-2.5 text-xs">
                            <span className="text-gray-400">Gross Sales:</span>
                            <span className="font-semibold text-slate-700">
                              ₹{(pharmacyStats?.kpis?.totalSales || 0).toLocaleString()}
                            </span>
                          </div>
                        </div>

                        {/* Profitability Card */}
                        <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm hover:shadow-md transition-all duration-300 relative overflow-hidden group">
                          <div className="absolute top-0 right-0 w-24 h-24 bg-teal-50 rounded-bl-full -z-10 group-hover:scale-110 transition-transform duration-300"></div>
                          <div className="flex items-center justify-between mb-4">
                            <span className="p-3 bg-teal-100/80 rounded-xl text-teal-600">
                              <svg
                                className="w-6 h-6"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 002 2h2a2 2 0 002-2"
                                />
                              </svg>
                            </span>
                            <span className="px-2 py-0.5 bg-teal-50 border border-teal-200 text-teal-700 rounded text-[10px] font-black uppercase">
                              Margin {pharmacyStats?.kpis?.profitMargin || 0}%
                            </span>
                          </div>
                          <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                            Gross profit
                          </p>
                          <h3 className="text-3xl font-black text-slate-800 mt-2">
                            ₹{(pharmacyStats?.kpis?.grossProfit || 0).toLocaleString()}
                          </h3>
                          <p className="text-xs text-gray-400 mt-2.5">
                            Reflects margins after cost of sales
                          </p>
                        </div>

                        {/* Stock Value Card */}
                        <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm hover:shadow-md transition-all duration-300 relative overflow-hidden group">
                          <div className="absolute top-0 right-0 w-24 h-24 bg-blue-50 rounded-bl-full -z-10 group-hover:scale-110 transition-transform duration-300"></div>
                          <div className="flex items-center justify-between mb-4">
                            <span className="p-3 bg-blue-100/80 rounded-xl text-blue-600">
                              <svg
                                className="w-6 h-6"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"
                                />
                              </svg>
                            </span>
                          </div>
                          <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                            Inventory Valuation
                          </p>
                          <h3 className="text-3xl font-black text-slate-800 mt-2">
                            ₹{(pharmacyStats?.kpis?.inventoryValue || 0).toLocaleString()}
                          </h3>
                          <p className="text-xs text-gray-400 mt-2.5">
                            Asset value of non-expired stock
                          </p>
                        </div>

                        {/* Expired Asset Risk Card */}
                        <div className="bg-white p-6 rounded-2xl border border-gray-150 shadow-sm hover:shadow-md transition-all duration-300 relative overflow-hidden group">
                          <div className="absolute top-0 right-0 w-24 h-24 bg-rose-50 rounded-bl-full -z-10 group-hover:scale-110 transition-transform duration-300"></div>
                          <div className="flex items-center justify-between mb-4">
                            <span className="p-3 bg-rose-100/80 rounded-xl text-rose-600">
                              <svg
                                className="w-6 h-6"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                                />
                              </svg>
                            </span>
                            {(pharmacyStats?.kpis?.expiredValue || 0) > 0 && (
                              <span className="px-2 py-0.5 bg-rose-50 border border-rose-200 text-rose-700 rounded text-[10px] font-black uppercase animate-pulse">
                                Risk Detected
                              </span>
                            )}
                          </div>
                          <p className="text-xs font-bold text-gray-400 uppercase tracking-wider">
                            Expired Loss Assets
                          </p>
                          <h3 className="text-3xl font-black text-rose-600 mt-2">
                            ₹{(pharmacyStats?.kpis?.expiredValue || 0).toLocaleString()}
                          </h3>
                          <p className="text-xs text-gray-400 mt-2.5">
                            Loss value of already expired stock
                          </p>
                        </div>
                      </div>

                      {/* GST Tax Ledger Summary */}
                      <div className="bg-gradient-to-br from-slate-900 to-slate-800 p-6 rounded-2xl border border-slate-950 shadow-md text-white">
                        <div className="flex items-center gap-3 mb-6">
                          <span className="p-2 bg-slate-800 text-emerald-400 rounded-xl">
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
                                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                              />
                            </svg>
                          </span>
                          <div>
                            <h3 className="font-bold text-base">Enterprise Tax & GST Summary</h3>
                            <p className="text-slate-400 text-xs mt-0.5">
                              Summary of input credit vs output tax liabilities.
                            </p>
                          </div>
                        </div>
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                          <div className="bg-slate-800/40 p-4 border border-slate-700/50 rounded-xl">
                            <div className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">
                              Input GST (On Purchases)
                            </div>
                            <div className="text-2xl font-black text-slate-100 mt-1">
                              ₹{(pharmacyStats?.taxSummary?.inputGst || 0).toLocaleString()}
                            </div>
                            <p className="text-[10px] text-emerald-400 font-semibold mt-1">
                              Claimable Input Credit
                            </p>
                          </div>
                          <div className="bg-slate-800/40 p-4 border border-slate-700/50 rounded-xl">
                            <div className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">
                              Output GST (Collected on Sales)
                            </div>
                            <div className="text-2xl font-black text-slate-100 mt-1">
                              ₹{(pharmacyStats?.taxSummary?.outputGst || 0).toLocaleString()}
                            </div>
                            <p className="text-[10px] text-slate-400 mt-1">
                              Collected tax liabilities
                            </p>
                          </div>
                          <div className="bg-slate-800/40 p-4 border border-slate-700/50 rounded-xl relative overflow-hidden group">
                            <div className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">
                              Net Tax Payable
                            </div>
                            <div className="text-2xl font-black text-emerald-400 mt-1">
                              ₹{(pharmacyStats?.taxSummary?.netGstPayable || 0).toLocaleString()}
                            </div>
                            <p className="text-[10px] text-slate-400 mt-1">
                              Output tax minus Input credits
                            </p>
                          </div>
                        </div>
                      </div>

                      {/* Expiry Risk & Top Moving Split Grid */}
                      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                        {/* Expiry Risk Card */}
                        <div className="bg-white border border-gray-150 rounded-2xl p-6 shadow-sm flex flex-col justify-between">
                          <div>
                            <div className="flex justify-between items-center mb-6">
                              <div className="flex items-center gap-2">
                                <span className="p-2 bg-rose-50 text-rose-600 rounded-lg">
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
                                      d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                                    />
                                  </svg>
                                </span>
                                <h4 className="font-bold text-sm text-slate-800">
                                  Expiry Risk Analysis (FEFO Risk Profile)
                                </h4>
                              </div>
                            </div>

                            <div className="space-y-4">
                              <div>
                                <div className="flex justify-between text-xs text-gray-500 font-semibold mb-1">
                                  <span className="flex items-center gap-1.5">
                                    <span className="w-2.5 h-2.5 bg-rose-500 rounded-full"></span>{' '}
                                    Critical (Next 30 Days)
                                  </span>
                                  <span className="font-bold text-slate-800">
                                    ₹{(pharmacyStats?.expiryRisk?.next30Days || 0).toLocaleString()}
                                  </span>
                                </div>
                                <div className="w-full bg-gray-100 h-2.5 rounded-full overflow-hidden">
                                  <div
                                    className="bg-rose-500 h-full rounded-full transition-all duration-500"
                                    style={{
                                      width: `${Math.min(100, ((pharmacyStats?.expiryRisk?.next30Days || 0) / (pharmacyStats?.kpis?.inventoryValue || 1)) * 100)}%`,
                                    }}
                                  ></div>
                                </div>
                              </div>

                              <div>
                                <div className="flex justify-between text-xs text-gray-500 font-semibold mb-1">
                                  <span className="flex items-center gap-1.5">
                                    <span className="w-2.5 h-2.5 bg-amber-500 rounded-full"></span>{' '}
                                    Warning (Next 60 Days)
                                  </span>
                                  <span className="font-bold text-slate-800">
                                    ₹{(pharmacyStats?.expiryRisk?.next60Days || 0).toLocaleString()}
                                  </span>
                                </div>
                                <div className="w-full bg-gray-100 h-2.5 rounded-full overflow-hidden">
                                  <div
                                    className="bg-amber-500 h-full rounded-full transition-all duration-500"
                                    style={{
                                      width: `${Math.min(100, ((pharmacyStats?.expiryRisk?.next60Days || 0) / (pharmacyStats?.kpis?.inventoryValue || 1)) * 100)}%`,
                                    }}
                                  ></div>
                                </div>
                              </div>

                              <div>
                                <div className="flex justify-between text-xs text-gray-500 font-semibold mb-1">
                                  <span className="flex items-center gap-1.5">
                                    <span className="w-2.5 h-2.5 bg-blue-400 rounded-full"></span>{' '}
                                    Monitoring (Next 90 Days)
                                  </span>
                                  <span className="font-bold text-slate-800">
                                    ₹{(pharmacyStats?.expiryRisk?.next90Days || 0).toLocaleString()}
                                  </span>
                                </div>
                                <div className="w-full bg-gray-100 h-2.5 rounded-full overflow-hidden">
                                  <div
                                    className="bg-blue-400 h-full rounded-full transition-all duration-500"
                                    style={{
                                      width: `${Math.min(100, ((pharmacyStats?.expiryRisk?.next90Days || 0) / (pharmacyStats?.kpis?.inventoryValue || 1)) * 100)}%`,
                                    }}
                                  ></div>
                                </div>
                              </div>
                            </div>
                          </div>
                          <p className="text-[10px] text-gray-400 mt-4 leading-relaxed bg-neutral-50 p-3 rounded-lg border border-neutral-100">
                            <strong>Note:</strong> Risk profile represents absolute purchase cost
                            asset valuation. Expiring batches within 30 days should be locked in the
                            Expiry Manager.
                          </p>
                        </div>

                        {/* Top Fast-Moving Medicines */}
                        <div className="bg-white border border-gray-150 rounded-2xl p-6 shadow-sm flex flex-col justify-between">
                          <div>
                            <div className="flex justify-between items-center mb-6">
                              <div className="flex items-center gap-2">
                                <span className="p-2 bg-emerald-50 text-emerald-600 rounded-lg">
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
                                      d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6"
                                    />
                                  </svg>
                                </span>
                                <h4 className="font-bold text-sm text-slate-800">
                                  Fast-Moving Medicines (Demand Leaderboard)
                                </h4>
                              </div>
                            </div>

                            <div className="overflow-x-auto">
                              <table className="min-w-full text-xs text-left">
                                <thead className="bg-neutral-50 text-slate-500 uppercase font-black tracking-wider border-b border-neutral-100">
                                  <tr>
                                    <th className="px-3 py-2.5">Medicine Name</th>
                                    <th className="px-3 py-2.5 text-right">Units Sold</th>
                                    <th className="px-3 py-2.5 text-right">Revenue</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-neutral-50">
                                  {pharmacyStats?.fastMoving?.length > 0 ? (
                                    pharmacyStats.fastMoving.map((item, idx) => (
                                      <tr
                                        key={idx}
                                        className="hover:bg-neutral-50/50 transition-colors"
                                      >
                                        <td className="px-3 py-3 font-semibold text-slate-800">
                                          {item.name}
                                        </td>
                                        <td className="px-3 py-3 text-right font-bold text-slate-600">
                                          {(item.quantity || 0).toLocaleString()}
                                        </td>
                                        <td className="px-3 py-3 text-right font-black text-slate-900">
                                          ₹{(item.revenue || 0).toLocaleString()}
                                        </td>
                                      </tr>
                                    ))
                                  ) : (
                                    <tr>
                                      <td
                                        colSpan="3"
                                        className="px-3 py-6 text-center text-gray-400 italic"
                                      >
                                        No sales transactions recorded yet.
                                      </td>
                                    </tr>
                                  )}
                                </tbody>
                              </table>
                            </div>
                          </div>
                        </div>
                      </div>

                      {/* Category Allocation Dashboard */}
                      <div className="bg-white border border-gray-150 rounded-2xl p-6 shadow-sm">
                        <div className="flex items-center gap-2 mb-6">
                          <span className="p-2 bg-indigo-50 text-indigo-600 rounded-lg">
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
                                d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
                              />
                            </svg>
                          </span>
                          <h4 className="font-bold text-sm text-slate-800">
                            Category Stock Asset Distribution
                          </h4>
                        </div>

                        <div className="overflow-x-auto">
                          <table className="min-w-full text-xs text-left">
                            <thead className="bg-neutral-50 text-slate-500 uppercase font-black tracking-wider border-b border-neutral-100">
                              <tr>
                                <th className="px-4 py-3">Category</th>
                                <th className="px-4 py-3 text-right">Unique Batches</th>
                                <th className="px-4 py-3 text-right">Inventory Stock Value</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-neutral-50">
                              {pharmacyStats?.categoryValuation?.length > 0 ? (
                                pharmacyStats.categoryValuation.map((cat, idx) => (
                                  <tr
                                    key={idx}
                                    className="hover:bg-neutral-50/50 transition-colors"
                                  >
                                    <td className="px-4 py-3 font-semibold text-slate-800">
                                      {cat.category}
                                    </td>
                                    <td className="px-4 py-3 text-right font-bold text-slate-600">
                                      {cat.count || 0}
                                    </td>
                                    <td className="px-4 py-3 text-right font-black text-slate-900">
                                      ₹{(cat.value || 0).toLocaleString()}
                                    </td>
                                  </tr>
                                ))
                              ) : (
                                <tr>
                                  <td
                                    colSpan="3"
                                    className="px-4 py-6 text-center text-gray-400 italic"
                                  >
                                    No non-expired active stock batches in system.
                                  </td>
                                </tr>
                              )}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              )}
              {activeTab === 'pathology' && (
                <div className="flex flex-col items-center justify-center p-12 text-center h-96">
                  <h2 className="text-2xl font-bold text-gray-900">Pathology</h2>
                  <p className="text-gray-600 mt-2">
                    Pathology Module is currently under development.
                  </p>
                </div>
              )}
              {activeTab === 'ot' && (
                <div className="max-w-3xl mx-auto my-4">
                  <OtRoomsCard />
                </div>
              )}
              {activeTab === 'time-slots' && <TimeSlotsView />}
              {activeTab === 'calendar' && <HospitalCalendar />}
              {activeTab === 'analytics' && (
                <div className="p-6 space-y-8 bg-gray-50/50 min-h-screen">
                  {/* Header and filters */}
                  <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-white p-6 rounded-2xl border border-gray-200 shadow-sm">
                    <div>
                      <h2 className="text-2xl font-bold text-gray-900">Reports & Analytics</h2>
                      <p className="text-sm text-gray-600 mt-1">
                        Real-time operational insight, patient volume trends, and revenue metrics.
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={handleExportCSV}
                        className="inline-flex items-center justify-center px-4 py-2 text-sm font-semibold text-gray-700 bg-white border border-gray-200 rounded-xl hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all cursor-pointer shadow-sm gap-2"
                      >
                        <svg
                          className="w-4 h-4 text-gray-500"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                          />
                        </svg>
                        Export CSV
                      </button>

                      <button
                        onClick={handleExportPDF}
                        className="inline-flex items-center justify-center px-4 py-2 text-sm font-semibold text-white bg-indigo-600 hover:bg-indigo-700 rounded-xl focus:outline-none focus:ring-2 focus:ring-indigo-500 transition-all cursor-pointer shadow-sm gap-2"
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
                            d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                          />
                        </svg>
                        Print PDF
                      </button>
                    </div>
                  </div>

                  {!analyticsData ? (
                    <div className="space-y-6">
                      <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                        <div className="h-32 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                        <div className="h-32 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                        <div className="h-32 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                        <div className="h-32 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                      </div>
                      <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
                        <div className="h-80 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                        <div className="h-80 bg-white rounded-2xl border border-gray-200 animate-pulse animate-duration-1000" />
                      </div>
                    </div>
                  ) : (
                    (() => {
                      const COLORS = ['#4F46E5', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6'];
                      return (
                        <>
                          {/* KPI Metric Cards */}
                          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm relative overflow-hidden group hover:shadow-md transition-all duration-300">
                              <div className="flex justify-between items-start">
                                <div>
                                  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
                                    Total Patients
                                  </p>
                                  <h3 className="text-3xl font-extrabold text-gray-900 mt-2">
                                    {analyticsData.totalPatients}
                                  </h3>
                                </div>
                                <div className="p-3 bg-indigo-50 text-indigo-600 rounded-xl">
                                  <svg
                                    className="w-6 h-6"
                                    fill="none"
                                    viewBox="0 0 24 24"
                                    stroke="currentColor"
                                  >
                                    <path
                                      strokeLinecap="round"
                                      strokeLinejoin="round"
                                      strokeWidth={2}
                                      d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
                                    />
                                  </svg>
                                </div>
                              </div>
                              <div className="mt-4 text-xs font-medium text-emerald-600 flex items-center gap-1">
                                <span>+8.4%</span>
                                <span className="text-gray-600">vs last month</span>
                              </div>
                            </div>

                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm relative overflow-hidden group hover:shadow-md transition-all duration-300">
                              <div className="flex justify-between items-start">
                                <div>
                                  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
                                    OPD Consultations
                                  </p>
                                  <h3 className="text-3xl font-extrabold text-gray-900 mt-2">
                                    {analyticsData.totalOPDConsultations}
                                  </h3>
                                </div>
                                <div className="p-3 bg-emerald-50 text-emerald-600 rounded-xl">
                                  <svg
                                    className="w-6 h-6"
                                    fill="none"
                                    viewBox="0 0 24 24"
                                    stroke="currentColor"
                                  >
                                    <path
                                      strokeLinecap="round"
                                      strokeLinejoin="round"
                                      strokeWidth={2}
                                      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                                    />
                                  </svg>
                                </div>
                              </div>
                              <div className="mt-4 text-xs font-medium text-emerald-600 flex items-center gap-1">
                                <span>+12.1%</span>
                                <span className="text-gray-600">vs last month</span>
                              </div>
                            </div>

                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm relative overflow-hidden group hover:shadow-md transition-all duration-300">
                              <div className="flex justify-between items-start">
                                <div>
                                  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
                                    Bed Occupancy Rate
                                  </p>
                                  <h3 className="text-3xl font-extrabold text-gray-900 mt-2">
                                    {analyticsData.bedOccupancyRate}%
                                  </h3>
                                </div>
                                <div className="p-3 bg-amber-50 text-amber-600 rounded-xl">
                                  <svg
                                    className="w-6 h-6"
                                    fill="none"
                                    viewBox="0 0 24 24"
                                    stroke="currentColor"
                                  >
                                    <path
                                      strokeLinecap="round"
                                      strokeLinejoin="round"
                                      strokeWidth={2}
                                      d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4"
                                    />
                                  </svg>
                                </div>
                              </div>
                              <div className="mt-4 text-xs text-gray-600 font-medium">
                                {analyticsData.occupiedBeds} of {analyticsData.totalBeds} beds
                                occupied
                              </div>
                            </div>

                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm relative overflow-hidden group hover:shadow-md transition-all duration-300">
                              <div className="flex justify-between items-start">
                                <div>
                                  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">
                                    Total Revenue
                                  </p>
                                  <h3 className="text-3xl font-extrabold text-gray-900 mt-2">
                                    ₹{analyticsData.totalRevenue?.toLocaleString()}
                                  </h3>
                                </div>
                                <div className="p-3 bg-rose-50 text-rose-600 rounded-xl">
                                  <svg
                                    className="w-6 h-6"
                                    fill="none"
                                    viewBox="0 0 24 24"
                                    stroke="currentColor"
                                  >
                                    <path
                                      strokeLinecap="round"
                                      strokeLinejoin="round"
                                      strokeWidth={2}
                                      d="M17 9V7a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2m2 4h10a2 2 0 002-2v-6a2 2 0 00-2-2H9a2 2 0 00-2 2v6a2 2 0 002 2zm7-5a2 2 0 11-4 0 2 2 0 014 0z"
                                    />
                                  </svg>
                                </div>
                              </div>
                              <div className="mt-4 text-xs font-medium text-emerald-600 flex items-center gap-1">
                                <span>+15.3%</span>
                                <span className="text-gray-600">vs last month</span>
                              </div>
                            </div>
                          </div>

                          {/* Charts Grid Row 1 */}
                          <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
                            {/* Patient Volume Trends */}
                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm">
                              <div className="flex justify-between items-center mb-6">
                                <h3 className="text-lg font-bold text-gray-900">
                                  OPD & IPD Trends
                                </h3>
                                <span className="text-xs font-medium px-2.5 py-1 bg-indigo-50 text-indigo-700 rounded-full">
                                  Monthly Counts
                                </span>
                              </div>
                              <div className="h-80">
                                <ResponsiveContainer width="100%" height="100%">
                                  <AreaChart
                                    data={analyticsData.monthlyTrends}
                                    margin={{ top: 10, right: 10, left: -20, bottom: 0 }}
                                  >
                                    <defs>
                                      <linearGradient id="colorOpd" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#4F46E5" stopOpacity={0.2} />
                                        <stop offset="95%" stopColor="#4F46E5" stopOpacity={0} />
                                      </linearGradient>
                                      <linearGradient id="colorIpd" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#10B981" stopOpacity={0.2} />
                                        <stop offset="95%" stopColor="#10B981" stopOpacity={0} />
                                      </linearGradient>
                                    </defs>
                                    <CartesianGrid
                                      strokeDasharray="3 3"
                                      vertical={false}
                                      stroke="#E5E7EB"
                                    />
                                    <XAxis
                                      dataKey="month"
                                      tickLine={false}
                                      axisLine={false}
                                      style={{ fontSize: '12px', fill: '#6B7280' }}
                                    />
                                    <YAxis
                                      tickLine={false}
                                      axisLine={false}
                                      style={{ fontSize: '12px', fill: '#6B7280' }}
                                    />
                                    <Tooltip
                                      contentStyle={{
                                        background: '#FFF',
                                        borderRadius: '12px',
                                        border: '1px solid #E5E7EB',
                                        boxShadow: '0 4px 6px -1px rgba(0,0,0,0.1)',
                                      }}
                                    />
                                    <Legend iconType="circle" />
                                    <Area
                                      name="OPD Consultations"
                                      type="monotone"
                                      dataKey="opdCount"
                                      stroke="#4F46E5"
                                      strokeWidth={2.5}
                                      fillOpacity={1}
                                      fill="url(#colorOpd)"
                                    />
                                    <Area
                                      name="IPD Admissions"
                                      type="monotone"
                                      dataKey="ipdCount"
                                      stroke="#10B981"
                                      strokeWidth={2.5}
                                      fillOpacity={1}
                                      fill="url(#colorIpd)"
                                    />
                                  </AreaChart>
                                </ResponsiveContainer>
                              </div>
                            </div>

                            {/* Revenue Breakdown */}
                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm">
                              <div className="flex justify-between items-center mb-6">
                                <h3 className="text-lg font-bold text-gray-900">
                                  Revenue Contribution
                                </h3>
                                <span className="text-xs font-medium px-2.5 py-1 bg-emerald-50 text-emerald-700 rounded-full">
                                  Billing vs Pharmacy
                                </span>
                              </div>
                              <div className="h-80">
                                <ResponsiveContainer width="100%" height="100%">
                                  <BarChart
                                    data={analyticsData.monthlyTrends}
                                    margin={{ top: 10, right: 10, left: -10, bottom: 0 }}
                                  >
                                    <CartesianGrid
                                      strokeDasharray="3 3"
                                      vertical={false}
                                      stroke="#E5E7EB"
                                    />
                                    <XAxis
                                      dataKey="month"
                                      tickLine={false}
                                      axisLine={false}
                                      style={{ fontSize: '12px', fill: '#6B7280' }}
                                    />
                                    <YAxis
                                      tickLine={false}
                                      axisLine={false}
                                      style={{ fontSize: '12px', fill: '#6B7280' }}
                                    />
                                    <Tooltip
                                      formatter={(value) => `₹${value.toLocaleString()}`}
                                      contentStyle={{
                                        background: '#FFF',
                                        borderRadius: '12px',
                                        border: '1px solid #E5E7EB',
                                        boxShadow: '0 4px 6px -1px rgba(0,0,0,0.1)',
                                      }}
                                    />
                                    <Legend iconType="circle" />
                                    <Bar
                                      name="OPD/IPD Billing"
                                      dataKey="billingRevenue"
                                      stackId="a"
                                      fill="#4F46E5"
                                      radius={[0, 0, 0, 0]}
                                    />
                                    <Bar
                                      name="Pharmacy Sales"
                                      dataKey="pharmacyRevenue"
                                      stackId="a"
                                      fill="#10B981"
                                      radius={[4, 4, 0, 0]}
                                    />
                                  </BarChart>
                                </ResponsiveContainer>
                              </div>
                            </div>
                          </div>

                          {/* Charts Grid Row 2 */}
                          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
                            {/* Doctor Workload */}
                            <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm lg:col-span-2">
                              <h3 className="text-lg font-bold text-gray-900 mb-6">
                                Doctor Workload (OPD Consultations)
                              </h3>
                              {analyticsData.doctorWorkload.length === 0 ? (
                                <div className="flex flex-col items-center justify-center h-64 text-gray-500">
                                  No consultations recorded.
                                </div>
                              ) : (
                                <div className="h-72">
                                  <ResponsiveContainer width="100%" height="100%">
                                    <BarChart
                                      layout="vertical"
                                      data={analyticsData.doctorWorkload}
                                      margin={{ top: 10, right: 20, left: 30, bottom: 5 }}
                                    >
                                      <CartesianGrid
                                        strokeDasharray="3 3"
                                        horizontal={false}
                                        stroke="#E5E7EB"
                                      />
                                      <XAxis
                                        type="number"
                                        tickLine={false}
                                        axisLine={false}
                                        style={{ fontSize: '11px', fill: '#6B7280' }}
                                      />
                                      <YAxis
                                        type="category"
                                        dataKey="doctorName"
                                        tickLine={false}
                                        axisLine={false}
                                        style={{
                                          fontSize: '11px',
                                          fill: '#374151',
                                          fontWeight: '500',
                                        }}
                                      />
                                      <Tooltip
                                        contentStyle={{
                                          background: '#FFF',
                                          borderRadius: '12px',
                                          border: '1px solid #E5E7EB',
                                        }}
                                      />
                                      <Bar
                                        name="Consultations"
                                        dataKey="consultations"
                                        fill="#4F46E5"
                                        radius={[0, 4, 4, 0]}
                                        barSize={16}
                                      />
                                    </BarChart>
                                  </ResponsiveContainer>
                                </div>
                              )}
                            </div>

                            {/* Appointment Status Donut. Only meaningful for a tenant that books
                                appointments — HospitalStatsService keeps returning the counts
                                either way (analytics is gated on REPORTS, not APPOINTMENTS), so
                                without this gate a walk-in-only hospital sees a 0/0/0 donut and
                                "Fulfillment 0%" and reads it as a fault. */}
                            {hasAppointments && (
                              <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm lg:col-span-1">
                                <h3 className="text-lg font-bold text-gray-900 mb-6">
                                  Appointment Status
                                </h3>
                                <div className="h-52 relative flex items-center justify-center">
                                  <ResponsiveContainer width="100%" height="100%">
                                    <PieChart>
                                      <Pie
                                        data={analyticsData.appointmentStatus}
                                        cx="50%"
                                        cy="50%"
                                        innerRadius={55}
                                        outerRadius={75}
                                        paddingAngle={4}
                                        dataKey="value"
                                      >
                                        {analyticsData.appointmentStatus.map((entry, index) => (
                                          <Cell
                                            key={`cell-${index}`}
                                            fill={COLORS[index % COLORS.length]}
                                          />
                                        ))}
                                      </Pie>
                                      <Tooltip formatter={(value) => `${value} appointments`} />
                                    </PieChart>
                                  </ResponsiveContainer>
                                  <div className="absolute text-center">
                                    <span className="text-[10px] font-bold text-gray-500 uppercase tracking-wider">
                                      Fulfillment
                                    </span>
                                    <p className="text-2xl font-black text-gray-900">
                                      {analyticsData.fulfillmentRate}%
                                    </p>
                                  </div>
                                </div>
                                <div className="flex justify-around mt-4">
                                  {analyticsData.appointmentStatus.map((entry, index) => (
                                    <div key={entry.name} className="flex flex-col items-center">
                                      <div className="flex items-center gap-1.5 text-xs text-gray-500 font-medium">
                                        <span
                                          className="w-2 h-2 rounded-full"
                                          style={{ backgroundColor: COLORS[index % COLORS.length] }}
                                        ></span>
                                        {entry.name}
                                      </div>
                                      <span className="text-xs font-bold text-gray-800 mt-1">
                                        {entry.value}
                                      </span>
                                    </div>
                                  ))}
                                </div>
                              </div>
                            )}
                          </div>

                          {/* Ward Bed Occupancy Breakdown */}
                          <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm">
                            <h3 className="text-lg font-bold text-gray-900 mb-6">
                              Ward Capacity & Utilization
                            </h3>
                            {analyticsData.wardOccupancy.length === 0 ? (
                              <div className="flex flex-col items-center justify-center h-48 text-gray-500">
                                No ward or bed records found.
                              </div>
                            ) : (
                              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                                {analyticsData.wardOccupancy.map((ward) => (
                                  <div
                                    key={ward.wardName}
                                    className="p-5 border border-gray-100 rounded-xl bg-gray-50/50"
                                  >
                                    <div className="flex justify-between items-center mb-3">
                                      <h4 className="font-bold text-gray-800 text-sm">
                                        {ward.wardName}
                                      </h4>
                                      <span className="text-xs font-bold text-indigo-600 bg-indigo-50 px-2.5 py-1 rounded-lg">
                                        {ward.occupancyRate}% Occ.
                                      </span>
                                    </div>
                                    <div className="space-y-2">
                                      <div className="flex justify-between text-xs text-gray-600 font-medium">
                                        <span>
                                          Occupied Beds: <strong>{ward.occupiedBeds}</strong>
                                        </span>
                                        <span>
                                          Available Beds: <strong>{ward.availableBeds}</strong>
                                        </span>
                                      </div>
                                      <div className="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
                                        <div
                                          className="h-full bg-indigo-600 rounded-full transition-all duration-500"
                                          style={{ width: `${ward.occupancyRate}%` }}
                                        />
                                      </div>
                                      <div className="text-[10px] text-gray-500 font-medium text-right">
                                        Total Bed Capacity: {ward.totalBeds}
                                      </div>
                                    </div>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        </>
                      );
                    })()
                  )}
                </div>
              )}
              {activeTab === 'ipd' &&
                (loadError ? (
                  <LoadFailureNotice message={loadError} onRetry={() => loadData()} />
                ) : ipds.length > 0 ? (
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
                        {ipds.map((item, idx) => {
                          const row = item.ipd || item;
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
                          const theId =
                            row.ipdId || row.id || row.ipd?.id || row.ipd?.ipdId || null;
                          return (
                            <tr key={idx} className="border-t">
                              <td className="px-4 py-3">{page * pageSize + idx + 1}</td>
                              <td className="px-4 py-3">{ipdNumber || row.id}</td>
                              <td className="px-4 py-3">{patientName}</td>
                              <td className="px-4 py-3">{doctorName}</td>
                              <td className="px-4 py-3">{wardName}</td>
                              <td className="px-4 py-3">{bedNumber}</td>
                              <td className="px-4 py-3">
                                {admittedAt ? new Date(admittedAt).toLocaleString() : '-'}
                              </td>
                              <td className="px-4 py-3">{status}</td>
                              <td className="px-4 py-3">
                                <button
                                  className={`px-3 py-1 rounded ${theId ? 'bg-blue-500 text-white' : 'bg-gray-200 text-gray-500 cursor-not-allowed'}`}
                                  onClick={() => {
                                    if (theId) window.location.href = `/ipd/${theId}`;
                                  }}
                                  disabled={!theId}
                                  title={theId ? 'View IPD details' : 'IPD id not available'}
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
                    message="No IPD admissions found for this hospital."
                  />
                ))}
              {activeTab === 'audit-logs' && (
                <div className="space-y-6">
                  {renderBranchFilterBar()}
                  {auditLogs.length > 0 ? (
                    <AuditLogsTable auditLogs={auditLogs} />
                  ) : (
                    <EmptyState
                      icon={null}
                      title="No Audit Logs"
                      message="No activity has been logged yet for your hospital."
                    />
                  )}
                </div>
              )}

              {activeTab === 'support' && (
                <div className="space-y-6">
                  <div className="flex flex-col md:flex-row md:items-center md:justify-between border-b border-neutral-200 pb-5">
                    <div>
                      <h2 className="text-2xl font-bold text-gray-900">Help & Support Center</h2>
                      <p className="text-sm text-gray-500 mt-1">
                        Get answers to frequently asked questions or raise a support ticket with our
                        team.
                      </p>
                    </div>
                    <div className="mt-4 md:mt-0">
                      <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-semibold bg-sky-50 text-sky-700 border border-sky-200">
                        <svg
                          className="w-3.5 h-3.5"
                          fill="none"
                          viewBox="0 0 24 24"
                          stroke="currentColor"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2.5}
                            d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                          />
                        </svg>
                        Secure Support Channel
                      </span>
                    </div>
                  </div>

                  {supportLoading ? (
                    <SkeletonTable rows={4} cols={6} />
                  ) : (
                    <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
                      {/* Left 7 Columns: FAQs */}
                      <div className="lg:col-span-7 space-y-6">
                        <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6">
                          <div className="flex items-center justify-between mb-6">
                            <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
                              <svg
                                className="w-5 h-5 text-sky-600"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={2}
                                  d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                                />
                              </svg>
                              Frequently Asked Questions
                            </h3>
                            <span className="text-xs text-gray-400 font-medium">
                              {faqs.length} articles
                            </span>
                          </div>

                          {/* Local search bar for FAQs */}
                          <div className="relative mb-6">
                            <input
                              type="text"
                              placeholder="Search FAQ questions or keywords..."
                              value={faqSearch}
                              onChange={(e) => setFaqSearch(e.target.value)}
                              className="w-full pl-10 pr-4 py-2.5 bg-neutral-50 hover:bg-neutral-100/50 focus:bg-white border border-neutral-200 rounded-xl text-sm transition-all focus:ring-2 focus:ring-sky-500 focus:border-transparent text-slate-800 placeholder-slate-400"
                            />
                            <span className="absolute left-3.5 top-3.5 text-slate-400">
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
                          </div>

                          {faqs.filter(
                            (faq) =>
                              faq.question.toLowerCase().includes(faqSearch.toLowerCase()) ||
                              faq.answer.toLowerCase().includes(faqSearch.toLowerCase())
                          ).length === 0 ? (
                            <div className="text-center py-12 border border-dashed border-gray-200 rounded-xl bg-gray-50/50">
                              <svg
                                className="w-10 h-10 text-gray-300 mx-auto mb-3"
                                fill="none"
                                viewBox="0 0 24 24"
                                stroke="currentColor"
                              >
                                <path
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                  strokeWidth={1.5}
                                  d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                                />
                              </svg>
                              <p className="text-sm font-medium text-gray-600">
                                No matching FAQs found
                              </p>
                              <p className="text-xs text-gray-400 mt-1">
                                Try searching for other keywords
                              </p>
                            </div>
                          ) : (
                            <div className="space-y-3">
                              {faqs
                                .filter(
                                  (faq) =>
                                    faq.question.toLowerCase().includes(faqSearch.toLowerCase()) ||
                                    faq.answer.toLowerCase().includes(faqSearch.toLowerCase())
                                )
                                .map((faq) => {
                                  const isExpanded = expandedFaqId === faq.id;
                                  return (
                                    <div
                                      key={faq.id}
                                      className={`group rounded-xl border transition-all duration-300 ${
                                        isExpanded
                                          ? 'bg-neutral-50/70 border-sky-200 shadow-sm'
                                          : 'bg-white border-neutral-200/80 hover:border-neutral-300 hover:shadow-sm'
                                      }`}
                                    >
                                      <button
                                        onClick={() => setExpandedFaqId(isExpanded ? null : faq.id)}
                                        className="w-full text-left px-5 py-4 flex items-center justify-between gap-4 focus:outline-none"
                                      >
                                        <span className="font-semibold text-neutral-800 text-sm md:text-base group-hover:text-sky-600 transition-colors">
                                          {faq.question}
                                        </span>
                                        <span
                                          className={`flex-shrink-0 text-neutral-400 group-hover:text-neutral-600 transition-transform duration-300 ${isExpanded ? 'rotate-180 text-sky-500' : ''}`}
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
                                              d="M19 9l-7 7-7-7"
                                            />
                                          </svg>
                                        </span>
                                      </button>
                                      <div
                                        className={`grid transition-all duration-300 ease-in-out ${
                                          isExpanded
                                            ? 'grid-rows-[1fr] opacity-100'
                                            : 'grid-rows-[0fr] opacity-0 pointer-events-none'
                                        }`}
                                      >
                                        <div className="overflow-hidden">
                                          <div className="px-5 pb-5 pt-1 text-sm text-neutral-600 leading-relaxed border-t border-neutral-100 bg-white/50 rounded-b-xl">
                                            {faq.answer}
                                          </div>
                                        </div>
                                      </div>
                                    </div>
                                  );
                                })}
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Right 5 Columns: Submit Ticket & List of submitted tickets */}
                      <div className="lg:col-span-5 space-y-6">
                        {/* Submit New Ticket Card */}
                        <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6">
                          <h3 className="text-lg font-bold text-gray-900 mb-4 flex items-center gap-2">
                            <svg
                              className="w-5 h-5 text-sky-600"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M12 9v3m0 0v3m0-3h3m-3 0H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z"
                              />
                            </svg>
                            Raise a Support Ticket
                          </h3>
                          <form onSubmit={handleCreateTicket} className="space-y-4">
                            <div>
                              <label
                                htmlFor="fld-113"
                                className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5"
                              >
                                Subject
                              </label>
                              <input
                                id="fld-113"
                                type="text"
                                value={ticketForm.subject}
                                onChange={(e) =>
                                  setTicketForm((prev) => ({ ...prev, subject: e.target.value }))
                                }
                                placeholder="Briefly describe the issue..."
                                required
                                className="w-full px-3.5 py-2 border border-neutral-200 rounded-xl text-sm focus:ring-2 focus:ring-sky-500 focus:border-transparent text-slate-800 placeholder-slate-400"
                              />
                            </div>

                            <div>
                              <span className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5">
                                Priority
                              </span>
                              <div className="grid grid-cols-3 gap-2">
                                {['LOW', 'MEDIUM', 'HIGH'].map((p) => {
                                  const isSelected = ticketForm.priority === p;
                                  return (
                                    <button
                                      key={p}
                                      type="button"
                                      onClick={() =>
                                        setTicketForm((prev) => ({ ...prev, priority: p }))
                                      }
                                      className={`py-2 px-3 text-xs font-semibold rounded-xl border text-center transition-all duration-200 focus:outline-none ${
                                        isSelected
                                          ? `${p === 'LOW' ? 'bg-sky-600 border-sky-600 text-white shadow-sm' : p === 'MEDIUM' ? 'bg-amber-500 border-amber-500 text-white shadow-sm' : 'bg-rose-600 border-rose-600 text-white shadow-sm'}`
                                          : 'bg-white hover:bg-neutral-50 text-neutral-600 border-neutral-200'
                                      }`}
                                    >
                                      {p}
                                    </button>
                                  );
                                })}
                              </div>
                            </div>

                            <div>
                              <label
                                htmlFor="fld-112"
                                className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5"
                              >
                                Detailed Description
                              </label>
                              <textarea
                                id="fld-112"
                                value={ticketForm.message}
                                onChange={(e) =>
                                  setTicketForm((prev) => ({ ...prev, message: e.target.value }))
                                }
                                placeholder="Explain what went wrong, step by step..."
                                rows={4}
                                required
                                className="w-full px-3.5 py-2 border border-neutral-200 rounded-xl text-sm focus:ring-2 focus:ring-sky-500 focus:border-transparent text-slate-800 placeholder-slate-400 resize-none"
                              ></textarea>
                            </div>

                            <button
                              type="submit"
                              disabled={supportSubmitting}
                              className="w-full py-2.5 bg-gray-900 hover:bg-gray-800 text-white font-semibold text-sm rounded-xl transition-all duration-300 flex items-center justify-center gap-2 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-900 disabled:opacity-50"
                            >
                              {supportSubmitting ? (
                                <>
                                  <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
                                  Submitting...
                                </>
                              ) : (
                                <>
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
                                      d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
                                    />
                                  </svg>
                                  Submit Ticket
                                </>
                              )}
                            </button>
                          </form>
                        </div>

                        {/* Ticket History Card */}
                        <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6">
                          <h3 className="text-lg font-bold text-gray-900 mb-4 flex items-center gap-2">
                            <svg
                              className="w-5 h-5 text-sky-600"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01"
                              />
                            </svg>
                            Your Support Tickets
                          </h3>

                          {tickets.length === 0 ? (
                            <div className="text-center py-10 border border-dashed border-gray-200 rounded-xl bg-gray-50/50">
                              <p className="text-sm font-medium text-gray-500">
                                No support tickets logged yet
                              </p>
                              <p className="text-xs text-gray-400 mt-1">
                                If you face any issues, submit a ticket above.
                              </p>
                            </div>
                          ) : (
                            <div className="space-y-4 max-h-[350px] overflow-y-auto pr-1">
                              {tickets.map((t) => (
                                <div
                                  key={t.id}
                                  className="p-4 rounded-xl border border-neutral-100 hover:border-neutral-200/80 bg-neutral-50/30 hover:bg-neutral-50/70 transition-all duration-300"
                                >
                                  <div className="flex justify-between items-start gap-2 mb-2">
                                    <h4 className="font-semibold text-neutral-800 text-sm truncate flex-1">
                                      {t.subject}
                                    </h4>
                                    <span
                                      className={`px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase ${
                                        t.status === 'RESOLVED'
                                          ? 'bg-emerald-100 text-emerald-800'
                                          : 'bg-sky-100 text-sky-800'
                                      }`}
                                    >
                                      {t.status}
                                    </span>
                                  </div>
                                  <p className="text-xs text-neutral-600 mb-3 line-clamp-2">
                                    {t.message}
                                  </p>
                                  <div className="flex justify-between items-center text-[10px] text-neutral-400">
                                    <span
                                      className={`px-2 py-0.5 rounded font-semibold ${
                                        t.priority === 'HIGH'
                                          ? 'bg-rose-50 text-rose-700 border border-rose-100'
                                          : t.priority === 'MEDIUM'
                                            ? 'bg-amber-50 text-amber-700 border border-amber-100'
                                            : 'bg-sky-50 text-sky-700 border border-sky-100'
                                      }`}
                                    >
                                      {t.priority} Priority
                                    </span>
                                    <span>
                                      {new Date(t.createdAt).toLocaleDateString('en-IN', {
                                        day: '2-digit',
                                        month: 'short',
                                      })}{' '}
                                      {new Date(t.createdAt).toLocaleTimeString('en-IN', {
                                        hour: '2-digit',
                                        minute: '2-digit',
                                        hour12: true,
                                      })}
                                    </span>
                                  </div>
                                </div>
                              ))}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </main>
      </div>

      {/* Confirmation Modal */}
      <ConfirmationModal
        isOpen={confirmModal.isOpen}
        title={confirmModal.title}
        message={confirmModal.message}
        onConfirm={confirmModal.onConfirm}
        onCancel={() => setConfirmModal((prev) => ({ ...prev, isOpen: false }))}
        showReasonInput={confirmModal.showReasonInput}
        inputPlaceholder={confirmModal.inputPlaceholder}
      />

      {/* Appointment Modal - Using Shared Component */}
      {showModal && modalType === 'appointments' && hasAppointments && (
        <AppointmentModal
          isOpen={showModal}
          onClose={() => {
            setShowModal(false);
            setModalType(null);
            setIsNewPatient(false); // Reset toggle
          }}
          onSuccess={() => {
            setShowModal(false);
            setModalType(null);
            success('Record saved successfully');
            loadData(page, pageSize, false);
          }}
          doctors={doctors}
          patients={patients}
        />
      )}

      {/* Patient Modal - Using Shared Component */}
      {showModal && modalType === 'patients' && (
        <PatientModal
          isOpen={showModal}
          onClose={() => {
            setShowModal(false);
            setModalType(null);
          }}
          onSuccess={() => {
            setShowModal(false);
            setModalType(null);
            success('Patient saved successfully');
            loadData();
          }}
          initialData={editData}
        />
      )}

      {/* Admin OPD Intake Modal */}
      {isAdminOpdModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-3xl animate-scale-in overflow-hidden max-h-[90vh]">
            <div className="bg-white px-8 py-6 border-b border-gray-200">
              <div className="flex justify-between items-center">
                <div>
                  <h3 className="text-2xl font-bold text-neutral-800">New OPD Case</h3>
                  <p className="text-sm text-neutral-600 mt-1">
                    Register a patient into the OPD queue
                  </p>
                </div>
                <button
                  onClick={() => {
                    setIsAdminOpdModalOpen(false);
                    setAdminOpdPatientSearch('');
                    setAdminOpdShowDropdown(false);
                    setAdminOpdForm({
                      patientId: null,
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
                      paymentMethod: 'CASH',
                      paymentReference: '',
                    });
                  }}
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
                if (!adminOpdForm.patientId) {
                  toastError('Please select a valid patient from the suggestions');
                  return;
                }
                if (!adminOpdForm.doctorId) {
                  toastError('Please select a doctor');
                  return;
                }
                if (adminOpdForm.bp) {
                  const bpVal = adminOpdForm.bp.trim();
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
                if (adminOpdForm.temperature) {
                  const temp = parseFloat(adminOpdForm.temperature);
                  if (isNaN(temp) || temp < 0) {
                    toastError('Temperature cannot be negative');
                    return;
                  }
                }
                if (adminOpdForm.pulse) {
                  const pulse = parseInt(adminOpdForm.pulse, 10);
                  if (isNaN(pulse) || pulse < 0) {
                    toastError('Pulse cannot be negative');
                    return;
                  }
                }
                if (adminOpdForm.weight) {
                  const weight = parseFloat(adminOpdForm.weight);
                  if (isNaN(weight) || weight < 0) {
                    toastError('Weight cannot be negative');
                    return;
                  }
                }
                if (adminOpdForm.spo2) {
                  const spo2 = parseInt(adminOpdForm.spo2, 10);
                  if (isNaN(spo2) || spo2 < 0) {
                    toastError('SpO2 cannot be negative');
                    return;
                  }
                }
                // Bill Payment = First: the fee is collected here, so the method is required.
                const payErr = validateOpdPayment(
                  adminOpdForm.paymentMethod,
                  adminOpdForm.paymentReference
                );
                if (payErr) {
                  toastError(payErr);
                  return;
                }

                try {
                  const payload = {
                    patientId: adminOpdForm.patientId,
                    doctorId: adminOpdForm.doctorId,
                    bp: adminOpdForm.bp ? adminOpdForm.bp : null,
                    temperature: adminOpdForm.temperature
                      ? parseFloat(adminOpdForm.temperature)
                      : null,
                    pulse: adminOpdForm.pulse ? parseInt(adminOpdForm.pulse) : null,
                    weight: adminOpdForm.weight ? parseFloat(adminOpdForm.weight) : null,
                    height: adminOpdForm.height ? parseFloat(adminOpdForm.height) : null,
                    customVitals: adminOpdForm.customVitals || {},
                    spo2: adminOpdForm.spo2 ? parseInt(adminOpdForm.spo2) : null,
                    problem: adminOpdForm.problem,
                    visitType: adminOpdForm.visitType,
                    ...(isPayFirst()
                      ? {
                          paymentMethod: adminOpdForm.paymentMethod,
                          paymentReference: adminOpdForm.paymentReference || null,
                        }
                      : {}),
                  };
                  const res = await hospitalService.createOpd(payload);
                  setIsAdminOpdModalOpen(false);
                  setAdminOpdPatientSearch('');
                  setAdminOpdForm({
                    patientId: null,
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
                    paymentMethod: 'CASH',
                    paymentReference: '',
                  });
                  success('OPD Case created — ID: ' + res.caseId);
                  loadData();
                } catch (err) {
                  console.error('Failed to create OPD', err);
                  toastError('Failed to create OPD case');
                }
              }}
              className="p-6 space-y-4 max-h-[76vh] overflow-auto"
            >
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                {/* Patient Search */}
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
                      className="w-full border border-gray-300 rounded-xl pl-10 pr-4 py-2 focus:ring-2 focus:ring-sky-500 text-sm focus:border-transparent text-slate-800"
                      value={adminOpdPatientSearch}
                      onChange={(e) => {
                        setAdminOpdPatientSearch(e.target.value);
                        setAdminOpdShowDropdown(true);
                        setAdminOpdForm((prev) => ({ ...prev, patientId: null }));
                      }}
                      onFocus={() => setAdminOpdShowDropdown(true)}
                      onBlur={() => setTimeout(() => setAdminOpdShowDropdown(false), 250)}
                      placeholder="Type patient name to search..."
                      autoComplete="off"
                    />
                  </div>
                  {adminOpdShowDropdown && adminOpdPatientSearch.trim().length >= 2 && (
                    <div className="absolute left-0 right-0 mt-1 bg-white border border-neutral-200 rounded-xl shadow-xl z-50 max-h-60 overflow-y-auto divide-y divide-neutral-100">
                      {patients.filter((p) =>
                        p.name?.toLowerCase().includes(adminOpdPatientSearch.toLowerCase())
                      ).length > 0 ? (
                        patients
                          .filter((p) =>
                            p.name?.toLowerCase().includes(adminOpdPatientSearch.toLowerCase())
                          )
                          .map((p) => (
                            <button
                              type="button"
                              key={p.id}
                              onMouseDown={() => {
                                setAdminOpdForm((prev) => ({ ...prev, patientId: p.id }));
                                setAdminOpdPatientSearch(
                                  `${p.name}${p.phone ? ` (${p.phone})` : ''}`
                                );
                                setAdminOpdShowDropdown(false);
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
                {/* Doctor Select */}
                <div>
                  <label
                    htmlFor="fld-111"
                    className="block text-sm font-semibold text-neutral-700 mb-2"
                  >
                    Doctor <span className="text-red-600">*</span>
                  </label>
                  <select
                    id="fld-111"
                    value={adminOpdForm.doctorId || ''}
                    onChange={(e) =>
                      setAdminOpdForm((prev) => ({ ...prev, doctorId: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-xl px-4 py-2 focus:ring-2 focus:ring-sky-500 text-sm text-slate-800"
                  >
                    <option value="">Select Doctor...</option>
                    {doctors.map((d) => (
                      <option key={d.id} value={d.id}>
                        {d.name}
                        {d.specialization ? ` — ${d.specialization}` : ''}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                {isOn('BP') && (
                  <div>
                    <label
                      htmlFor="fld-110"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      BP
                    </label>
                    <input
                      id="fld-110"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.bp}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({
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
                      htmlFor="fld-109"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Temperature (°F)
                    </label>
                    <input
                      id="fld-109"
                      type="number"
                      step="0.1"
                      min="0"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.temperature}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({ ...prev, temperature: e.target.value }))
                      }
                    />
                  </div>
                )}
              </div>
              <div className="grid grid-cols-3 gap-4">
                {isOn('PULSE') && (
                  <div>
                    <label
                      htmlFor="fld-108"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Pulse
                    </label>
                    <input
                      id="fld-108"
                      type="number"
                      min="0"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.pulse}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({ ...prev, pulse: e.target.value }))
                      }
                    />
                  </div>
                )}
                {isOn('HEIGHT') && (
                  <div>
                    <label
                      htmlFor="fld-107"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Height (cm)
                    </label>
                    <input
                      id="fld-107"
                      type="number"
                      step="0.1"
                      min="0"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.height}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({ ...prev, height: e.target.value }))
                      }
                    />
                  </div>
                )}
                {isOn('WEIGHT') && (
                  <div>
                    <label
                      htmlFor="fld-106"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      Weight (kg)
                    </label>
                    <input
                      id="fld-106"
                      type="number"
                      step="0.1"
                      min="0"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.weight}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({ ...prev, weight: e.target.value }))
                      }
                    />
                  </div>
                )}
                {isOn('SPO2') && (
                  <div>
                    <label
                      htmlFor="fld-105"
                      className="block text-sm font-semibold text-neutral-700 mb-2"
                    >
                      SpO2 (%)
                    </label>
                    <input
                      id="fld-105"
                      type="number"
                      min="0"
                      className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800"
                      value={adminOpdForm.spo2}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({ ...prev, spo2: e.target.value }))
                      }
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
                      value={adminOpdForm.customVitals?.[v.key] || ''}
                      onChange={(e) =>
                        setAdminOpdForm((prev) => ({
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
                  htmlFor="fld-104"
                  className="block text-sm font-semibold text-neutral-700 mb-2"
                >
                  Problem / Reason
                </label>
                <textarea
                  id="fld-104"
                  rows={3}
                  className="w-full border border-gray-300 rounded-xl px-4 py-2 text-sm text-slate-800 resize-none"
                  value={adminOpdForm.problem}
                  onChange={(e) =>
                    setAdminOpdForm((prev) => ({ ...prev, problem: e.target.value }))
                  }
                />
              </div>
              <div className="flex items-center gap-4">
                <label htmlFor="fld-10024" className="text-sm font-medium">
                  Visit Type:
                </label>
                <label className="inline-flex items-center gap-2 cursor-pointer">
                  <input
                    id="fld-10024"
                    type="radio"
                    name="adminVisitType"
                    value="NEW"
                    checked={adminOpdForm.visitType === 'NEW'}
                    onChange={() => setAdminOpdForm((prev) => ({ ...prev, visitType: 'NEW' }))}
                  />{' '}
                  New
                </label>
                <label className="inline-flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name="adminVisitType"
                    value="FOLLOWUP"
                    checked={adminOpdForm.visitType === 'FOLLOWUP'}
                    onChange={() => setAdminOpdForm((prev) => ({ ...prev, visitType: 'FOLLOWUP' }))}
                  />{' '}
                  Follow-up
                </label>
              </div>

              <OpdPaymentFields
                method={adminOpdForm.paymentMethod}
                reference={adminOpdForm.paymentReference}
                onChange={(patch) => setAdminOpdForm((prev) => ({ ...prev, ...patch }))}
              />

              <div className="flex gap-4 pt-4">
                <button
                  type="button"
                  onClick={() => {
                    setIsAdminOpdModalOpen(false);
                    setAdminOpdPatientSearch('');
                    setAdminOpdForm({
                      patientId: null,
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
                      paymentMethod: 'CASH',
                      paymentReference: '',
                    });
                  }}
                  className="flex-1 py-2.5 rounded-xl border border-gray-300 font-semibold text-gray-700 hover:bg-gray-50 transition"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 py-2.5 rounded-xl bg-slate-900 text-white font-semibold hover:bg-slate-800 transition"
                >
                  Create OPD Case
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Admin IPD Admit Modal */}
      {isAdminIpdAdmitOpen && (
        <IpdAdmitModal
          isOpen={isAdminIpdAdmitOpen}
          onClose={() => {
            setIsAdminIpdAdmitOpen(false);
            setAdminIpdOpdForAdmit(null);
          }}
          opd={adminIpdOpdForAdmit}
          onSuccess={() => {
            loadData();
          }}
        />
      )}

      {/* Ward modal (use specialized modal for wards) */}
      {showModal && modalType === 'wards' && (
        <WardModal
          open={showModal}
          initial={editData}
          onClose={() => {
            setShowModal(false);
            setModalType(null);
          }}
          onSaved={() => {
            setShowModal(false);
            setModalType(null);
            success('Record saved successfully');
            loadData();
          }}
        />
      )}

      {/* Other Modals - doctors, receptionists, billing (exclude wards) */}
      {showModal &&
        modalType !== 'appointments' &&
        modalType !== 'patients' &&
        modalType !== 'wards' &&
        modalType && (
          <AddModal
            type={modalType}
            onClose={() => {
              setShowModal(false);
              setModalType(null);
            }}
            onSuccess={() => {
              setShowModal(false);
              setModalType(null);
              success('Record saved successfully');
              loadData();
            }}
            doctors={doctors}
            patients={patients}
            openConfirmation={openConfirmation}
            initialData={editData}
          />
        )}

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

      {/* Staff Details Modal */}
      {staffDetailsModal.isOpen && (
        <StaffDetailsModal
          staff={staffDetailsModal.staff}
          role={staffDetailsModal.role}
          onClose={() => setStaffDetailsModal({ isOpen: false, staff: null, role: null })}
        />
      )}

      {assignNurseModal.isOpen && (
        <AssignNurseModal
          row={assignNurseModal.row}
          nurseOptions={nurseOptions}
          onSubmit={handleSubmitAssignNurse}
          onClose={() => setAssignNurseModal({ isOpen: false, row: null })}
        />
      )}

      {createTaskModal && (
        <CreateNurseTaskModal
          nurseOptions={nurseOptions}
          activeAdmissions={nurseAssignments}
          onSubmit={handleCreateNurseTask}
          onClose={() => setCreateTaskModal(false)}
        />
      )}

      {/* Password Reset Result Modal */}
      {resetPasswordModal.isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in">
          <div className="bg-white w-full max-w-md rounded-2xl shadow-2xl border border-neutral-100 max-h-[90vh] overflow-y-auto">
            <div className="p-6 border-b border-neutral-100 flex items-center justify-between">
              <div>
                <h3 className="text-lg font-bold text-slate-800">Reset Password</h3>
                <p className="text-xs text-slate-500 mt-0.5">
                  {resetPasswordModal.staff?.name} &middot;{' '}
                  <span className="capitalize">{resetPasswordModal.role}</span>
                </p>
              </div>
              <button
                onClick={() => setResetPasswordModal({ isOpen: false, staff: null, role: '' })}
                className="text-gray-400 hover:text-gray-600 transition"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  className="h-5 w-5"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path
                    fillRule="evenodd"
                    d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
                    clipRule="evenodd"
                  />
                </svg>
              </button>
            </div>
            <div className="p-6 space-y-4">
              <div>
                <label
                  htmlFor="fld-10023"
                  className="block text-xs font-semibold text-slate-600 mb-1.5"
                >
                  New Password
                </label>
                <div className="relative">
                  <input
                    id="fld-10023"
                    type={resetPasswordForm.showNew ? 'text' : 'password'}
                    value={resetPasswordForm.newPassword}
                    onChange={(e) =>
                      setResetPasswordForm((f) => ({
                        ...f,
                        newPassword: e.target.value,
                        error: '',
                      }))
                    }
                    placeholder="Enter new password"
                    className="w-full px-3 py-2 pr-10 border border-gray-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-gray-900/10 focus:border-gray-900"
                  />
                  <button
                    type="button"
                    onClick={() => setResetPasswordForm((f) => ({ ...f, showNew: !f.showNew }))}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                  >
                    {resetPasswordForm.showNew ? (
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
                    ) : (
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="h-4 w-4"
                        viewBox="0 0 20 20"
                        fill="currentColor"
                      >
                        <path
                          fillRule="evenodd"
                          d="M3.707 2.293a1 1 0 00-1.414 1.414l14 14a1 1 0 001.414-1.414l-1.473-1.473A10.014 10.014 0 0019.542 10C18.268 5.943 14.478 3 10 3a9.958 9.958 0 00-4.512 1.074l-1.78-1.781zm4.261 4.26l1.514 1.515a2.003 2.003 0 012.45 2.45l1.514 1.514a4 4 0 00-5.478-5.478z"
                          clipRule="evenodd"
                        />
                        <path d="M12.454 16.697L9.75 13.992a4 4 0 01-3.742-3.741L2.335 6.578A9.98 9.98 0 00.458 10c1.274 4.057 5.065 7 9.542 7 .847 0 1.669-.105 2.454-.303z" />
                      </svg>
                    )}
                  </button>
                </div>
              </div>
              <div>
                <label
                  htmlFor="fld-10022"
                  className="block text-xs font-semibold text-slate-600 mb-1.5"
                >
                  Confirm New Password
                </label>
                <div className="relative">
                  <input
                    id="fld-10022"
                    type={resetPasswordForm.showConfirm ? 'text' : 'password'}
                    value={resetPasswordForm.confirmPassword}
                    onChange={(e) =>
                      setResetPasswordForm((f) => ({
                        ...f,
                        confirmPassword: e.target.value,
                        error: '',
                      }))
                    }
                    placeholder="Confirm new password"
                    className="w-full px-3 py-2 pr-10 border border-gray-200 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-gray-900/10 focus:border-gray-900"
                    onKeyDown={(e) => e.key === 'Enter' && handleResetPasswordSubmit()}
                  />
                  <button
                    type="button"
                    onClick={() =>
                      setResetPasswordForm((f) => ({ ...f, showConfirm: !f.showConfirm }))
                    }
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                  >
                    {resetPasswordForm.showConfirm ? (
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
                    ) : (
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="h-4 w-4"
                        viewBox="0 0 20 20"
                        fill="currentColor"
                      >
                        <path
                          fillRule="evenodd"
                          d="M3.707 2.293a1 1 0 00-1.414 1.414l14 14a1 1 0 001.414-1.414l-1.473-1.473A10.014 10.014 0 0019.542 10C18.268 5.943 14.478 3 10 3a9.958 9.958 0 00-4.512 1.074l-1.78-1.781zm4.261 4.26l1.514 1.515a2.003 2.003 0 012.45 2.45l1.514 1.514a4 4 0 00-5.478-5.478z"
                          clipRule="evenodd"
                        />
                        <path d="M12.454 16.697L9.75 13.992a4 4 0 01-3.742-3.741L2.335 6.578A9.98 9.98 0 00.458 10c1.274 4.057 5.065 7 9.542 7 .847 0 1.669-.105 2.454-.303z" />
                      </svg>
                    )}
                  </button>
                </div>
              </div>
              {resetPasswordForm.error && (
                <p className="text-xs text-red-600 font-medium">{resetPasswordForm.error}</p>
              )}
            </div>
            <div className="p-4 bg-neutral-50/50 border-t border-neutral-100 flex justify-end gap-3">
              <button
                onClick={() => setResetPasswordModal({ isOpen: false, staff: null, role: '' })}
                disabled={resetPasswordForm.submitting}
                className="px-4 py-2 text-sm font-semibold text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded-xl transition"
              >
                Cancel
              </button>
              <button
                onClick={handleResetPasswordSubmit}
                disabled={resetPasswordForm.submitting}
                className="px-5 py-2 bg-slate-800 hover:bg-slate-900 text-white rounded-xl text-sm font-semibold shadow-md active:scale-95 transition-all disabled:opacity-60"
              >
                {resetPasswordForm.submitting ? 'Resetting...' : 'Reset Password'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Payment Modal (Admin Dashboard consistent implementation) */}
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
                  <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
                    <h3 className="text-lg leading-6 font-bold text-gray-900 mb-4">
                      Collect Payment
                    </h3>
                    <div className="mt-2 space-y-4">
                      <div className="bg-neutral-50 p-4 rounded-xl">
                        <div className="text-xs text-gray-400 uppercase font-bold tracking-wide">
                          Patient Name
                        </div>
                        <div className="text-sm font-semibold text-gray-900 mt-0.5">
                          {paymentModal.patientName}
                        </div>
                        <div className="text-xs text-gray-400 uppercase font-bold tracking-wide mt-3">
                          Outstanding Amount
                        </div>
                        <div className="text-xl font-bold text-red-600 mt-0.5">
                          ₹{paymentModal.amount}
                        </div>
                      </div>
                      <div className="grid grid-cols-2 gap-4">
                        <button
                          onClick={() => handleProcessPayment('Cash')}
                          disabled={!!paymentProcessing}
                          className={`flex flex-col items-center justify-center gap-2 p-4 border rounded-xl transition-all duration-200 group ${paymentProcessing ? 'opacity-50 cursor-not-allowed bg-gray-50' : 'bg-white hover:bg-neutral-50 hover:border-neutral-300 active:scale-95'}`}
                        >
                          <span className="font-semibold text-neutral-800 text-sm">Cash</span>
                        </button>
                        <button
                          onClick={() => handleProcessPayment('Online')}
                          disabled={!!paymentProcessing}
                          className={`flex flex-col items-center justify-center gap-2 p-4 border rounded-xl transition-all duration-200 group ${paymentProcessing ? 'opacity-50 cursor-not-allowed bg-gray-50' : 'bg-white hover:bg-neutral-50 hover:border-neutral-300 active:scale-95'}`}
                        >
                          <span className="font-semibold text-neutral-800 text-sm">
                            Online / UPI
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
                  <div className="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
                    <h3 className="text-lg leading-6 font-bold text-gray-900">
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
                        className="w-full flex items-center justify-center gap-2 p-3 bg-gray-900 text-white rounded-lg hover:bg-gray-800 transition-colors mb-2 text-sm font-semibold"
                      >
                        <span>Print Receipt</span>
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

      {/* Custom Fee Modal */}
      {customFeeModal.isOpen && (
        <div className="fixed inset-0 z-50 overflow-y-auto">
          <div className="flex items-center justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
            <div className="fixed inset-0 transition-opacity" aria-hidden="true">
              <button
                type="button"
                aria-label="Close"
                className="absolute inset-0 bg-gray-500 opacity-75"
                onClick={() => setCustomFeeModal({ ...customFeeModal, isOpen: false })}
              />
            </div>
            <span className="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">
              &#8203;
            </span>
            <form
              onSubmit={handleSaveCustomFee}
              className="inline-block align-bottom bg-white rounded-2xl text-left max-h-[90vh] overflow-y-auto shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-md sm:w-full border border-gray-200"
            >
              <div className="bg-white px-6 pt-6 pb-4">
                <h3 className="text-lg leading-6 font-bold text-gray-900 mb-4">
                  {customFeeModal.mode === 'add'
                    ? `Add Custom ${tenantWord} Fee`
                    : `Edit Custom ${tenantWord} Fee`}
                </h3>
                <div className="space-y-4">
                  <div>
                    <label
                      htmlFor="fld-103"
                      className="block text-xs font-bold text-gray-500 uppercase tracking-wide mb-1"
                    >
                      Fee Description / Name
                    </label>
                    <input
                      id="fld-103"
                      type="text"
                      required
                      value={customFeeModal.name}
                      onChange={(e) =>
                        setCustomFeeModal((prev) => ({ ...prev, name: e.target.value }))
                      }
                      className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:ring-2 focus:ring-sky-500 focus:border-transparent"
                      placeholder="e.g. Dressing Charges, Lab Test A"
                    />
                  </div>
                  <div>
                    <label
                      htmlFor="fld-102"
                      className="block text-xs font-bold text-gray-500 uppercase tracking-wide mb-1"
                    >
                      Default Amount (₹)
                    </label>
                    <input
                      id="fld-102"
                      type="number"
                      step="0.01"
                      value={customFeeModal.defaultAmount}
                      onChange={(e) =>
                        setCustomFeeModal((prev) => ({ ...prev, defaultAmount: e.target.value }))
                      }
                      className="w-full px-4 py-2 border border-gray-200 rounded-xl focus:ring-2 focus:ring-sky-500 focus:border-transparent"
                      placeholder="0.00"
                    />
                  </div>
                </div>
              </div>
              <div className="bg-gray-50 px-6 py-4 flex flex-row-reverse gap-3">
                <button
                  type="submit"
                  disabled={customFeeSubmitting}
                  className="px-4 py-2 bg-gray-900 text-white rounded-xl text-sm font-semibold hover:bg-gray-800 transition disabled:opacity-50"
                >
                  {customFeeSubmitting ? 'Saving...' : 'Save Fee'}
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setCustomFeeModal({
                      isOpen: false,
                      mode: 'add',
                      feeId: null,
                      name: '',
                      defaultAmount: '',
                    })
                  }
                  className="px-4 py-2 bg-white border border-gray-200 text-gray-700 rounded-xl text-sm font-semibold hover:bg-gray-50 transition"
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

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
                                    fees.consultationFee || '0'
                                  );
                                } else if (val === 'case_paper') {
                                  updateBillItem(index, 'name', 'Case Paper Fee');
                                  updateBillItem(index, 'defaultAmount', fees.casePaperFee || '0');
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
                                Consultation Fee (₹{fees.consultationFee || 0})
                              </option>
                              <option value="case_paper">
                                Case Paper Fee (₹{fees.casePaperFee || 0})
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
    </div>
  );
};

// Patient Activity Table Component for Date View
const PatientActivityTable = ({ activities }) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.No.',
      cell: (info) => info.row.index + 1,
    }),
    columnHelper.accessor('patientId', {
      header: 'Patient ID',
      cell: (info) => (
        <span className="font-semibold text-gray-700">{info.getValue() || 'N/A'}</span>
      ),
    }),
    columnHelper.accessor('patientName', {
      header: 'Patient Name',
      cell: (info) => (
        <span className="font-medium text-gray-900">{info.getValue() || 'Unknown'}</span>
      ),
    }),
    columnHelper.accessor('phone', {
      header: 'Phone',
      cell: (info) => info.getValue() || 'N/A',
    }),
    columnHelper.accessor('activityType', {
      header: 'Activity Type',
      cell: (info) => {
        const type = info.getValue();
        switch (type) {
          case 'OPD':
            return (
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-800 border border-blue-200">
                OPD
              </span>
            );
          case 'APPOINTMENT':
            return (
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-100 text-emerald-800 border border-emerald-200">
                Appointment
              </span>
            );
          case 'IPD':
            return (
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-orange-100 text-orange-800 border border-orange-200">
                IPD
              </span>
            );
          default:
            return (
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-gray-100 text-gray-800 border border-gray-200">
                {type}
              </span>
            );
        }
      },
    }),
    columnHelper.accessor('activityTime', {
      header: 'Time',
      cell: (info) => {
        const val = info.getValue();
        if (!val) return 'N/A';
        try {
          const date = new Date(val);
          return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
          return val;
        }
      },
    }),
    columnHelper.accessor('doctorName', {
      header: 'Doctor Name',
      cell: (info) => info.getValue() || 'N/A',
    }),
    columnHelper.accessor('details', {
      header: 'Details',
      cell: (info) => (
        <span className="text-gray-500 font-mono text-xs">{info.getValue() || 'N/A'}</span>
      ),
    }),
  ];

  return <DataTable data={activities} columns={columns} />;
};

// Patients Table Component
const PatientsTable = ({
  patients,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onHistory,
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
    columnHelper.accessor('name', {
      header: 'Name',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('age', {
      header: 'Age',
    }),
    columnHelper.accessor('gender', {
      header: 'Gender',
    }),
    columnHelper.accessor('phone', {
      header: 'Phone',
    }),
    columnHelper.accessor('address', {
      header: 'Address',
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">Actions</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
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
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={patients} columns={columns} pagination={pagination} />;
};

// Doctors Table Component
const DoctorsTable = ({
  doctors,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onResetPassword,
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
    columnHelper.accessor('name', {
      header: 'Name',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('specialization', {
      header: 'Specialization',
    }),
    columnHelper.accessor('phone', {
      header: 'Phone',
    }),
    columnHelper.accessor('email', {
      header: 'Email',
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">Actions</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
                      icon: (
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
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Reset Password',
                      onClick: () => onResetPassword(info.row.original),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v-2l2-2 1-.743A6 6 0 1118 8zm-6-2a1 1 0 100-2 1 1 0 000 2z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={doctors} columns={columns} pagination={pagination} />;
};

// Appointments Table Component
const AppointmentsTable = ({
  appointments,
  doctors,
  isAdmin,
  onDelete,
  onStatusUpdate,
  onHistory,
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
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">Actions</div>,
            cell: (info) => {
              const status = info.row.original.status;
              const isFinal = ['CANCELLED', 'COMPLETED'].includes(status);

              return (
                <div className="text-right">
                  <ActionMenu
                    actions={[
                      {
                        label: 'History',
                        onClick: () => onHistory(info.row.original),
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
                      },
                      ...(isFinal
                        ? []
                        : [
                            {
                              label: 'Delete',
                              onClick: () =>
                                onDelete(info.row.original.publicId || info.row.original.id),
                              icon: (
                                <svg
                                  xmlns="http://www.w3.org/2000/svg"
                                  className="h-4 w-4"
                                  viewBox="0 0 20 20"
                                  fill="currentColor"
                                >
                                  <path
                                    fillRule="evenodd"
                                    d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                                    clipRule="evenodd"
                                  />
                                </svg>
                              ),
                              danger: true,
                            },
                          ]),
                    ]}
                  />
                </div>
              );
            },
          }),
        ]
      : []),
  ];

  return <DataTable data={appointments} columns={columns} pagination={pagination} />;
};

// Add/Edit Modal Component
const AddModal = ({
  type,
  onClose,
  onSuccess,
  doctors,
  patients,
  openConfirmation,
  initialData,
  isNewPatient,
  setIsNewPatient,
}) => {
  const [formData, setFormData] = useState(initialData || {});
  const [errors, setErrors] = useState({}); // Changed to object for field-level errors
  const isEdit = !!initialData;
  const { error: toastError } = useToast(); // Use toast for backend errors

  useEffect(() => {
    if (initialData) {
      setFormData(initialData);
    } else {
      setFormData({});
    }
    setErrors({});
  }, [initialData]);

  // Auto-select doctor if only one is available for appointments or billing
  useEffect(() => {
    if (
      !initialData &&
      doctors &&
      doctors.length === 1 &&
      (type === 'appointment' || type === 'billing')
    ) {
      setFormData((prev) => ({ ...prev, doctorId: doctors[0].id }));
    }
  }, [type, doctors, initialData]);

  // Load wards for the nurse form's "Select Ward" dropdown (nurses are assigned
  // to a ward created in the Wards & Beds tab).
  const [wardOptions, setWardOptions] = useState([]);
  useEffect(() => {
    if (type !== 'nurses') return;
    let active = true;
    wardService
      .getWards()
      .then((data) => {
        if (active) setWardOptions(Array.isArray(data) ? data : data?.content || []);
      })
      .catch(() => {
        if (active) setWardOptions([]);
      });
    return () => {
      active = false;
    };
  }, [type]);

  // Load active shift templates for the optional "Assign Shift" section on nurse creation.
  const [shiftTemplateOptions, setShiftTemplateOptions] = useState([]);
  useEffect(() => {
    if (type !== 'nurses') return;
    let active = true;
    timeSlotService
      .listShiftTemplates(true)
      .then((data) => {
        if (active) setShiftTemplateOptions(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (active) setShiftTemplateOptions([]);
      });
    return () => {
      active = false;
    };
  }, [type]);

  // Days-of-week are held in formData.shiftDaysOfWeek as a CSV "1,2,..." (1=Mon..7=Sun).
  const SHIFT_DOW = [
    ['Mon', 1],
    ['Tue', 2],
    ['Wed', 3],
    ['Thu', 4],
    ['Fri', 5],
    ['Sat', 6],
    ['Sun', 7],
  ];
  const shiftDaysArray = () =>
    formData.shiftDaysOfWeek
      ? String(formData.shiftDaysOfWeek).split(',').filter(Boolean).map(Number)
      : [];
  const toggleShiftDay = (dow) => {
    const cur = shiftDaysArray();
    const next = cur.includes(dow) ? cur.filter((d) => d !== dow) : [...cur, dow];
    handleChange('shiftDaysOfWeek', next.sort((a, b) => a - b).join(','));
  };

  // For an incharge being edited, pre-check the wards they currently manage
  // (Ward.inchargeNurseId === their profile id) once the ward list has loaded.
  useEffect(() => {
    if (type !== 'nurses' || !isEdit || !formData.isIncharge) return;
    if (formData.inchargeWardIds !== undefined) return;
    if (!wardOptions.length) return;
    const managed = wardOptions
      .filter((w) => w.inchargeNurseId === formData.nurseProfileId)
      .map((w) => w.wardId);
    setFormData((prev) => ({ ...prev, inchargeWardIds: managed }));
  }, [
    type,
    isEdit,
    formData.isIncharge,
    formData.nurseProfileId,
    formData.inchargeWardIds,
    wardOptions,
  ]);

  const toggleInchargeWard = (wardId) => {
    const cur = Array.isArray(formData.inchargeWardIds) ? formData.inchargeWardIds : [];
    const next = cur.includes(wardId) ? cur.filter((id) => id !== wardId) : [...cur, wardId];
    handleChange('inchargeWardIds', next);
  };

  const handleChange = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    // Clear error for this field
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: null }));
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrors({});

    const rules = {};

    if (type === 'patients') {
      Object.assign(rules, {
        name: ['required', 'name'],
        phone: ['required', 'phone'],
        age: ['required', 'age'],
        gender: ['required'],
        address: ['required'],
      });
    } else if (type === 'doctors') {
      Object.assign(rules, {
        name: ['required', 'name'],
        phone: ['required', 'phone'],
        email: ['required', 'email'],
        specialization: ['required', 'text'],
      });
      if (!isEdit) {
        rules.password = ['required', 'password'];
      }
    } else if (type === 'receptionists') {
      Object.assign(rules, {
        name: ['required', 'name'],
        email: ['required', 'email'],
        phone: ['required', 'phone'],
      });
      if (!isEdit) {
        rules.password = ['required', 'password'];
      }
    } else if (type === 'pharmacists') {
      Object.assign(rules, {
        name: ['required', 'name'],
        email: ['required', 'email'],
        phone: ['required', 'phone'],
      });
      if (!isEdit) {
        rules.password = ['required', 'password'];
      }
    } else if (type === 'ot-incharges') {
      Object.assign(rules, {
        name: ['required', 'name'],
        email: ['required', 'email'],
        phone: ['required', 'phone'],
      });
      if (!isEdit) {
        rules.password = ['required', 'password'];
      }
    } else if (type === 'nurses') {
      Object.assign(rules, {
        name: ['required', 'name'],
        email: ['required', 'email'],
        phone: ['required', 'phone'],
      });
      // An incharge manages wards via the multi-select, so a single "home
      // ward" isn't required when editing one.
      if (!(isEdit && formData.isIncharge)) {
        rules.wardId = ['required'];
      }
      if (!isEdit) {
        rules.password = ['required', 'password'];
      }
    } else if (type === 'billing') {
      rules.amount = ['required', 'positiveNumber'];
    } else if (type === 'appointments') {
      rules.doctorId = ['required'];
      rules.appointmentDate = ['required'];
      if (isNewPatient) {
        Object.assign(rules, {
          patientName: ['required', 'name'],
          patientPhone: ['required', 'phone'],
          patientAge: ['required', 'age'],
          patientGender: ['required'],
        });
      } else {
        rules.patientId = ['required'];
      }
    }

    const validationErrors = validateForm(formData, rules);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    const action = isEdit ? 'Update' : 'Add';
    const entity =
      type === 'patients'
        ? 'Patient'
        : type === 'doctors'
          ? 'Doctor'
          : type === 'receptionists'
            ? 'Receptionist'
            : type === 'nurses'
              ? 'Nurse'
              : type === 'pharmacists'
                ? 'Pharmacist'
                : type === 'ot-incharges'
                  ? 'OT Incharge'
                  : type === 'appointments'
                    ? 'Appointment'
                    : 'Billing Record';

    openConfirmation(
      `${action} ${entity}`,
      `Are you sure you want to ${action.toLowerCase()} this ${entity.toLowerCase()}?`,
      async () => {
        try {
          const requestData = {
            ...formData,
            ...(type === 'appointments'
              ? {
                  patientId: formData.patientId === '' ? null : Number(formData.patientId),
                  doctorId: formData.doctorId === '' ? null : Number(formData.doctorId),
                }
              : {}),
            ...(type === 'billing' ? { amount: Number(formData.amount) } : {}),
          };
          if (type === 'patients') {
            if (isEdit)
              await hospitalService.updatePatient(initialData.publicId || initialData.id, requestData);
            else await hospitalService.addPatient(requestData);
          } else if (type === 'doctors') {
            if (isEdit)
              await hospitalService.updateDoctor(initialData.publicId || initialData.id, requestData);
            else await hospitalService.addDoctor(requestData);
          } else if (type === 'receptionists') {
            if (isEdit)
              await hospitalService.updateReceptionist(
                initialData.publicId || initialData.id,
                requestData
              );
            else await hospitalService.addReceptionist(requestData);
          } else if (type === 'pharmacists') {
            if (isEdit)
              await hospitalService.updatePharmacist(
                initialData.publicId || initialData.id,
                requestData
              );
            else await hospitalService.addPharmacist(requestData);
          } else if (type === 'ot-incharges') {
            if (isEdit)
              await hospitalService.updateOtIncharge(
                initialData.publicId || initialData.id,
                requestData
              );
            else await hospitalService.addOtIncharge(requestData);
          } else if (type === 'nurses') {
            if (isEdit) {
              await hospitalService.updateNurse(initialData.publicId || initialData.id, requestData);
              // Reconcile the incharge's managed wards (Ward.inchargeNurseId).
              if (formData.isIncharge && Array.isArray(formData.inchargeWardIds)) {
                const profileId = formData.nurseProfileId;
                const currentlyManaged = wardOptions
                  .filter((w) => w.inchargeNurseId === profileId)
                  .map((w) => w.wardId);
                const selected = formData.inchargeWardIds;
                const toClear = currentlyManaged.filter((id) => !selected.includes(id));
                const toAssign = selected.filter((id) => !currentlyManaged.includes(id));
                for (const wid of toClear) await hospitalService.setWardIncharge(wid, null);
                for (const wid of toAssign) await hospitalService.setWardIncharge(wid, profileId);
              }
            } else {
              await hospitalService.addNurse(requestData);
            }
          } else if (type === 'appointments') {
            // Appointments editing not supported in this modal yet
            await hospitalService.createAppointment(requestData);
          } else if (type === 'billing') {
            await hospitalService.createBilling(requestData);
          }
          onSuccess();
        } catch (err) {
          toastError(extractApiError(err, 'Failed to save record'));
          throw err;
        }
      }
    );
  };

  const isFieldDisabled = (field) => {
    if (!isEdit) return false;
    // Disable email/password editing for doctors, receptionists, pharmacists as per security rules
    if (
      (type === 'doctors' ||
        type === 'receptionists' ||
        type === 'pharmacists' ||
        type === 'nurses' ||
        type === 'ot-incharges') &&
      (field === 'email' || field === 'password')
    )
      return true;
    return false;
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-lg border border-gray-200 w-full max-w-md max-h-[90vh] overflow-y-auto">
        <div className="p-6">
          <h2 className="text-2xl font-bold text-gray-900 mb-6 capitalize">
            {isEdit ? 'Edit' : 'Add'} {type.slice(0, -1)}
          </h2>

          <form onSubmit={handleSubmit} className="space-y-4">
            {type === 'patients' && (
              <>
                <div>
                  <label htmlFor="fld-101" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-101"
                    type="text"
                    placeholder="John Doe"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-100" className="block text-sm font-medium text-gray-700 mb-2">
                    Age
                  </label>
                  <input
                    id="fld-100"
                    type="number"
                    placeholder="30"
                    value={formData.age || ''}
                    onChange={(e) => handleChange('age', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.age ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.age && <p className="text-red-500 text-xs mt-1">{errors.age}</p>}
                </div>
                <div>
                  <label htmlFor="fld-99" className="block text-sm font-medium text-gray-700 mb-2">
                    Gender
                  </label>
                  <select
                    id="fld-99"
                    value={formData.gender || ''}
                    onChange={(e) => handleChange('gender', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.gender ? 'border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Select Gender</option>
                    <option value="Male">Male</option>
                    <option value="Female">Female</option>
                    <option value="Other">Other</option>
                  </select>
                  {errors.gender && <p className="text-red-500 text-xs mt-1">{errors.gender}</p>}
                </div>
                <div>
                  <label htmlFor="fld-98" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-98"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                <div>
                  <label htmlFor="fld-97" className="block text-sm font-medium text-gray-700 mb-2">
                    Address
                  </label>
                  <textarea
                    id="fld-97"
                    placeholder="123 Main St, City, Country"
                    value={formData.address || ''}
                    onChange={(e) => handleChange('address', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.address ? 'border-red-500' : 'border-gray-300'}`}
                    rows="3"
                  />
                  {errors.address && <p className="text-red-500 text-xs mt-1">{errors.address}</p>}
                </div>
              </>
            )}

            {type === 'doctors' && (
              <>
                <div>
                  <label htmlFor="fld-96" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-96"
                    type="text"
                    placeholder="Dr. John Doe"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-95" className="block text-sm font-medium text-gray-700 mb-2">
                    Specialization
                  </label>
                  <input
                    id="fld-95"
                    type="text"
                    placeholder="Cardiology"
                    value={formData.specialization || ''}
                    onChange={(e) => handleChange('specialization', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.specialization ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.specialization && (
                    <p className="text-red-500 text-xs mt-1">{errors.specialization}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="fld-94" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-94"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                <div>
                  <label htmlFor="fld-93" className="block text-sm font-medium text-gray-700 mb-2">
                    Email
                  </label>
                  <input
                    id="fld-93"
                    type="email"
                    placeholder="doctor@hospital.com"
                    value={formData.email || ''}
                    onChange={(e) => handleChange('email', e.target.value)}
                    disabled={isFieldDisabled('email')}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.email ? 'border-red-500' : 'border-gray-300'} ${isFieldDisabled('email') ? 'bg-gray-100 cursor-not-allowed' : ''}`}
                  />
                  {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email}</p>}
                </div>
                {!isEdit && (
                  <div>
                    <label
                      htmlFor="fld-92"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Password
                    </label>
                    <input
                      id="fld-92"
                      type="password"
                      placeholder="******"
                      value={formData.password || ''}
                      onChange={(e) => handleChange('password', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
                    />
                    {errors.password && (
                      <p className="text-red-500 text-xs mt-1">{errors.password}</p>
                    )}
                  </div>
                )}
              </>
            )}

            {type === 'pharmacists' && (
              <>
                <div>
                  <label htmlFor="fld-91" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-91"
                    type="text"
                    placeholder="Pharmacist Name"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-90" className="block text-sm font-medium text-gray-700 mb-2">
                    Email
                  </label>
                  <input
                    id="fld-90"
                    type="email"
                    placeholder="pharmacist@hospital.com"
                    value={formData.email || ''}
                    onChange={(e) => handleChange('email', e.target.value)}
                    disabled={isFieldDisabled('email')}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.email ? 'border-red-500' : 'border-gray-300'} ${isFieldDisabled('email') ? 'bg-gray-100 cursor-not-allowed' : ''}`}
                  />
                  {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email}</p>}
                </div>
                <div>
                  <label htmlFor="fld-89" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-89"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                {!isEdit && (
                  <div>
                    <label
                      htmlFor="fld-88"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Password
                    </label>
                    <input
                      id="fld-88"
                      type="password"
                      placeholder="******"
                      value={formData.password || ''}
                      onChange={(e) => handleChange('password', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
                    />
                    {errors.password && (
                      <p className="text-red-500 text-xs mt-1">{errors.password}</p>
                    )}
                  </div>
                )}
              </>
            )}

            {type === 'receptionists' && (
              <>
                <div>
                  <label htmlFor="fld-87" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-87"
                    type="text"
                    placeholder="Receptionist Name"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-86" className="block text-sm font-medium text-gray-700 mb-2">
                    Email
                  </label>
                  <input
                    id="fld-86"
                    type="email"
                    placeholder="receptionist@hospital.com"
                    value={formData.email || ''}
                    onChange={(e) => handleChange('email', e.target.value)}
                    disabled={isFieldDisabled('email')}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.email ? 'border-red-500' : 'border-gray-300'} ${isFieldDisabled('email') ? 'bg-gray-100 cursor-not-allowed' : ''}`}
                  />
                  {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email}</p>}
                </div>
                <div>
                  <label htmlFor="fld-85" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-85"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                {!isEdit && (
                  <div>
                    <label
                      htmlFor="fld-84"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Password
                    </label>
                    <input
                      id="fld-84"
                      type="password"
                      placeholder="******"
                      value={formData.password || ''}
                      onChange={(e) => handleChange('password', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
                    />
                    {errors.password && (
                      <p className="text-red-500 text-xs mt-1">{errors.password}</p>
                    )}
                  </div>
                )}
              </>
            )}

            {type === 'ot-incharges' && (
              <>
                <div>
                  <label htmlFor="fld-83" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-83"
                    type="text"
                    placeholder="OT Incharge Name"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-82" className="block text-sm font-medium text-gray-700 mb-2">
                    Email
                  </label>
                  <input
                    id="fld-82"
                    type="email"
                    placeholder="otincharge@hospital.com"
                    value={formData.email || ''}
                    onChange={(e) => handleChange('email', e.target.value)}
                    disabled={isFieldDisabled('email')}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.email ? 'border-red-500' : 'border-gray-300'} ${isFieldDisabled('email') ? 'bg-gray-100 cursor-not-allowed' : ''}`}
                  />
                  {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email}</p>}
                </div>
                <div>
                  <label htmlFor="fld-81" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-81"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                {!isEdit && (
                  <div>
                    <label
                      htmlFor="fld-80"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Password
                    </label>
                    <input
                      id="fld-80"
                      type="password"
                      placeholder="******"
                      value={formData.password || ''}
                      onChange={(e) => handleChange('password', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
                    />
                    {errors.password && (
                      <p className="text-red-500 text-xs mt-1">{errors.password}</p>
                    )}
                  </div>
                )}
              </>
            )}

            {type === 'nurses' && (
              <>
                <div>
                  <label htmlFor="fld-79" className="block text-sm font-medium text-gray-700 mb-2">
                    Name
                  </label>
                  <input
                    id="fld-79"
                    type="text"
                    placeholder="Nurse Name"
                    value={formData.name || ''}
                    onChange={(e) => handleChange('name', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.name ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.name && <p className="text-red-500 text-xs mt-1">{errors.name}</p>}
                </div>
                <div>
                  <label htmlFor="fld-78" className="block text-sm font-medium text-gray-700 mb-2">
                    Email
                  </label>
                  <input
                    id="fld-78"
                    type="email"
                    placeholder="nurse@hospital.com"
                    value={formData.email || ''}
                    onChange={(e) => handleChange('email', e.target.value)}
                    disabled={isFieldDisabled('email')}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.email ? 'border-red-500' : 'border-gray-300'} ${isFieldDisabled('email') ? 'bg-gray-100 cursor-not-allowed' : ''}`}
                  />
                  {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email}</p>}
                </div>
                <div>
                  <label htmlFor="fld-77" className="block text-sm font-medium text-gray-700 mb-2">
                    Phone
                  </label>
                  <input
                    id="fld-77"
                    type="tel"
                    placeholder="10-digit number"
                    value={formData.phone || ''}
                    onChange={(e) => handleChange('phone', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.phone ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.phone && <p className="text-red-500 text-xs mt-1">{errors.phone}</p>}
                </div>
                {isEdit && formData.isIncharge ? (
                  <div>
                    <span className="block text-sm font-medium text-gray-700 mb-2">
                      Wards Managed (Incharge)
                    </span>
                    <div className="border border-gray-300 rounded-lg p-2 max-h-44 overflow-y-auto space-y-1">
                      {wardOptions.map((w) => {
                        const selected =
                          Array.isArray(formData.inchargeWardIds) &&
                          formData.inchargeWardIds.includes(w.wardId);
                        const takenByOther =
                          w.inchargeNurseId != null &&
                          w.inchargeNurseId !== formData.nurseProfileId;
                        return (
                          <label
                            key={w.wardId}
                            className="flex items-center gap-2 text-sm text-gray-700 px-1 py-0.5 cursor-pointer"
                          >
                            <input
                              type="checkbox"
                              checked={!!selected}
                              onChange={() => toggleInchargeWard(w.wardId)}
                              className="h-4 w-4"
                            />
                            <span>
                              {w.wardName}
                              {w.floorNumber != null ? ` (Floor ${w.floorNumber})` : ''}
                            </span>
                            {takenByOther && (
                              <span className="text-xs text-amber-600">(another incharge)</span>
                            )}
                          </label>
                        );
                      })}
                      {wardOptions.length === 0 && (
                        <p className="text-amber-600 text-xs">
                          No wards yet — create wards in the Wards &amp; Beds tab first.
                        </p>
                      )}
                    </div>
                    <p className="text-xs text-gray-400 mt-1">
                      Select every ward this incharge manages. Unchecking a ward removes them as its
                      incharge.
                    </p>
                  </div>
                ) : (
                  <div>
                    <label
                      htmlFor="fld-76"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Select Ward
                    </label>
                    <select
                      id="fld-76"
                      value={formData.wardId || ''}
                      onChange={(e) => handleChange('wardId', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.wardId ? 'border-red-500' : 'border-gray-300'}`}
                    >
                      <option value="">Select a ward…</option>
                      {wardOptions.map((w) => (
                        <option key={w.wardId} value={w.wardId}>
                          {w.wardName}
                          {w.floorNumber != null ? ` (Floor ${w.floorNumber})` : ''}
                        </option>
                      ))}
                    </select>
                    {errors.wardId && <p className="text-red-500 text-xs mt-1">{errors.wardId}</p>}
                    {wardOptions.length === 0 && (
                      <p className="text-amber-600 text-xs mt-1">
                        No wards yet — create wards in the Wards &amp; Beds tab first.
                      </p>
                    )}
                  </div>
                )}
                <div>
                  <label htmlFor="fld-75" className="block text-sm font-medium text-gray-700 mb-2">
                    License Number (optional)
                  </label>
                  <input
                    id="fld-75"
                    type="text"
                    placeholder="Nursing council registration no."
                    value={formData.licenseNumber || ''}
                    onChange={(e) => handleChange('licenseNumber', e.target.value)}
                    className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                  />
                </div>
                {!isEdit && (
                  <div>
                    <label
                      htmlFor="fld-74"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Password
                    </label>
                    <input
                      id="fld-74"
                      type="password"
                      placeholder="******"
                      value={formData.password || ''}
                      onChange={(e) => handleChange('password', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
                    />
                    {errors.password && (
                      <p className="text-red-500 text-xs mt-1">{errors.password}</p>
                    )}
                  </div>
                )}
                {
                  <div className="pt-3 mt-1 border-t border-gray-100 space-y-3">
                    <p className="text-sm font-semibold text-gray-700">
                      Assign Shift <span className="font-normal text-gray-400">(optional)</span>
                    </p>
                    <div>
                      <label
                        htmlFor="fld-73"
                        className="block text-xs font-medium text-gray-500 mb-1"
                      >
                        Shift Template
                      </label>
                      <select
                        id="fld-73"
                        value={formData.shiftTemplatePublicId || ''}
                        onChange={(e) => handleChange('shiftTemplatePublicId', e.target.value)}
                        className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                      >
                        <option value="">No shift for now</option>
                        {shiftTemplateOptions.map((t) => (
                          <option key={t.publicId} value={t.publicId}>
                            {t.name} ({String(t.startTime).slice(0, 5)}–
                            {String(t.endTime).slice(0, 5)})
                          </option>
                        ))}
                      </select>
                      {shiftTemplateOptions.length === 0 && (
                        <p className="text-amber-600 text-xs mt-1">
                          No shift templates — create them in the Time Slots tab first.
                        </p>
                      )}
                    </div>
                    {formData.shiftTemplatePublicId && (
                      <>
                        <div className="grid grid-cols-2 gap-3">
                          <div>
                            <label
                              htmlFor="fld-72"
                              className="block text-xs font-medium text-gray-500 mb-1"
                            >
                              From
                            </label>
                            <DateSelect
                              value={formData.shiftFromDate || ''}
                              onChange={(v) => handleChange('shiftFromDate', v)}
                            />
                          </div>
                          <div>
                            <label
                              htmlFor="fld-71"
                              className="block text-xs font-medium text-gray-500 mb-1"
                            >
                              To
                            </label>
                            <DateSelect
                              value={formData.shiftToDate || ''}
                              onChange={(v) => handleChange('shiftToDate', v)}
                            />
                          </div>
                        </div>
                        <div>
                          <span className="block text-xs font-medium text-gray-500 mb-1">
                            Days of Week <span className="text-gray-400">(none = all days)</span>
                          </span>
                          <div className="flex flex-wrap gap-2">
                            {SHIFT_DOW.map(([label, dow]) => {
                              const checked = shiftDaysArray().includes(dow);
                              return (
                                <button
                                  type="button"
                                  key={dow}
                                  onClick={() => toggleShiftDay(dow)}
                                  className={`px-2.5 py-1 text-xs font-semibold rounded-lg border ${checked ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-600 border-gray-300'}`}
                                >
                                  {label}
                                </button>
                              );
                            })}
                          </div>
                        </div>
                        {(!formData.shiftFromDate || !formData.shiftToDate) && (
                          <p className="text-amber-600 text-xs">
                            Pick a From and To date to create the shifts.
                          </p>
                        )}
                      </>
                    )}
                  </div>
                }
              </>
            )}

            {type === 'appointments' && (
              <>
                {/* Toggle for Existing/New Patient */}
                <div className="mb-4 p-3 bg-gray-50 rounded-lg">
                  <label className="flex items-center cursor-pointer">
                    <input
                      type="checkbox"
                      checked={isNewPatient}
                      onChange={(e) => setIsNewPatient(e.target.checked)}
                      className="mr-3 h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
                    />
                    <span className="text-sm font-medium text-gray-700">
                      New Patient (not registered yet)
                    </span>
                  </label>
                </div>

                {/* Conditional Patient Fields */}
                {isNewPatient ? (
                  <>
                    <div>
                      <label
                        htmlFor="fld-70"
                        className="block text-sm font-medium text-gray-700 mb-2"
                      >
                        Patient Name <span className="text-red-600">*</span>
                      </label>
                      <input
                        id="fld-70"
                        type="text"
                        placeholder="Enter patient name"
                        value={formData.patientName || ''}
                        onChange={(e) => handleChange('patientName', e.target.value)}
                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientName ? 'border-red-500' : 'border-gray-300'}`}
                      />
                      {errors.patientName && (
                        <p className="text-red-500 text-xs mt-1">{errors.patientName}</p>
                      )}
                    </div>
                    <div>
                      <label
                        htmlFor="fld-69"
                        className="block text-sm font-medium text-gray-700 mb-2"
                      >
                        Patient Phone <span className="text-red-600">*</span>
                      </label>
                      <input
                        id="fld-69"
                        type="tel"
                        placeholder="Enter phone number"
                        value={formData.patientPhone || ''}
                        onChange={(e) => handleChange('patientPhone', e.target.value)}
                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientPhone ? 'border-red-500' : 'border-gray-300'}`}
                      />
                      {errors.patientPhone && (
                        <p className="text-red-500 text-xs mt-1">{errors.patientPhone}</p>
                      )}
                    </div>
                    <div>
                      <label
                        htmlFor="fld-68"
                        className="block text-sm font-medium text-gray-700 mb-2"
                      >
                        Patient Age <span className="text-red-600">*</span>
                      </label>
                      <input
                        id="fld-68"
                        type="number"
                        placeholder="Age"
                        value={formData.patientAge || ''}
                        onChange={(e) => handleChange('patientAge', e.target.value)}
                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientAge ? 'border-red-500' : 'border-gray-300'}`}
                      />
                      {errors.patientAge && (
                        <p className="text-red-500 text-xs mt-1">{errors.patientAge}</p>
                      )}
                    </div>
                    <div>
                      <label
                        htmlFor="fld-67"
                        className="block text-sm font-medium text-gray-700 mb-2"
                      >
                        Patient Gender <span className="text-red-600">*</span>
                      </label>
                      <select
                        id="fld-67"
                        value={formData.patientGender || ''}
                        onChange={(e) => handleChange('patientGender', e.target.value)}
                        className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientGender ? 'border-red-500' : 'border-gray-300'}`}
                      >
                        <option value="">Select Gender</option>
                        <option value="Male">Male</option>
                        <option value="Female">Female</option>
                        <option value="Other">Other</option>
                      </select>
                      {errors.patientGender && (
                        <p className="text-red-500 text-xs mt-1">{errors.patientGender}</p>
                      )}
                    </div>
                    <div>
                      <label
                        htmlFor="fld-66"
                        className="block text-sm font-medium text-gray-700 mb-2"
                      >
                        Patient Email
                      </label>
                      <input
                        id="fld-66"
                        type="email"
                        placeholder="Enter email (optional)"
                        value={formData.patientEmail || ''}
                        onChange={(e) => handleChange('patientEmail', e.target.value)}
                        className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                      />
                    </div>
                  </>
                ) : (
                  <div>
                    <label
                      htmlFor="fld-65"
                      className="block text-sm font-medium text-gray-700 mb-2"
                    >
                      Patient <span className="text-red-600">*</span>
                    </label>
                    <select
                      id="fld-65"
                      value={formData.patientId || ''}
                      onChange={(e) => handleChange('patientId', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientId ? 'border-red-500' : 'border-gray-300'}`}
                    >
                      <option value="">Select Patient</option>
                      {patients.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                        </option>
                      ))}
                    </select>
                    {errors.patientId && (
                      <p className="text-red-500 text-xs mt-1">{errors.patientId}</p>
                    )}
                  </div>
                )}
                <div>
                  <span className="block text-sm font-medium text-gray-700 mb-2">Doctor</span>
                  {doctors && doctors.length === 1 ? (
                    <div className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 text-gray-800 rounded-lg text-sm font-semibold flex items-center justify-between">
                      <span>{doctors[0].name}</span>
                      <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                        Assigned
                      </span>
                    </div>
                  ) : (
                    <select
                      value={formData.doctorId || ''}
                      onChange={(e) => handleChange('doctorId', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.doctorId ? 'border-red-500' : 'border-gray-300'}`}
                    >
                      <option value="">Select Doctor</option>
                      {doctors.map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                  )}
                  {errors.doctorId && (
                    <p className="text-red-500 text-xs mt-1">{errors.doctorId}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="fld-64" className="block text-sm font-medium text-gray-700 mb-2">
                    Date
                  </label>
                  <DateSelect
                    value={formData.appointmentDate || ''}
                    onChange={(v) => handleChange('appointmentDate', v)}
                    hasError={!!errors.appointmentDate}
                  />
                  {errors.appointmentDate && (
                    <p className="text-red-500 text-xs mt-1">{errors.appointmentDate}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="fld-63" className="block text-sm font-medium text-gray-700 mb-2">
                    Notes
                  </label>
                  <textarea
                    id="fld-63"
                    value={formData.notes || ''}
                    onChange={(e) => handleChange('notes', e.target.value)}
                    className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                    rows="3"
                  />
                </div>
              </>
            )}

            {type === 'billing' && (
              <>
                <div>
                  <label htmlFor="fld-62" className="block text-sm font-medium text-gray-700 mb-2">
                    Patient
                  </label>
                  <select
                    id="fld-62"
                    value={formData.patientId || ''}
                      onChange={(e) => handleChange('patientId', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientId ? 'border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Select Patient</option>
                    {patients.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  {errors.patientId && (
                    <p className="text-red-500 text-xs mt-1">{errors.patientId}</p>
                  )}
                </div>
                <div>
                  <span className="block text-sm font-medium text-gray-700 mb-2">Doctor</span>
                  {doctors && doctors.length === 1 ? (
                    <div className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 text-gray-800 rounded-lg text-sm font-semibold flex items-center justify-between">
                      <span>{doctors[0].name}</span>
                      <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                        Assigned
                      </span>
                    </div>
                  ) : (
                    <select
                      value={formData.doctorId || ''}
                      onChange={(e) => handleChange('doctorId', e.target.value)}
                      className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.doctorId ? 'border-red-500' : 'border-gray-300'}`}
                    >
                      <option value="">Select Doctor</option>
                      {doctors.map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                  )}
                  {errors.doctorId && (
                    <p className="text-red-500 text-xs mt-1">{errors.doctorId}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="fld-61" className="block text-sm font-medium text-gray-700 mb-2">
                    Amount
                  </label>
                  <input
                    id="fld-61"
                    type="number"
                    step="0.01"
                    placeholder="0.00"
                    value={formData.amount || ''}
                    onChange={(e) => handleChange('amount', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.amount ? 'border-red-500' : 'border-gray-300'}`}
                  />
                  {errors.amount && <p className="text-red-500 text-xs mt-1">{errors.amount}</p>}
                </div>
                <div>
                  <label htmlFor="fld-60" className="block text-sm font-medium text-gray-700 mb-2">
                    Description
                  </label>
                  <textarea
                    id="fld-60"
                    value={formData.description || ''}
                    onChange={(e) => handleChange('description', e.target.value)}
                    className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                    rows="3"
                  />
                </div>
              </>
            )}

            <div className="flex gap-3 pt-4">
              <button
                type="button"
                onClick={onClose}
                className="flex-1 bg-gray-200 text-gray-700 px-4 py-2 rounded-lg font-medium hover:bg-gray-300 transition"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="flex-1 bg-gray-900 text-white px-4 py-2 rounded-lg font-medium hover:bg-gray-800 transition"
              >
                Add
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
};

// Receptionists Table Component
const ReceptionistsTable = ({
  receptionists,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onResetPassword,
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
    columnHelper.accessor('email', {
      header: 'EMAIL',
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">ACTIONS</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
                      icon: (
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
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Reset Password',
                      onClick: () => onResetPassword(info.row.original),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v-2l2-2 1-.743A6 6 0 1118 8zm-6-2a1 1 0 100-2 1 1 0 000 2z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={receptionists} columns={columns} pagination={pagination} />;
};

// OT Incharges Table Component
const OtInchargesTable = ({
  otIncharges,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onResetPassword,
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
    columnHelper.accessor('email', {
      header: 'EMAIL',
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">ACTIONS</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
                      icon: (
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
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Reset Password',
                      onClick: () => onResetPassword(info.row.original),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v-2l2-2 1-.743A6 6 0 1118 8zm-6-2a1 1 0 100-2 1 1 0 000 2z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={otIncharges} columns={columns} pagination={pagination} />;
};

// NursesTable Component (Phase 1 Nurse module) — mirrors ReceptionistsTable.
const NursesTable = ({
  nurses,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onResetPassword,
  onPromote,
  onDemote,
  onToggleActive,
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
    columnHelper.accessor('email', {
      header: 'EMAIL',
    }),
    columnHelper.display({
      id: 'shift',
      header: 'SHIFT',
      cell: (info) => {
        const row = info.row.original;
        return (
          <div className="leading-tight">
            <span className={row.shiftName || row.shiftStartTime ? 'text-gray-800' : 'text-gray-400'}>
              {describeShift(row)}
            </span>
            {isOnShiftNow(row) && (
              <span className="ml-2 px-2 py-0.5 text-[10px] font-bold rounded-full bg-green-100 text-green-700">
                ON SHIFT
              </span>
            )}
          </div>
        );
      },
    }),
    columnHelper.accessor('wardName', {
      header: 'WARD',
      cell: (info) => info.getValue() || <span className="text-gray-400">—</span>,
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">ACTIONS</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
                      icon: (
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
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Reset Password',
                      onClick: () => onResetPassword(info.row.original),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v-2l2-2 1-.743A6 6 0 1118 8zm-6-2a1 1 0 100-2 1 1 0 000 2z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                    },
                    info.row.original.isIncharge
                      ? {
                          label: 'Demote to Nurse',
                          onClick: () => onDemote(info.row.original),
                          icon: (
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
                                d="M19 14l-7 7m0 0l-7-7m7 7V3"
                              />
                            </svg>
                          ),
                        }
                      : {
                          label: 'Promote to Incharge',
                          onClick: () => onPromote(info.row.original),
                          icon: (
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
                                d="M5 10l7-7m0 0l7 7m-7-7v18"
                              />
                            </svg>
                          ),
                        },
                    {
                      label: info.row.original.isActive === false ? 'Activate' : 'Deactivate',
                      onClick: () => onToggleActive(info.row.original),
                      icon: (
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
                            d="M13 10V3L4 14h7v7l9-11h-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={nurses} columns={columns} pagination={pagination} />;
};

// NurseAssignmentsTable Component (Phase 1 Nurse module).
// Lists active IPD admissions with their currently assigned nurse (or none),
// and lets a Hospital Admin assign / reassign / unassign.
const NurseAssignmentsTable = ({ rows, isAdmin, onAssign, onUnassign }) => {
  if (!rows || rows.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No Active Admissions"
        message="Nurse assignments appear here once patients are admitted (IPD)."
      />
    );
  }
  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
            <th className="px-4 py-3">IPD NO.</th>
            <th className="px-4 py-3">PATIENT</th>
            <th className="px-4 py-3">DOCTOR</th>
            <th className="px-4 py-3">ADMITTED</th>
            <th className="px-4 py-3">ASSIGNED NURSE</th>
            {isAdmin && <th className="px-4 py-3 text-right">ACTIONS</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.ipdAdmissionId} className="border-b border-gray-100 hover:bg-gray-50">
              <td className="px-4 py-3 font-medium text-gray-900">{r.ipdNumber}</td>
              <td className="px-4 py-3">{r.patientName || '—'}</td>
              <td className="px-4 py-3">{r.doctorName || '—'}</td>
              <td className="px-4 py-3 text-gray-500">{fmt(r.admissionDateTime)}</td>
              <td className="px-4 py-3">
                {r.nurseName ? (
                  <span className="font-medium text-gray-900">{r.nurseName}</span>
                ) : (
                  <span className="text-amber-600">Unassigned</span>
                )}
              </td>
              {isAdmin && (
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  <button
                    onClick={() => onAssign(r)}
                    className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800"
                  >
                    {r.assignmentPublicId ? 'Reassign' : 'Assign'}
                  </button>
                  {r.assignmentPublicId && (
                    <button
                      onClick={() => onUnassign(r.assignmentPublicId)}
                      className="ml-2 px-3 py-1 text-xs font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
                    >
                      Unassign
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// AssignNurseModal Component (Phase 1 Nurse module).
const AssignNurseModal = ({ row, nurseOptions, onSubmit, onClose }) => {
  const [nurseUserId, setNurseUserId] = useState(row?.nurseUserId ? String(row.nurseUserId) : '');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSave = async () => {
    if (!nurseUserId) return;
    setSubmitting(true);
    await onSubmit(Number(nurseUserId), notes);
    setSubmitting(false);
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-lg border border-gray-200 w-full max-w-md max-h-[90vh] overflow-y-auto">
        <div className="p-6">
          <h2 className="text-xl font-bold text-gray-900 mb-1">
            {row?.assignmentPublicId ? 'Reassign Nurse' : 'Assign Nurse'}
          </h2>
          <p className="text-xs text-gray-500 mb-5">
            {row?.ipdNumber} &middot; {row?.patientName}
          </p>
          <div className="space-y-4">
            <div>
              <label htmlFor="fld-59" className="block text-sm font-medium text-gray-700 mb-2">
                Nurse
              </label>
              <select
                id="fld-59"
                value={nurseUserId}
                onChange={(e) => setNurseUserId(e.target.value)}
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              >
                <option value="">Select a nurse…</option>
                {nurseOptions.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                    {n.customId ? ` (${n.customId})` : ''}
                  </option>
                ))}
              </select>
              {nurseOptions.length === 0 && (
                <p className="text-amber-600 text-xs mt-1">
                  No nurses yet — add nurses in the Nurses tab first.
                </p>
              )}
            </div>
            <div>
              <label htmlFor="fld-58" className="block text-sm font-medium text-gray-700 mb-2">
                Notes (optional)
              </label>
              <input
                id="fld-58"
                type="text"
                placeholder="e.g. beds 1-8"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
          </div>
          <div className="mt-6 flex justify-end gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={!nurseUserId || submitting}
              className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${!nurseUserId || submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
            >
              {submitting ? 'Saving…' : row?.assignmentPublicId ? 'Reassign' : 'Assign'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

// NurseTasksTable Component (Phase 1 Nurse module, M7).
const NurseTasksTable = ({ rows, nurseOptions, onCancel, isAdmin }) => {
  if (!rows || rows.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No Tasks Found"
        message="Create a task to assign duties to a nurse."
      />
    );
  }

  const getNurseName = (id) => {
    const found = nurseOptions.find((n) => n.id === id);
    return found ? found.name : `Nurse ID: ${id}`;
  };

  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';

  const getStatusColor = (s) => {
    switch (s) {
      case 'PENDING':
        return 'bg-yellow-100 text-yellow-800';
      case 'IN_PROGRESS':
        return 'bg-blue-100 text-blue-800';
      case 'COMPLETED':
        return 'bg-green-100 text-green-800';
      case 'CANCELLED':
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
            <th className="px-4 py-3">TITLE</th>
            <th className="px-4 py-3">DESCRIPTION</th>
            <th className="px-4 py-3">ASSIGNED TO</th>
            <th className="px-4 py-3">PRIORITY</th>
            <th className="px-4 py-3">STATUS</th>
            <th className="px-4 py-3">DUE DATE</th>
            <th className="px-4 py-3">COMPLETED AT</th>
            {isAdmin && <th className="px-4 py-3 text-right">ACTIONS</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.publicId || r.id} className="border-b border-gray-100 hover:bg-gray-50">
              <td className="px-4 py-3 font-medium text-gray-900">{r.title}</td>
              <td className="px-4 py-3 max-w-xs truncate text-gray-500" title={r.description}>
                {r.description || '—'}
              </td>
              <td className="px-4 py-3">{getNurseName(r.assignedToNurseUserId)}</td>
              <td className="px-4 py-3">
                <span
                  className={`px-2 py-0.5 text-xs font-semibold rounded ${r.priority === 'HIGH' ? 'bg-red-50 text-red-700' : r.priority === 'LOW' ? 'bg-gray-50 text-gray-600' : 'bg-blue-50 text-blue-700'}`}
                >
                  {r.priority}
                </span>
              </td>
              <td className="px-4 py-3">
                <span
                  className={`px-2 py-0.5 text-xs font-semibold rounded-full ${getStatusColor(r.status)}`}
                >
                  {r.status}
                </span>
              </td>
              <td className="px-4 py-3 text-gray-500">{fmt(r.dueDate)}</td>
              <td className="px-4 py-3 text-gray-500">
                {r.completedAt ? (
                  <div>
                    <div>{fmt(r.completedAt)}</div>
                    {r.completionRemarks && (
                      <div className="text-xxs italic text-gray-400">
                        &quot;{r.completionRemarks}&quot;
                      </div>
                    )}
                  </div>
                ) : (
                  '—'
                )}
              </td>
              {isAdmin && (
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {(r.status === 'PENDING' || r.status === 'IN_PROGRESS') && (
                    <button
                      onClick={() => onCancel(r.publicId)}
                      className="px-3 py-1 text-xs font-semibold rounded-lg border border-red-200 text-red-600 hover:bg-red-50"
                    >
                      Cancel
                    </button>
                  )}
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// CreateNurseTaskModal Component (Phase 1 Nurse module, M7).
const CreateNurseTaskModal = ({ nurseOptions, activeAdmissions, onSubmit, onClose }) => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [assignedToNurseUserId, setAssignedToNurseUserId] = useState('');
  const [ipdAdmissionId, setIpdAdmissionId] = useState('');
  const [priority, setPriority] = useState('MEDIUM');
  const [dueDate, setDueDate] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSave = async () => {
    if (!title.trim() || !assignedToNurseUserId) return;
    setSubmitting(true);
    const payload = {
      title: title.trim(),
      description: description.trim() || null,
      assignedToNurseUserId: Number(assignedToNurseUserId),
      ipdAdmissionId: ipdAdmissionId ? Number(ipdAdmissionId) : null,
      priority,
      dueDate: dueDate ? new Date(dueDate).toISOString() : null,
    };
    await onSubmit(payload);
    setSubmitting(false);
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-lg border border-gray-200 w-full max-w-md max-h-[90vh] overflow-y-auto">
        <div className="p-6">
          <h2 className="text-xl font-bold text-gray-900 mb-5">Create Nurse Task</h2>
          <div className="space-y-4">
            <div>
              <label htmlFor="fld-57" className="block text-xs font-medium text-gray-700 mb-1">
                Title <span className="text-red-600">*</span>
              </label>
              <input
                id="fld-57"
                type="text"
                placeholder="e.g. Change dressing, check vitals"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
            <div>
              <label htmlFor="fld-56" className="block text-xs font-medium text-gray-700 mb-1">
                Description
              </label>
              <textarea
                id="fld-56"
                placeholder="Details about the task..."
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
            <div>
              <label htmlFor="fld-55" className="block text-xs font-medium text-gray-700 mb-1">
                Assign Nurse <span className="text-red-600">*</span>
              </label>
              <select
                id="fld-55"
                value={assignedToNurseUserId}
                onChange={(e) => setAssignedToNurseUserId(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              >
                <option value="">Select a nurse…</option>
                {nurseOptions.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name} ({n.customId || 'No ID'})
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="fld-54" className="block text-xs font-medium text-gray-700 mb-1">
                Patient Admission (optional)
              </label>
              <select
                id="fld-54"
                value={ipdAdmissionId}
                onChange={(e) => setIpdAdmissionId(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              >
                <option value="">Select admission…</option>
                {activeAdmissions.map((adm) => (
                  <option key={adm.ipdAdmissionId || adm.id} value={adm.ipdAdmissionId || adm.id}>
                    {adm.ipdNumber} - {adm.patientName}
                  </option>
                ))}
              </select>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="fld-53" className="block text-xs font-medium text-gray-700 mb-1">
                  Priority
                </label>
                <select
                  id="fld-53"
                  value={priority}
                  onChange={(e) => setPriority(e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                >
                  <option value="LOW">LOW</option>
                  <option value="MEDIUM">MEDIUM</option>
                  <option value="HIGH">HIGH</option>
                </select>
              </div>
              <div>
                <label htmlFor="fld-52" className="block text-xs font-medium text-gray-700 mb-1">
                  Due Date
                </label>
                <input
                  id="fld-52"
                  type="datetime-local"
                  value={dueDate}
                  onChange={(e) => setDueDate(e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
            </div>
          </div>
          <div className="mt-6 flex justify-end gap-3">
            <button
              onClick={onClose}
              className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={!title.trim() || !assignedToNurseUserId || submitting}
              className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${!title.trim() || !assignedToNurseUserId || submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
            >
              {submitting ? 'Creating…' : 'Create Task'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

// AuditLogsTable Component
const AuditLogsTable = ({ auditLogs, startIndex = 0 }) => {
  const columnHelper = createColumnHelper();
  const [page, setPage] = useState(0);
  const pageSize = 10;

  // Helper: format date part only
  const formatDate = (timestamp) => {
    return new Date(timestamp).toLocaleDateString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  };

  // Helper: format time part only
  const formatTime = (timestamp) => {
    return new Date(timestamp)
      .toLocaleTimeString('en-IN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true,
      })
      .toUpperCase();
  };

  // Helper function to get action badge color (unchanged)
  const getActionBadgeColor = (action) => {
    return 'bg-gray-100 text-gray-800';
  };

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.NO.',
      cell: (info) => page * pageSize + info.row.index + 1,
    }),
    columnHelper.accessor('timestamp', {
      header: 'TIMESTAMP',
      cell: (info) => (
        <div>
          <p className="text-sm font-semibold text-gray-900">{formatDate(info.getValue())}</p>
          <p className="text-xs text-gray-400 mt-0.5">{formatTime(info.getValue())}</p>
        </div>
      ),
    }),
    columnHelper.accessor('action', {
      header: 'ACTION',
      cell: (info) => (
        <span
          className={`px-2 py-1 rounded-full text-xs font-semibold ${getActionBadgeColor(info.getValue())}`}
        >
          {info.getValue()}
        </span>
      ),
    }),
    columnHelper.accessor('details', {
      header: 'DETAILS',
      cell: (info) => <span className="text-sm text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('performedBy', {
      header: 'PERFORMED BY',
      cell: (info) => <span className="text-sm text-gray-700 font-medium">{info.getValue()}</span>,
    }),
    columnHelper.accessor('entityType', {
      header: 'ENTITY TYPE',
      cell: (info) => (
        <span className="text-xs text-gray-500 uppercase">{info.getValue() || 'N/A'}</span>
      ),
    }),
    columnHelper.accessor('reason', {
      header: 'REASON',
      cell: (info) => (
        <span className="text-sm text-gray-600 italic">{info.getValue() || '-'}</span>
      ),
    }),
  ];

  // Client-side pagination logic
  const totalPages = Math.ceil(auditLogs.length / pageSize);
  const paginatedLogs = auditLogs.slice(page * pageSize, (page + 1) * pageSize);

  const pagination = {
    pageIndex: page,
    pageSize: pageSize,
    totalItems: auditLogs.length,
    pageCount: totalPages,
    onPageChange: (newPage) => setPage(newPage),
  };

  return <DataTable data={paginatedLogs} columns={columns} pagination={pagination} />;
};

// Pharmacies Tab — Multi Pharmacy owner manages branch outlets (each with one login).
const PharmaciesTab = () => {
  const navigate = useNavigate();
  const { success, error: toastError } = useToast();
  const [branches, setBranches] = useState([]);
  const [loading, setLoading] = useState(true);
  const [branchesError, setBranchesError] = useState(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null); // branch being edited, or null for create
  const [form, setForm] = useState({ name: '', address: '', phone: '', email: '', password: '' });
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setBranchesError(null);
    try {
      const data = await branchesApi.getAll();
      setBranches(Array.isArray(data) ? data : []);
    } catch (e) {
      // Whatever was already listed stays on screen; a failed refresh is not a closed branch.
      setBranchesError(safeLoadMessage(e, "Couldn't load branches."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const openCreate = () => {
    setEditing(null);
    setForm({ name: '', address: '', phone: '', email: '', password: '' });
    setModalOpen(true);
  };

  const openEdit = (branch) => {
    setEditing(branch);
    setForm({
      name: branch.name || '',
      address: branch.address || '',
      phone: branch.phone || '',
      email: '',
      password: '',
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    if (!form.name.trim()) {
      toastError('Branch name is required');
      return;
    }
    if (!editing && (!form.email.trim() || !form.password.trim())) {
      toastError('Login email and password are required');
      return;
    }
    setSubmitting(true);
    try {
      if (editing) {
        await branchesApi.update(editing.id, {
          name: form.name,
          address: form.address,
          phone: form.phone,
        });
        success('Branch updated');
      } else {
        await branchesApi.create({
          name: form.name,
          address: form.address,
          phone: form.phone,
          email: form.email,
          password: form.password,
        });
        success('Branch created');
      }
      setModalOpen(false);
      load();
    } catch (e) {
      toastError(extractApiError(e, 'Failed to save branch'));
    } finally {
      setSubmitting(false);
    }
  };

  const handleResetPassword = async (branch) => {
    const pw = window.prompt(`Enter a new login password for "${branch.name}":`);
    if (!pw) return;
    try {
      await branchesApi.resetPassword(branch.id, pw);
      success('Branch password reset');
    } catch (e) {
      toastError(e.response?.data?.message || 'Failed to reset password');
    }
  };

  const handleDelete = async (branch) => {
    if (!window.confirm(`Delete branch "${branch.name}"? Its login will be disabled.`)) return;
    try {
      await branchesApi.delete(branch.id);
      success('Branch deleted');
      load();
    } catch (e) {
      toastError(e.response?.data?.message || 'Failed to delete branch');
    }
  };

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-100 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-bold text-gray-900">Pharmacies</h2>
          <p className="text-xs text-gray-500 mt-0.5">
            Branch outlets under your ownership · {branches.length} active
          </p>
        </div>
        <button
          onClick={openCreate}
          className="px-4 py-2 bg-gray-900 text-white text-sm font-bold rounded-lg hover:bg-gray-800 transition-all shadow-md"
        >
          + Create Branch
        </button>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
            <tr>
              <th className="px-6 py-3">Branch</th>
              <th className="px-6 py-3">Phone</th>
              <th className="px-6 py-3">Address</th>
              <th className="px-6 py-3 text-center">Status</th>
              <th className="px-6 py-3 text-center">Open</th>
              <th className="px-6 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="py-16 text-center text-gray-400">
                  Loading branches...
                </td>
              </tr>
            ) : branchesError ? (
              <tr>
                <td colSpan={6} className="py-16 text-center" role="alert">
                  <p className="text-sm font-bold text-gray-900">Couldn't load branches</p>
                  <p className="mt-1 text-sm text-gray-600">{branchesError}</p>
                  <button
                    type="button"
                    onClick={load}
                    className="mt-4 px-4 py-2 text-xs font-black uppercase tracking-widest bg-gray-900 text-white rounded"
                  >
                    Retry
                  </button>
                </td>
              </tr>
            ) : branches.length > 0 ? (
              branches.map((b) => (
                <tr key={b.id} className="hover:bg-gray-50/50">
                  <td className="px-6 py-3 font-bold text-gray-900">{b.name}</td>
                  <td className="px-6 py-3 text-gray-600">{b.phone || '-'}</td>
                  <td className="px-6 py-3 text-gray-600">{b.address || '-'}</td>
                  <td className="px-6 py-3 text-center">
                    <span
                      className={`px-2 py-0.5 rounded text-[9px] font-black uppercase ${b.isActive !== false ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}`}
                    >
                      {b.isActive !== false ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td className="px-6 py-3 text-center">
                    <button
                      onClick={() => {
                        sessionStorage.setItem('selectedBranchId', b.id);
                        sessionStorage.setItem('selectedBranchName', b.name);
                        navigate('/pharmacy/pharmacy');
                      }}
                      className="inline-flex items-center gap-1 px-3 py-1 bg-green-50 hover:bg-green-100 text-green-700 hover:text-green-800 text-xs font-bold rounded-lg border border-green-200/50 transition-all cursor-pointer"
                    >
                      <svg
                        className="w-3.5 h-3.5"
                        fill="none"
                        viewBox="0 0 24 24"
                        stroke="currentColor"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"
                        />
                      </svg>
                      Open
                    </button>
                  </td>
                  <td className="px-6 py-3 text-right">
                    <ActionMenu
                      actions={[
                        { label: 'Edit details', onClick: () => openEdit(b) },
                        { label: 'Reset password', onClick: () => handleResetPassword(b) },
                        { label: 'Delete branch', danger: true, onClick: () => handleDelete(b) },
                      ]}
                    />
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={6} className="py-16 text-center text-gray-400 italic">
                  No branches yet. Create your first outlet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {modalOpen && (
        <div className="fixed inset-0 bg-gray-950/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-xl shadow-2xl border border-gray-150 max-w-md w-full p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex justify-between items-start mb-4">
              <h3 className="font-bold text-gray-900 text-lg">
                {editing ? 'Edit Branch' : 'Create Branch'}
              </h3>
              <button
                onClick={() => setModalOpen(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </div>
            <div className="space-y-3">
              <div>
                <label
                  htmlFor="fld-51"
                  className="block text-xs font-bold text-gray-500 uppercase mb-1"
                >
                  Branch Name
                </label>
                <input
                  id="fld-51"
                  value={form.name}
                  onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-gray-900"
                  placeholder="e.g. MG Road Outlet"
                />
              </div>
              <div>
                <label
                  htmlFor="fld-50"
                  className="block text-xs font-bold text-gray-500 uppercase mb-1"
                >
                  Address
                </label>
                <input
                  id="fld-50"
                  value={form.address}
                  onChange={(e) => setForm((f) => ({ ...f, address: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-gray-900"
                />
              </div>
              <div>
                <label
                  htmlFor="fld-49"
                  className="block text-xs font-bold text-gray-500 uppercase mb-1"
                >
                  Phone
                </label>
                <input
                  id="fld-49"
                  value={form.phone}
                  onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-gray-900"
                />
              </div>
              {!editing && (
                <>
                  <div className="pt-2 border-t border-gray-100">
                    <p className="text-[11px] font-bold text-gray-400 uppercase tracking-wide mb-2">
                      Branch Login (one per outlet)
                    </p>
                    <label
                      htmlFor="fld-48"
                      className="block text-xs font-bold text-gray-500 uppercase mb-1"
                    >
                      Login Email
                    </label>
                    <input
                      id="fld-48"
                      type="email"
                      value={form.email}
                      onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                      className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-gray-900"
                      placeholder="branch@pharmacy.com"
                    />
                  </div>
                  <div>
                    <label
                      htmlFor="fld-47"
                      className="block text-xs font-bold text-gray-500 uppercase mb-1"
                    >
                      Password
                    </label>
                    <input
                      id="fld-47"
                      type="text"
                      value={form.password}
                      onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))}
                      className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-gray-900"
                    />
                  </div>
                </>
              )}
            </div>
            <div className="flex justify-end gap-2 mt-6 pt-4 border-t border-gray-100">
              <button
                onClick={() => setModalOpen(false)}
                disabled={submitting}
                className="px-4 py-2 border border-gray-300 text-gray-700 text-xs font-bold rounded-lg hover:bg-gray-50 disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={handleSubmit}
                disabled={submitting}
                className={`px-4 py-2 text-white text-xs font-bold rounded-lg ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
              >
                {submitting ? 'Saving...' : editing ? 'Save' : 'Create Branch'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

// Pharmacy Billing Tab — paginated list of all pharmacy sales bills (Single Pharmacy admin).
// Pharmacists Table Component (Reusing similar structure)
const PharmacistsTable = ({
  pharmacists,
  isAdmin,
  onDelete,
  onEdit,
  onViewDetails,
  onResetPassword,
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
    columnHelper.accessor('email', {
      header: 'EMAIL',
    }),
    ...(isAdmin
      ? [
          columnHelper.display({
            id: 'actions',
            header: () => <div className="text-right">ACTIONS</div>,
            cell: (info) => (
              <div className="text-right">
                <ActionMenu
                  actions={[
                    {
                      label: 'View Details',
                      onClick: () => onViewDetails(info.row.original),
                      icon: (
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
                            d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                          />
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Edit',
                      onClick: () => onEdit(info.row.original),
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
                    },
                    {
                      label: 'Reset Password',
                      onClick: () => onResetPassword(info.row.original),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v-2l2-2 1-.743A6 6 0 1118 8zm-6-2a1 1 0 100-2 1 1 0 000 2z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                    },
                    {
                      label: 'Delete',
                      onClick: () => onDelete(info.row.original.publicId || info.row.original.id),
                      icon: (
                        <svg
                          xmlns="http://www.w3.org/2000/svg"
                          className="h-4 w-4"
                          viewBox="0 0 20 20"
                          fill="currentColor"
                        >
                          <path
                            fillRule="evenodd"
                            d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                            clipRule="evenodd"
                          />
                        </svg>
                      ),
                      danger: true,
                    },
                  ]}
                />
              </div>
            ),
          }),
        ]
      : []),
  ];

  return <DataTable data={pharmacists} columns={columns} pagination={pagination} />;
};

const AdminOpdTable = ({ opds, onPrintOpd, onAdmitToIpd, startIndex = 0, pagination }) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.NO.',
      cell: (info) => startIndex + info.row.index + 1,
    }),
    columnHelper.accessor('caseId', {
      header: 'CASE ID',
      cell: (info) => (
        <span className="font-medium text-slate-800">
          {info.getValue() || info.row.original.id}
        </span>
      ),
    }),
    columnHelper.accessor((row) => row.patient?.name || row.patientName || '-', {
      id: 'patient',
      header: 'PATIENT',
    }),
    columnHelper.accessor((row) => row.doctor?.name || row.doctorName || '-', {
      id: 'doctor',
      header: 'DOCTOR',
    }),
    columnHelper.accessor('visitType', {
      header: 'VISIT TYPE',
    }),
    columnHelper.accessor('status', {
      header: 'STATUS',
      cell: (info) => (
        <span
          className={`px-2.5 py-0.5 text-xs font-semibold rounded-full ${
            info.getValue() === 'QUEUED'
              ? 'bg-amber-100 text-amber-800'
              : info.getValue() === 'CONSULTING'
                ? 'bg-blue-100 text-blue-800'
                : info.getValue() === 'CONSULTED'
                  ? 'bg-indigo-100 text-indigo-800'
                  : info.getValue() === 'COMPLETED'
                    ? 'bg-emerald-100 text-emerald-800'
                    : 'bg-slate-100 text-slate-800'
          }`}
        >
          {info.getValue() === 'QUEUED'
            ? '⏳ In Queue'
            : info.getValue() === 'CONSULTING'
              ? '🩺 Consulting'
              : info.getValue()}
        </span>
      ),
    }),
    columnHelper.accessor('createdAt', {
      header: 'REGISTERED AT',
      cell: (info) => new Date(info.getValue()).toLocaleString(),
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">ACTIONS</div>,
      cell: (info) => (
        <div className="text-right flex items-center justify-end gap-2">
          <button
            onClick={() => onPrintOpd(info.row.original)}
            className="bg-sky-50 text-sky-700 hover:bg-sky-100 px-3 py-1 rounded-md text-xs font-semibold shadow-sm transition-all inline-flex items-center gap-1"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="h-3.5 w-3.5"
              viewBox="0 0 20 20"
              fill="currentColor"
            >
              <path
                fillRule="evenodd"
                d="M5 4v3H4a2 2 0 00-2 2v3a2 2 0 002 2h1v2a2 2 0 002 2h6a2 2 0 002-2v-2h1a2 2 0 002-2V9a2 2 0 00-2-2h-1V4a2 2 0 00-2-2H7a2 2 0 00-2 2zm8 0H7v3h6V4zm0 8H7v4h6v-4z"
                clipRule="evenodd"
              />
            </svg>
            Print
          </button>
          {info.row.original.status !== 'IN_IPD' && info.row.original.ipdAdmitRecommended && (
            <button
              onClick={() => onAdmitToIpd && onAdmitToIpd(info.row.original)}
              className="bg-emerald-50 text-emerald-700 hover:bg-emerald-100 px-3 py-1 rounded-md text-xs font-semibold shadow-sm transition-all inline-flex items-center gap-1"
            >
              <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 4v16m8-8H4"
                />
              </svg>
              Admit to IPD
            </button>
          )}
        </div>
      ),
    }),
  ];

  return <DataTable data={opds} columns={columns} pagination={pagination} />;
};

export default HospitalAdminDashboard;
