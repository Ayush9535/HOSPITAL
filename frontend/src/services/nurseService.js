import apiClient from './apiService';
import authService from './authService';

/**
 * nurseService - nurse-facing API calls (Phase 1 Nurse module).
 * Mirrors the hospitalService axios-wrapper style; interceptors (auth token,
 * 401 handling) are shared via apiClient.
 */
const nurseService = {
  // --- Nurse Incharge dashboard ---
  getInchargeDashboard: async () =>
    (await apiClient.get('/hospital/nurse-incharge/dashboard')).data,

  // --- Shift (availability) ---

  /** Whether the current nurse is on shift. */
  getShiftStatus: async () => {
    const response = await apiClient.get('/hospital/nurse/shift/status');
    return response.data; // { onShift: boolean }
  },

  /** Dashboard aggregates: assigned patient count + recent patients. */
  getDashboard: async () => {
    const response = await apiClient.get('/hospital/nurse/dashboard');
    return response.data;
  },

  /** Active IPD admissions assigned to the logged-in nurse. */
  getMyPatients: async () => {
    const response = await apiClient.get('/hospital/nurse/my-patients');
    return response.data;
  },

  /**
   * Composite read-only bedside view for one admission.
   *
   * Two endpoints serve the same view under two different scopes, and the caller's role decides
   * which one is theirs: a staff nurse may open a patient they are assigned to, an incharge may
   * open any patient in a ward they run. Picking here keeps the branch in one place rather than
   * in every screen that opens a chart.
   */
  getPatientDetail: async (admissionId) => {
    const role = authService.getCurrentUser()?.role;
    const path =
      role === 'NURSE_INCHARGE' || role === 'HOSPITAL_ADMIN'
        ? `/hospital/nurse-incharge/patients/${admissionId}`
        : `/hospital/nurse/patients/${admissionId}`;
    const response = await apiClient.get(path);
    return response.data;
  },

  // --- Admission form (complete the IPD admission) ---

  /** Get the admission form (pre-filled draft if not saved yet). */
  getAdmissionForm: async (admissionId) => {
    const response = await apiClient.get(`/hospital/nurse/admission-form/admission/${admissionId}`);
    return response.data;
  },

  /** Save the admission form. */
  saveAdmissionForm: async (admissionId, form) => {
    const response = await apiClient.post(
      `/hospital/nurse/admission-form/admission/${admissionId}`,
      form
    );
    return response.data;
  },

  /** Mark the patient admitted after the signed form is collected. */
  confirmAdmission: async (admissionId) => {
    const response = await apiClient.post(
      `/hospital/nurse/admission-form/admission/${admissionId}/confirm`
    );
    return response.data;
  },

  // --- Initial assessment (nurse-captured clinical fields) ---

  getInitialAssessment: async (admissionId) => {
    const response = await apiClient.get(
      `/hospital/nurse/initial-assessment/admission/${admissionId}`
    );
    return response.data;
  },

  saveInitialAssessment: async (admissionId, body) => {
    const response = await apiClient.post(
      `/hospital/nurse/initial-assessment/admission/${admissionId}`,
      body
    );
    return response.data;
  },

  // --- Vulnerability assessment ---

  getVulnerabilityAssessment: async (admissionId) => {
    const response = await apiClient.get(
      `/hospital/nurse/vulnerability-assessment/admission/${admissionId}`
    );
    return response.data;
  },

  saveVulnerabilityAssessment: async (admissionId, body) => {
    const response = await apiClient.post(
      `/hospital/nurse/vulnerability-assessment/admission/${admissionId}`,
      body
    );
    return response.data;
  },

  // --- Sugar chart ---

  getSugarEntries: async (admissionId) => {
    const response = await apiClient.get(`/hospital/nurse/sugar-chart/admission/${admissionId}`);
    return response.data;
  },
  createSugarEntry: async (payload) => {
    const response = await apiClient.post('/hospital/nurse/sugar-chart', payload);
    return response.data;
  },
  updateSugarEntry: async (publicId, payload) => {
    const response = await apiClient.put(`/hospital/nurse/sugar-chart/${publicId}`, payload);
    return response.data;
  },
  deleteSugarEntry: async (publicId) => {
    const response = await apiClient.delete(`/hospital/nurse/sugar-chart/${publicId}`);
    return response.data;
  },

  // --- Vitals (M4) ---

  /** Record a new vitals reading for an admission. */
  createVitals: async (payload) => {
    const response = await apiClient.post('/hospital/nurse/vitals', payload);
    return response.data;
  },

  /** Vitals timeline for an admission (newest first). */
  getVitals: async (admissionId) => {
    const response = await apiClient.get(`/hospital/nurse/vitals/admission/${admissionId}`);
    return response.data;
  },

  /** Edit a vitals reading (author only, within the edit window). */
  updateVitals: async (publicId, payload) => {
    const response = await apiClient.put(`/hospital/nurse/vitals/${publicId}`, payload);
    return response.data;
  },

  // --- Nursing Notes (M5) ---

  /** Add a nursing note for an admission. */
  createNote: async (payload) => {
    const response = await apiClient.post('/hospital/nurse/notes', payload);
    return response.data;
  },

  /** Notes timeline for an admission (newest first). */
  getNotes: async (admissionId) => {
    const response = await apiClient.get(`/hospital/nurse/notes/admission/${admissionId}`);
    return response.data;
  },

  /** Edit a note (author only, within the edit window). */
  updateNote: async (publicId, payload) => {
    const response = await apiClient.put(`/hospital/nurse/notes/${publicId}`, payload);
    return response.data;
  },

  /** Soft-delete a note (author only, within the edit window). */
  deleteNote: async (publicId) => {
    const response = await apiClient.delete(`/hospital/nurse/notes/${publicId}`);
    return response.data;
  },

  // --- OT Notes (intra-operative notes linked to a surgery) ---

  /** Active surgery for an admission (or null) — to decide whether to show OT notes. */
  getActiveSurgery: async (admissionId) => {
    const response = await apiClient.get(`/hospital/surgeries/admission/${admissionId}/active`);
    return response.data;
  },

  /** OT notes for a surgery. */
  getSurgeryNotes: async (surgeryId) => {
    const response = await apiClient.get(`/hospital/nurse/notes/surgery/${surgeryId}`);
    return response.data;
  },

  /** Create an OT note (nursing note linked to a surgery). */
  addSurgeryNote: async (admissionId, surgeryId, noteText) => {
    const response = await apiClient.post('/hospital/nurse/notes', {
      ipdAdmissionId: admissionId,
      surgeryId,
      category: 'OT',
      noteText,
    });
    return response.data;
  },

  // --- Medication Administration (M6) ---

  /** Active prescriptions for an admission. */
  getMedicationPrescriptions: async (admissionId) => {
    const response = await apiClient.get(
      `/hospital/nurse/medication/admission/${admissionId}/prescriptions`
    );
    return response.data;
  },

  /** Record administration of a prescribed dose. */
  recordMedication: async (payload) => {
    const response = await apiClient.post('/hospital/nurse/medication', payload);
    return response.data;
  },

  /** Medication administration history for an admission. */
  getMedicationHistory: async (admissionId) => {
    const response = await apiClient.get(`/hospital/nurse/medication/admission/${admissionId}`);
    return response.data;
  },

  /** Full medication chart: all orders (active/stopped/completed) + reminders. */
  getMedicationChart: async (admissionId) => {
    const response = await apiClient.get(
      `/hospital/nurse/medication/admission/${admissionId}/chart`
    );
    return response.data;
  },

  // --- Manual Tasks (M7) ---

  /** Get assigned tasks for the logged-in nurse. */
  getMyTasks: async () => {
    const response = await apiClient.get('/hospital/nurse-tasks/my');
    return response.data;
  },

  /** Update status of a nurse task. */
  updateTaskStatus: async (publicId, payload) => {
    const response = await apiClient.put(`/hospital/nurse-tasks/${publicId}/status`, payload);
    return response.data;
  },

  // --- Nurse Incharge (Phase A2) ---

  /** Active admissions across the incharge's wards (or all wards for admin). */
  getWardPatients: async () => (await apiClient.get('/hospital/nurse-incharge/patients')).data,

  /** Ward-visible active admissions with no primary nursing owner. */
  getUnassignedPatients: async () =>
    (await apiClient.get('/hospital/nurse-incharge/unassigned-patients')).data,

  /** Active, non-incharge staff nurses in a ward (candidates for assignment). */
  getWardStaffNurses: async (wardId) =>
    (await apiClient.get(`/hospital/nurse-incharge/wards/${wardId}/nurses`)).data,

  /** Assign a staff nurse to a patient's IPD admission. */
  assignPatientNurse: async (ipdAdmissionId, nurseProfileId) =>
    (await apiClient.post('/hospital/nurse-incharge/assign', { ipdAdmissionId, nurseProfileId }))
      .data,

  // --- Performed By Nurse (Phase A3) ---

  /**
   * Whether this hospital has Separate Nurse Login turned ON. When OFF
   * ("Shared Login"), nursing entry forms must ask which nurse performed
   * the action (a required "Performed By Nurse" dropdown) instead of
   * relying on the logged-in user's identity.
   */
  getSeparateNurseLogin: async () =>
    (await apiClient.get('/hospital/settings/operations')).data?.separateNurseLogin === true,

  // --- Beds (Nursing Mgmt Phase C3) ---

  /** The incharge's wards (all wards for admin), each with its beds. */
  getMyWards: async () => (await apiClient.get('/hospital/nurse-incharge/wards')).data,

  /** The incharge's nurse roster (staff nurses across their wards). */
  getMyNurses: async () => (await apiClient.get('/hospital/nurse-incharge/nurses')).data,

  /** Mark a bed awaiting cleaning as cleaned -> available. */
  markBedCleaned: async (bedId, remarks) =>
    (await apiClient.post(`/hospital/beds/${bedId}/cleaned`, { remarks })).data,

  /** Put a non-occupied bed under maintenance. */
  markBedMaintenance: async (bedId, remarks) =>
    (await apiClient.post(`/hospital/beds/${bedId}/maintenance`, { remarks })).data,

  /** Return a bed under maintenance back to available. */
  markBedAvailable: async (bedId, remarks) =>
    (await apiClient.post(`/hospital/beds/${bedId}/available`, { remarks })).data,

  /** Status-change history for a bed. */
  getBedHistory: async (bedId) => (await apiClient.get(`/hospital/beds/${bedId}/history`)).data,

  // --- Coverage: temp ward assignments + substitutions (Nursing Mgmt Phase F) ---

  getTempAssignments: async () =>
    (await apiClient.get('/hospital/nurse-coverage/temp-assignments')).data,
  createTempAssignment: async (payload) =>
    (await apiClient.post('/hospital/nurse-coverage/temp-assignments', payload)).data,
  removeTempAssignment: async (publicId) =>
    (await apiClient.delete(`/hospital/nurse-coverage/temp-assignments/${publicId}`)).data,

  getSubstitutions: async () =>
    (await apiClient.get('/hospital/nurse-coverage/substitutions')).data,
  createSubstitution: async (payload) =>
    (await apiClient.post('/hospital/nurse-coverage/substitutions', payload)).data,
  removeSubstitution: async (publicId) =>
    (await apiClient.delete(`/hospital/nurse-coverage/substitutions/${publicId}`)).data,

  /** Active substitutions where the logged-in nurse is the replacement. */
  getMyCoverage: async () => (await apiClient.get('/hospital/nurse-coverage/mine')).data,
};

export default nurseService;
