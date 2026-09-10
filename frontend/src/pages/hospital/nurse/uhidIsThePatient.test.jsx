import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { buildConsentHtml } from './AdmissionFormModal';
import { buildReassessmentHtml } from './NotesPanel';
import { buildSugarChartHtml } from './SugarChartPanel';
import { buildIoChartHtml } from './VitalsPanel';
import { buildVulnerabilityHtml } from './VulnerabilityAssessmentPanel';

/**
 * The "UHID No." field on the NABH forms has to identify the patient.
 *
 * It was printing hospital.customId — the facility's own id, identical on every patient's
 * chart — so a printed vitals sheet, sugar chart or consent form carried no way to tell one
 * patient from another beyond the name. The value comes from prnNo, the patient registration
 * number the admission prefill already supplies and eleven other forms already print.
 */

// A hospital whose id is deliberately distinctive: if it ever reaches the UHID field again,
// these assertions say so by name rather than by a vague mismatch.
const hospital = { name: 'Sunrise', address: 'MG Road', customId: 'HOSP-FACILITY-ID-999' };
const withPatient = { prnNo: 'PAT42', ipdRegistrationNo: 'IPD-7', patientFirstName: 'Neha' };
const withoutPrn = { ipdRegistrationNo: 'IPD-7', patientFirstName: 'Neha' };

/** The label and the value that follows it, so a match elsewhere in the page cannot fool us. */
const uhidValue = (html) => {
  // Two shapes in use: the inline `<b>UHID No. :</b> <span class="flexval">…` header used by
  // most forms, and the fld() helper's `<span class="lbl">UHID No:</span><span class="val">…`
  // on the admission form.
  // &nbsp; separates label from value in one of the two fld() helpers.
  const m = html.match(
    /UHID No\.?\s*:\s*<\/(?:b|span)>(?:&nbsp;|\s)*<span class="(?:flexval|val)">([\s\S]*?)<\/span>/
  );
  return m ? m[1].trim() : null;
};

const forms = [
  ['I/O chart (vitals)', (f) => buildIoChartHtml([], f, hospital, [])],
  ['sugar chart', (f) => buildSugarChartHtml([], f, hospital)],
  ['re-assessment notes', (f) => buildReassessmentHtml([], f, hospital)],
  ['vulnerability assessment', (f) => buildVulnerabilityHtml(f, hospital, {})],
  ['general consent form', (f) => buildConsentHtml(f, hospital)],
];

describe('NABH forms — the UHID field identifies the patient', () => {
  it.each(forms)('%s prints the patient PRN', (_name, build) => {
    expect(uhidValue(build(withPatient))).toBe('PAT42');
  });

  it.each(forms)('%s never prints the hospital id as the patient UHID', (_name, build) => {
    const html = build(withPatient);
    // Anywhere on the page, not merely in the UHID field: the facility id is not a patient
    // identifier and these forms have no other reason to carry it.
    expect(html).not.toContain('HOSP-FACILITY-ID-999');
  });

  it.each(forms)('%s falls back to a dash when the patient has no PRN', (_name, build) => {
    // Patients created through the appointment path currently have a null customId, so this
    // is reachable today. A blank line reads as "not filled in"; a dash reads as "none on file".
    const value = uhidValue(build(withoutPrn));
    expect(value).toBe('—');
    expect(value).not.toMatch(/undefined|null/);
  });

  it.each(forms)('%s prints no literal undefined or null anywhere', (_name, build) => {
    const html = build(withoutPrn);
    expect(html).not.toMatch(/>undefined</);
    expect(html).not.toMatch(/>null</);
  });
});

describe('no form pairs the UHID label with a hospital identifier', () => {
  // The audit named four files; there were ten, five of them OT forms whose builders are not
  // exported and so are not covered above. This walks the printable forms instead, so a new
  // form that copies the old header is caught wherever it is added.
  it('holds across every nurse and OT form', () => {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..');
    const offenders = [];
    const walk = (dir) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (/\.jsx$/.test(entry.name) && !/\.test\./.test(entry.name)) {
          for (const line of readFileSync(full, 'utf8').split('\n')) {
            if (/UHID/i.test(line) && /hospital[?.]/.test(line)) offenders.push(`${entry.name}: ${line.trim()}`);
          }
        }
      }
    };
    walk(root);
    expect(offenders).toEqual([]);
  });
});
