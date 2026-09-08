import apiClient from './apiService';

/**
 * ICU Phase 2 — read-only capacity views.
 *
 * There is deliberately no write call here. Bed status and admissions keep their existing
 * owners (WardService / hospitalService); ICU only reads what those already record, so the
 * board can never become a second place a bed is marked occupied.
 */
const IcuService = {
  /** Totals, per-unit counts and every bed row, as one snapshot. */
  getBoard: () => apiClient.get('/hospital/icu/board').then((r) => r.data),

  /** Totals and per-unit counts without the bed grid — the dashboard's lighter refresh. */
  getUnits: () => apiClient.get('/hospital/icu/board/units').then((r) => r.data),

  /**
   * ICU-10: set or clear the stay's intensivist. Null leaves the admitting doctor responsible.
   *
   * The endpoint shipped in ICU-3 with its tenant-checked doctor lookup; until now nothing
   * called it.
   */
  setIntensivist: (stayPublicId, doctorId) =>
    apiClient
      .put(`/hospital/icu/stays/${stayPublicId}/intensivist`, { doctorId })
      .then((r) => r.data),

  /** ICU-4: every ICU stay for an admission, newest first. Empty when the patient has none. */
  getStaysForAdmission: (ipdId) =>
    apiClient.get(`/hospital/icu/admissions/${ipdId}/stays`).then((r) => r.data),

  /** ICU-5: fluid I/O entries for an admission, newest first, including superseded ones. */
  getIoEntries: (ipdId) =>
    apiClient.get(`/hospital/nurse/io/admission/${ipdId}`).then((r) => r.data),

  /** ICU-5: intake/output totals and net. Computed server-side from the entries, never stored. */
  getIoBalance: (ipdId) =>
    apiClient.get(`/hospital/nurse/io/admission/${ipdId}/balance`).then((r) => r.data),

  recordIoEntry: (payload) => apiClient.post('/hospital/nurse/io', payload).then((r) => r.data),

  /** Append-only: writes a new entry superseding the original, which stays readable. */
  correctIoEntry: (publicId, payload) =>
    apiClient.post(`/hospital/nurse/io/${publicId}/correction`, payload).then((r) => r.data),

  /**
   * ICU-6: every infusion for an admission, running and stopped, newest first.
   *
   * Infusions are drug delivery. They are deliberately NOT part of the I/O balance (D-1) —
   * nothing here reaches `getIoBalance`, and nothing there reaches this.
   */
  getInfusions: (ipdId) =>
    apiClient.get(`/hospital/nurse/infusions/admission/${ipdId}`).then((r) => r.data),

  /** The full rate history of one infusion, newest first, including superseded rows. */
  getInfusionRates: (publicId) =>
    apiClient.get(`/hospital/nurse/infusions/${publicId}/rates`).then((r) => r.data),

  startInfusion: (payload) =>
    apiClient.post('/hospital/nurse/infusions', payload).then((r) => r.data),

  /** Titrating APPENDS a rate; the previous one stays in the history. */
  titrateInfusion: (publicId, payload) =>
    apiClient.post(`/hospital/nurse/infusions/${publicId}/rate`, payload).then((r) => r.data),

  stopInfusion: (publicId, payload) =>
    apiClient.post(`/hospital/nurse/infusions/${publicId}/stop`, payload).then((r) => r.data),

  /** Append-only: the mistaken rate stays readable, struck through. */
  correctInfusionRate: (ratePublicId, payload) =>
    apiClient
      .post(`/hospital/nurse/infusions/rate/${ratePublicId}/correction`, payload)
      .then((r) => r.data),

  /** The rate-unit catalogue. Rates are stored as entered and never converted. */
  getInfusionRateUnits: () =>
    apiClient.get('/hospital/nurse/infusions/rate-units').then((r) => r.data),

  /**
   * ICU-7: the whole ventilator chart in one call — every entry with its parsed values, the
   * label map for every parameter key any entry holds, and the superseded ids.
   *
   * One call on purpose: a disabled or renamed parameter must never be rendered from a stale
   * catalogue, so labels arrive with the values they caption.
   */
  getVentilatorChart: (ipdId) =>
    apiClient.get(`/hospital/nurse/ventilator/admission/${ipdId}`).then((r) => r.data),

  recordVentilatorSetting: (payload) =>
    apiClient.post('/hospital/nurse/ventilator', payload).then((r) => r.data),

  /** Append-only: the original snapshot stays readable, struck through. */
  correctVentilatorSetting: (publicId, payload) =>
    apiClient
      .post(`/hospital/nurse/ventilator/${publicId}/correction`, payload)
      .then((r) => r.data),

  /** ICU-7 config: what may be charted now. Read by every clinical role. */
  getEnabledVentilatorParams: () =>
    apiClient.get('/hospital/icu/ventilator-parameters/enabled').then((r) => r.data),

  /** ICU-7 config, admin: every parameter with its effective enabled flag. */
  getVentilatorParams: () =>
    apiClient.get('/hospital/icu/ventilator-parameters').then((r) => r.data),

  /** Toggle and/or edit display name, unit and category. The key is never rewritten. */
  updateVentilatorParam: (paramKey, payload) =>
    apiClient.put(`/hospital/icu/ventilator-parameters/${paramKey}`, payload).then((r) => r.data),

  addVentilatorParam: (payload) =>
    apiClient.post('/hospital/icu/ventilator-parameters/custom', payload).then((r) => r.data),

  /** Controlled mode values. Parameter names are configurable; mode values are not. */
  getVentilatorModes: () =>
    apiClient.get('/hospital/icu/ventilator-parameters/modes').then((r) => r.data),

  /**
   * ICU-8: the whole severity-score chart in one call — every scoring with its parsed components,
   * the score-type registry as it stands, the latest per type, and the superseded ids.
   */
  getScoreChart: (ipdId) =>
    apiClient.get(`/hospital/nurse/severity-scores/admission/${ipdId}`).then((r) => r.data),

  recordScore: (payload) =>
    apiClient.post('/hospital/nurse/severity-scores', payload).then((r) => r.data),

  /** Append-only: the original scoring stays readable, struck through. */
  correctScore: (publicId, payload) =>
    apiClient
      .post(`/hospital/nurse/severity-scores/${publicId}/correction`, payload)
      .then((r) => r.data),

  /** The enabled score types with their components and ranges, for the entry form. */
  getEnabledScoreTypes: () =>
    apiClient.get('/hospital/nurse/severity-scores/types').then((r) => r.data),

  /** ICU-8 config, admin: every score type with its effective enabled flag. */
  getScoreTypes: () => apiClient.get('/hospital/icu/score-types').then((r) => r.data),

  /** The only thing configurable: whether this hospital uses the score. */
  toggleScoreType: (scoreType, payload) =>
    apiClient.put(`/hospital/icu/score-types/${scoreType}`, payload).then((r) => r.data),

  /** ICU-9 config, admin: every alertable vitals metric with its configured bounds. */
  getAlertThresholds: () => apiClient.get('/hospital/icu/alert-thresholds').then((r) => r.data),

  /** Set or clear the bounds for one metric, or turn it off. */
  saveAlertThreshold: (metricKey, payload) =>
    apiClient.put(`/hospital/icu/alert-thresholds/${metricKey}`, payload).then((r) => r.data),

  /** Ward unit-type catalogue for the ward form's classification selector. */
  getUnitTypes: () => apiClient.get('/hospital/icu/unit-types').then((r) => r.data),
};

export default IcuService;
