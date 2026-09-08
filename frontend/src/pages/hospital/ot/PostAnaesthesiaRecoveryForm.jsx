import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Post Anaesthesia Recovery Chart (PACU) — VH/NABH/OT/03/2026.
 * Top vitals/airway/oxygen fields + Ramsay & Modified Aldrete reference scales.
 * Monitoring grid and Pain Management table print blank for bedside entry;
 * discharge/instructions fields are editable. Header auto-fills.
 */

const esc = escapeHtml;

const buildPrintHtml = (data, prefill, hospital) => {
  const f = prefill || {};
  const d = data || {};
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = esc(
    titleCase([f.patientSurname, f.patientFirstName, f.husbandFatherName].filter(Boolean).join(' '))
  );
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:48px;width:auto;object-fit:contain"/>`
    : '';
  const ln = (v, w = 70) => `<span class="line" style="min-width:${w}px">${esc(v)}</span>`;
  const pick = (opts, sel) =>
    opts.map((o) => (sel === o ? `<b class="pk">${o}</b>` : o)).join(' / ');

  const monRows = [
    'PR',
    'BP',
    '',
    'SpO2',
    'IV Fluids',
    'Urine',
    'BSL',
    'Blood Loss',
    'Blood Transfusion',
  ]
    .map((lab) => `<tr><td class="ml">${lab}</td>${'<td></td>'.repeat(5)}</tr>`)
    .join('');
  const painRows = Array.from({ length: 4 })
    .map(() => '<tr><td></td><td></td><td></td></tr>')
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Post Anaesthesia Recovery Chart</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      html, body { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:9.5px; margin:0; line-height:1.3; }
      .top { display:flex; align-items:center; justify-content:space-between; }
      .brand { text-align:left; }
      .hname { font-size:16px; font-weight:800; color:#1d4ed8; }
      .haddr { font-size:10px; font-weight:600; color:#7c3aed; }
      .tbar { background:#1f2937; color:#fff; font-weight:800; font-size:12px; padding:6px 11px; }
      .cols { display:flex; gap:10px; }
      .col { flex:1; }
      .box { border:1px solid #111; padding:6px 9px; }
      .r { margin:4px 0; }
      .line { border-bottom:1px solid #666; display:inline-block; padding:0 3px; min-height:12px; }
      .pk { border:1px solid #111; padding:0 4px; font-weight:700; }
      .bt { font-weight:700; margin-bottom:3px; }
      ol { margin:2px 0 0 16px; padding:0; } ol li { margin:1px 0; }
      .mtitle { font-weight:800; font-size:13px; margin:8px 0 4px; }
      table { border-collapse:collapse; }
      td, th { border:1px solid #111; }
      .mon { width:100%; } .mon td { height:17px; } .mon .ml { width:110px; padding:0 4px; font-weight:600; }
      .pain { width:100%; } .pain th { font-size:8.5px; padding:2px; } .pain td { height:17px; }
      .patientline { font-size:10px; margin:6px 0; }
      .code { text-align:right; font-size:9px; color:#555; margin-top:5px; }
      .scale .sc { margin-top:4px; } .scale b.h { display:block; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">POST ANAESTHESIA RECOVERY CHART</div>
      </div>
      <div class="patientline"><b>Patient :</b> ${ln(patientName, 220)} &nbsp; <b>IPD No. :</b> ${ln(f.ipdRegistrationNo, 120)} &nbsp; <b>Bed :</b> ${ln(f.bedNo, 60)} &nbsp; <b>Date :</b> ${ln(d.date, 90)}</div>

      <div class="cols">
        <div class="col">
          <div class="r">PR : ${ln(d.pr, 45)} &nbsp; BP : ${ln(d.bp, 55)} &nbsp; SpO2 ${ln(d.spo2, 45)}</div>
          <div class="r">Airway : ${pick(['Nasal', 'Oral'], d.airway)}</div>
          <div class="r">Oxygen Support : Flow ${ln(d.oxygenFlow, 55)} L / Min</div>
          <div class="r">${pick(['Nasal Cannula', "Hudson's Mask", 'Venti Mask'], d.oxygenDevice)} &nbsp; FiO2 ${ln(d.fiO2, 45)}</div>
          <div class="r">IV lines ${ln(d.ivLines, 45)} &nbsp; NGT - ${ln(d.ngt, 45)} &nbsp; Urinary Catheter - ${ln(d.urinaryCatheter, 45)} &nbsp; Drains - ${ln(d.drains, 45)}</div>
        </div>
        <div class="col box scale">
          <b class="h">RAMSAY SEDATION SCALE</b>
          <ol>
            <li>Patient is anxious &amp; agitated or restless, or both</li>
            <li>Patient is cooperative, oriented &amp; tranquil</li>
            <li>Patient responds to commands only</li>
            <li>Patient exhibits brisk response to light glabellar tap or loud auditory stimulus</li>
            <li>Patient exhibits a sluggish response to light glabellar tap or loud auditory stimulus</li>
            <li>Patient exhibits no response</li>
          </ol>
          <div class="sc">Ramsay Score : ${ln(d.ramsayScore, 50)}</div>
        </div>
      </div>

      <div class="mtitle">Monitoring</div>
      <div class="cols">
        <div class="col">
          <table class="mon"><tbody>${monRows}</tbody></table>
        </div>
        <div class="col">
          <div class="bt">Pain Management</div>
          <table class="pain">
            <thead><tr><th>Time</th><th>Vas Score</th><th>Analgesic Give</th></tr></thead>
            <tbody>${painRows}</tbody>
          </table>
          <div class="bt" style="margin-top:6px">Any Specific Event &amp; Management</div>
          <div class="box" style="min-height:60px;white-space:pre-wrap">${esc(d.specificEvent)}</div>
        </div>
      </div>

      <div class="cols" style="margin-top:8px">
        <div class="col">
          <div class="r"><b>Discharge Time from PACU :</b> ${ln(d.dischargeTime, 120)}</div>
          <div class="r">Consciousness ${ln(d.consciousness, 150)}</div>
          <div class="r">Pulse : ${ln(d.pulse, 70)}</div>
          <div class="r">BP : ${ln(d.bpD, 70)}</div>
          <div class="r">SpO2 : ${ln(d.spo2D, 70)}</div>
          <div class="r">CVS : ${ln(d.cvs, 130)}</div>
          <div class="r"><b>Modified Aldrete Score</b> ${ln(d.modifiedAldreteScore, 60)}</div>
          <div class="r" style="margin-top:6px"><b>Post Operative instructions :</b></div>
          <div style="padding-left:8px">
            <div>- NBM</div>
            <div>- Watch for PR, BP Respiration</div>
            <div>- Inform SOS</div>
            <div>- Special instructions ${ln(d.specialInstructions, 150)}</div>
          </div>
          <div class="r" style="margin-top:6px"><b>Shift out PACU to ward No.</b> ${ln(d.shiftWardNo, 100)}</div>
          <div class="r"><b>Unplanned ICU Shifting / Ventilation</b> ${ln(d.unplannedICU, 120)}</div>
          <div class="r" style="margin-top:8px">Name and Signature of Anaesthesiologist : ${ln(titleCase(d.anaesthetistName || ''), 150)}</div>
          <div class="r">Time : ${ln(d.time, 90)} &nbsp; Date : ${ln(d.date, 90)}</div>
        </div>
        <div class="col box scale">
          <b class="h" style="text-align:center;display:block">Modified Alderete Score</b>
          <div><b>1. Activity</b> <span style="float:right">Score</span></div>
          <div>Able to move four extremities in command &nbsp; 2</div>
          <div>Able to move two extremities in command &nbsp; 1</div>
          <div>Not able to move any extremity &nbsp; 0</div>
          <div style="margin-top:4px"><b>2. Breathing</b></div>
          <div>Able to breath deeply and freely &nbsp; 2</div>
          <div>Dyspnea, Shallow breathing &nbsp; 1</div>
          <div>Apnea &nbsp; 0</div>
          <div style="margin-top:4px"><b>3. Circulation</b></div>
          <div>Systolic blood pressure + 20mm Hg preop &nbsp; 2</div>
          <div>Systolic blood pressure + 20-50mm Hg preop &nbsp; 1</div>
          <div>Systolic blood pressure + 50mm Hg preop &nbsp; 0</div>
          <div style="margin-top:4px"><b>4. Consciousness</b></div>
          <div>Fully awake &nbsp; 2</div>
          <div>Arousable on calling &nbsp; 1</div>
          <div>Not responding &nbsp; 0</div>
          <div style="margin-top:4px"><b>5. Oxygen Saturation</b></div>
          <div>Maintains &gt; 92% on room air &nbsp; 2</div>
          <div>Needs O2 inhalation to maintain O2 saturation &gt;90% &nbsp; 1</div>
          <div>&lt;90% even with supplemental oxygen &nbsp; 0</div>
          <div style="margin-top:4px"><b>Score &gt; 9 is Acceptable</b></div>
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
const Sel = ({ label, v, on, options }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    <select
      value={v || ''}
      onChange={(e) => on(e.target.value)}
      className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
    >
      <option value="">—</option>
      {options.map((o) => (
        <option key={o} value={o}>
          {o}
        </option>
      ))}
    </select>
  </div>
);
const H = ({ children }) => (
  <div className="text-xs font-bold text-gray-800 uppercase tracking-wide mt-2 mb-1">
    {children}
  </div>
);

const PostAnaesthesiaRecoveryForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="POST_ANAES_RECOVERY"
    title="Post Anaesthesia Recovery Chart"
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
          {prefill.ipdRegistrationNo ? ` · IPD ${prefill.ipdRegistrationNo}` : ''} — monitoring &
          pain grids print blank for bedside entry.
        </div>

        <H>On Arrival</H>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          <Txt label="PR" v={data.pr} on={(x) => set('pr', x)} />
          <Txt label="BP" v={data.bp} on={(x) => set('bp', x)} />
          <Txt label="SpO2" v={data.spo2} on={(x) => set('spo2', x)} />
          <Sel
            label="Airway"
            v={data.airway}
            on={(x) => set('airway', x)}
            options={['Nasal', 'Oral']}
          />
          <Txt label="Oxygen Flow (L/Min)" v={data.oxygenFlow} on={(x) => set('oxygenFlow', x)} />
          <Sel
            label="Oxygen Device"
            v={data.oxygenDevice}
            on={(x) => set('oxygenDevice', x)}
            options={['Nasal Cannula', "Hudson's Mask", 'Venti Mask']}
          />
          <Txt label="FiO2" v={data.fiO2} on={(x) => set('fiO2', x)} />
          <Txt label="IV lines" v={data.ivLines} on={(x) => set('ivLines', x)} />
          <Txt label="NGT" v={data.ngt} on={(x) => set('ngt', x)} />
          <Txt
            label="Urinary Catheter"
            v={data.urinaryCatheter}
            on={(x) => set('urinaryCatheter', x)}
          />
          <Txt label="Drains" v={data.drains} on={(x) => set('drains', x)} />
          <Txt label="Ramsay Score" v={data.ramsayScore} on={(x) => set('ramsayScore', x)} />
          <Txt label="Date" v={data.date} on={(x) => set('date', x)} />
        </div>

        <H>Event</H>
        <Txt
          label="Any Specific Event & Management"
          v={data.specificEvent}
          on={(x) => set('specificEvent', x)}
          area
        />

        <H>Discharge from PACU</H>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          <Txt
            label="Discharge Time from PACU"
            v={data.dischargeTime}
            on={(x) => set('dischargeTime', x)}
          />
          <Txt label="Consciousness" v={data.consciousness} on={(x) => set('consciousness', x)} />
          <Txt label="Pulse" v={data.pulse} on={(x) => set('pulse', x)} />
          <Txt label="BP" v={data.bpD} on={(x) => set('bpD', x)} />
          <Txt label="SpO2" v={data.spo2D} on={(x) => set('spo2D', x)} />
          <Txt label="CVS" v={data.cvs} on={(x) => set('cvs', x)} />
          <Txt
            label="Modified Aldrete Score"
            v={data.modifiedAldreteScore}
            on={(x) => set('modifiedAldreteScore', x)}
          />
          <Txt
            label="Special instructions"
            v={data.specialInstructions}
            on={(x) => set('specialInstructions', x)}
          />
          <Txt
            label="Shift out PACU to ward No."
            v={data.shiftWardNo}
            on={(x) => set('shiftWardNo', x)}
          />
          <Txt
            label="Unplanned ICU Shifting / Ventilation"
            v={data.unplannedICU}
            on={(x) => set('unplannedICU', x)}
          />
          <Txt
            label="Anaesthesiologist Name"
            v={data.anaesthetistName}
            on={(x) => set('anaesthetistName', x)}
          />
          <Txt label="Time" v={data.time} on={(x) => set('time', x)} />
        </div>
        <p className="text-xs text-gray-400">
          Ramsay & Aldrete scales and the monitoring/pain grids print as on the form. Signature
          prints blank.
        </p>
      </div>
    )}
  />
);

export default PostAnaesthesiaRecoveryForm;
