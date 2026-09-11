import React from 'react';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import { printHtml } from '../../../utils/printHtml';
import { buildPrintHtml, buildConsentHtml } from './AdmissionFormModal';
import { buildAssessmentHtml } from './InitialAssessmentPanel';
import { buildReassessmentHtml } from './NotesPanel';
import { buildSugarChartHtml } from './SugarChartPanel';
import { buildIoChartHtml } from './VitalsPanel';
import { buildVulnerabilityHtml } from './VulnerabilityAssessmentPanel';

/**
 * NurseFormsView - blank form library (top-level nurse tab).
 *
 * Reuses the exact same print builders as the per-patient panels, but calls
 * them with EMPTY patient/clinical data so every filled field prints blank.
 * The nurse can print a clean template and fill it fully offline. Only the
 * hospital branding (logo / name / address) is retained — no "SAMPLE"
 * watermark, so the printout is a usable real form.
 */

// The hospital letterhead only; no patient identifiers (UHID, nurse, etc.).
const brandingOnly = (user) => ({
  name: user?.hospitalName,
  address: user?.hospitalAddress,
  logo: user?.logoUrl,
  // customId / nurse intentionally omitted so those fields print blank
});

// Each entry builds a completely blank version of the form. The data args
// (patient form `{}`, empty note/vitals/sugar arrays, empty assessment `{}`)
// carry no values, so the builders render every dynamic field empty.
const FORMS = [
  {
    title: 'Admission Form',
    desc: 'Patient admission / registration record.',
    build: (H) => buildPrintHtml({}, H),
  },
  {
    title: 'General Consent Form',
    desc: 'Consent for admission and treatment.',
    build: (H) => buildConsentHtml({}, H),
  },
  {
    title: 'Admission History & Initial Assessment',
    desc: 'Nursing history and initial assessment sheet.',
    build: (H) => buildAssessmentHtml({}, [], H, {}),
  },
  {
    title: 'Vulnerability Assessment',
    desc: 'Vulnerability screening and patient transfer form.',
    build: (H) => buildVulnerabilityHtml({}, H, {}),
  },
  {
    title: 'Re-Assessment Sheet',
    desc: 'Nurse progress / re-assessment notes.',
    build: (H) => buildReassessmentHtml([], {}, H),
  },
  {
    title: 'Sugar Chart',
    desc: 'Blood sugar monitoring and treatment chart.',
    build: (H) => buildSugarChartHtml([], {}, H),
  },
  {
    title: 'Input & Output Chart',
    desc: 'Vitals and fluid input / output monitoring.',
    build: (H) => buildIoChartHtml([], {}, H),
  },
];

const NurseFormsView = () => {
  const { error: toastError } = useToast();
  const user = authService.getCurrentUser();

  const printBlank = (form) => {
    printHtml(form.build(brandingOnly(user)));
  };

  return (
    <div>
      <div className="mb-5 rounded-xl border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-800">
        Print a blank copy of any form to fill in by hand. Every field prints empty — only your
        hospital&apos;s name and logo are included.
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {FORMS.map((form) => (
          <div
            key={form.title}
            className="flex flex-col justify-between rounded-2xl border border-gray-200 bg-white p-5 shadow-sm hover:shadow-md transition"
          >
            <div>
              <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-xl bg-gray-900 text-white">
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
              </div>
              <h3 className="text-sm font-bold text-gray-900">{form.title}</h3>
              <p className="mt-1 text-xs text-gray-500">{form.desc}</p>
            </div>
            <button
              onClick={() => printBlank(form)}
              className="mt-4 inline-flex items-center justify-center gap-2 rounded-lg bg-gray-900 px-4 py-2.5 text-sm font-semibold text-white hover:bg-gray-700 transition"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                />
              </svg>
              Print Blank Form
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

export default NurseFormsView;
