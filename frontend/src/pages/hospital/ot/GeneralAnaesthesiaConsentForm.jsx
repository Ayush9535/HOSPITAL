import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Consent Form for General / Spinal Anaesthesia — VH/NABH/OT/03/2026.
 * Fixed consent text; header auto-fills from admission data; MLC/date/time and
 * the anaesthetist name are editable. Signature lines print blank.
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
  const anaes = esc(titleCase(data.anaesthetistName || ''));

  const sig = (label, value = '') =>
    `<div class="sigrow">
            <span class="sc"><b>${label} :</b> <span class="ul">${esc(value)}</span></span>
            <span class="sc s"><b>Signature :</b> <span class="ul"></span></span>
            <span class="sc s"><b>Date :</b> <span class="ul"></span></span>
            <span class="sc s"><b>Time :</b> <span class="ul"></span></span>
        </div>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Consent for General Anaesthesia</title>
    <style>
      @page { size: A4; margin: 10mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; line-height:1.4; }
      .head { text-align:center; }
      .hname { font-size:22px; font-weight:800; color:#1d4ed8; margin:6px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:14px; font-weight:800; margin:12px 0 8px; text-transform:uppercase; }
      .idbox { border:1px solid #111; padding:8px 12px; font-size:12px; }
      .idrow { display:flex; gap:20px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:15px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      h3 { font-size:12px; margin:10px 0 3px; }
      p { margin:5px 0; text-align:justify; }
      ol { margin:3px 0 3px 20px; padding:0; } li { margin:2px 0; }
      .sigrow { display:flex; gap:14px; margin:9px 0; align-items:flex-end; }
      .sc { display:flex; align-items:flex-end; gap:5px; flex:1.6; }
      .sc.s { flex:1; }
      .ul { border-bottom:1px solid #666; flex:1; min-height:14px; padding:0 3px; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:8px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">Consent Form for General Anaesthesia / Spinal Anaesthesia</div>
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

      <h3>Proposed Treatment</h3>
      <p>The doctors have explained that I am having the following operation and anaesthetist explained that General anaesthesia is proposed for the same.</p>

      <h3>Procedure for Giving General Anaesthesia</h3>
      <p>General anaesthesia is usually started with an injection of a quick acting medication given into a vein to put the patient to sleep (with memory loss for the event, muscle relaxation and pain relief). If the operation needs muscle relaxation the anaesthetist will also take over the person's breathing using a tube to protect the airway.</p>
      <p>The anaesthesia is sustained using gases (which are inhaled in) and medication for pain are given into the vein. Other drugs may be used, for example, to control the blood pressure or heart rate to prevent nausea and vomiting. At the end of the operation the patient wakes up gradually, while the anaesthetist and a nursing staff continuously monitor the vital parameters and protect the airway until the patient is fully awake. Blood may be needed to be given during the required operation.</p>

      <h3>Risks</h3>
      <p><b>While anaesthesia is generally very safe, there are always associated risks. Commonly after general anaesthesia I may notice one or more of the following</b></p>
      <ol>
        <li>Headache or blurred vision.</li>
        <li>Bruising at the site of injections or drips.</li>
        <li>Nausea or vomiting (although the anaesthetist will limit or prevent this as far as possible).</li>
        <li>Sore throat from the gases and/or the breathing tube. I may notice temporary difficulty in speaking. This should improve after some hours.</li>
        <li>Temporary muscle pains.</li>
        <li>Damage to teeth or dental works, lips or tongue.</li>
      </ol>
      <p><b>The risk of more serious events are very low in normally fit and healthy people, but I may have</b></p>
      <ol>
        <li>An asthmatic reaction (wheezing).</li>
        <li>I also understand that risks are more likely if I am a smoker, hypertensive, an asthmatic, a diabetic, a hypersensitive or overweight or have bad previous history of heart disease.</li>
        <li>I understand that in the event of hemodynamic instability or a delayed recovery from anaesthesia after the procedure, I may need to be artificially ventilated for a period deemed appropriate by my anaesthetist.</li>
      </ol>

      <h3>Declaration by the patient</h3>
      <ol>
        <li>I acknowledge that the anaesthetist has informed me about the anaesthetic procedure, alternative treatments and answered my specific queries and concerns about this matter.</li>
        <li>I acknowledge that I have discussed with the anaesthetist regarding significant risks and complications specific to my individual circumstances.</li>
        <li>I understand that an anaesthetist training to be a specialist anaesthetist may administer the anaesthesia, but under supervision.</li>
        <li>I consent to have blood transfusion if needed.</li>
        <li>If any person is injured or exposed to my blood or other body fluids then I give my consent to a sample of my blood being collected for the purpose of testing for infectious diseases, such as Hepatitis B and HIV.</li>
        <li>I understand all the above mentioned details and give my full consent for the general anaesthesia.</li>
      </ol>

      ${sig('Patient Name', patientName)}
      ${sig('Anaesthetist Name', anaes)}
      ${sig('Witness Name')}

      <div class="code">VH/NABH/OT/03/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const Labelled = ({ label, children }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    {children}
  </div>
);

const GeneralAnaesthesiaConsentForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="GA_CONSENT"
    title="Consent Form for General Anaesthesia"
    code="VH/NABH/OT/03/2026"
    defaults={{ mlcNo: '', date: '', time: '', anaesthetistName: '' }}
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
        <Labelled label="Anaesthetist Name">
          <input
            value={data.anaesthetistName}
            onChange={(e) => set('anaesthetistName', e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            placeholder="Anaesthetist's name"
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
        <p className="text-xs text-gray-400">
          Signature lines print blank for offline signing. Empty fields print empty.
        </p>
      </div>
    )}
  />
);

export default GeneralAnaesthesiaConsentForm;
