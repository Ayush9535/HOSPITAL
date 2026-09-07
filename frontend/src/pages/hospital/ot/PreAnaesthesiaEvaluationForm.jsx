import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Pre-Anaesthesia Evaluation — VH/NABH/OT/03/2026.
 * Header auto-fills from admission data. ASA grade + clinical history/exam
 * fields are editable (filled by the anaesthetist).
 */

const esc = escapeHtml;
const GRADES = ['I', 'II', 'III', 'IV', 'E'];

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
  const hist = (label, v) => `<div class="hr">${label} - ${esc(v)}</div>`;
  const asa = GRADES.map((g) => `<b class="g ${d.asaGrade === g ? 'on' : ''}">${g}</b>`).join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Pre-Anaesthesia Evaluation</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:10px; margin:0; line-height:1.35; }
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
      .asa { border:1px solid #111; border-top:0; padding:4px 9px; font-weight:700; }
      .g { border:1px solid #111; padding:0 7px; margin:0 3px; font-weight:700; }
      .g.on { background:#111; color:#fff; }
      .h { font-weight:700; margin:0 0 4px; }
      .area { border-bottom:1px dashed #bbb; min-height:14px; padding:2px 0; }
      .hr { margin:3px 0; }
      .line { border-bottom:1px solid #666; display:inline-block; padding:0 3px; min-height:12px; }
      .r { margin:4px 0; }
      .code { text-align:right; font-size:9px; color:#555; margin-top:5px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">PRE ANAESTHESIA EVALUATION</div>
      </div>

      <div class="idwrap cols">
        <div class="col">
          <div class="idline"><b>Patient's Name :</b> <span class="ul">${patientName}</span></div>
          <div class="idline"><b>Patient's Address :</b> <span class="ul">${esc(f.patientAddress)}</span></div>
          <div class="idline"><b>Age :</b> <span class="ul w">${esc(f.age)}</span> <b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></div>
        </div>
        <div class="col">
          <div class="idline"><b>PRN No. :</b> <span class="ul">${esc(f.prnNo)}</span></div>
          <div class="idline"><b>IPD No. :</b> <span class="ul">${esc(f.ipdRegistrationNo)}</span></div>
          <div class="idline"><b>Category :</b> <span class="ul">${esc(f.category)}</span> <b>Bed No. :</b> <span class="ul w">${esc(f.bedNo)}</span></div>
          <div class="idline"><b>Date :</b> <span class="ul">${esc(d.date)}</span> <b>Time :</b> <span class="ul">${esc(d.time)}</span></div>
        </div>
      </div>
      <div class="asa">ASA Grade : ${asa}</div>

      <div class="box" style="border-top:0">
        <div class="cols">
          <div class="col">
            <div class="h">History of present illness :</div>
            <div class="area" style="min-height:120px;white-space:pre-wrap">${esc(d.historyPresentIllness)}</div>
            <div class="h" style="margin-top:10px">Investigation :</div>
            <div class="r">P ${ln(d.invP, 150)}</div>
            <div class="r">BP ${ln(d.invBP, 145)}</div>
            <div class="r">SPO2 ${ln(d.invSPO2, 135)}</div>
          </div>
          <div class="col">
            <div class="h">History of</div>
            ${hist('Cough / Cold / Fever', d.h_coughColdFever)}
            ${hist('Breathlessness - MET', d.h_breathlessness)}
            ${hist('Palpitations', d.h_palpitations)}
            ${hist('Chest Pain', d.h_chestPain)}
            <div style="height:6px"></div>
            ${hist('Convulsion', d.h_convulsion)}
            ${hist('Old CVA / Stroke', d.h_oldCVA)}
            ${hist('Thyroid disorder', d.h_thyroid)}
            ${hist('Diabetes', d.h_diabetes)}
            ${hist('Hypertension', d.h_hypertension)}
            <div style="height:6px"></div>
            ${hist('Surgery', d.h_surgery)}
            ${hist('Asthma', d.h_asthma)}
            ${hist('OSA', d.h_osa)}
            ${hist('TB', d.h_tb)}
            <div style="height:6px"></div>
            ${hist('Current Medication', d.h_currentMed)}
          </div>
        </div>

        <div class="rowsep" style="padding:6px 9px">
          <div class="r"><b>Past History :</b> ${ln(d.pastHistory, 380)}</div>
          <div class="r"><b>Personal History -</b> Addiction : ${ln(d.addiction, 200)} &nbsp;&nbsp; LMP ${ln(d.lmp, 90)}</div>
          <div class="r" style="padding-left:96px">Allergies : ${ln(d.allergies, 300)}</div>
          <div class="r"><b>Family History :</b> ${ln(d.familyHistory, 370)}</div>
          <div class="r"><b>Birth and Immunisation history :</b> ${ln(d.birthImmunisation, 280)}</div>
          <div class="r"><b>Systemic examination :</b></div>
          <div class="r" style="padding-left:14px">Rs ${ln(d.se_Rs, 380)}</div>
          <div class="r" style="padding-left:14px">Ws ${ln(d.se_Ws, 380)}</div>
          <div class="r" style="padding-left:14px">J. O. Teeth ${ln(d.se_teeth, 330)}</div>
          <div class="r" style="padding-left:14px">MPC ${ln(d.se_mpc, 360)}</div>
          <div class="r"><b>Advice :</b></div>
          <div class="area" style="min-height:50px;white-space:pre-wrap">${esc(d.advice)}</div>
        </div>
      </div>

      <div class="code">VH/NABH/OT/03/2026</div>
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
        rows={3}
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

const PreAnaesthesiaEvaluationForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="PRE_ANAES_EVAL"
    title="Pre-Anaesthesia Evaluation"
    code="VH/NABH/OT/03/2026"
    defaults={{ asaGrade: '' }}
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
          automatically.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Date" v={data.date} on={(x) => set('date', x)} />
          <Txt label="Time" v={data.time} on={(x) => set('time', x)} />
        </div>
        <div>
          <span className="block text-xs font-semibold text-gray-600 mb-1">ASA Grade</span>
          <div className="flex gap-3 text-sm">
            {GRADES.map((g) => (
              <label key={g} className="inline-flex items-center gap-1">
                <input
                  type="radio"
                  checked={data.asaGrade === g}
                  onChange={() => set('asaGrade', g)}
                />
                {g}
              </label>
            ))}
          </div>
        </div>
        <Txt
          label="History of present illness"
          v={data.historyPresentIllness}
          on={(x) => set('historyPresentIllness', x)}
          area
        />

        <H>History of</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt
            label="Cough / Cold / Fever"
            v={data.h_coughColdFever}
            on={(x) => set('h_coughColdFever', x)}
          />
          <Txt
            label="Breathlessness (MET)"
            v={data.h_breathlessness}
            on={(x) => set('h_breathlessness', x)}
          />
          <Txt label="Palpitations" v={data.h_palpitations} on={(x) => set('h_palpitations', x)} />
          <Txt label="Chest Pain" v={data.h_chestPain} on={(x) => set('h_chestPain', x)} />
          <Txt label="Convulsion" v={data.h_convulsion} on={(x) => set('h_convulsion', x)} />
          <Txt label="Old CVA / Stroke" v={data.h_oldCVA} on={(x) => set('h_oldCVA', x)} />
          <Txt label="Thyroid disorder" v={data.h_thyroid} on={(x) => set('h_thyroid', x)} />
          <Txt label="Diabetes" v={data.h_diabetes} on={(x) => set('h_diabetes', x)} />
          <Txt label="Hypertension" v={data.h_hypertension} on={(x) => set('h_hypertension', x)} />
          <Txt label="Surgery" v={data.h_surgery} on={(x) => set('h_surgery', x)} />
          <Txt label="Asthma" v={data.h_asthma} on={(x) => set('h_asthma', x)} />
          <Txt label="OSA" v={data.h_osa} on={(x) => set('h_osa', x)} />
          <Txt label="TB" v={data.h_tb} on={(x) => set('h_tb', x)} />
          <Txt
            label="Current Medication"
            v={data.h_currentMed}
            on={(x) => set('h_currentMed', x)}
          />
        </div>

        <H>Investigation</H>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <Txt label="P" v={data.invP} on={(x) => set('invP', x)} />
          <Txt label="BP" v={data.invBP} on={(x) => set('invBP', x)} />
          <Txt label="SPO2" v={data.invSPO2} on={(x) => set('invSPO2', x)} />
        </div>

        <H>History &amp; Examination</H>
        <Txt label="Past History" v={data.pastHistory} on={(x) => set('pastHistory', x)} />
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <Txt label="Addiction" v={data.addiction} on={(x) => set('addiction', x)} />
          <Txt label="Allergies" v={data.allergies} on={(x) => set('allergies', x)} />
          <Txt label="LMP" v={data.lmp} on={(x) => set('lmp', x)} />
        </div>
        <Txt label="Family History" v={data.familyHistory} on={(x) => set('familyHistory', x)} />
        <Txt
          label="Birth and Immunisation history"
          v={data.birthImmunisation}
          on={(x) => set('birthImmunisation', x)}
        />
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          <Txt label="Rs" v={data.se_Rs} on={(x) => set('se_Rs', x)} />
          <Txt label="Ws" v={data.se_Ws} on={(x) => set('se_Ws', x)} />
          <Txt label="J. O. Teeth" v={data.se_teeth} on={(x) => set('se_teeth', x)} />
          <Txt label="MPC" v={data.se_mpc} on={(x) => set('se_mpc', x)} />
        </div>
        <Txt label="Advice" v={data.advice} on={(x) => set('advice', x)} area />
      </div>
    )}
  />
);

export default PreAnaesthesiaEvaluationForm;
