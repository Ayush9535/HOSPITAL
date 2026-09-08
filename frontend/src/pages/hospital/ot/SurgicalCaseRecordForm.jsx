import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Surgical Case Record — VH/NABH/OT/04/2026 (two pages).
 * Page 1: header + ID + case fields + Operation Notes. Page 2: Anaesthetist
 * Notes + surgeon/anaesthetist signatures. Notes print over ruled lines.
 */

const esc = escapeHtml;

const buildPrintHtml = (data, prefill, hospital) => {
  const f = prefill || {};
  const d = data || {};
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = esc(
    titleCase([f.patientSurname, f.patientFirstName, f.husbandFatherName].filter(Boolean).join(' '))
  );
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M'),
    isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:54px;width:auto;object-fit:contain"/>`
    : '';
  const val = (v) => `<span class="line">${esc(v)}</span>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Surgical Case Record</title>
    <style>
      @page { size: A4; margin: 10mm; }
      * { box-sizing: border-box; }
      html, body { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; line-height:1.4; }
      .head { text-align:center; }
      .hname { font-size:21px; font-weight:800; color:#1d4ed8; margin:4px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:15px; font-weight:800; margin:10px 0 8px; text-transform:uppercase; }
      .idbox { border:1px solid #111; padding:8px 12px; font-size:12px; }
      .idrow { display:flex; gap:20px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:15px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      .fr { display:flex; gap:22px; margin:9px 0; }
      .fc { flex:1; display:flex; align-items:flex-end; gap:6px; }
      .fc2 { flex:1; display:flex; align-items:flex-end; gap:6px; }
      .line { border-bottom:1px solid #666; flex:1; min-height:14px; padding:0 3px; display:inline-block; }
      .sech { text-align:center; font-weight:800; text-decoration:underline; margin:12px 0 8px; }
      .ruled { background-image: repeating-linear-gradient(transparent, transparent 23px, #bbb 23px, #bbb 24px); line-height:24px; white-space:pre-wrap; padding-top:1px; }
      .signs { display:flex; gap:40px; margin-top:24px; }
      .signs .sc { flex:1; }
      .signs .sc b { display:block; margin-bottom:8px; }
      .signs .sc div { margin:8px 0; display:flex; align-items:flex-end; gap:6px; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:8px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">Surgical Case Record</div>
      </div>

      <div class="idbox">
        <div class="idrow">
          <span><b>UHID No. :</b> <span class="flexval">${esc(hospital.customId)}</span></span>
          <span><b>IPD No. :</b> <span class="flexval">${esc(f.ipdRegistrationNo)}</span></span>
          <span><b>MLC No. :</b> <span class="flexval">${esc(d.mlcNo)}</span></span>
          <span><b>Bed No. :</b> <span class="flexval">${esc(f.bedNo)}</span></span>
        </div>
        <div class="idrow"><span style="flex:2"><b>Patient Name :</b> <span class="flexval">${patientName}</span></span></div>
        <div class="idrow">
          <span><b>Age :</b> <span class="flexval">${esc(f.age)}</span></span>
          <span style="flex:0.7"><b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></span>
          <span><b>Date :</b> <span class="flexval">${esc(d.date)}</span></span>
          <span><b>Time :</b> <span class="flexval">${esc(d.time)}</span></span>
        </div>
      </div>

      <div class="fr"><span class="fc2"><b>PRE-OP DIAGNOSIS :</b> ${val(d.preOpDiagnosis)}</span></div>
      <div class="fr"><span class="fc2"><b>NAME OF THE PROCEDURE :</b> ${val(d.nameOfProcedure)}</span></div>
      <div class="fr">
        <span class="fc"><b>SURGEON NAME :</b> ${val(d.surgeonName)}</span>
        <span class="fc"><b>ANAESTHETIST NAME :</b> ${val(d.anaesthetistName)}</span>
      </div>
      <div class="fr">
        <span class="fc"><b>ASSIST :</b> ${val(d.assist1)}</span>
        <span class="fc"><b>ASSIST :</b> ${val(d.assist2)}</span>
      </div>
      <div class="fr">
        <span class="fc"><b>SCRUB NURSE :</b> ${val(d.scrubNurse)}</span>
        <span class="fc"><b>OT TECHNICIAN :</b> ${val(d.otTechnician)}</span>
      </div>
      <div class="fr">
        <span class="fc"><b>DATE OF SURGERY :</b> ${val(d.dateOfSurgery)}</span>
        <span class="fc"><b>TYPE OF ANAESTHESIA :</b> ${val(d.typeOfAnaesthesia)}</span>
      </div>
      <div class="fr">
        <span class="fc"><b>STARTED TIME :</b> ${val(d.startedTime)}</span>
        <span class="fc"><b>COMPLETED TIME :</b> ${val(d.completedTime)}</span>
      </div>

      <div class="sech">OPERATION NOTES</div>
      <div><b>PROCEDURE :</b></div>
      <div class="ruled" style="min-height:340px">${esc(d.operationNotes)}</div>

      <div style="page-break-before:always"></div>
      <div class="sech">ANAESTHETIST NOTES</div>
      <div class="ruled" style="min-height:600px">${esc(d.anaesthetistNotes)}</div>

      <div class="signs">
        <div class="sc"><b>SURGEON</b>
          <div>SIGNATURE : <span class="line"></span></div>
          <div>Reg. No. : <span class="line">${esc(d.surgeonRegNo)}</span></div>
        </div>
        <div class="sc"><b>ANAESTHETIST</b>
          <div>SIGNATURE : <span class="line"></span></div>
          <div>Reg. No. : <span class="line">${esc(d.anaesthetistRegNo)}</span></div>
        </div>
      </div>

      <div class="code">VH/NABH/OT/04/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const Txt = ({ label, v, on, area }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    {area ? (
      <textarea
        value={v || ''}
        onChange={(e) => on(e.target.value)}
        rows={5}
        className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
      />
    ) : (
      <input
        value={v || ''}
        onChange={(e) => on(e.target.value)}
        className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
      />
    )}
  </div>
);
const H = ({ children }) => (
  <div className="text-xs font-bold text-gray-800 uppercase tracking-wide mt-2 mb-1">
    {children}
  </div>
);

const SurgicalCaseRecordForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="SURGICAL_CASE_RECORD"
    title="Surgical Case Record"
    code="VH/NABH/OT/04/2026"
    defaults={{}}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => (
      <div className="space-y-2">
        <div className="rounded-lg bg-gray-50 border border-gray-200 px-3 py-2 text-xs text-gray-600">
          For{' '}
          <b>
            {titleCase(
              [prefill.patientSurname, prefill.patientFirstName, prefill.husbandFatherName]
                .filter(Boolean)
                .join(' ')
            ) || '—'}
          </b>
          {prefill.ipdRegistrationNo ? ` · IPD ${prefill.ipdRegistrationNo}` : ''} — header fills
          automatically. Two-page printout.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <Txt label="MLC No." v={data.mlcNo} on={(x) => set('mlcNo', x)} />
          <Txt label="Date" v={data.date} on={(x) => set('date', x)} />
          <Txt label="Time" v={data.time} on={(x) => set('time', x)} />
        </div>
        <Txt
          label="Pre-op Diagnosis"
          v={data.preOpDiagnosis}
          on={(x) => set('preOpDiagnosis', x)}
        />
        <Txt
          label="Name of the Procedure"
          v={data.nameOfProcedure}
          on={(x) => set('nameOfProcedure', x)}
        />
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Surgeon Name" v={data.surgeonName} on={(x) => set('surgeonName', x)} />
          <Txt
            label="Anaesthetist Name"
            v={data.anaesthetistName}
            on={(x) => set('anaesthetistName', x)}
          />
          <Txt label="Assist (1)" v={data.assist1} on={(x) => set('assist1', x)} />
          <Txt label="Assist (2)" v={data.assist2} on={(x) => set('assist2', x)} />
          <Txt label="Scrub Nurse" v={data.scrubNurse} on={(x) => set('scrubNurse', x)} />
          <Txt label="OT Technician" v={data.otTechnician} on={(x) => set('otTechnician', x)} />
          <Txt label="Date of Surgery" v={data.dateOfSurgery} on={(x) => set('dateOfSurgery', x)} />
          <Txt
            label="Type of Anaesthesia"
            v={data.typeOfAnaesthesia}
            on={(x) => set('typeOfAnaesthesia', x)}
          />
          <Txt label="Started Time" v={data.startedTime} on={(x) => set('startedTime', x)} />
          <Txt label="Completed Time" v={data.completedTime} on={(x) => set('completedTime', x)} />
        </div>

        <H>Operation Notes (Procedure)</H>
        <Txt
          label="Operation / procedure notes"
          v={data.operationNotes}
          on={(x) => set('operationNotes', x)}
          area
        />

        <H>Anaesthetist Notes (page 2)</H>
        <Txt
          label="Anaesthetist notes"
          v={data.anaesthetistNotes}
          on={(x) => set('anaesthetistNotes', x)}
          area
        />

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Surgeon Reg. No." v={data.surgeonRegNo} on={(x) => set('surgeonRegNo', x)} />
          <Txt
            label="Anaesthetist Reg. No."
            v={data.anaesthetistRegNo}
            on={(x) => set('anaesthetistRegNo', x)}
          />
        </div>
        <p className="text-xs text-gray-400">Signature lines print blank for offline signing.</p>
      </div>
    )}
  />
);

export default SurgicalCaseRecordForm;
