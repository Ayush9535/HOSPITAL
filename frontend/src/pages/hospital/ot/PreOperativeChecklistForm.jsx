import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Pre-Operative Checklist — VH/NABH/OT/03/2026.
 * Header auto-fills from admission data; the rest are editable checklist
 * (Yes/No) + text fields the nurse fills. Nurse signature blocks print blank.
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
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:52px;width:auto;object-fit:contain"/>`
    : '';
  const ln = (v, w = 60) => `<span class="line" style="min-width:${w}px">${esc(v)}</span>`;
  const yn = (v) =>
    `Yes <b class="bx">${v === 'YES' ? '✓' : '&nbsp;'}</b> No <b class="bx">${v === 'NO' ? '✓' : '&nbsp;'}</b>`;
  const ck = (label, v) =>
    `<div class="ck"><span class="lbl">${label}</span> : <span class="yn">${yn(v)}</span></div>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Pre-Operative Checklist</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:10px; margin:0; line-height:1.3; }
      .top { display:flex; align-items:center; justify-content:space-between; }
      .brand { text-align:left; }
      .hname { font-size:17px; font-weight:800; color:#1d4ed8; }
      .haddr { font-size:10.5px; font-weight:600; color:#7c3aed; }
      .tbar { background:#1f2937; color:#fff; font-weight:800; font-size:13px; padding:7px 12px; }
      .idwrap, .box { border:1px solid #111; }
      .cols { display:flex; }
      .col { flex:1; padding:6px 9px; }
      .col + .col { border-left:1px solid #111; }
      .rowsep { border-top:1px solid #111; }
      .idline { display:flex; align-items:flex-end; gap:5px; margin:5px 0; }
      .ul { border-bottom:1px solid #666; flex:1; min-height:12px; padding:0 3px; }
      .ul.w { flex:0.5; }
      .sex b { border:1px solid #111; padding:0 5px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      .idwrap { margin-top:8px; }
      .main { margin-top:8px; }
      .ck { display:flex; align-items:center; margin:3px 0; }
      .ck .lbl { flex:1; }
      .yn { white-space:nowrap; }
      .bx { border:1px solid #111; width:14px; height:13px; display:inline-block; text-align:center; line-height:12px; margin:0 3px; }
      .line { border-bottom:1px solid #666; display:inline-block; padding:0 3px; min-height:12px; }
      .h { font-weight:700; margin:2px 0 3px; }
      .r { margin:3px 0; }
      .sec { font-weight:700; padding:4px 9px; border-top:1px solid #111; border-bottom:1px solid #111; background:#f3f4f6; }
      .code { text-align:right; font-size:9px; color:#555; margin-top:5px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">PRE - OPERATIVE CHECK LIST</div>
      </div>

      <div class="idwrap cols">
        <div class="col">
          <div class="idline"><b>Patient's Name :</b> <span class="ul">${patientName}</span></div>
          <div class="idline"><b>Patient's Address :</b> <span class="ul">${esc(f.patientAddress)}</span></div>
          <div class="idline"><b>Age :</b> <span class="ul w">${esc(f.age)}</span> <b>Gender :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></div>
        </div>
        <div class="col">
          <div class="idline"><b>Name of Surgeon :</b> <span class="ul">${esc(d.surgeonName)}</span></div>
          <div class="idline"><b>Type of Anesthesia :</b> <span class="ul">${esc(d.anesthesiaType)}</span></div>
          <div class="idline"><b>Name of Anaesthetist :</b> <span class="ul">${esc(d.anaesthetistName)}</span></div>
          <div class="idline"><b>Provisional diagnosis :</b> <span class="ul">${esc(d.provisionalDiagnosis)}</span></div>
        </div>
      </div>

      <div class="box main">
        <div class="cols">
          <div class="col">
            ${ck('ID Band Checked', d.idBand)}
            ${ck('Site Marking Done', d.siteMarking)}
            ${ck('Consent Signed', d.consentSigned)}
            ${ck('&nbsp;&nbsp;1. By Patient', d.consentPatient)}
            ${ck('&nbsp;&nbsp;2. Surgeon', d.consentSurgeon)}
            ${ck('&nbsp;&nbsp;3. Anesthetist', d.consentAnesthetist)}
            ${ck('PAC Done', d.pacDone)}
            ${ck('Shaving / Clipping Done', d.shaving)}
            ${ck('Denture Removed', d.dentureRemoved)}
            ${ck('Any Other Devices on Patient', d.otherDevices)}
            <div class="r">Specify : ${ln(d.otherDevicesSpecify, 150)}</div>
          </div>
          <div class="col">
            <div class="h">Pre - Medication</div>
            <div class="r">1. at ${ln(d.preMed1, 170)}</div>
            <div class="r">2. at ${ln(d.preMed2, 170)}</div>
            <div class="r">3. at ${ln(d.preMed3, 170)}</div>
            <div class="r">4. at ${ln(d.preMed4, 170)}</div>
            <div class="r"><b>Blood group :</b> ${ln(d.bloodGroup, 70)} &nbsp; <b>Rh :</b> ${ln(d.rh, 50)}</div>
            <div class="h">Blood Available</div>
            <div class="r">PRBC : ${ln(d.prbc, 60)} &nbsp; Units : ${ln(d.prbcUnits, 50)}</div>
            <div class="r">FFP : ${ln(d.ffp, 60)} &nbsp; Units : ${ln(d.ffpUnits, 50)}</div>
            <div class="r">Platelets : ${ln(d.platelets, 55)} &nbsp; Units : ${ln(d.plateletsUnits, 50)}</div>
          </div>
        </div>

        <div class="cols rowsep">
          <div class="col">
            <div class="r">Vital Parameter At ${ln(d.vitalAt, 60)} Hrs</div>
            <div class="r">Temp : ${ln(d.temp, 45)} &nbsp; Pulse : ${ln(d.pulse, 45)} &nbsp; Resp : ${ln(d.resp, 45)}</div>
            <div class="r">BP ${ln(d.bp, 60)} &nbsp; SpO2 ${ln(d.spo2, 50)}</div>
            ${ck('Betadine Wash / Bath', d.betadineWash)}
            ${ck('Chlorhexidine Mouth Wash Done', d.chlorhexidine)}
            ${ck('Betadine Paint Done', d.betadinePaint)}
          </div>
          <div class="col">
            <div class="r">Allergy known : ${ln(d.allergyKnown, 200)}</div>
            <div class="r" style="min-height:26px"></div>
            <div class="r">Reasons for Late Shifting (If any) : ${ln(d.lateShifting, 150)}</div>
            <div class="r" style="min-height:26px"></div>
          </div>
        </div>

        <div class="sec">Investigation :</div>
        <div style="padding:6px 9px">
          <div class="ck"><span class="lbl">All Reports Attached &amp; File Complete</span> : <span class="yn">${yn(d.reportsComplete)}</span></div>
          <div class="r">Pending If Any : ${ln(d.pendingAny, 260)}</div>
          <div class="r">Serology Status : &nbsp; HIV <span class="yn">${yn(d.serHIV)}</span> &nbsp;&nbsp; HCV <span class="yn">${yn(d.serHCV)}</span> &nbsp;&nbsp; HbsAg <span class="yn">${yn(d.serHbsAg)}</span></div>
        </div>

        <div class="cols rowsep">
          <div class="col">
            <div class="h" style="text-align:center">Ward Nurse</div>
            <div class="r">Name : ${ln('', 150)}</div>
            <div class="r">Signature : ${ln('', 130)}</div>
            <div class="r">Date : ${ln('', 80)} &nbsp; Time : ${ln('', 70)}</div>
          </div>
          <div class="col">
            <div class="h" style="text-align:center">OT Nurse</div>
            <div class="r">Name : ${ln('', 150)}</div>
            <div class="r">Signature : ${ln('', 130)}</div>
            <div class="r">Date : ${ln('', 80)} &nbsp; Time : ${ln('', 70)}</div>
          </div>
        </div>
      </div>

      <div class="code">VH/NABH/OT/03/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

// --- editor helpers ---
const Txt = ({ label, v, on, ph }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    <input
      value={v || ''}
      onChange={(e) => on(e.target.value)}
      placeholder={ph}
      className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
    />
  </div>
);
const YN = ({ label, v, on }) => (
  <div className="flex items-center justify-between gap-3 py-1">
    <span className="text-sm text-gray-700">{label}</span>
    <div className="flex gap-3 text-sm shrink-0">
      {['YES', 'NO'].map((o) => (
        <label key={o} className="inline-flex items-center gap-1">
          <input type="radio" checked={v === o} onChange={() => on(o)} />
          {o === 'YES' ? 'Yes' : 'No'}
        </label>
      ))}
    </div>
  </div>
);
const H = ({ children }) => (
  <div className="text-xs font-bold text-gray-800 uppercase tracking-wide mt-2 mb-1">
    {children}
  </div>
);

const PreOperativeChecklistForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="PRE_OP_CHECKLIST"
    title="Pre-Operative Checklist"
    code="VH/NABH/OT/03/2026"
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
          {prefill.ipdRegistrationNo ? ` · IPD ${prefill.ipdRegistrationNo}` : ''} — patient header
          fills automatically. Nurse signatures print blank.
        </div>

        <H>Header</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Name of Surgeon" v={data.surgeonName} on={(x) => set('surgeonName', x)} />
          <Txt
            label="Type of Anesthesia"
            v={data.anesthesiaType}
            on={(x) => set('anesthesiaType', x)}
          />
          <Txt
            label="Name of Anaesthetist"
            v={data.anaesthetistName}
            on={(x) => set('anaesthetistName', x)}
          />
          <Txt
            label="Provisional Diagnosis"
            v={data.provisionalDiagnosis}
            on={(x) => set('provisionalDiagnosis', x)}
          />
        </div>

        <H>Checklist</H>
        <YN label="ID Band Checked" v={data.idBand} on={(x) => set('idBand', x)} />
        <YN label="Site Marking Done" v={data.siteMarking} on={(x) => set('siteMarking', x)} />
        <YN label="Consent Signed" v={data.consentSigned} on={(x) => set('consentSigned', x)} />
        <YN label="1. By Patient" v={data.consentPatient} on={(x) => set('consentPatient', x)} />
        <YN label="2. Surgeon" v={data.consentSurgeon} on={(x) => set('consentSurgeon', x)} />
        <YN
          label="3. Anesthetist"
          v={data.consentAnesthetist}
          on={(x) => set('consentAnesthetist', x)}
        />
        <YN label="PAC Done" v={data.pacDone} on={(x) => set('pacDone', x)} />
        <YN label="Shaving / Clipping Done" v={data.shaving} on={(x) => set('shaving', x)} />
        <YN label="Denture Removed" v={data.dentureRemoved} on={(x) => set('dentureRemoved', x)} />
        <YN
          label="Any Other Devices on Patient"
          v={data.otherDevices}
          on={(x) => set('otherDevices', x)}
        />
        <Txt
          label="Specify (other devices)"
          v={data.otherDevicesSpecify}
          on={(x) => set('otherDevicesSpecify', x)}
        />

        <H>Pre-Medication</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="1. at" v={data.preMed1} on={(x) => set('preMed1', x)} />
          <Txt label="2. at" v={data.preMed2} on={(x) => set('preMed2', x)} />
          <Txt label="3. at" v={data.preMed3} on={(x) => set('preMed3', x)} />
          <Txt label="4. at" v={data.preMed4} on={(x) => set('preMed4', x)} />
        </div>

        <H>Blood</H>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          <Txt label="Blood group" v={data.bloodGroup} on={(x) => set('bloodGroup', x)} />
          <Txt label="Rh" v={data.rh} on={(x) => set('rh', x)} />
          <Txt label="PRBC" v={data.prbc} on={(x) => set('prbc', x)} />
          <Txt label="PRBC Units" v={data.prbcUnits} on={(x) => set('prbcUnits', x)} />
          <Txt label="FFP" v={data.ffp} on={(x) => set('ffp', x)} />
          <Txt label="FFP Units" v={data.ffpUnits} on={(x) => set('ffpUnits', x)} />
          <Txt label="Platelets" v={data.platelets} on={(x) => set('platelets', x)} />
          <Txt
            label="Platelets Units"
            v={data.plateletsUnits}
            on={(x) => set('plateletsUnits', x)}
          />
        </div>

        <H>Vitals &amp; Asepsis</H>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          <Txt label="Vital Parameter At (Hrs)" v={data.vitalAt} on={(x) => set('vitalAt', x)} />
          <Txt label="Temp" v={data.temp} on={(x) => set('temp', x)} />
          <Txt label="Pulse" v={data.pulse} on={(x) => set('pulse', x)} />
          <Txt label="Resp" v={data.resp} on={(x) => set('resp', x)} />
          <Txt label="BP" v={data.bp} on={(x) => set('bp', x)} />
          <Txt label="SpO2" v={data.spo2} on={(x) => set('spo2', x)} />
        </div>
        <YN label="Betadine Wash / Bath" v={data.betadineWash} on={(x) => set('betadineWash', x)} />
        <YN
          label="Chlorhexidine Mouth Wash Done"
          v={data.chlorhexidine}
          on={(x) => set('chlorhexidine', x)}
        />
        <YN
          label="Betadine Paint Done"
          v={data.betadinePaint}
          on={(x) => set('betadinePaint', x)}
        />

        <H>Allergy / Shifting</H>
        <Txt label="Allergy known" v={data.allergyKnown} on={(x) => set('allergyKnown', x)} />
        <Txt
          label="Reasons for Late Shifting (if any)"
          v={data.lateShifting}
          on={(x) => set('lateShifting', x)}
        />

        <H>Investigation</H>
        <YN
          label="All Reports Attached & File Complete"
          v={data.reportsComplete}
          on={(x) => set('reportsComplete', x)}
        />
        <Txt label="Pending if any" v={data.pendingAny} on={(x) => set('pendingAny', x)} />
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <YN label="Serology HIV" v={data.serHIV} on={(x) => set('serHIV', x)} />
          <YN label="Serology HCV" v={data.serHCV} on={(x) => set('serHCV', x)} />
          <YN label="Serology HbsAg" v={data.serHbsAg} on={(x) => set('serHbsAg', x)} />
        </div>
      </div>
    )}
  />
);

export default PreOperativeChecklistForm;
