import apiClient from './apiService';

/**
 * otService - Operation Theatre API wrapper (OT module, Phase 2).
 * Shares auth/401 interceptors via apiClient.
 */
const otService = {
  // Doctor
  createRequest: async (payload) => (await apiClient.post('/hospital/surgeries', payload)).data,
  getActiveForAdmission: async (admissionId) =>
    (await apiClient.get(`/hospital/surgeries/admission/${admissionId}/active`)).data,
  getMyBoard: async () => (await apiClient.get('/hospital/surgeries/my-board')).data,

  // Reception
  getRequests: async () => (await apiClient.get('/hospital/surgeries/requests')).data,
  getBoard: async () => (await apiClient.get('/hospital/surgeries/board')).data,
  getSurgeons: async () => (await apiClient.get('/hospital/surgeries/surgeons')).data,
  schedule: async (publicId, payload) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/schedule`, payload)).data,
  start: async (publicId) => (await apiClient.post(`/hospital/surgeries/${publicId}/start`)).data,
  complete: async (publicId) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/complete`)).data,
  cancel: async (publicId, payload) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/cancel`, payload || {})).data,

  // --- OT recovery / PACU (Phase 8, OT-P0B) ---
  close: async (publicId) => (await apiClient.post(`/hospital/surgeries/${publicId}/close`)).data,
  getRecovery: async (surgeryId) =>
    (await apiClient.get(`/hospital/ot/surgeries/${surgeryId}/recovery`)).data,
  // Every active RecoveryEpisode, plus every COMPLETED surgery with no active one yet -- the
  // patient can never fall out of both lists, whatever went wrong at admit time.
  getRecoveryBoard: async () => (await apiClient.get('/hospital/ot/recovery/board')).data,
  // Recovery bays, each with an `occupied` flag. Admission requires one selected; a bay that is
  // already occupied or inactive is filtered out client-side, and the server refuses it either way.
  getRecoveryBays: async () => (await apiClient.get('/hospital/ot/recovery-bays')).data,
  admitRecovery: async (surgeryId, recoveryBayId) =>
    (
      await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/recovery/admit`, {
        recoveryBayId,
      })
    ).data,
  observeRecovery: async (surgeryId, payload) =>
    (await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/recovery/observe`, payload)).data,
  dischargeRecovery: async (surgeryId, destination) =>
    (
      await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/recovery/discharge`, {
        destination,
      })
    ).data,

  // --- OT execution: WHO checklist, milestones, operative note (Phase 7) ---
  getWhoChecklist: async (surgeryId) =>
    (await apiClient.get(`/hospital/ot/surgeries/${surgeryId}/who-checklist`)).data,
  signWhoPhase: async (surgeryId, phase, payload) =>
    (
      await apiClient.post(
        `/hospital/ot/surgeries/${surgeryId}/who-checklist/${phase}/sign`,
        payload || {}
      )
    ).data,
  getMilestones: async (surgeryId) =>
    (await apiClient.get(`/hospital/ot/surgeries/${surgeryId}/milestones`)).data,
  recordMilestone: async (surgeryId, payload) =>
    (await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/milestones`, payload)).data,
  saveOperativeNote: async (surgeryId, note) =>
    (await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/operative-note`, { note })).data,

  // --- OT team & case roles (Phase 6) ---
  getCaseRoles: async () => (await apiClient.get('/hospital/ot/case-roles')).data,
  addCaseRole: async (label) => (await apiClient.post('/hospital/ot/case-roles', { label })).data,
  getTeam: async (surgeryId) =>
    (await apiClient.get(`/hospital/ot/surgeries/${surgeryId}/team`)).data,
  assignTeamMember: async (surgeryId, payload) =>
    (await apiClient.post(`/hospital/ot/surgeries/${surgeryId}/team`, payload)).data,
  removeTeamMember: async (surgeryId, memberId) =>
    (await apiClient.delete(`/hospital/ot/surgeries/${surgeryId}/team/${memberId}`)).data,

  // --- OT policies + analytics (Phase 5) ---
  getPolicies: async () => (await apiClient.get('/hospital/ot/policies')).data,
  updatePolicies: async (values) => (await apiClient.put('/hospital/ot/policies', values)).data,
  applyArchetype: async (name) =>
    (await apiClient.post(`/hospital/ot/policies/archetype/${name}`)).data,
  resetPolicies: async () => (await apiClient.post('/hospital/ot/policies/reset')).data,
  getOtAnalytics: async (date) =>
    (await apiClient.get('/hospital/ot/analytics/summary', { params: date ? { date } : {} })).data,
  getOtNabh: async (from, to) =>
    (
      await apiClient.get('/hospital/ot/analytics/nabh', {
        params: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
      })
    ).data,
  /** Move a scheduled case into pre-operative preparation. */
  preOp: async (publicId) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/pre-op`)).data,

  /**
   * Record the anaesthetist's decision. The outcome is theirs; the HMS never infers fitness, so
   * there is no default and the caller must state one of the domain's four outcomes.
   */
  recordAnaesthesiaClearance: async (publicId, { outcome, conditionsComments } = {}) =>
    (
      await apiClient.post(`/hospital/surgeries/${publicId}/anaesthesia-clearance`, {
        outcome,
        conditionsComments,
      })
    ).data,

  approve: async (publicId) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/approve`)).data,

  // --- OT rooms (Phase 4) ---
  getRooms: async () => (await apiClient.get('/hospital/ot/rooms')).data,
  getRoomSuggestions: async () => (await apiClient.get('/hospital/ot/rooms/suggestions')).data,
  createRoom: async (payload) => (await apiClient.post('/hospital/ot/rooms', payload)).data,
  updateRoom: async (publicId, payload) =>
    (await apiClient.put(`/hospital/ot/rooms/${publicId}`, payload)).data,
  deactivateRoom: async (publicId) =>
    (await apiClient.delete(`/hospital/ot/rooms/${publicId}`)).data,

  // Reads that back the board, list, waiting list and case timeline.
  //
  // These five sat under /hospital/ot/surgeries/... and the surgery endpoints are served from
  // /hospital/surgeries -- only theatre EXECUTION and RECOVERY live under /hospital/ot/surgeries.
  // Today's OT List and the printable list therefore called an address that does not serve them,
  // which is a screen that cannot work at all rather than one that works badly. The other three
  // had no caller yet and would have failed the moment one was added.
  getOtList: async (date) =>
    (await apiClient.get('/hospital/surgeries/list', { params: date ? { date } : {} })).data,
  getWaitingList: async () => (await apiClient.get('/hospital/surgeries/waiting-list')).data,
  getTimeline: async (publicId) =>
    (await apiClient.get(`/hospital/surgeries/${publicId}/timeline`)).data,
  getCancellationReasons: async () =>
    (await apiClient.get('/hospital/surgeries/cancellation-reasons')).data,
  postpone: async (publicId, payload) =>
    (await apiClient.post(`/hospital/surgeries/${publicId}/postpone`, payload || {})).data,

  // --- OT permissions (Phase 2) ---
  // The caller's own effective permissions. The UI renders by capability, never by role.
  getMyOtPermissions: async () => (await apiClient.get('/hospital/ot/permissions/me')).data,
  getOtPermissionCatalogue: async () =>
    (await apiClient.get('/hospital/ot/permissions/catalogue')).data,
  getOtPermissionMatrix: async () => (await apiClient.get('/hospital/ot/permissions')).data,
  // Whole-matrix replace: a partial write cannot express "this role now has nothing".
  updateOtPermissionMatrix: async (matrix) =>
    (await apiClient.put('/hospital/ot/permissions', matrix)).data,
  resetOtPermissions: async () => (await apiClient.post('/hospital/ot/permissions/reset')).data,

  // --- OT/NABH surgery forms (nurse fill/save/print) ---
  // A form belongs to a PROCEDURE. The admission-scoped calls resolve the admission's
  // active surgery, which is ambiguous once it carries more than one — pass surgeryId
  // whenever the caller knows it.
  getSurgeryForm: async (admissionId, formType) =>
    (await apiClient.get(`/hospital/surgery-forms/admission/${admissionId}/${formType}`)).data,
  saveSurgeryForm: async (admissionId, formType, data, performedByNurseId) =>
    (
      await apiClient.post('/hospital/surgery-forms', {
        ipdAdmissionId: admissionId,
        formType,
        data,
        ...(performedByNurseId != null ? { performedByNurseId } : {}),
      })
    ).data,
  getSavedFormTypes: async (admissionId) =>
    (await apiClient.get(`/hospital/surgery-forms/admission/${admissionId}`)).data,

  // Procedure-scoped: preferred, and the only addressing that works for a day-care
  // case, which has no admission at all.
  getSurgeryFormBySurgery: async (surgeryId, formType) =>
    (await apiClient.get(`/hospital/surgery-forms/surgery/${surgeryId}/${formType}`)).data,
  saveSurgeryFormBySurgery: async (surgeryId, formType, data, performedByNurseId, sign) =>
    (
      await apiClient.post('/hospital/surgery-forms', {
        surgeryId,
        formType,
        data,
        ...(performedByNurseId != null ? { performedByNurseId } : {}),
        ...(sign ? { sign: true } : {}),
      })
    ).data,
  getSavedFormTypesBySurgery: async (surgeryId) =>
    (await apiClient.get(`/hospital/surgery-forms/surgery/${surgeryId}`)).data,
  getSurgeryFormVersions: async (surgeryId, formType) =>
    (await apiClient.get(`/hospital/surgery-forms/surgery/${surgeryId}/${formType}/versions`)).data,
  signSurgeryForm: async (surgeryId, formType) =>
    (await apiClient.post(`/hospital/surgery-forms/surgery/${surgeryId}/${formType}/sign`)).data,
};

export default otService;
