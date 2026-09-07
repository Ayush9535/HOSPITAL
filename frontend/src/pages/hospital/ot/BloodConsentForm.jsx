import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Blood Consent Form — VH/NABH/OT/02/2026
 * "Consent for Blood & Blood Products Transfusion".
 * Known patient data auto-fills the header; the explaining doctor, MLC no.,
 * date/time and interpreter fields are editable/optional. Signature lines print
 * blank for offline signing.
 */

const esc = escapeHtml;

const buildPrintHtml = (data, prefill, hospital) => {
  const f = prefill || {};
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = esc(
    titleCase([f.patientSurname, f.patientFirstName, f.husbandFatherName].filter(Boolean).join(' '))
  );
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M'),
    isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:56px;width:auto;object-fit:contain"/>`
    : '';
  const doctorName = esc(titleCase(data.doctorName || f.refDr || f.underCareOfDr || ''));
  const yes = data.interpreterRequired === 'YES';
  const no = data.interpreterRequired === 'NO';
  const tick = (on) => `<b class="bx">${on ? '✓' : '&nbsp;'}</b>`;

  // A signature row: label + blank underline, then Signature / Date / Time blanks.
  const sig = (label, value = '') =>
    `<div class="sigrow">
            <span class="sc"><b>${label} :</b> <span class="ul">${esc(value)}</span></span>
            <span class="sc s"><b>Signature :</b> <span class="ul"></span></span>
            <span class="sc s"><b>Date :</b> <span class="ul"></span></span>
            <span class="sc s"><b>Time :</b> <span class="ul"></span></span>
        </div>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Blood Consent Form</title>
    <style>
      @page { size: A4; margin: 10mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; line-height:1.45; }
      .head { text-align:center; }
      .hname { font-size:22px; font-weight:800; color:#1d4ed8; margin:6px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:15px; font-weight:800; margin:12px 0 8px; text-transform:uppercase; }
      .idbox { border:1px solid #111; padding:8px 12px; font-size:12px; }
      .idrow { display:flex; gap:20px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:15px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      p { margin:8px 0; text-align:justify; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:4px; }
      .sigrow { display:flex; gap:14px; margin:9px 0; align-items:flex-end; }
      .sc { display:flex; align-items:flex-end; gap:5px; flex:1.6; }
      .sc.s { flex:1; }
      .ul { border-bottom:1px solid #666; flex:1; min-height:14px; padding:0 3px; }
      .sub { font-weight:700; margin:10px 0 2px; }
      .bx { border:1px solid #111; padding:0 6px; margin:0 4px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">Consent for Blood &amp; Blood Products Transfusion</div>
      </div>

      <div class="idbox">
        <div class="idrow">
          <span><b>UHID No. :</b> <span class="flexval">${esc(hospital.customId)}</span></span>
          <span><b>IPD No. :</b> <span class="flexval">${esc(f.ipdRegistrationNo)}</span></span>
          <span><b>MLC No. :</b> <span class="flexval">${esc(data.mlcNo)}</span></span>
          <span><b>Bed No. :</b> <span class="flexval">${esc(f.bedNo)}</span></span>
        </div>
        <div class="idrow"><span style="flex:2"><b>Patient Name :</b> <span class="flexval">${patientName}</span></span></div>
        <div class="idrow">
          <span><b>Age :</b> <span class="flexval">${esc(f.age)}</span></span>
          <span style="flex:0.7"><b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></span>
          <span><b>Date :</b> <span class="flexval">${esc(data.date)}</span></span>
          <span><b>Time :</b> <span class="flexval">${esc(data.time)}</span></span>
        </div>
      </div>

      <p>Dr. <b>${doctorName || '_______________________'}</b> has explained to me the Potential complications of transfusion of blood/ blood products which include the risk of transmission of infectious diseases such as Hepatitis, HIV-AIDS and other bacterial and parasitic diseases, allergic and febrile reactions etc.</p>

      <p>I understand that all units of blood collected in the blood bank of Hospital are tested for antibodies to HIV1 / HIV2, Hepatitis C, Hepatitis B, Surface Antigen, Syphilis (Rapid Plasma Regain Test) and screened for Malarial Parasite; and that only units that are negative for these infectious diseases are released for transfusion; and that sterility checks and quality control procedures are regularly performed on samples of blood components.</p>

      <p>I also realize that no known test method can offer complete assurance that products derived from human sources will not transmit infection.</p>

      <p>I have read and understood this document and had a chance to clarify my doubts.</p>

      <p>If need arises for the transfusion of blood /products as part of my care, I here by give consent to such transfusion.</p>

      ${sig('Patient Name', patientName)}
      <div class="sub">If patient is unable to sign, the name and relation of the person signing on his/her behalf</div>
      ${sig('Name')}
      ${sig("Doctor's Name", doctorName)}
      <div class="sub">Witness on behalf at the Patient:</div>
      ${sig('Witness Name')}
      <div class="sub">Witness on behalf of the Hospital:</div>
      ${sig('Witness Name')}

      <div class="sub">Interpreter's Statement :</div>
      <div>Specific language requirements (if any) : <b>${esc(data.interpreterLanguage)}</b></div>
      <div style="margin:6px 0">Interpreter services required &nbsp; Yes ${tick(yes)} &nbsp; No ${tick(no)}</div>
      <p>I confirm that I have accurately interpreted the contents of this form, and the related conversation/s between the Patient / person giving consent and the doctor.</p>
      ${sig("Interpreter's Name")}
      <div class="sigrow"><span class="sc"><b>Full Name :</b> <span class="ul"></span></span></div>

      <div class="code">VH/NABH/OT/02/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const Labelled = ({ label, children }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    {children}
  </div>
);

const BloodConsentForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="BLOOD_CONSENT"
    title="Blood Consent Form"
    code="VH/NABH/OT/02/2026"
    defaults={{
      doctorName: '',
      mlcNo: '',
      date: '',
      time: '',
      interpreterRequired: '',
      interpreterLanguage: '',
    }}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => (
      <div className="space-y-4">
        <div className="rounded-lg bg-gray-50 border border-gray-200 px-3 py-2 text-xs text-gray-600">
          For{' '}
          <b>
            {titleCase(
              [prefill.patientSurname, prefill.patientFirstName, prefill.husbandFatherName]
                .filter(Boolean)
                .join(' ')
            ) || '—'}
          </b>
          {prefill.ipdRegistrationNo ? ` · IPD ${prefill.ipdRegistrationNo}` : ''}
          {prefill.bedNo ? ` · Bed ${prefill.bedNo}` : ''} — header fills automatically on the
          printout.
        </div>
        <Labelled label="Doctor who explained (Dr.)">
          <input
            value={data.doctorName ?? (prefill.refDr || prefill.underCareOfDr || '')}
            onChange={(e) => set('doctorName', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            placeholder="Doctor's name"
          />
        </Labelled>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <Labelled label="MLC No.">
            <input
              value={data.mlcNo}
              onChange={(e) => set('mlcNo', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              placeholder="If applicable"
            />
          </Labelled>
          <Labelled label="Date">
            <DateSelect value={data.date} onChange={(v) => set('date', v)} />
          </Labelled>
          <Labelled label="Time">
            <input
              type="time"
              value={data.time}
              onChange={(e) => set('time', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </Labelled>
        </div>
        <Labelled label="Interpreter services required">
          <div className="flex gap-4 text-sm">
            {['YES', 'NO'].map((v) => (
              <label key={v} className="inline-flex items-center gap-1.5">
                <input
                  type="radio"
                  name="interpreterRequired"
                  checked={data.interpreterRequired === v}
                  onChange={() => set('interpreterRequired', v)}
                />
                {v === 'YES' ? 'Yes' : 'No'}
              </label>
            ))}
          </div>
        </Labelled>
        <Labelled label="Specific language requirements (if any)">
          <input
            value={data.interpreterLanguage}
            onChange={(e) => set('interpreterLanguage', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            placeholder="Language"
          />
        </Labelled>
        <p className="text-xs text-gray-400">
          All signature lines print blank for offline signing. Fields left empty print empty.
        </p>
      </div>
    )}
  />
);

export default BloodConsentForm;
