import BloodConsentForm from './BloodConsentForm';
import DrugAdministrationSheet from './DrugAdministrationSheet';
import GeneralAnaesthesiaConsentForm from './GeneralAnaesthesiaConsentForm';
import GeneralAnaesthesiaRecordForm from './GeneralAnaesthesiaRecordForm';
import InformedConsentAnaesthesiaForm from './InformedConsentAnaesthesiaForm';
import InformedConsentSurgeryForm from './InformedConsentSurgeryForm';
import IoChartForm from './IoChartForm';
import PostAnaesthesiaRecoveryForm from './PostAnaesthesiaRecoveryForm';
import PostOperativeCarePlanForm from './PostOperativeCarePlanForm';
import PostOperativeChecklist02Form from './PostOperativeChecklist02Form';
import PostOperativeChecklistForm from './PostOperativeChecklistForm';
import PreAnaesthesiaEvaluationForm from './PreAnaesthesiaEvaluationForm';
import PreOperativeChecklistForm from './PreOperativeChecklistForm';
import SurgicalCaseRecordForm from './SurgicalCaseRecordForm';
import WhoSafetyChecklistForm from './WhoSafetyChecklistForm';

/**
 * Registry of the mandatory OT/NABH surgery forms shown in the nurse's
 * "Consent Forms" tab (for surgery patients).
 *
 * Each entry: { type, title, code, Component }.
 *  - `type` is the stable key persisted in surgery_forms.form_type.
 *  - `Component` receives { admissionId, onClose }. When null, the form is not
 *    built yet (listed but not openable) — we add each one from its reference
 *    image over time.
 *
 * Order matches the required checklist.
 */
const SURGERY_FORMS = [
  {
    type: 'BLOOD_CONSENT',
    title: 'Blood Consent Form',
    code: 'VH/NABH/OT/02/2026',
    Component: BloodConsentForm,
  },
  { type: 'IO_CHART', title: 'Input & Output Chart', code: '', Component: IoChartForm },
  {
    type: 'GA_CONSENT',
    title: 'Consent Form for General Anaesthesia',
    code: 'VH/NABH/OT/03/2026',
    Component: GeneralAnaesthesiaConsentForm,
  },
  {
    type: 'DRUG_ADMIN_SHEET',
    title: 'Drug Administration Sheet',
    code: 'VH/NABH/OT/12/2026',
    Component: DrugAdministrationSheet,
  },
  {
    type: 'INFORMED_CONSENT_ANAES',
    title: 'Informed Consent — Anaesthesia',
    code: 'VH/NABH/OT/02/2026',
    Component: InformedConsentAnaesthesiaForm,
  },
  {
    type: 'INFORMED_CONSENT_SURGERY',
    title: 'Informed Consent — Surgery',
    code: 'VH/NABH/OT/01/2026',
    Component: InformedConsentSurgeryForm,
  },
  {
    type: 'PRE_OP_CHECKLIST',
    title: 'Pre-Operative Checklist',
    code: 'VH/NABH/OT/03/2026',
    Component: PreOperativeChecklistForm,
  },
  {
    type: 'PRE_ANAES_EVAL',
    title: 'Pre-Anaesthesia Evaluation',
    code: 'VH/NABH/OT/03/2026',
    Component: PreAnaesthesiaEvaluationForm,
  },
  {
    type: 'GENERAL_ANAESTHESIA',
    title: 'General Anaesthesia',
    code: '',
    Component: GeneralAnaesthesiaRecordForm,
  },
  {
    type: 'SURGICAL_CASE_RECORD',
    title: 'Surgical Case Record',
    code: 'VH/NABH/OT/04/2026',
    Component: SurgicalCaseRecordForm,
  },
  {
    type: 'POST_OP_CARE_PLAN',
    title: 'Post-Operative Care Plan',
    code: 'VH/NABH/OT/09/2026',
    Component: PostOperativeCarePlanForm,
  },
  {
    type: 'POST_OP_CHECKLIST_10',
    title: 'Post-Operative Checklist',
    code: 'VH/NABH/OT/10/2026',
    Component: PostOperativeChecklistForm,
  },
  {
    type: 'POST_OP_CHECKLIST_02',
    title: 'Post-Operative Checklist (+ I/O page)',
    code: 'VH/NABH/OT/02/2026',
    Component: PostOperativeChecklist02Form,
  },
  {
    type: 'POST_ANAES_RECOVERY',
    title: 'Post-Anaesthesia Recovery Chart',
    code: 'VH/NABH/OT/03/2026',
    Component: PostAnaesthesiaRecoveryForm,
  },
  {
    type: 'WHO_CHECKLIST',
    title: 'WHO Surgical Safety Checklist',
    code: 'Landscape',
    Component: WhoSafetyChecklistForm,
  },
];

export default SURGERY_FORMS;
