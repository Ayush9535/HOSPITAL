import { createColumnHelper } from '@tanstack/react-table';
import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ActionMenu from '../../components/ActionMenu';
import ConfirmationModal from '../../components/ConfirmationModal';
import DataTable from '../../components/DataTable';
import DateSelect from '../../components/DateSelect';
import Navbar from '../../components/Navbar';
import PageHeader from '../../components/PageHeader';
import PlansTab from '../../components/PlansTab';
import PlatformInventoryItemsTab from '../../components/PlatformInventoryItemsTab';
import PlatformMedicinesTab from '../../components/PlatformMedicinesTab';
import ProfileModal from '../../components/ProfileModal';
import Sidebar from '../../components/Sidebar';
import { SkeletonTable, SkeletonDashboard } from '../../components/Skeleton';
import { useToast } from '../../context/ToastContext';
import { API_BASE_URL } from '../../services/apiService';
import authService from '../../services/authService';
import platformService from '../../services/platformService';
import statsCache from '../../services/platformStatsCache';
import { extractApiError } from '../../utils/apiError';
import { backdropProps } from '../../utils/modalA11y';
import { validateForm } from '../../utils/validation';

/**
 * PlatformDashboard - Super Admin dashboard
 *
 * This page allows Super Admin to:
 * - View all hospitals
 * - Create new hospitals
 * - Activate/deactivate hospitals
 *
 * @author HMS Team
 * @version Phase-1
 */
// BUG-015: Named constant – single source of truth for platform list page size
const PLATFORM_PAGE_SIZE = 10;

// Compact single-card summary (Total/Active/Inactive) for one business type
// (Hospitals, Clinics, or Pharmacies). One component instead of three
// near-identical cards per type so the counts can't drift out of sync again,
// and one card per type instead of three keeps the Overview from sprawling.
const STATS_ACCENTS = {
  blue: { icon: 'text-blue-600', iconBg: 'bg-blue-100' },
  purple: { icon: 'text-purple-600', iconBg: 'bg-purple-100' },
  amber: { icon: 'text-amber-600', iconBg: 'bg-amber-100' },
};

const TYPE_ICON_PATHS = {
  blue: 'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
  purple:
    'M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4',
  amber: 'M19 14l-7 7m0 0l-7-7m7 7V3',
};

const TypeStatsCard = ({ label, stats, accent }) => {
  const { total = 0, active = 0, inactive = 0 } = stats || {};
  const { icon, iconBg } = STATS_ACCENTS[accent] || STATS_ACCENTS.blue;

  return (
    <div className="bg-white border border-gray-200 rounded-lg p-4 hover:shadow-sm transition-shadow duration-200">
      <div className="flex items-center gap-2 mb-3">
        <div
          className={`w-7 h-7 ${iconBg} rounded-md flex items-center justify-center flex-shrink-0`}
        >
          <svg className={`w-4 h-4 ${icon}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d={TYPE_ICON_PATHS[accent] || TYPE_ICON_PATHS.blue}
            />
          </svg>
        </div>
        <p className="text-sm font-semibold text-gray-700">{label}</p>
      </div>
      <div className="flex items-stretch divide-x divide-gray-100">
        <div className="flex-1 text-center">
          <p className="text-xl font-bold text-gray-900">{total}</p>
          <p className="text-[11px] text-gray-500 uppercase tracking-wide">Total</p>
        </div>
        <div className="flex-1 text-center">
          <p className="text-xl font-bold text-green-600">{active}</p>
          <p className="text-[11px] text-gray-500 uppercase tracking-wide">Active</p>
        </div>
        <div className="flex-1 text-center">
          <p className={`text-xl font-bold ${inactive > 0 ? 'text-red-600' : 'text-gray-400'}`}>
            {inactive}
          </p>
          <p className="text-[11px] text-gray-500 uppercase tracking-wide">Inactive</p>
        </div>
      </div>
    </div>
  );
};

const PlatformDashboard = () => {
  const navigate = useNavigate();
  const { success } = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const activeTab = searchParams.get('tab') || 'dashboard';

  // Which sidebar groups are currently expanded. Auto-expands the group that
  // owns the active subtab so a page refresh keeps the correct group open.
  const [expandedGroups, setExpandedGroups] = useState(() => {
    if (activeTab.includes(':')) {
      return { [activeTab.split(':')[0]]: true };
    }
    return {};
  });

  // Toggle a sidebar group open/closed (does NOT change the active tab)
  const toggleGroup = (groupId) => {
    setExpandedGroups((prev) => ({ ...prev, [groupId]: !prev[groupId] }));
  };

  // Helper to switch tabs. When navigating to a subtab, ensure its parent
  // group is expanded so the selection stays visible.
  const setActiveTab = (tab) => {
    if (tab && tab.includes(':')) {
      const group = tab.split(':')[0];
      setExpandedGroups((prev) => ({ ...prev, [group]: true }));
    }
    setSearchParams({ tab });
  };

  // Helper: Parse tab string in format "group:subtab" or top-level tab
  const parseTab = (tab) => {
    if (!tab) return { isTopLevel: true, tab: 'dashboard' };
    if (tab.includes(':')) {
      const [group, subtab] = tab.split(':');
      return { isTopLevel: false, group, subtab };
    }
    return { isTopLevel: true, tab };
  };

  // Helper: Convert group name to hospital type
  const getHospitalTypeFromGroup = (group) => {
    if (group === 'clinic') return 'CLINIC';
    if (group === 'pharmacy') return 'PHARMACY';
    if (group === 'hospital') return 'HOSPITAL';
    return null;
  };

  // Helper: Get entity type from activeTab (backward compatible)
  const getEntityType = (tab) => {
    const parsed = parseTab(tab);
    if (parsed.isTopLevel) return 'HOSPITAL';
    const type = getHospitalTypeFromGroup(parsed.group);
    return type || 'HOSPITAL';
  };

  // Helper: Get current hospitalType for props
  const getCurrentHospitalType = () => {
    const parsed = parseTab(activeTab);
    if (parsed.isTopLevel) return null;
    return getHospitalTypeFromGroup(parsed.group);
  };

  // BUG-013: Centralised error extractor – all catch blocks must use this.
  const extractError = (err, fallback) => {
    const d = err?.response?.data;
    if (!d) return fallback;
    if (typeof d === 'string') return d;
    if (d.errors && typeof d.errors === 'object') {
      return Object.values(d.errors).join(', ');
    }
    return d.message || d.error || d.detail || fallback;
  };

  const [hospitals, setHospitals] = useState([]);
  const [hospitalPage, setHospitalPage] = useState({
    content: [],
    totalPages: 0,
    totalElements: 0,
    number: 0,
    size: 10,
  });

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Confirmation Modal State
  const [confirmModal, setConfirmModal] = useState({
    isOpen: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  const [availablePlans, setAvailablePlans] = useState([]);

  const [formData, setFormData] = useState({
    hospitalName: '',
    adminName: '',
    adminEmail: '',
    adminPassword: '',
    type: 'HOSPITAL',
    planPublicId: '',
    billingPeriod: 'MONTHLY',
    isSingleDoctor: false,
  });
  const [errors, setErrors] = useState({}); // Field-level errors

  // Audit Logs State
  const [auditLogs, setAuditLogs] = useState([]);

  // Sidebar collapse state
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // Overview Stats State — per business type (hospitals/clinics/pharmacies are
  // separate HospitalType-discriminated rows in the same table; keep their
  // counts segmented rather than merged)
  const emptyTypeStats = { total: 0, active: 0, inactive: 0 };
  const [hospitalStats, setHospitalStats] = useState({
    hospitals: emptyTypeStats,
    clinics: emptyTypeStats,
    pharmacies: emptyTypeStats,
  });

  // UI State - removed local activeTab state, now using URL params
  // const [activeTab, setActiveTab] = useState('dashboard');

  // Edit Hospital State
  const [editHospitalModal, setEditHospitalModal] = useState({
    isOpen: false,
    hospital: null,
    name: '',
    adminEmail: '',
    adminName: '',
    isSingleDoctor: false,
    planName: '',
    billingPeriod: '',
    assignedAt: null,
    expiresAt: null,
    subscriptionStatus: '',
    newPlanPublicId: '',
    newBillingPeriod: 'MONTHLY',
    availablePlansForEdit: [],
  });

  // Password Reset Modal State
  const [passwordModal, setPasswordModal] = useState({
    isOpen: false,
    email: '',
    password: '',
  });

  // Profile Modal State
  const [profileOpen, setProfileOpen] = useState(false);

  // Tickets State
  const [tickets, setTickets] = useState([]);
  const [ticketsLoading, setTicketsLoading] = useState(false);
  const [viewTicket, setViewTicket] = useState(null); // ticket whose full details are open

  // FAQs State
  const [faqs, setFaqs] = useState([]);
  const [faqsLoading, setFaqsLoading] = useState(false);
  const [showFaqModal, setShowFaqModal] = useState(false);
  const [faqForm, setFaqForm] = useState({ question: '', answer: '' });
  const [faqSubmitting, setFaqSubmitting] = useState(false);

  // Set Password Modal State (for Reset Password flow)
  const [resetPwModal, setResetPwModal] = useState({ isOpen: false, hospitalId: null });

  // BUG-014: AbortController refs so stale in-flight requests are cancelled
  // when the user switches tabs quickly.
  const hospitalsAbortRef = useRef(null);
  const auditAbortRef = useRef(null);

  // Load data based on active tab
  useEffect(() => {
    const parsed = parseTab(activeTab);
    const subtab = parsed.isTopLevel ? parsed.tab : activeTab.split(':')[1];

    if (activeTab === 'dashboard') {
      loadHospitals();
      loadHospitalStats();
    } else if (subtab === 'hospitals' || subtab === 'clinics' || subtab === 'pharmacies') {
      // BUG-015: use PLATFORM_PAGE_SIZE constant instead of hard-coded 10
      loadHospitals(0, PLATFORM_PAGE_SIZE, getEntityType(activeTab));
    } else if (subtab === 'audit_logs') {
      loadAuditLogs();
    } else if (subtab === 'tickets') {
      loadTickets();
    } else if (subtab === 'faqs') {
      loadFaqs();
    }
  }, [activeTab]);

  // BUG-015: default size uses PLATFORM_PAGE_SIZE constant
  // BUG-014: AbortController cancels in-flight request on re-invocation
  const loadHospitals = async (page = 0, size = PLATFORM_PAGE_SIZE, type = 'HOSPITAL') => {
    // Cancel any previous in-flight hospitals request
    if (hospitalsAbortRef.current) {
      hospitalsAbortRef.current.abort();
    }
    const controller = new AbortController();
    hospitalsAbortRef.current = controller;

    try {
      setLoading(true);
      const data = await platformService.getHospitals(page, size, type, {
        signal: controller.signal,
      });
      if (data.content) {
        setHospitals(data.content);
        setHospitalPage(data);
      } else {
        setHospitals(data);
        setHospitalPage({
          content: data,
          totalPages: 1,
          totalElements: data.length,
          number: 0,
          size: data.length,
        });
      }
    } catch (err) {
      if (err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED') return; // aborted – ignore
      // BUG-013: use extractError for consistent server-side message propagation
      setError(extractError(err, 'Failed to load hospitals'));
    } finally {
      setLoading(false);
    }
  };

  // BUG-014: AbortController cancels in-flight audit request on re-invocation
  const loadAuditLogs = async () => {
    if (auditAbortRef.current) {
      auditAbortRef.current.abort();
    }
    const controller = new AbortController();
    auditAbortRef.current = controller;

    try {
      setLoading(true);
      const data = await platformService.getAuditLogs({ signal: controller.signal });
      setAuditLogs(data);
    } catch (err) {
      if (err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED') return;
      // BUG-013: use extractError
      setError(extractError(err, 'Failed to load audit logs'));
    } finally {
      setLoading(false);
    }
  };

  // BUG-027: Cache hospital stats for 60 seconds to avoid redundant fetches
  // on every dashboard-tab visit.
  const loadHospitalStats = async () => {
    if (statsCache.isValid()) {
      setHospitalStats(statsCache.data);
      return;
    }
    try {
      const stats = await platformService.getHospitalStats();
      statsCache.set(stats);
      setHospitalStats(stats);
    } catch (err) {
      // BUG-013: use extractError
      setError(extractError(err, 'Failed to load hospital statistics'));
    }
  };

  const loadTickets = async () => {
    setTicketsLoading(true);
    try {
      const data = await platformService.getTickets(getCurrentHospitalType());
      setTickets(data);
    } catch {
      setTickets([]); // graceful fallback if endpoint not yet live
    } finally {
      setTicketsLoading(false);
    }
  };

  // Keep live pointers to the loaders so the mount-once WebSocket effect below always calls the
  // current closure (fresh activeTab / hospital type / page), avoiding a stale capture.
  const reloadTicketsRef = useRef(() => {});
  const reloadTenantsRef = useRef(() => {});
  useEffect(() => {
    reloadTicketsRef.current = () => {
      loadTickets();
    };
    // Reload the tenant list the Super Admin is actually looking at — same page, same type —
    // so a live refresh never yanks them back to page 1 of a different group.
    reloadTenantsRef.current = () => {
      // Whatever changed elsewhere may have moved the counts too, so drop the cached
      // overview as well as reloading the list.
      statsCache.clear();
      loadHospitals(hospitalPage.number, hospitalPage.size, getEntityType(activeTab));
      if (activeTab === 'dashboard') loadHospitalStats();
    };
  });

  // Real-time: the platform (Super Admin) connects to the reserved channel (/ws/hospital/0).
  //   NEW_TICKET   — a tenant filed a ticket.
  //   REFRESH_DATA — a tenant was created/blocked/updated/deleted, or a plan was assigned or
  //                  edited. Previously the platform listened for NEW_TICKET and nothing else,
  //                  so its own tenant list (including the Plan column) went stale the moment a
  //                  second Super Admin tab — or this one's own plan edit — changed anything.
  // Best-effort: a socket failure just falls back to manual reload.
  useEffect(() => {
    const token = sessionStorage.getItem('token');
    if (!token) return;
    let ws;
    try {
      const base = API_BASE_URL || '';
      const wsBase = base.startsWith('http')
        ? base.replace(/^http/, 'ws')
        : `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}${base.startsWith('/') ? '' : '/'}${base}`;
      ws = new WebSocket(`${wsBase}/ws/hospital/0?token=${encodeURIComponent(token)}`);
      ws.onmessage = (evt) => {
        try {
          const data = JSON.parse(evt.data);
          if (data?.type === 'NEW_TICKET') reloadTicketsRef.current();
          else if (data?.type === 'REFRESH_DATA') reloadTenantsRef.current();
        } catch {
          /* ignore malformed frames */
        }
      };
    } catch {
      /* WebSocket unsupported/unavailable — silently skip */
    }
    return () => {
      try {
        ws && ws.close();
      } catch {
        /* noop */
      }
    };
  }, []);

  const [resolvingTicketId, setResolvingTicketId] = useState(null);
  const handleResolveTicket = async (ticketId) => {
    if (resolvingTicketId) return;
    setResolvingTicketId(ticketId);
    try {
      await platformService.resolveTicket(ticketId, getCurrentHospitalType());
      loadTickets();
    } catch {
      setError('Failed to resolve ticket');
    } finally {
      setResolvingTicketId(null);
    }
  };

  const loadFaqs = async () => {
    setFaqsLoading(true);
    try {
      const data = await platformService.getPlatformFaqs(getCurrentHospitalType());
      setFaqs(data || []);
    } catch {
      setError('Failed to load FAQs');
    } finally {
      setFaqsLoading(false);
    }
  };

  const handleCreateFaq = async (e) => {
    e.preventDefault();
    if (!faqForm.question.trim() || !faqForm.answer.trim()) {
      setError('Question and Answer are required');
      return;
    }
    setFaqSubmitting(true);
    try {
      await platformService.addFaq(faqForm, getCurrentHospitalType());
      success('FAQ added successfully');
      setShowFaqModal(false);
      setFaqForm({ question: '', answer: '' });
      loadFaqs();
    } catch (err) {
      setError(extractError(err, 'Failed to add FAQ'));
    } finally {
      setFaqSubmitting(false);
    }
  };

  const handleDeleteFaq = (faqId) => {
    openConfirmation(
      'Delete FAQ',
      'Are you sure you want to delete this FAQ? This action cannot be undone.',
      async () => {
        try {
          await platformService.deleteFaq(faqId, getCurrentHospitalType());
          setConfirmModal((prev) => ({ ...prev, isOpen: false }));
          success('FAQ deleted successfully');
          loadFaqs();
        } catch (err) {
          // BUG-013: propagate server error message
          setError(extractError(err, 'Failed to delete FAQ'));
        }
      }
    );
  };

  const handleLogout = () => {
    authService.logout();
    navigate('/platform/login');
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
      onConfirm: action,
      showReasonInput,
      inputPlaceholder,
    });
  };

  const handleCreateHospital = async (e) => {
    e.preventDefault();
    setError('');
    setErrors({});

    const rules = {
      hospitalName: ['required'],
      adminName: ['required', 'name'],
      adminEmail: ['required', 'email'],
      adminPassword: ['required', 'password'],
      planPublicId: ['required'],
    };

    const validationErrors = validateForm(formData, rules);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    const subtabForCreate = activeTab.split(':')[1];
    const entityLabel =
      subtabForCreate === 'clinics'
        ? 'Clinic'
        : subtabForCreate === 'pharmacies'
          ? 'Pharmacy'
          : 'Hospital';
    openConfirmation(
      `Create ${entityLabel}`,
      `Are you sure you want to create this new ${entityLabel.toLowerCase()}?`,
      async () => {
        try {
          await platformService.createHospital(formData);
          statsCache.clear();
          setShowCreateModal(false);
          setFormData({
            hospitalName: '',
            adminName: '',
            adminEmail: '',
            adminPassword: '',
            type: getEntityType(activeTab),
            planPublicId: '',
            billingPeriod: 'MONTHLY',
            isSingleDoctor: false,
          });
          loadHospitals(0, 10, getEntityType(activeTab));
        } catch (err) {
          setError(extractError(err, 'Failed to create hospital'));
        }
      }
    );
  };

  const handleToggleStatus = async (id, currentStatus) => {
    const action = currentStatus ? 'Deactivate' : 'Activate';
    openConfirmation(
      `${action} Hospital`,
      `Are you sure you want to ${action.toLowerCase()} this hospital?`,
      async (reason) => {
        try {
          await platformService.updateHospitalStatus(id, !currentStatus, reason);
          // The active/inactive split on the overview just changed.
          statsCache.clear();
          // BUG-015: use PLATFORM_PAGE_SIZE constant
          loadHospitals(0, PLATFORM_PAGE_SIZE, getEntityType(activeTab));
        } catch (err) {
          // BUG-013: propagate server error message
          setError(extractError(err, 'Failed to update hospital status'));
        }
      },
      true, // Require reason
      `Why are you ${action.toLowerCase().replace(/e$/, '')}ing this hospital?`
    );
  };

  const handleHospitalUpdate = async () => {
    try {
      const hospitalId = editHospitalModal.hospital.publicId || editHospitalModal.hospital.id;

      await platformService.updateHospitalDetails(
        hospitalId,
        editHospitalModal.name,
        editHospitalModal.adminEmail,
        editHospitalModal.adminName,
        '',
        editHospitalModal.isSingleDoctor
      );

      if (editHospitalModal.newPlanPublicId) {
        await platformService.assignPlan(
          editHospitalModal.newPlanPublicId,
          hospitalId,
          editHospitalModal.newBillingPeriod
        );
      }

      statsCache.clear();
      success('Updated successfully');
      setEditHospitalModal((prev) => ({ ...prev, isOpen: false }));
      loadHospitals(hospitalPage.number, hospitalPage.size, getEntityType(activeTab));
    } catch (err) {
      setError(extractError(err, 'Failed to update'));
    }
  };

  const openEditHospitalModal = async (hospital) => {
    try {
      const details = await platformService.getHospitalById(hospital.publicId || hospital.id);
      const plans = await platformService.getPlans(details.type || getEntityType(activeTab));
      setEditHospitalModal({
        isOpen: true,
        hospital: details,
        name: details.name,
        adminEmail: details.adminEmail || '',
        adminName: details.adminName || '',
        isSingleDoctor: details.isSingleDoctor || false,
        planName: details.planName || '—',
        billingPeriod: details.billingPeriod || '—',
        assignedAt: details.assignedAt,
        expiresAt: details.expiresAt,
        subscriptionStatus: details.subscriptionStatus || 'ACTIVE',
        newPlanPublicId: '',
        newBillingPeriod: 'MONTHLY',
        availablePlansForEdit: plans.filter((p) => p.isActive !== false),
      });
    } catch (err) {
      // BUG-013: propagate server error message
      setError(extractError(err, 'Failed to fetch hospital details'));
    }
  };

  const openCreateModal = async () => {
    const type = getEntityType(activeTab);
    setFormData((prev) => ({ ...prev, type, planPublicId: '', billingPeriod: 'MONTHLY' }));
    setError('');
    setErrors({});
    try {
      const plans = await platformService.getPlans(type);
      setAvailablePlans(plans.filter((p) => p.isActive !== false));
    } catch {
      setAvailablePlans([]);
    }
    setShowCreateModal(true);
  };

  const handleDeleteHospital = (id, name) => {
    openConfirmation(
      'Delete Hospital',
      `Permanently delete "${name}"? This will remove all patients, staff, billing records, and data. This cannot be undone.`,
      async () => {
        try {
          await platformService.deleteHospital(id);
          // BUG-027: invalidate stats cache after deletion
          statsCache.clear();
          success('Hospital deleted successfully');
          // BUG-015: use PLATFORM_PAGE_SIZE constant
          loadHospitals(0, PLATFORM_PAGE_SIZE, getEntityType(activeTab));
        } catch (err) {
          setError(extractError(err, 'Failed to delete hospital'));
        }
      }
    );
  };

  const handleResetPassword = (id) => {
    setResetPwModal({ isOpen: true, hospitalId: id });
  };

  const handleUserResetPassword = (id) => {
    openConfirmation(
      'Reset User Password',
      'Are you sure you want to reset the password for this user?',
      async () => {
        try {
          const data = await platformService.resetUserPassword(id);
          setPasswordModal({
            isOpen: true,
            email: data.email,
            password: data.password,
          });
          success('Password reset successfully');
        } catch (err) {
          setError('Failed to reset password');
        }
      }
    );
  };

  const user = authService.getCurrentUser();

  const tabs = [
    { id: 'dashboard', label: 'Dashboard', isTopLevel: true },
    {
      id: 'hospital',
      label: 'Hospital',
      group: true,
      isExpanded: !!expandedGroups['hospital'],
      subItems: [
        { id: 'hospital:hospitals', label: 'Hospitals' },
        { id: 'hospital:inventory_items', label: 'Inventory Items' },
        { id: 'hospital:plans', label: 'Plans' },
        { id: 'hospital:tickets', label: 'Tickets' },
        { id: 'hospital:faqs', label: 'FAQs' },
      ],
    },
    {
      id: 'clinic',
      label: 'Clinic',
      group: true,
      isExpanded: !!expandedGroups['clinic'],
      subItems: [
        { id: 'clinic:clinics', label: 'Clinics' },
        { id: 'clinic:inventory_items', label: 'Inventory Items' },
        { id: 'clinic:plans', label: 'Plans' },
        { id: 'clinic:tickets', label: 'Tickets' },
        { id: 'clinic:faqs', label: 'FAQs' },
      ],
    },
    {
      id: 'pharmacy',
      label: 'Pharmacy',
      group: true,
      isExpanded: !!expandedGroups['pharmacy'],
      subItems: [
        { id: 'pharmacy:pharmacies', label: 'Pharmacies' },
        { id: 'pharmacy:plans', label: 'Plans' },
        { id: 'pharmacy:tickets', label: 'Tickets' },
        { id: 'pharmacy:faqs', label: 'FAQs' },
      ],
    },
    // Global medicine catalog — one shared list used by hospital, clinic and
    // pharmacy searches alike, so it lives outside the tenant-type groups.
    { id: 'medicines', label: 'Medicines', isTopLevel: true },
    { id: 'audit_logs', label: 'Audit Logs', isTopLevel: true },
  ];

  // Helper: Get display label for active tab
  const getTabLabel = () => {
    const parsed = parseTab(activeTab);
    if (parsed.isTopLevel) {
      return tabs.find((t) => t.id === parsed.tab)?.label || 'Dashboard';
    }
    // For grouped tabs, find the subtab label
    const group = tabs.find((t) => t.id === parsed.group);
    if (group?.subItems) {
      const subtab = group.subItems.find((st) => st.id === activeTab);
      return subtab?.label || 'Dashboard';
    }
    return 'Dashboard';
  };

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Sidebar */}
      <Sidebar
        title="HMS Portal"
        tabs={tabs}
        activeTab={activeTab}
        onTabChange={setActiveTab}
        onToggleGroup={toggleGroup}
        footerTitle="Platform"
        footerData="Super Admin"
        variant="plain"
        isCollapsed={sidebarCollapsed}
      />

      {/* Main Content Wrapper */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* Navbar */}
        <Navbar
          title={getTabLabel()}
          user={user}
          onLogout={handleLogout}
          onProfile={() => setProfileOpen(true)}
          onToggleSidebar={() => setSidebarCollapsed(!sidebarCollapsed)}
        />

        {/* Main Content Area */}
        <main className="flex-1 overflow-x-hidden overflow-y-auto bg-gray-50 p-6">
          {/* Standardized Header */}
          {activeTab !== 'dashboard' && (
            <div className="mb-6">
              {(() => {
                const parsed = parseTab(activeTab);
                const subtab = parsed.isTopLevel ? parsed.tab : activeTab.split(':')[1];

                // Determine if this is a manageable entity (hospitals, clinics, pharmacies)
                const isEntityTab =
                  subtab === 'hospitals' || subtab === 'clinics' || subtab === 'pharmacies';
                const isFaqTab = subtab === 'faqs';

                // Generate subtitle
                let subtitle = '';
                if (subtab === 'hospitals')
                  subtitle = 'Manage and monitor all registered hospitals on the platform.';
                else if (subtab === 'clinics')
                  subtitle = 'Manage and monitor all registered clinics on the platform.';
                else if (subtab === 'pharmacies')
                  subtitle = 'Manage and monitor all registered pharmacies on the platform.';
                else if (subtab === 'medicines')
                  subtitle = 'Manage the central unified global medicine catalog directory.';
                else if (subtab === 'inventory_items')
                  subtitle = 'Manage the central unified global hospital inventory item directory.';
                else if (subtab === 'tickets')
                  subtitle = 'View and resolve support tickets submitted by hospital admins.';
                else if (isFaqTab)
                  subtitle = 'Manage global frequently asked questions for hospital admins.';
                else
                  subtitle =
                    'Track system activities and administrative actions across the platform.';

                return (
                  <PageHeader
                    title={getTabLabel()}
                    subtitle={subtitle}
                    onAdd={
                      isEntityTab
                        ? openCreateModal
                        : isFaqTab
                          ? () => {
                              setFaqForm({ question: '', answer: '' });
                              setShowFaqModal(true);
                            }
                          : null
                    }
                    addLabel={
                      subtab === 'hospitals'
                        ? 'Create Hospital'
                        : subtab === 'clinics'
                          ? 'Create Clinic'
                          : subtab === 'pharmacies'
                            ? 'Create Pharmacy'
                            : isFaqTab
                              ? 'Add FAQ'
                              : ''
                    }
                  />
                );
              })()}
            </div>
          )}

          {/* Dashboard Tab */}
          {activeTab === 'dashboard' && (
            <div className="space-y-6">
              {/* Welcome Section */}
              <div className="bg-white border border-gray-200 p-6">
                <h1 className="text-2xl font-bold text-gray-900 mb-2">Platform Overview</h1>
                <p className="text-gray-600">
                  Monitor and manage all hospitals from your central dashboard
                </p>
              </div>

              {/* Stats Cards — hospitals/clinics/pharmacies are separate business
                                types and must never be summed into one number (they share a
                                table but are distinct tenants). */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <TypeStatsCard label="Hospitals" stats={hospitalStats.hospitals} accent="blue" />
                <TypeStatsCard label="Clinics" stats={hospitalStats.clinics} accent="purple" />
                <TypeStatsCard label="Pharmacies" stats={hospitalStats.pharmacies} accent="amber" />
              </div>

              {/* Recent Hospitals */}
              <div className="bg-white border border-gray-200">
                <div className="px-6 py-4 border-b border-gray-200">
                  <div className="flex items-center justify-between">
                    <div>
                      <h2 className="text-lg font-bold text-gray-900">Recent Hospitals</h2>
                      <p className="text-gray-600 mt-1">
                        Latest registered hospitals on the platform
                      </p>
                    </div>
                    <button
                      onClick={() => setActiveTab('hospitals')}
                      className="px-4 py-2 bg-gray-900 text-white font-medium hover:bg-gray-700 transition-colors duration-200"
                    >
                      View All
                    </button>
                  </div>
                </div>

                {loading ? (
                  <div className="p-2">
                    <SkeletonTable rows={5} cols={5} />
                  </div>
                ) : hospitals.length > 0 ? (
                  <div className="overflow-x-auto">
                    <table className="min-w-full">
                      <thead className="bg-gray-50">
                        <tr>
                          <th className="px-6 py-3 text-left text-xs font-medium text-gray-600 uppercase">
                            Hospital
                          </th>
                          <th className="px-6 py-3 text-left text-xs font-medium text-gray-600 uppercase">
                            Status
                          </th>
                          <th className="px-6 py-3 text-left text-xs font-medium text-gray-600 uppercase">
                            Plan
                          </th>
                          <th className="px-6 py-3 text-left text-xs font-medium text-gray-600 uppercase">
                            Created
                          </th>
                          <th className="px-6 py-3 text-right text-xs font-medium text-gray-600 uppercase">
                            Actions
                          </th>
                        </tr>
                      </thead>
                      <tbody className="bg-white divide-y divide-gray-200">
                        {hospitals.slice(0, 5).map((hospital) => (
                          <tr key={hospital.id} className="hover:bg-gray-50">
                            <td className="px-6 py-4">
                              <div>
                                <p className="text-sm font-medium text-gray-900">{hospital.name}</p>
                                <p className="text-xs text-gray-600">
                                  ID: {hospital.customId || hospital.id}
                                </p>
                              </div>
                            </td>
                            <td className="px-6 py-4">
                              <span
                                className={`px-2.5 py-1 text-xs font-semibold rounded-full border ${
                                  hospital.isActive
                                    ? 'bg-green-100 text-green-800 border-green-200'
                                    : 'bg-red-100 text-red-700 border-red-200'
                                }`}
                              >
                                {hospital.isActive ? 'Active' : 'Inactive'}
                              </span>
                            </td>
                            {/* The tenant's actual subscribed plan. Plan names are free text the Super
                                                            Admin types when creating a plan, so there are no fixed tiers to colour-code
                                                            against — a neutral badge that shows the real name beats a coloured one that
                                                            has to guess. "—" means no current subscription, which is a data problem worth
                                                            seeing rather than papering over with a default. */}
                            <td className="px-6 py-4">
                              <span className="px-2.5 py-1 text-xs font-semibold rounded-full border bg-gray-100 text-gray-700 border-gray-200">
                                {hospital.planName || '—'}
                              </span>
                            </td>
                            <td className="px-6 py-4 text-sm text-gray-600">
                              {new Date(hospital.createdAt).toLocaleDateString('en-US', {
                                year: 'numeric',
                                month: 'short',
                                day: 'numeric',
                              })}
                            </td>
                            <td className="px-6 py-4 text-right">
                              <button
                                onClick={() =>
                                  handleToggleStatus(
                                    hospital.publicId || hospital.id,
                                    hospital.isActive
                                  )
                                }
                                className={`px-3 py-1 text-xs font-semibold rounded-lg border transition-colors duration-200 ${
                                  hospital.isActive
                                    ? 'bg-red-50 text-red-600 border-red-200 hover:bg-red-100'
                                    : 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100'
                                }`}
                              >
                                {hospital.isActive ? 'Deactivate' : 'Activate'}
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="p-12 text-center">
                    <h3 className="text-lg font-medium text-gray-900 mb-2">No hospitals yet</h3>
                    <p className="text-gray-600 mb-6">
                      Get started by creating your first hospital
                    </p>
                    <button
                      onClick={openCreateModal}
                      className="px-6 py-2 bg-gray-900 text-white font-medium hover:bg-gray-700 transition-colors duration-200"
                    >
                      Create Hospital
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Error Display */}
          {error && (
            <div className="mb-6 bg-red-50 border border-red-300 p-4 rounded-lg">
              <div className="flex items-start">
                <div className="flex-shrink-0">
                  <span className="text-red-700 font-medium">Error:</span>
                </div>
                <div className="ml-3">
                  <p className="text-sm text-red-700">
                    {typeof error === 'string' ? error : JSON.stringify(error)}
                  </p>
                </div>
                <div className="ml-auto pl-3">
                  <button onClick={() => setError('')} className="text-red-600 hover:text-red-900">
                    ×
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Plans Tab */}
          {(() => {
            const subtab = activeTab.split(':')[1];
            const hospitalType = getCurrentHospitalType();
            return subtab === 'plans' && <PlansTab hospitalType={hospitalType} />;
          })()}

          {/* Medicines Tab — global catalog, shared by all tenant types */}
          {activeTab === 'medicines' && <PlatformMedicinesTab hospitalType={null} />}

          {/* Inventory Items Tab */}
          {(() => {
            const subtab = activeTab.split(':')[1];
            const hospitalType = getCurrentHospitalType();
            return (
              subtab === 'inventory_items' && (
                <PlatformInventoryItemsTab hospitalType={hospitalType} />
              )
            );
          })()}

          {/* Content Sections */}
          {(() => {
            const parsed = parseTab(activeTab);
            const subtab = parsed.isTopLevel ? parsed.tab : activeTab.split(':')[1];

            // The global `loading` flag is set ONLY by loadHospitals() and
            // loadAuditLogs(), and it starts as `true`. So it must gate only the
            // subtabs those two fetch for. Every other subtab (plans, tickets,
            // faqs, medicines, inventory_items, …) owns its own loading state and
            // renders its own skeleton — gating them on the global flag pinned a
            // skeleton on screen forever, since nothing ever cleared it.
            // Allowlist, not blocklist: a new subtab must opt IN, so it can't
            // silently inherit a stuck skeleton.
            const usesGlobalLoading =
              subtab === 'dashboard' ||
              subtab === 'hospitals' ||
              subtab === 'clinics' ||
              subtab === 'pharmacies' ||
              subtab === 'audit_logs';

            if (loading && usesGlobalLoading) {
              return <SkeletonDashboard statCount={3} tableRows={6} tableCols={7} />;
            }

            if (subtab === 'hospitals' || subtab === 'clinics' || subtab === 'pharmacies') {
              return (
                <div className="bg-white border border-gray-200">
                  <HospitalsTable
                    hospitals={hospitals}
                    hospitalPage={hospitalPage}
                    handleToggleStatus={handleToggleStatus}
                    openEditHospitalModal={openEditHospitalModal}
                    onResetPassword={handleResetPassword}
                    onDeleteHospital={handleDeleteHospital}
                    loadHospitals={loadHospitals}
                    entityType={getEntityType(activeTab)}
                  />
                </div>
              );
            }

            if (subtab === 'audit_logs') {
              return (
                <div className="bg-white border border-gray-200">
                  {auditLogs.length > 0 ? (
                    <AuditLogsTable auditLogs={auditLogs} />
                  ) : (
                    <div className="p-12 text-center">
                      <h3 className="text-lg font-medium text-gray-900 mb-2">No audit logs yet</h3>
                      <p className="text-gray-600">
                        System activities will appear here once actions are performed
                      </p>
                    </div>
                  )}
                </div>
              );
            }

            if (subtab === 'tickets') {
              return (
                <TicketsTable
                  tickets={tickets}
                  loading={ticketsLoading}
                  onResolve={handleResolveTicket}
                  resolvingId={resolvingTicketId}
                  onView={setViewTicket}
                />
              );
            }

            if (subtab === 'faqs') {
              return <FaqsTable faqs={faqs} loading={faqsLoading} onDelete={handleDeleteFaq} />;
            }

            return null;
          })()}
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

      {/* Ticket Detail Modal — shows the full description the truncated row can't */}
      {viewTicket && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          {...backdropProps(() => {
            setViewTicket(null);
          })}
        >
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[85vh] overflow-y-auto">
            <div className="flex items-start justify-between p-6 border-b border-gray-100">
              <div>
                <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Support Ticket
                </p>
                <h3 className="text-lg font-semibold text-gray-900 mt-1">{viewTicket.subject}</h3>
              </div>
              <button
                onClick={() => setViewTicket(null)}
                className="text-gray-400 hover:text-gray-600 text-2xl leading-none"
              >
                &times;
              </button>
            </div>
            <div className="p-6 space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                    Hospital
                  </p>
                  <p className="text-sm text-gray-900 mt-1">{viewTicket.hospitalName || '—'}</p>
                </div>
                <div>
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                    Submitted By
                  </p>
                  <p className="text-sm text-gray-900 mt-1">{viewTicket.adminName || '—'}</p>
                </div>
                <div>
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                    Priority
                  </p>
                  <p className="text-sm text-gray-900 mt-1">{viewTicket.priority || '—'}</p>
                </div>
                <div>
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                    Status
                  </p>
                  <p className="text-sm text-gray-900 mt-1">
                    {viewTicket.status?.replace('_', ' ') || '—'}
                  </p>
                </div>
                <div className="col-span-2">
                  <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                    Submitted
                  </p>
                  <p className="text-sm text-gray-900 mt-1">
                    {viewTicket.createdAt
                      ? new Date(viewTicket.createdAt).toLocaleString('en-IN', {
                          day: '2-digit',
                          month: 'short',
                          year: 'numeric',
                          hour: '2-digit',
                          minute: '2-digit',
                          hour12: true,
                        })
                      : '—'}
                  </p>
                </div>
              </div>
              <div>
                <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
                  Description
                </p>
                <p className="text-sm text-gray-800 mt-1 whitespace-pre-wrap break-words">
                  {viewTicket.message || '—'}
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-2 p-6 border-t border-gray-100">
              {viewTicket.status !== 'RESOLVED' && (
                <button
                  onClick={() => {
                    handleResolveTicket(viewTicket.id);
                    setViewTicket(null);
                  }}
                  className="px-4 h-10 text-sm font-semibold bg-green-50 text-green-700 border border-green-200 rounded-xl hover:bg-green-100 transition-colors"
                >
                  Mark Resolved
                </button>
              )}
              <button
                onClick={() => setViewTicket(null)}
                className="px-4 h-10 text-sm font-semibold bg-gray-900 text-white rounded-xl hover:bg-gray-700 transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Password Reset Display Modal */}
      {passwordModal.isOpen && (
        <PasswordResetModal
          email={passwordModal.email}
          password={passwordModal.password}
          onClose={() => setPasswordModal({ isOpen: false, email: '', password: '' })}
        />
      )}

      {/* Profile Modal */}
      {profileOpen && <SuperAdminProfileModal user={user} onClose={() => setProfileOpen(false)} />}

      {/* Set Password Modal (Admin-defined password reset) */}
      {resetPwModal.isOpen && (
        <SetPasswordModal
          hospitalId={resetPwModal.hospitalId}
          onClose={() => setResetPwModal({ isOpen: false, hospitalId: null })}
          onSuccess={() => {
            setResetPwModal({ isOpen: false, hospitalId: null });
            success('Password reset successfully');
          }}
        />
      )}

      {/* Create Hospital Modal */}
      {showCreateModal &&
        (() => {
          const subtab = activeTab.split(':')[1];
          const isClinic = subtab === 'clinics';
          const isPharmacy = subtab === 'pharmacies';
          const entityName = isClinic ? 'clinic' : isPharmacy ? 'pharmacy' : 'hospital';
          const entityNameCap = entityName.charAt(0).toUpperCase() + entityName.slice(1);

          return (
            <div
              className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
              {...backdropProps(() => {
                setShowCreateModal(false);
              })}
            >
              <div className="bg-white border border-gray-200 w-full max-w-lg max-h-[90vh] overflow-y-auto">
                <div className="p-6">
                  <div className="mb-6">
                    <h2 className="text-xl font-bold text-gray-900 mb-2">
                      Create New {entityNameCap}
                    </h2>
                    <p className="text-gray-600">
                      Add a new {entityName} to the platform with admin credentials
                    </p>
                  </div>

                  <form onSubmit={handleCreateHospital} className="space-y-4">
                    <div>
                      <label className="block text-sm font-medium text-gray-900 mb-2">
                        {entityNameCap} Name
                      </label>
                      <input
                        type="text"
                        value={formData.hospitalName}
                        onChange={(e) => {
                          setFormData({ ...formData, hospitalName: e.target.value });
                          if (errors.hospitalName) setErrors({ ...errors, hospitalName: null });
                        }}
                        placeholder={`Enter ${entityName} name`}
                        className={`w-full px-3 py-2 bg-white border text-gray-900 placeholder-gray-500 focus:border-gray-900 ${errors.hospitalName ? 'border-red-400 bg-red-50' : 'border-gray-200'}`}
                      />
                      {errors.hospitalName && (
                        <p className="text-red-600 text-sm font-medium mt-1">
                          {errors.hospitalName}
                        </p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="fld-235"
                        className="block text-sm font-medium text-gray-900 mb-2"
                      >
                        Admin Name
                      </label>
                      <input
                        id="fld-235"
                        type="text"
                        value={formData.adminName}
                        onChange={(e) => {
                          setFormData({ ...formData, adminName: e.target.value });
                          if (errors.adminName) setErrors({ ...errors, adminName: null });
                        }}
                        placeholder="Enter admin full name"
                        className={`w-full px-3 py-2 bg-white border text-gray-900 placeholder-gray-500 focus:border-gray-900 ${errors.adminName ? 'border-red-400 bg-red-50' : 'border-gray-200'}`}
                      />
                      {errors.adminName && (
                        <p className="text-red-600 text-sm font-medium mt-1">{errors.adminName}</p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="fld-234"
                        className="block text-sm font-medium text-gray-900 mb-2"
                      >
                        Admin Email
                      </label>
                      <input
                        id="fld-234"
                        type="email"
                        value={formData.adminEmail}
                        onChange={(e) => {
                          setFormData({ ...formData, adminEmail: e.target.value });
                          if (errors.adminEmail) setErrors({ ...errors, adminEmail: null });
                        }}
                        placeholder={`admin@${entityName}.com`}
                        className={`w-full px-3 py-2 bg-white border text-gray-900 placeholder-gray-500 focus:border-gray-900 ${errors.adminEmail ? 'border-red-400 bg-red-50' : 'border-gray-200'}`}
                      />
                      {errors.adminEmail && (
                        <p className="text-red-600 text-sm font-medium mt-1">{errors.adminEmail}</p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="fld-233"
                        className="block text-sm font-medium text-gray-900 mb-2"
                      >
                        Admin Password
                      </label>
                      <input
                        id="fld-233"
                        type="password"
                        value={formData.adminPassword}
                        onChange={(e) => {
                          setFormData({ ...formData, adminPassword: e.target.value });
                          if (errors.adminPassword) setErrors({ ...errors, adminPassword: null });
                        }}
                        placeholder="Create secure password"
                        className={`w-full px-3 py-2 bg-white border text-gray-900 placeholder-gray-500 focus:border-gray-900 ${errors.adminPassword ? 'border-red-400 bg-red-50' : 'border-gray-200'}`}
                      />
                      {errors.adminPassword && (
                        <p className="text-red-600 text-sm font-medium mt-1">
                          {errors.adminPassword}
                        </p>
                      )}
                    </div>

                    {/* Plan Selection — required */}
                    <div>
                      <span className="block text-sm font-medium text-gray-700 mb-1">
                        Plan <span className="text-red-500">*</span>
                      </span>
                      {availablePlans.length === 0 ? (
                        <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-800">
                          No plans found for {formData.type}. Please create a plan in the Plans tab
                          first before adding a {formData.type.toLowerCase()}.
                        </div>
                      ) : (
                        <select
                          value={formData.planPublicId}
                          onChange={(e) =>
                            setFormData((p) => ({ ...p, planPublicId: e.target.value }))
                          }
                          className={`w-full border rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 ${errors.planPublicId ? 'border-red-400 bg-red-50' : 'border-gray-300'}`}
                        >
                          <option value="">-- Select a plan --</option>
                          {availablePlans.map((p) => (
                            <option key={p.publicId} value={p.publicId}>
                              {p.name} — ₹
                              {formData.billingPeriod === 'MONTHLY'
                                ? p.monthlyPrice
                                : p.yearlyPrice}
                            </option>
                          ))}
                        </select>
                      )}
                      {errors.planPublicId && (
                        <p className="text-red-600 text-sm font-medium mt-1">
                          {errors.planPublicId}
                        </p>
                      )}
                    </div>

                    {/* Billing Period — always shown */}
                    <div>
                      <span className="block text-sm font-medium text-gray-700 mb-2">
                        Billing Period <span className="text-red-500">*</span>
                      </span>
                      <div className="flex gap-4">
                        {['MONTHLY', 'YEARLY'].map((period) => (
                          <label key={period} className="flex items-center gap-2 cursor-pointer">
                            <input
                              type="radio"
                              name="billingPeriod"
                              value={period}
                              checked={formData.billingPeriod === period}
                              onChange={() => setFormData((p) => ({ ...p, billingPeriod: period }))}
                            />
                            <span className="text-sm text-gray-700">
                              {period === 'MONTHLY' ? 'Monthly' : 'Yearly'}
                            </span>
                          </label>
                        ))}
                      </div>
                    </div>

                    {/* Doesn't apply to Pharmacy — pharmacies have no doctors */}
                    {!isPharmacy && (
                      <div className="p-3 bg-gray-50 border border-gray-200 rounded-lg">
                        <label
                          htmlFor="create-single-doctor"
                          className="flex items-center space-x-2.5 cursor-pointer"
                        >
                          <input
                            id="create-single-doctor"
                            type="checkbox"
                            aria-label="Single Doctor Clinic"
                            checked={formData.isSingleDoctor}
                            onChange={(e) => {
                              setFormData({ ...formData, isSingleDoctor: e.target.checked });
                            }}
                            className="w-4 h-4 rounded text-gray-900 border-gray-300 focus:ring-gray-900"
                          />
                          <div>
                            <span className="text-sm font-bold text-gray-900">
                              Single Doctor {entityNameCap}
                            </span>
                            <p className="text-xs text-gray-500 mt-0.5">
                              Enable unified doctor-admin dashboards for single-doctor {entityName}
                              s.
                            </p>
                          </div>
                        </label>
                      </div>
                    )}

                    <div className="flex gap-3 pt-4 border-t border-gray-200">
                      <button
                        type="button"
                        onClick={() => setShowCreateModal(false)}
                        className="flex-1 bg-gray-200 text-gray-900 px-4 py-2 font-medium hover:bg-gray-300"
                      >
                        Cancel
                      </button>
                      <button
                        type="submit"
                        className="flex-1 bg-gray-900 text-white px-4 py-2 font-medium hover:bg-gray-700"
                      >
                        Create {entityNameCap}
                      </button>
                    </div>
                  </form>
                </div>
              </div>
            </div>
          );
        })()}

      {/* Create FAQ Modal */}
      {showFaqModal && (
        <div
          className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
          {...backdropProps(() => {
            setShowFaqModal(false);
          })}
        >
          <div className="bg-white rounded-2xl border border-gray-200 w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <div className="p-6">
              <div className="mb-6 flex justify-between items-center">
                <div>
                  <h2 className="text-xl font-bold text-gray-900 mb-1">Add Global FAQ</h2>
                  <p className="text-sm text-gray-500">
                    Create an FAQ that will be visible to all hospital admins.
                  </p>
                </div>
                <button
                  onClick={() => setShowFaqModal(false)}
                  className="p-1 hover:bg-neutral-100 rounded-lg text-neutral-400 hover:text-neutral-600 transition-colors"
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

              <form onSubmit={handleCreateFaq} className="space-y-4">
                <div>
                  <label
                    htmlFor="fld-232"
                    className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5"
                  >
                    Question
                  </label>
                  <input
                    id="fld-232"
                    type="text"
                    value={faqForm.question}
                    onChange={(e) => setFaqForm((prev) => ({ ...prev, question: e.target.value }))}
                    placeholder="e.g. How do I configure my billing settings?"
                    required
                    className="w-full px-3.5 py-2 border border-neutral-200 rounded-xl text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent text-slate-800 placeholder-slate-400"
                  />
                </div>

                <div>
                  <label
                    htmlFor="fld-231"
                    className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-1.5"
                  >
                    Answer
                  </label>
                  <textarea
                    id="fld-231"
                    value={faqForm.answer}
                    onChange={(e) => setFaqForm((prev) => ({ ...prev, answer: e.target.value }))}
                    placeholder="Provide a detailed, helpful answer..."
                    rows={6}
                    required
                    className="w-full px-3.5 py-2 border border-neutral-200 rounded-xl text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent text-slate-800 placeholder-slate-400 resize-none"
                  ></textarea>
                </div>

                <div className="flex gap-3 justify-end pt-4 border-t border-neutral-100">
                  <button
                    type="button"
                    onClick={() => setShowFaqModal(false)}
                    className="px-4 py-2 text-sm font-semibold text-neutral-600 hover:bg-neutral-50 rounded-xl border border-neutral-200 transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={faqSubmitting}
                    className="px-4 py-2 bg-gray-900 hover:bg-gray-800 text-white font-semibold text-sm rounded-xl transition-all duration-300 flex items-center gap-2 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-900 disabled:opacity-50"
                  >
                    {faqSubmitting ? (
                      <>
                        <div className="animate-spin rounded-full h-4 w-4 border-b-2 border-white"></div>
                        Adding...
                      </>
                    ) : (
                      'Add FAQ'
                    )}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* Edit Hospital Modal */}
      {editHospitalModal.isOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
          {...backdropProps(() => {
            setEditHospitalModal({ ...editHospitalModal, isOpen: false });
          })}
        >
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg max-h-[90vh] overflow-y-auto">
            <div className="p-8">
              <div className="text-center mb-8">
                <div className="w-16 h-16 bg-gray-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
                  <svg
                    className="w-8 h-8 text-gray-700"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                    />
                  </svg>
                </div>
                <h3 className="text-2xl font-bold text-gray-900 mb-2">
                  Edit{' '}
                  {editHospitalModal.hospital?.type === 'CLINIC'
                    ? 'Clinic'
                    : editHospitalModal.hospital?.type === 'PHARMACY'
                      ? 'Pharmacy'
                      : 'Hospital'}
                </h3>
                <p className="text-gray-600">
                  Update settings for{' '}
                  <span className="font-semibold text-gray-900">
                    {editHospitalModal.hospital?.name}
                  </span>
                </p>
              </div>

              <div className="space-y-6">
                <div>
                  <label className="block text-sm font-semibold text-gray-700 mb-2">
                    {editHospitalModal.hospital?.type === 'CLINIC'
                      ? 'Clinic Name'
                      : editHospitalModal.hospital?.type === 'PHARMACY'
                        ? 'Pharmacy Name'
                        : 'Hospital Name'}
                  </label>
                  <input
                    type="text"
                    value={editHospitalModal.name}
                    onChange={(e) =>
                      setEditHospitalModal({ ...editHospitalModal, name: e.target.value })
                    }
                    className="w-full px-4 py-3 bg-gray-50 border-2 border-gray-200 rounded-xl text-gray-900 focus:bg-white focus:border-gray-900 focus:ring-4 focus:ring-gray-100 transition-all duration-200"
                  />
                </div>

                <div>
                  <label
                    htmlFor="fld-230"
                    className="block text-sm font-semibold text-gray-700 mb-2"
                  >
                    Admin Name
                  </label>
                  <input
                    id="fld-230"
                    type="text"
                    value={editHospitalModal.adminName}
                    onChange={(e) =>
                      setEditHospitalModal({ ...editHospitalModal, adminName: e.target.value })
                    }
                    className="w-full px-4 py-3 bg-gray-50 border-2 border-gray-200 rounded-xl text-gray-900 focus:bg-white focus:border-gray-900 focus:ring-4 focus:ring-gray-100 transition-all duration-200"
                  />
                </div>

                <div>
                  <label
                    htmlFor="fld-229"
                    className="block text-sm font-semibold text-gray-700 mb-2"
                  >
                    Admin Email
                  </label>
                  <input
                    id="fld-229"
                    type="email"
                    value={editHospitalModal.adminEmail}
                    onChange={(e) =>
                      setEditHospitalModal({ ...editHospitalModal, adminEmail: e.target.value })
                    }
                    className="w-full px-4 py-3 bg-gray-50 border-2 border-gray-200 rounded-xl text-gray-900 focus:bg-white focus:border-gray-900 focus:ring-4 focus:ring-gray-100 transition-all duration-200"
                  />
                </div>

                {/* Current Subscription (read-only) */}
                <div className="bg-gray-50 border border-gray-200 rounded-lg p-4">
                  <h4 className="text-sm font-semibold text-gray-700 mb-3">Current Subscription</h4>
                  <div className="grid grid-cols-2 gap-2 text-sm">
                    <div>
                      <span className="text-gray-500">Plan:</span>{' '}
                      <span className="font-medium">{editHospitalModal.planName}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">Period:</span>{' '}
                      <span className="font-medium">{editHospitalModal.billingPeriod}</span>
                    </div>
                    <div>
                      <span className="text-gray-500">Assigned:</span>{' '}
                      <span className="font-medium">
                        {editHospitalModal.assignedAt
                          ? new Date(editHospitalModal.assignedAt).toLocaleDateString('en-IN')
                          : '—'}
                      </span>
                    </div>
                    <div>
                      <span className="text-gray-500">Expires:</span>{' '}
                      <span className="font-medium">
                        {editHospitalModal.expiresAt
                          ? new Date(editHospitalModal.expiresAt).toLocaleDateString('en-IN')
                          : '—'}
                      </span>
                    </div>
                  </div>
                  {editHospitalModal.subscriptionStatus === 'WARNING' && (
                    <p className="mt-2 text-xs text-amber-600 font-medium">
                      ⚠ Plan expires within 7 days
                    </p>
                  )}
                  {editHospitalModal.subscriptionStatus === 'EXPIRED' && (
                    <p className="mt-2 text-xs text-red-600 font-medium">
                      ✕ Plan expired — entity is locked
                    </p>
                  )}
                </div>

                {/* Reassign Plan (optional) */}
                <div>
                  <label htmlFor="fld-228" className="block text-sm font-medium text-gray-700 mb-1">
                    Reassign Plan (optional)
                  </label>
                  <select
                    id="fld-228"
                    value={editHospitalModal.newPlanPublicId}
                    onChange={(e) =>
                      setEditHospitalModal((p) => ({ ...p, newPlanPublicId: e.target.value }))
                    }
                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-gray-900"
                  >
                    <option value="">-- Keep current plan --</option>
                    {editHospitalModal.availablePlansForEdit.map((p) => (
                      <option key={p.publicId} value={p.publicId}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                  {editHospitalModal.newPlanPublicId && (
                    <div className="flex gap-4 mt-2">
                      {['MONTHLY', 'YEARLY'].map((period) => (
                        <label key={period} className="flex items-center gap-2 cursor-pointer">
                          <input
                            type="radio"
                            name="newBillingPeriod"
                            value={period}
                            checked={editHospitalModal.newBillingPeriod === period}
                            onChange={() =>
                              setEditHospitalModal((p) => ({ ...p, newBillingPeriod: period }))
                            }
                          />
                          <span className="text-sm text-gray-700">
                            {period === 'MONTHLY' ? 'Monthly' : 'Yearly'}
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>

                {/* Doesn't apply to Pharmacy — pharmacies have no doctors */}
                {editHospitalModal.hospital?.type !== 'PHARMACY' && (
                  <div className="p-4 bg-gray-50 border border-gray-200 rounded-2xl">
                    <label
                      htmlFor="edit-single-doctor"
                      className="flex items-center space-x-3 cursor-pointer"
                    >
                      <input
                        id="edit-single-doctor"
                        type="checkbox"
                        aria-label="Single Doctor Clinic"
                        checked={editHospitalModal.isSingleDoctor}
                        onChange={(e) => {
                          setEditHospitalModal({
                            ...editHospitalModal,
                            isSingleDoctor: e.target.checked,
                          });
                        }}
                        className="w-4 h-4 text-gray-900 bg-gray-100 border-gray-300 rounded focus:ring-gray-900 focus:ring-2"
                      />
                      <div>
                        <span className="text-sm font-bold text-gray-900">
                          {editHospitalModal.hospital?.type === 'CLINIC'
                            ? 'Single Doctor Clinic'
                            : 'Single Doctor Hospital'}
                        </span>
                        <p className="text-xs text-gray-500 mt-0.5">
                          Enable unified doctor-admin dashboards for single-doctor{' '}
                          {editHospitalModal.hospital?.type === 'CLINIC' ? 'clinics' : 'hospitals'}.
                        </p>
                      </div>
                    </label>
                  </div>
                )}

                <div className="flex gap-4 pt-6 border-t border-gray-100">
                  <button
                    onClick={() => setEditHospitalModal({ ...editHospitalModal, isOpen: false })}
                    className="flex-1 bg-gray-100 text-gray-700 px-6 py-3 rounded-xl font-semibold hover:bg-gray-200 transition-colors duration-200"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleHospitalUpdate}
                    className="flex-1 bg-gray-900 text-white px-6 py-3 rounded-xl font-semibold hover:bg-gray-800 transition-colors duration-200"
                  >
                    Save Changes
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Password Display Modal */}
      {passwordModal.isOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
          {...backdropProps(() => {
            setPasswordModal({ ...passwordModal, isOpen: false });
          })}
        >
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md max-h-[90vh] overflow-y-auto">
            <div className="p-8 text-center">
              <div className="w-16 h-16 bg-gray-100 rounded-2xl flex items-center justify-center mx-auto mb-6">
                <svg
                  className="w-8 h-8 text-gray-700"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
              </div>

              <h3 className="text-2xl font-bold text-gray-900 mb-2">Password Reset Successful</h3>
              <p className="text-gray-600 mb-8">
                Please copy and share these credentials with the hospital admin immediately.
              </p>

              <div className="bg-gray-50 rounded-2xl p-6 mb-8 text-left border border-gray-200">
                <div className="mb-4">
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
                    Admin Email
                  </span>
                  <p className="text-gray-900 font-semibold text-lg mt-1">{passwordModal.email}</p>
                </div>
                <div>
                  <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
                    New Password
                  </span>
                  <div className="flex items-center justify-between mt-2">
                    <code className="bg-white px-4 py-2 rounded-xl border border-gray-300 font-mono text-lg text-gray-900 font-bold flex-1 mr-3">
                      {passwordModal.password}
                    </code>
                    <button
                      onClick={() => {
                        navigator.clipboard.writeText(passwordModal.password);
                        success('Password copied to clipboard');
                      }}
                      className="px-4 py-2 bg-gray-100 text-gray-900 rounded-xl hover:bg-gray-200 transition-colors duration-200 font-semibold text-sm"
                    >
                      Copy
                    </button>
                  </div>
                </div>
              </div>

              <button
                onClick={() => setPasswordModal({ ...passwordModal, isOpen: false })}
                className="w-full bg-gray-900 text-white px-6 py-3 rounded-xl font-semibold hover:bg-gray-800 transition-colors duration-200"
              >
                Done
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Profile Settings Modal */}
      <ProfileModal isOpen={profileOpen} onClose={() => setProfileOpen(false)} />
    </div>
  );
};

export default PlatformDashboard;

// SetPasswordModal — Super Admin sets the new password manually
const SetPasswordModal = ({ hospitalId, onClose, onSuccess }) => {
  const [newPw, setNewPw] = useState('');
  const [confirmPw, setConfirmPw] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [showCon, setShowCon] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async () => {
    setError('');
    if (!newPw) return setError('New password is required.');
    if (newPw.length < 6) return setError('Password must be at least 6 characters.');
    if (newPw !== confirmPw) return setError('Passwords do not match.');

    setLoading(true);
    try {
      await platformService.resetTenantPassword(hospitalId, newPw);
      onSuccess();
    } catch (err) {
      setError(extractApiError(err, 'Failed to reset password.'));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-sm shadow-2xl max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-gray-100">
          <div>
            <h3 className="text-base font-bold text-gray-900">Reset Admin Password</h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Set a new password for the hospital admin
            </p>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400"
          >
            <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        <div className="px-6 py-5 space-y-4">
          {/* Warning */}
          <div className="bg-amber-50 border border-amber-200 rounded-lg p-3 flex gap-2.5">
            <svg
              className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
              />
            </svg>
            <p className="text-xs text-amber-800">
              The old password will stop working immediately after reset.
            </p>
          </div>

          {/* New Password */}
          <div>
            <label
              htmlFor="fld-10030"
              className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5"
            >
              New Password
            </label>
            <div className="relative">
              <input
                id="fld-10030"
                type={showNew ? 'text' : 'password'}
                value={newPw}
                onChange={(e) => setNewPw(e.target.value)}
                placeholder="Min. 6 characters"
                className="w-full h-10 px-3 pr-10 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <button
                type="button"
                onClick={() => setShowNew((p) => !p)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700"
              >
                {showNew ? (
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
                    />
                  </svg>
                ) : (
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
                )}
              </button>
            </div>
          </div>

          {/* Confirm Password */}
          <div>
            <label
              htmlFor="fld-10029"
              className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5"
            >
              Confirm Password
            </label>
            <div className="relative">
              <input
                id="fld-10029"
                type={showCon ? 'text' : 'password'}
                value={confirmPw}
                onChange={(e) => setConfirmPw(e.target.value)}
                placeholder="Re-enter password"
                className="w-full h-10 px-3 pr-10 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <button
                type="button"
                onClick={() => setShowCon((p) => !p)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700"
              >
                {showCon ? (
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
                    />
                  </svg>
                ) : (
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
                )}
              </button>
            </div>
          </div>

          {/* Error */}
          {error && <p className="text-sm text-red-600 font-medium">{error}</p>}

          {/* Actions */}
          <div className="flex gap-3 pt-1">
            <button
              onClick={onClose}
              className="flex-1 h-10 text-sm border border-gray-200 rounded-xl text-gray-600 hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={loading}
              className="flex-1 h-10 text-sm bg-gray-900 text-white font-semibold rounded-xl hover:bg-gray-700 transition-colors disabled:opacity-60"
            >
              {loading ? 'Resetting...' : 'Reset Password'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

// PasswordResetModal Component
const PasswordResetModal = ({ email, password, onClose }) => {
  const [showPassword, setShowPassword] = useState(false);
  const [copiedEmail, setCopiedEmail] = useState(false);
  const [copiedPassword, setCopiedPassword] = useState(false);

  const copyToClipboard = (text, setCopied) => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl w-full max-w-md shadow-2xl max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="p-8 pb-0 text-center">
          <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <svg
              className="w-8 h-8 text-green-600"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M5 13l4 4L19 7"
              />
            </svg>
          </div>
          <h3 className="text-xl font-bold text-gray-900">Password Reset Successfully!</h3>
          <p className="text-sm text-gray-500 mt-1">
            Share these credentials securely with the admin.
          </p>
        </div>

        <div className="p-8 space-y-5">
          {/* Warning */}
          <div className="bg-yellow-50 border border-yellow-200 rounded-xl p-4 flex gap-3">
            <svg
              className="w-5 h-5 text-yellow-600 flex-shrink-0 mt-0.5"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
              />
            </svg>
            <p className="text-sm text-yellow-800 font-medium">
              Save this password now — it cannot be retrieved again after closing this window.
            </p>
          </div>

          {/* Email Row */}
          <div>
            <label
              htmlFor="fld-10028"
              className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2"
            >
              Email
            </label>
            <div className="flex gap-2">
              <input
                id="fld-10028"
                readOnly
                value={email}
                className="flex-1 h-10 px-3 text-sm bg-gray-50 border border-gray-200 rounded-lg text-gray-800 select-all"
              />
              <button
                onClick={() => copyToClipboard(email, setCopiedEmail)}
                className={`h-10 px-4 text-xs font-semibold rounded-lg border transition-all duration-200 ${
                  copiedEmail
                    ? 'bg-green-100 text-green-700 border-green-300'
                    : 'bg-gray-100 text-gray-600 border-gray-200 hover:bg-gray-200'
                }`}
              >
                {copiedEmail ? '✓ Copied' : 'Copy'}
              </button>
            </div>
          </div>

          {/* Password Row */}
          <div>
            <label
              htmlFor="fld-10027"
              className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-2"
            >
              New Password
            </label>
            <div className="flex gap-2">
              <div className="flex-1 relative">
                <input
                  id="fld-10027"
                  readOnly
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  className="w-full h-10 px-3 pr-10 text-sm bg-gray-50 border border-gray-200 rounded-lg text-gray-800 select-all"
                />
                <button
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700"
                >
                  {showPassword ? (
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
                      />
                    </svg>
                  ) : (
                    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
                  )}
                </button>
              </div>
              <button
                onClick={() => copyToClipboard(password, setCopiedPassword)}
                className={`h-10 px-4 text-xs font-semibold rounded-lg border transition-all duration-200 ${
                  copiedPassword
                    ? 'bg-green-100 text-green-700 border-green-300'
                    : 'bg-gray-100 text-gray-600 border-gray-200 hover:bg-gray-200'
                }`}
              >
                {copiedPassword ? '✓ Copied' : 'Copy'}
              </button>
            </div>
          </div>

          {/* Close Button */}
          <button
            onClick={onClose}
            className="w-full h-11 bg-gray-900 text-white font-semibold rounded-xl hover:bg-gray-700 transition-colors duration-200"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
}; // end PasswordResetModal

// SuperAdminProfileModal Component
const SuperAdminProfileModal = ({ user, onClose }) => {
  const [name, setName] = useState(user?.name || 'Super Admin');
  const [phone, setPhone] = useState(user?.phone || '');
  const [showPwSection, setShowPwSection] = useState(false);
  const [currentPw, setCurrentPw] = useState('');
  const [newPw, setNewPw] = useState('');
  const [confirmPw, setConfirmPw] = useState('');
  const [showCur, setShowCur] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [showCon, setShowCon] = useState(false);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState({ type: '', text: '' });

  const initials = (name || 'SA')
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2);

  const handleSave = async () => {
    if (!name || !name.trim()) return setMsg({ type: 'error', text: 'Name is required.' });
    if (showPwSection) {
      if (!currentPw) return setMsg({ type: 'error', text: 'Current password is required.' });
      if (newPw.length < 6)
        return setMsg({ type: 'error', text: 'New password must be at least 6 characters.' });
      if (newPw !== confirmPw) return setMsg({ type: 'error', text: 'Passwords do not match.' });
    }
    setSaving(true);
    try {
      // This used to be a fake `await setTimeout(800)` that reported success without
      // calling the backend — the Super Admin's name and password changes were silently
      // discarded. Persist for real; authService.updateProfile also refreshes the
      // cached user, so the dashboard header shows the new name once the modal closes.
      await authService.updateProfile({
        name: name.trim(),
        currentPassword: showPwSection ? currentPw : null,
        newPassword: showPwSection ? newPw : null,
      });
      setMsg({ type: 'success', text: 'Profile updated successfully.' });
      setTimeout(onClose, 1200);
    } catch (err) {
      const text = err?.response?.data?.error || err?.response?.data || 'Failed to save changes.';
      setMsg({ type: 'error', text: typeof text === 'string' ? text : 'Failed to save changes.' });
    } finally {
      setSaving(false);
    }
  };

  const EyeBtn = ({ show, toggle }) => (
    <button
      type="button"
      onClick={toggle}
      className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-700"
    >
      {show ? (
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
          />
        </svg>
      ) : (
        <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
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
      )}
    </button>
  );

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-md shadow-2xl max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between px-8 pt-8 pb-6 border-b border-gray-100">
          <h3 className="text-lg font-bold text-gray-900">Profile Settings</h3>
          <button
            onClick={onClose}
            className="w-8 h-8 flex items-center justify-center rounded-lg hover:bg-gray-100 text-gray-400 hover:text-gray-700 transition-colors"
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

        <div className="px-8 py-6 space-y-6">
          {/* Avatar + Role */}
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 bg-gray-900 rounded-full flex items-center justify-center flex-shrink-0">
              <span className="text-lg font-bold text-white">{initials}</span>
            </div>
            <div>
              <p className="font-semibold text-gray-900">{name}</p>
              <span className="inline-block mt-1 px-2.5 py-0.5 text-xs font-semibold bg-blue-100 text-blue-700 border border-blue-200 rounded-full">
                Super Admin
              </span>
            </div>
          </div>

          {/* Fields */}
          <div className="space-y-4">
            <div>
              <label
                htmlFor="fld-227"
                className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5"
              >
                Full Name
              </label>
              <input
                id="fld-227"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full h-10 px-3 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <span className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
                Email
              </span>
              <div className="flex items-center h-10 px-3 text-sm bg-gray-50 border border-gray-200 rounded-lg text-gray-500 gap-2">
                <svg
                  className="w-4 h-4 text-gray-400 flex-shrink-0"
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
                {user?.email || 'sa@hms.com'}
              </div>
            </div>
            {/* Platform accounts have no actor row (hospital_admins/doctors/…), which
                            is where a phone number is stored — so there is nowhere to persist it.
                            Shown disabled rather than as an editable field that silently discards
                            input, matching how ProfileModal treats SUPER_ADMIN phone. */}
            <div>
              <label
                htmlFor="fld-226"
                className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5"
              >
                Phone
              </label>
              <input
                id="fld-226"
                value={phone}
                disabled
                placeholder="Not stored for platform accounts"
                className="w-full h-10 px-3 text-sm border border-gray-200 rounded-lg bg-gray-50 text-gray-400 cursor-not-allowed"
              />
            </div>
          </div>

          {/* Change Password Toggle */}
          <div className="border border-gray-200 rounded-xl overflow-hidden">
            <button
              onClick={() => setShowPwSection(!showPwSection)}
              className="w-full flex items-center justify-between px-4 py-3 bg-gray-50 hover:bg-gray-100 transition-colors text-sm font-semibold text-gray-700"
            >
              <div className="flex items-center gap-2">
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
                    d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z"
                  />
                </svg>
                Change Password
              </div>
              <svg
                className={`w-4 h-4 text-gray-400 transition-transform ${showPwSection ? 'rotate-180' : ''}`}
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
            </button>
            {showPwSection && (
              <div className="p-4 space-y-3 border-t border-gray-200">
                {[
                  {
                    label: 'Current Password',
                    val: currentPw,
                    setVal: setCurrentPw,
                    show: showCur,
                    toggle: () => setShowCur((p) => !p),
                  },
                  {
                    label: 'New Password',
                    val: newPw,
                    setVal: setNewPw,
                    show: showNew,
                    toggle: () => setShowNew((p) => !p),
                  },
                  {
                    label: 'Confirm Password',
                    val: confirmPw,
                    setVal: setConfirmPw,
                    show: showCon,
                    toggle: () => setShowCon((p) => !p),
                  },
                ].map(({ label, val, setVal, show, toggle }) => (
                  <div key={label}>
                    <label className="block text-xs font-medium text-gray-500 mb-1">{label}</label>
                    <div className="relative">
                      <input
                        type={show ? 'text' : 'password'}
                        value={val}
                        onChange={(e) => setVal(e.target.value)}
                        className="w-full h-10 px-3 pr-10 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                      />
                      <EyeBtn show={show} toggle={toggle} />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Message */}
          {msg.text && (
            <p
              className={`text-sm font-medium ${msg.type === 'error' ? 'text-red-600' : 'text-green-600'}`}
            >
              {msg.text}
            </p>
          )}

          {/* Actions */}
          <div className="flex gap-3 pt-2">
            <button
              onClick={onClose}
              className="flex-1 h-10 text-sm border border-gray-200 rounded-xl text-gray-600 hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="flex-1 h-10 text-sm bg-gray-900 text-white font-semibold rounded-xl hover:bg-gray-700 transition-colors disabled:opacity-60"
            >
              {saving ? 'Saving...' : 'Save Changes'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}; // end SuperAdminProfileModal

// TicketsTable Component
const TicketsTable = ({ tickets, loading, onResolve, resolvingId, onView }) => {
  const priorityBadge = (p) => {
    if (p === 'HIGH') return 'bg-red-100 text-red-700 border-red-200';
    if (p === 'MEDIUM') return 'bg-yellow-100 text-yellow-700 border-yellow-200';
    return 'bg-green-100 text-green-700 border-green-200';
  };
  const statusBadge = (s) => {
    if (s === 'OPEN') return 'bg-yellow-100 text-yellow-700 border-yellow-200';
    if (s === 'IN_PROGRESS') return 'bg-blue-100 text-blue-700 border-blue-200';
    return 'bg-green-100 text-green-700 border-green-200';
  };

  if (loading)
    return (
      <div className="bg-white border border-gray-200 rounded-xl p-2">
        <SkeletonTable rows={4} cols={7} />
      </div>
    );

  if (tickets.length === 0)
    return (
      <div className="bg-white border border-gray-200 p-12 text-center">
        <div className="w-14 h-14 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <svg
            className="w-7 h-7 text-gray-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M7 8h10M7 12h4m1 8l-4-4H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-3l-4 4z"
            />
          </svg>
        </div>
        <h3 className="text-base font-semibold text-gray-900 mb-1">No tickets yet</h3>
        <p className="text-sm text-gray-500">
          Support tickets submitted by hospital admins will appear here.
        </p>
      </div>
    );

  return (
    <div className="bg-white border border-gray-200 overflow-x-auto">
      <table className="min-w-full">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>
            {['#', 'Hospital', 'Subject', 'Priority', 'Status', 'Submitted', 'Action'].map((h) => (
              <th
                key={h}
                className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {tickets.map((ticket, idx) => (
            <tr key={ticket.id} className="hover:bg-gray-50 transition-colors">
              <td className="px-6 py-4 text-sm text-gray-400">{idx + 1}</td>
              <td className="px-6 py-4">
                <p className="text-sm font-medium text-gray-900">{ticket.hospitalName}</p>
              </td>
              <td className="px-6 py-4">
                <button
                  type="button"
                  onClick={() => onView && onView(ticket)}
                  className="text-sm text-gray-900 text-left hover:text-blue-600 hover:underline"
                >
                  {ticket.subject}
                </button>
              </td>
              <td className="px-6 py-4">
                <span
                  className={`px-2.5 py-1 text-xs font-semibold rounded-full border ${priorityBadge(ticket.priority)}`}
                >
                  {ticket.priority}
                </span>
              </td>
              <td className="px-6 py-4">
                <span
                  className={`px-2.5 py-1 text-xs font-semibold rounded-full border ${statusBadge(ticket.status)}`}
                >
                  {ticket.status?.replace('_', ' ')}
                </span>
              </td>
              <td className="px-6 py-4">
                {ticket.createdAt ? (
                  <div>
                    <p className="text-sm text-gray-700">
                      {new Date(ticket.createdAt).toLocaleDateString('en-IN', {
                        day: '2-digit',
                        month: 'short',
                        year: 'numeric',
                      })}
                    </p>
                    <p className="text-xs text-gray-400">
                      {new Date(ticket.createdAt)
                        .toLocaleTimeString('en-IN', {
                          hour: '2-digit',
                          minute: '2-digit',
                          hour12: true,
                        })
                        .toUpperCase()}
                    </p>
                  </div>
                ) : (
                  <span className="text-gray-300 text-xs">—</span>
                )}
              </td>
              <td className="px-6 py-4">
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => onView && onView(ticket)}
                    className="px-3 py-1.5 text-xs font-semibold border rounded-lg bg-gray-50 text-gray-700 border-gray-200 hover:bg-gray-100 transition-colors"
                  >
                    View
                  </button>
                  {ticket.status !== 'RESOLVED' ? (
                    <button
                      onClick={() => onResolve(ticket.id)}
                      disabled={!!resolvingId}
                      className={`px-3 py-1.5 text-xs font-semibold border rounded-lg transition-colors ${resolvingId === ticket.id ? 'bg-gray-200 text-gray-400 border-gray-200 cursor-not-allowed' : resolvingId ? 'opacity-50 cursor-not-allowed bg-green-50 text-green-700 border-green-200' : 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100'}`}
                    >
                      {resolvingId === ticket.id ? 'Resolving...' : 'Resolve'}
                    </button>
                  ) : (
                    <span className="text-xs text-gray-400 italic">Closed</span>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// FaqsTable Component
const FaqsTable = ({ faqs, loading, onDelete }) => {
  if (loading)
    return (
      <div className="bg-white border border-gray-200 rounded-xl p-2">
        <SkeletonTable rows={3} cols={4} />
      </div>
    );

  if (faqs.length === 0)
    return (
      <div className="bg-white border border-gray-200 p-12 text-center">
        <div className="w-14 h-14 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <svg
            className="w-7 h-7 text-gray-400"
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
        </div>
        <h3 className="text-base font-semibold text-gray-900 mb-1">No FAQs yet</h3>
        <p className="text-sm text-gray-500">Global frequently asked questions will appear here.</p>
      </div>
    );

  return (
    <div className="bg-white border border-gray-200 overflow-x-auto">
      <table className="min-w-full">
        <thead className="bg-gray-50 border-b border-gray-200">
          <tr>
            {['#', 'Question', 'Answer', 'Actions'].map((h) => (
              <th
                key={h}
                className="px-6 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {faqs.map((faq, idx) => (
            <tr key={faq.id} className="hover:bg-gray-50 transition-colors">
              <td className="px-6 py-4 text-sm text-gray-400">{idx + 1}</td>
              <td className="px-6 py-4 font-medium text-gray-900 text-sm">{faq.question}</td>
              <td className="px-6 py-4 text-gray-600 text-sm whitespace-pre-wrap max-w-lg">
                {faq.answer}
              </td>
              <td className="px-6 py-4">
                <button
                  onClick={() => onDelete(faq.id)}
                  className="p-1.5 text-gray-500 hover:text-red-600 rounded hover:bg-red-50 transition-colors"
                  title="Delete FAQ"
                >
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                    />
                  </svg>
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

// Hospitals Table Component using DataTable
const HospitalsTable = ({
  hospitals,
  hospitalPage,
  handleToggleStatus,
  openEditHospitalModal,
  onResetPassword,
  onDeleteHospital,
  loadHospitals,
  entityType,
}) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.display({
      id: 'sno',
      header: 'S.No.',
      cell: (info) => hospitalPage.number * hospitalPage.size + info.row.index + 1,
    }),
    columnHelper.accessor((row) => row.customId || row.id, {
      id: 'id',
      header: 'ID',
      cell: (info) => <span title="Serial Number">{info.getValue()}</span>,
    }),
    columnHelper.accessor('type', {
      header: 'Type',
      cell: (info) => {
        const type = info.getValue() || 'HOSPITAL';
        const colors = {
          HOSPITAL: 'bg-blue-100 text-blue-700',
          CLINIC: 'bg-green-100 text-green-700',
          PHARMACY: 'bg-purple-100 text-purple-700',
        };
        return (
          <span
            className={`px-2 py-0.5 rounded-full text-xs font-semibold ${colors[type] || 'bg-gray-100'}`}
          >
            {type}
          </span>
        );
      },
    }),
    columnHelper.accessor('name', {
      header: 'Name',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('planName', {
      header: 'Plan',
      cell: (info) => {
        // Plan names are free text (the Super Admin names them in the Plans tab), so a
        // neutral badge showing the real name beats guessing at fixed tiers. "—" means the
        // tenant has no current subscription — surface that instead of defaulting it.
        const plan = info.getValue() || '—';
        return (
          <div className="flex items-center gap-2">
            <span className="px-2.5 py-1 rounded-full text-xs font-semibold border bg-gray-100 text-gray-700 border-gray-200">
              {plan}
            </span>
            <button
              onClick={() => openEditHospitalModal(info.row.original)}
              className="text-gray-400 hover:text-blue-600 transition-colors"
              aria-label="Edit plan"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-4 w-4"
                viewBox="0 0 20 20"
                fill="currentColor"
              >
                <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
              </svg>
            </button>
          </div>
        );
      },
    }),
    columnHelper.accessor('isActive', {
      header: 'Status',
      cell: (info) => (
        <span
          className={`px-2.5 py-1 inline-flex text-xs leading-5 font-semibold rounded-full border ${
            info.getValue()
              ? 'bg-green-100 text-green-800 border-green-200'
              : 'bg-red-100 text-red-700 border-red-200'
          }`}
        >
          {info.getValue() ? 'Active' : 'Inactive'}
        </span>
      ),
    }),
    columnHelper.accessor('isSingleDoctor', {
      header: 'Single Doctor',
      cell: (info) => (
        <span
          className={`px-2.5 py-1 inline-flex text-xs leading-5 font-semibold rounded-full border ${
            info.getValue()
              ? 'bg-amber-100 text-amber-800 border-amber-200'
              : 'bg-slate-100 text-slate-600 border-slate-200'
          }`}
        >
          {info.getValue() ? 'Yes' : 'No'}
        </span>
      ),
    }),
    columnHelper.accessor('createdAt', {
      header: 'Created At',
      cell: (info) => new Date(info.getValue()).toLocaleDateString(),
    }),
    columnHelper.display({
      id: 'actions',
      header: () => <div className="text-right">Actions</div>,
      cell: (info) => (
        <div className="text-right">
          <ActionMenu
            actions={[
              {
                label: 'Edit Details',
                onClick: () => openEditHospitalModal(info.row.original),
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
                onClick: () =>
                  onResetPassword(
                    info.row.original.publicId || info.row.original.id,
                    info.row.original.adminEmail
                  ),
                icon: (
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="h-4 w-4"
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v3H2v-3a6 6 0 016-6 6 6 0 0110 0zm-2-3a1 1 0 10-2 0 1 1 0 002 0z"
                      clipRule="evenodd"
                    />
                  </svg>
                ),
                danger: true,
              },
              {
                label: info.row.original.isActive ? 'Deactivate' : 'Activate',
                onClick: () =>
                  handleToggleStatus(
                    info.row.original.publicId || info.row.original.id,
                    info.row.original.isActive
                  ),
                icon: info.row.original.isActive ? (
                  <svg
                    xmlns="http://www.w3.org/2000/svg"
                    className="h-4 w-4"
                    viewBox="0 0 20 20"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
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
                      d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                      clipRule="evenodd"
                    />
                  </svg>
                ),
                danger: info.row.original.isActive,
              },
              {
                label: 'Delete',
                onClick: () =>
                  onDeleteHospital(
                    info.row.original.publicId || info.row.original.id,
                    info.row.original.name
                  ),
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
  ];

  const pagination = {
    pageIndex: hospitalPage.number,
    pageSize: hospitalPage.size,
    totalItems: hospitalPage.totalElements,
    pageCount: hospitalPage.totalPages,
    onPageChange: (newPage) => loadHospitals(newPage, hospitalPage.size, entityType),
  };

  return (
    <DataTable
      data={hospitals}
      columns={columns}
      pagination={pagination}
      emptyState={
        <div className="p-12 text-center">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            className="h-12 w-12 mb-4 text-slate-400 mx-auto"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M3 10l9-6 9 6v7a2 2 0 01-2 2H5a2 2 0 01-2-2v-7z"
            />
          </svg>
          <p className="text-gray-500">No hospitals registered yet</p>
        </div>
      }
    />
  );
};

// AuditLogsTable Component (Standardized)
const AuditLogsTable = ({ auditLogs }) => {
  const columnHelper = createColumnHelper();
  const [page, setPage] = useState(0);
  const pageSize = 10;

  // Filter state
  const [actionFilter, setActionFilter] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');

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

  // Helper: color-coded action badge
  const getActionBadgeColor = (action) => {
    if (!action) return 'bg-gray-100 text-gray-600 border-gray-200';
    const a = action.toUpperCase();
    if (a.includes('CREATED') || a.includes('CREATE'))
      return 'bg-blue-100 text-blue-700 border-blue-200';
    if (a.includes('UPDATED') || a.includes('UPDATE') || a.includes('MODULES'))
      return 'bg-purple-100 text-purple-700 border-purple-200';
    if (a.includes('COMPLETED') || a.includes('DISCHARGED'))
      return 'bg-green-100 text-green-700 border-green-200';
    if (a.includes('STATUS') || a.includes('CHANGED'))
      return 'bg-yellow-100 text-yellow-700 border-yellow-200';
    if (a.includes('DELETE') || a.includes('DISABLED') || a.includes('REMOVED'))
      return 'bg-red-100 text-red-700 border-red-200';
    if (a.includes('LOGIN') || a.includes('LOGOUT'))
      return 'bg-gray-100 text-gray-600 border-gray-200';
    if (a.includes('RESET') || a.includes('PASSWORD'))
      return 'bg-orange-100 text-orange-700 border-orange-200';
    if (a.includes('ENABLED') || a.includes('ACTIVATED'))
      return 'bg-green-100 text-green-700 border-green-200';
    return 'bg-gray-100 text-gray-600 border-gray-200';
  };

  // Unique actions for dropdown (built from actual log data)
  const uniqueActions = [...new Set(auditLogs.map((l) => l.action).filter(Boolean))].sort();

  // Client-side filter logic
  const filteredLogs = auditLogs.filter((log) => {
    const logDate = new Date(log.timestamp || log.createdAt);
    const matchAction = !actionFilter || log.action === actionFilter;
    const matchFrom = !fromDate || logDate >= new Date(fromDate);
    const matchTo = !toDate || logDate <= new Date(toDate + 'T23:59:59');
    return matchAction && matchFrom && matchTo;
  });

  const handleFilterChange = (setter, value) => {
    setter(value);
    setPage(0);
  };
  const clearFilters = () => {
    setActionFilter('');
    setFromDate('');
    setToDate('');
    setPage(0);
  };
  const hasActiveFilter = actionFilter || fromDate || toDate;

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
          <p className="text-sm font-medium text-gray-900">{formatDate(info.getValue())}</p>
          <p className="text-xs text-gray-400 mt-0.5">{formatTime(info.getValue())}</p>
        </div>
      ),
    }),
    columnHelper.accessor('action', {
      header: 'ACTION',
      cell: (info) => (
        <span
          className={`px-2.5 py-1 rounded-md text-xs font-semibold border ${getActionBadgeColor(info.getValue())}`}
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
      cell: (info) => {
        const val = info.getValue();
        if (!val || val === '-' || val === 'N/A') {
          return <span className="text-gray-300 text-xs">—</span>;
        }
        return <span className="text-sm text-gray-600 italic">{val}</span>;
      },
    }),
  ];

  // Paginate the filtered results
  const totalPages = Math.ceil(filteredLogs.length / pageSize);
  const paginatedLogs = filteredLogs.slice(page * pageSize, (page + 1) * pageSize);

  const pagination = {
    pageIndex: page,
    pageSize: pageSize,
    totalItems: filteredLogs.length,
    pageCount: totalPages,
    onPageChange: (newPage) => setPage(newPage),
  };

  return (
    <div>
      {/* Filter Bar */}
      <div className="px-6 py-4 border-b border-gray-200 bg-gray-50">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex flex-col gap-1">
            <label htmlFor="fld-225" className="text-xs font-medium text-gray-500">
              From
            </label>
            <DateSelect value={fromDate} onChange={(v) => handleFilterChange(setFromDate, v)} />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="fld-224" className="text-xs font-medium text-gray-500">
              To
            </label>
            <DateSelect value={toDate} onChange={(v) => handleFilterChange(setToDate, v)} />
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor="fld-223" className="text-xs font-medium text-gray-500">
              Action
            </label>
            <select
              id="fld-223"
              value={actionFilter}
              onChange={(e) => handleFilterChange(setActionFilter, e.target.value)}
              className="h-9 px-3 text-sm border border-gray-200 rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-blue-500 min-w-[180px]"
            >
              <option value="">All Actions</option>
              {uniqueActions.map((action) => (
                <option key={action} value={action}>
                  {action}
                </option>
              ))}
            </select>
          </div>
          {hasActiveFilter && (
            <>
              <div className="flex flex-col gap-1">
                <span className="text-xs font-medium text-transparent">x</span>
                <button
                  onClick={clearFilters}
                  className="h-9 px-4 text-sm text-gray-500 border border-gray-200 rounded-lg bg-white hover:bg-gray-100 transition-colors flex items-center gap-1.5"
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
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                  Clear
                </button>
              </div>
              <div className="ml-auto flex items-end pb-0.5">
                <span className="text-xs text-gray-400">
                  {filteredLogs.length} of {auditLogs.length} results
                </span>
              </div>
            </>
          )}
        </div>
      </div>
      <DataTable data={paginatedLogs} columns={columns} pagination={pagination} />
    </div>
  );
};

// Users Table Component
const UsersTable = ({ users, userPage, onResetPassword, loadUsers }) => {
  const columnHelper = createColumnHelper();

  const columns = [
    columnHelper.accessor('name', {
      header: 'Name',
      cell: (info) => <span className="font-medium text-gray-900">{info.getValue()}</span>,
    }),
    columnHelper.accessor('email', {
      header: 'Email',
      cell: (info) => info.getValue(),
    }),
    columnHelper.accessor('role', {
      header: 'Role',
      cell: (info) => (
        <span className="px-2 py-1 rounded text-xs font-semibold bg-gray-100 text-gray-800">
          {info.getValue().replace('_', ' ')}
        </span>
      ),
    }),
    columnHelper.accessor('hospitalName', {
      header: 'Hospital',
      cell: (info) => info.getValue(),
    }),
    columnHelper.accessor('isActive', {
      header: 'Status',
      cell: (info) => (
        <span className="px-2 py-1 rounded-full text-xs font-semibold bg-gray-100 text-gray-800">
          {info.getValue() ? 'Active' : 'Inactive'}
        </span>
      ),
    }),
    columnHelper.display({
      id: 'actions',
      header: 'Actions',
      cell: (info) => (
        <ActionMenu
          actions={[
            {
              label: 'Reset Password',
              onClick: () => {
                const uid = info.row.original.id;
                if (uid) {
                  onResetPassword(uid);
                } else {
                  // Fallback or alert
                  console.error('User Public ID is missing', info.row.original);
                  // Using toast context would require hooking it up here or passing it down
                  // For now just safe guard to prevent 404/CORS error
                  alert(
                    'Error: User data is incomplete (Missing Public ID). Please refresh the page. If issue persists, contact support.'
                  );
                }
              },
              className: 'text-amber-600 hover:bg-amber-50',
            },
          ]}
        />
      ),
    }),
  ];

  const pagination = {
    pageIndex: userPage.number,
    pageSize: userPage.size,
    totalItems: userPage.totalElements,
    pageCount: userPage.totalPages,
    onPageChange: (newPage) => loadUsers(newPage, userPage.size),
  };

  return (
    <DataTable
      data={users}
      columns={columns}
      pagination={pagination}
      isLoading={false}
      emptyState={
        <div className="p-12 text-center">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            className="h-12 w-12 mb-4 text-slate-400 mx-auto"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M17 20h5v-2a4 4 0 00-4-4h-1M9 20H4v-2a4 4 0 014-4h1m6-4a4 4 0 11-8 0 4 4 0 018 0z"
            />
          </svg>
          <p className="text-gray-500">No users found</p>
        </div>
      }
    />
  );
};
