import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Informed Consent — Anaesthesia — VH/NABH/OT/02/2026.
 * Two-box header (auto-filled from admission data). Fixed risk text, editable
 * anaesthesia-type checkboxes + individual-risk-factor line, blank signatures.
 */

const esc = escapeHtml;

const ANAES_TYPES = [
  'General Anaesthesia',
  'Nerve Block',
  'Monitored Anaesthesia care with sedation',
  'Spinal Anaesthesia',
  'Monitored Anaesthesia care without sedation',
  'Epidural Anaesthesia / Analgesia',
];

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
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:52px;width:auto;object-fit:contain"/>`
    : '';
  const types = Array.isArray(data.anaesthesiaTypes) ? data.anaesthesiaTypes : [];
  const cb = (label) =>
    `<span class="cb">${types.includes(label) ? '✓' : '&nbsp;'}</span> ${label}`;
  const anaes = esc(titleCase(data.anaesthetistName || ''));

  return `<!doctype html><html><head><meta charset="utf-8"><title>Informed Consent Anaesthesia</title>
    <style>
      @page { size: A4; margin: 9mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:10.5px; margin:0; line-height:1.35; }
      .top { display:flex; align-items:center; justify-content:space-between; }
      .brand { text-align:left; }
      .hname { font-size:18px; font-weight:800; color:#1d4ed8; }
      .haddr { font-size:11px; font-weight:600; color:#7c3aed; }
      .tbar { background:#1f2937; color:#fff; font-weight:800; font-size:14px; padding:8px 14px; }
      .idwrap { display:flex; border:1px solid #111; margin-top:8px; }
      .idcol { flex:1; padding:6px 10px; }
      .idcol + .idcol { border-left:1px solid #111; }
      .idline { display:flex; align-items:flex-end; gap:5px; margin:5px 0; }
      .ul { border-bottom:1px solid #666; flex:1; min-height:13px; padding:0 3px; }
      .ul.w { flex:0.5; }
      .sex b { border:1px solid #111; padding:0 5px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      p { margin:5px 0; text-align:justify; }
      .num { margin:3px 0; padding-left:20px; text-indent:-20px; }
      .num .n { display:inline-block; width:16px; font-weight:700; }
      .sub2 { display:grid; grid-template-columns:1fr 1fr; gap:0 16px; margin:2px 0 2px 20px; text-indent:0; }
      .cbgrid { display:grid; grid-template-columns:1fr 1fr; gap:3px 16px; margin:4px 0 4px 10px; }
      .cb { display:inline-block; width:14px; height:14px; border:1px solid #111; text-align:center; line-height:13px; margin-right:5px; }
      .sigrow { display:flex; gap:20px; margin:10px 0; align-items:flex-end; }
      .sc { display:flex; align-items:flex-end; gap:5px; flex:1; }
      .sul { border-bottom:1px solid #666; flex:1; min-height:14px; padding:0 3px; }
      .note { font-size:9.5px; color:#444; margin-top:2px; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:6px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">INFORMED CONSENT ANAESTHESIA</div>
      </div>

      <div class="idwrap">
        <div class="idcol">
          <div class="idline"><b>Patient's Name :</b> <span class="ul">${patientName}</span></div>
          <div class="idline"><b>Patient's Address :</b> <span class="ul">${esc(f.patientAddress)}</span></div>
          <div class="idline"><b>Age :</b> <span class="ul w">${esc(f.age)}</span> <b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></div>
        </div>
        <div class="idcol">
          <div class="idline"><b>PRN No. :</b> <span class="ul">${esc(f.prnNo)}</span></div>
          <div class="idline"><b>IPD No. :</b> <span class="ul">${esc(f.ipdRegistrationNo)}</span></div>
          <div class="idline"><b>Category :</b> <span class="ul">${esc(f.category)}</span> <b>Bed No. :</b> <span class="ul w">${esc(f.bedNo)}</span></div>
          <div class="idline"><b>Date :</b> <span class="ul">${esc(data.date)}</span> <b>Time :</b> <span class="ul">${esc(data.time)}</span></div>
        </div>
      </div>

      <p>Prior to being given an anaesthetic, all patients are requested to read and sign this form which is intended to give an idea of the risks involved in undergoing anaesthesia. The vast majority of patients will not experience any problems or complications with anaesthesia.</p>
      <p>However the risk of complications is always present and must be considered. Listed below are some areas where difficulties may arise. It is not possible to list every conceivable complication of anesthesia. These complications occur rarely and can often be treated without serious after effects. However the risk of complications and / or death is always present.</p>

      <p><b>A)</b> I understand that during my procedure/surgery invasive monitoring may be necessary. I understand the risks &amp; benefits associated with this type of monitoring which have been fully explained to me.</p>
      <div class="num"><span class="n">1)</span> Patients with pre-existing medical problems have a greater risk of developing complications during and following anaesthesia.</div>
      <div class="num"><span class="n">2)</span> Patients receiving general anaesthesia may require a tube in the windpipe (Endotracheal intubation). The intubation could cause a sore throat. Also, teeth or dental prosthesis could be loosened.</div>
      <div class="num"><span class="n">3)</span> Allergic reaction can occur. You must inform us of any allergy you have.</div>
      <div class="num"><span class="n">4)</span> Nausea and Vomiting can occur.</div>
      <div class="num"><span class="n">5)</span> Risks related to Spinal Epidural Analgesia / Anesthesia : Headache, backache, buzzing in the ears, convulsions, infection, persistent weakness, numbness, residual pain, injury to blood vessels, "total spinal".</div>
      <div class="num"><span class="n">6)</span> Risks related to Nerve blocks: infection, convulsions, weakness, persistent numbness, residual pain requiring additional anesthesia, injury to blood vessels, failed block.</div>
      <div class="num"><span class="n">7)</span> To access his/her own medical records.</div>
      <div class="num"><span class="n">8)</span> Risk related to Anaesthesia are increased by
        <div class="sub2">
          <div>a) A bad cold or flu, asthma or other chest disease</div><div>b) Being over weight</div>
          <div>c) Diabetes</div><div>d) Heart problems</div>
          <div>e) Kidney disease</div><div>f) High blood pressure</div>
          <div>g) Other serious medical conditions</div>
        </div>
      </div>
      <div class="num"><span class="n">9)</span> Individual risk factor : <span class="ul" style="display:inline-block;min-width:60%">${esc(data.riskFactor)}</span></div>

      <p><b>B)</b> Type of Anaesthesia Planned : Regional Block</p>
      <div class="cbgrid">
        <div>${cb('General Anaesthesia')}</div><div>${cb('Nerve Block')}</div>
        <div>${cb('Monitored Anaesthesia care with sedation')}</div><div>${cb('Spinal Anaesthesia')}</div>
        <div>${cb('Monitored Anaesthesia care without sedation')}</div><div>${cb('Epidural Anaesthesia / Analgesia')}</div>
      </div>

      <p><b>C)</b> It has been explained to me that (1) sometimes an anaesthesia technique which involves the use of local anaesthetics, with or without sedation, may not succeed completely &amp; therefore general anaesthesia technique may have to be used and / or (2) while I am receiving anaesthesia, condition may develop which may require modification of anaesthesia technique.</p>

      <div class="sigrow">
        <span class="sc"><b>Patient's Name :</b> <span class="sul">${patientName}</span></span>
        <span class="sc"><b>Signature :</b> <span class="sul"></span></span>
      </div>
      <div class="sigrow">
        <span class="sc"><b>Witness Name :</b> <span class="sul"></span></span>
        <span class="sc"><b>Signature :</b> <span class="sul"></span></span>
      </div>
      <div class="sigrow" style="margin-top:0">
        <span class="sc note">(or person or relative or guardian authorised to sign for patient)</span>
        <span class="sc note">(Relation With Patient)</span>
      </div>
      <div class="sigrow">
        <span class="sc"><b>Anaesthetist's Name :</b> <span class="sul">${anaes}</span></span>
        <span class="sc"><b>Signature :</b> <span class="sul"></span></span>
      </div>

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

const InformedConsentAnaesthesiaForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="INFORMED_CONSENT_ANAES"
    title="Informed Consent — Anaesthesia"
    code="VH/NABH/OT/02/2026"
    defaults={{ date: '', time: '', riskFactor: '', anaesthetistName: '', anaesthesiaTypes: [] }}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => {
      const types = Array.isArray(data.anaesthesiaTypes) ? data.anaesthesiaTypes : [];
      const toggle = (t) =>
        set('anaesthesiaTypes', types.includes(t) ? types.filter((x) => x !== t) : [...types, t]);
      return (
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
            {prefill.category ? ` · ${prefill.category}` : ''} — header fills automatically on the
            printout.
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
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
          <Labelled label="Type of Anaesthesia Planned">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {ANAES_TYPES.map((t) => (
                <label key={t} className="inline-flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={types.includes(t)} onChange={() => toggle(t)} />
                  {t}
                </label>
              ))}
            </div>
          </Labelled>
          <Labelled label="Individual risk factor (optional)">
            <input
              value={data.riskFactor}
              onChange={(e) => set('riskFactor', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              placeholder="Any specific risk factor"
            />
          </Labelled>
          <Labelled label="Anaesthetist's Name">
            <input
              value={data.anaesthetistName}
              onChange={(e) => set('anaesthetistName', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              placeholder="Anaesthetist's name"
            />
          </Labelled>
          <p className="text-xs text-gray-400">Signature lines print blank for offline signing.</p>
        </div>
      );
    }}
  />
);

export default InformedConsentAnaesthesiaForm;
