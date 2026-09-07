import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Post-Operative Checklist — VH/NABH/OT/10/2026.
 * Grouped checklist (VITALS/REPORT/SPECIMEN/POSITION/MANAGEMENT). Each row is
 * marked Present (tick) / Absent (cross) — On/Off for the oxygen row — with an
 * optional remark. Header auto-fills; signatures print blank.
 */

const esc = escapeHtml;

export const GROUPS = [
  {
    name: 'VITALS',
    items: [
      { k: 'v1', label: 'Patient sensation / alertness' },
      { k: 'v2', label: 'Operation site bleeding / soakage' },
      { k: 'v3', label: 'Pulsation (Pulse assessing points)' },
      { k: 'v4', label: 'Is Oxygen flow meter', type: 'ONOFF' },
      { k: 'v5', label: 'Any drainage / Catheter / Wire / Special Stitch' },
      { k: 'v6', label: 'Peripheral Warmness' },
      { k: 'v7', label: 'Cyanosis / Abnormal body colour' },
    ],
  },
  {
    name: 'REPORT',
    items: [
      { k: 'r1', label: 'Handed over same Investigation Reports' },
      { k: 'r2', label: 'All radiological Plates are' },
      { k: 'r3', label: 'Handed over Other Documents' },
      { k: 'r4', label: 'Other : Linen' },
      { k: 'r5', label: 'Other : Articles / Instruments' },
    ],
  },
  {
    name: 'SPECIMEN',
    items: [
      { k: 's1', label: 'Is the specimen labeled' },
      { k: 's2', label: "Dr's Order : For Biopsy" },
      { k: 's3', label: "Dr's Order : For handing over to relatives" },
      { k: 's4', label: "Dr's Order : For discard after showing to" },
      { k: 's5', label: 'The relatives' },
    ],
  },
  {
    name: 'POSITION',
    items: [
      { k: 'p1', label: "Dr's Order : For immediate positioning" },
      { k: 'p2', label: "Dr's Order : For latter positioning" },
    ],
  },
  {
    name: 'MANAGEMENT',
    items: [
      { k: 'm1', label: "Dr's Order Post Operative : IV Fluids" },
      { k: 'm2', label: 'Medications' },
      { k: 'm3', label: 'Oxygen hours' },
      { k: 'm4', label: 'Resting hours' },
      { k: 'm5', label: 'NPM hours' },
      { k: 'm6', label: 'Dressing Changing hours' },
    ],
  },
];

// Builds the checklist <tbody> rows (shared with the OT/02 variant).
export const buildChecklistBody = (d) => {
  let body = '';
  GROUPS.forEach((g) => {
    g.items.forEach((it, idx) => {
      const s = d[`${it.k}_s`];
      let present, absent;
      if (it.type === 'ONOFF') {
        present = `On ${s === 'ON' ? '<b class="tk">✓</b>' : ''}`;
        absent = `Off ${s === 'OFF' ? '<b class="tk">✓</b>' : ''}`;
      } else {
        present = s === 'P' ? '✓' : '';
        absent = s === 'A' ? '✗' : '';
      }
      body += '<tr>';
      if (idx === 0) body += `<td rowspan="${g.items.length}" class="grp">${g.name}</td>`;
      body += `<td class="content">${esc(it.label)}</td>`;
      body += `<td class="tc">${present}</td>`;
      body += `<td class="tc">${absent}</td>`;
      body += `<td class="rem">${esc(d[`${it.k}_r`])}</td>`;
      body += '</tr>';
    });
  });
  return body;
};

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
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:50px;width:auto;object-fit:contain"/>`
    : '';
  const ln = (v, w = 90) => `<span class="line" style="min-width:${w}px">${esc(v)}</span>`;
  const pick = (opts, sel) =>
    opts.map((o) => (sel === o ? `<b class="pk">${o}</b>` : o)).join(' / ');

  const body = buildChecklistBody(d);

  return `<!doctype html><html><head><meta charset="utf-8"><title>Post-Operative Checklist</title>
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
      .idwrap { display:flex; border:1px solid #111; margin-top:7px; }
      .idcol { flex:1; padding:5px 9px; }
      .idcol + .idcol { border-left:1px solid #111; }
      .idline { display:flex; align-items:flex-end; gap:5px; margin:4px 0; }
      .ul { border-bottom:1px solid #666; flex:1; min-height:12px; padding:0 3px; }
      .ul.w { flex:0.5; }
      .sex b { border:1px solid #111; padding:0 5px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      .meta { margin:6px 0; }
      .meta .r { margin:4px 0; }
      .line { border-bottom:1px solid #666; display:inline-block; padding:0 3px; min-height:12px; }
      .pk { border:1px solid #111; padding:0 5px; font-weight:700; }
      table { width:100%; border-collapse:collapse; margin-top:4px; }
      th, td { border:1px solid #111; padding:2px 4px; }
      thead th { font-size:9px; font-weight:700; text-align:center; }
      .grp { writing-mode:vertical-rl; transform:rotate(180deg); text-align:center; font-weight:700; letter-spacing:2px; width:20px; }
      .content { text-align:left; }
      .tc { width:80px; text-align:center; font-weight:700; }
      .rem { width:150px; }
      td { height:16px; }
      .tk { font-weight:700; }
      .btm { display:flex; gap:20px; margin-top:12px; }
      .btm > div { flex:1; }
      .signrow { display:flex; gap:40px; margin-top:20px; }
      .signrow > div { flex:1; text-align:center; border-top:1px solid #111; padding-top:3px; }
      .code { text-align:right; font-size:9px; color:#555; margin-top:5px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">POST - OPERATIVE CHECK LIST</div>
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
          <div class="idline"><b>Date :</b> <span class="ul">${esc(d.date)}</span> <b>Time :</b> <span class="ul">${esc(d.time)}</span></div>
        </div>
      </div>

      <div class="meta">
        <div class="r"><b>Under Dr. :</b> ${ln(d.underDr, 220)} &nbsp;&nbsp; <b>Surgeon :</b> ${ln(d.surgeon, 200)}</div>
        <div class="r"><b>Name of Anesthetist :</b> ${ln(d.nameOfAnesthetist, 300)}</div>
        <div class="r"><b>Operation :</b> ${pick(['Done', 'Not Done'], d.operationDone)} &nbsp;&nbsp;&nbsp; <b>Type of Anesthesia :</b> ${pick(['GA', 'LA'], d.typeOfAnesthesia)}</div>
      </div>

      <table>
        <thead>
          <tr>
            <th></th>
            <th>CHECKING CONTENT</th>
            <th>IF PRESENT (TICK)</th>
            <th>IF ABSENT (CROSS)</th>
            <th>REMARKS / COMMENT</th>
          </tr>
        </thead>
        <tbody>${body}</tbody>
      </table>

      <div class="btm">
        <div>Time : ${ln(d.bTime, 70)} &nbsp; Date : ${ln(d.bDate, 70)} &nbsp; Place ${ln(d.bPlace, 90)}</div>
      </div>
      <div class="btm"><div>Place : ${ln(d.bPlace2, 120)}</div></div>
      <div class="signrow">
        <div>Signature (OT Staff)</div>
        <div>Signature (Ward Staff)</div>
      </div>

      <div class="code">VH/NABH/OT/10/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const Txt = ({ label, v, on }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    <input
      value={v || ''}
      onChange={(e) => on(e.target.value)}
      className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
    />
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

export const ChecklistRow = ({ it, data, set }) => {
  const s = data[`${it.k}_s`];
  const opts =
    it.type === 'ONOFF'
      ? [
          ['ON', 'On'],
          ['OFF', 'Off'],
        ]
      : [
          ['P', 'Present'],
          ['A', 'Absent'],
        ];
  return (
    <div className="flex items-center gap-2 py-1 border-b border-gray-100">
      <span className="flex-1 text-xs text-gray-700">{it.label}</span>
      <div className="flex gap-1 shrink-0">
        {opts.map(([val, lbl]) => (
          <button
            key={val}
            type="button"
            onClick={() => set(`${it.k}_s`, s === val ? '' : val)}
            className={`px-2 py-0.5 rounded text-[11px] font-semibold border ${s === val ? 'bg-gray-900 text-white border-gray-900' : 'border-gray-300 text-gray-600'}`}
          >
            {lbl}
          </button>
        ))}
      </div>
      <input
        value={data[`${it.k}_r`] || ''}
        onChange={(e) => set(`${it.k}_r`, e.target.value)}
        placeholder="Remark"
        className="w-28 px-2 py-1 border border-gray-300 rounded text-xs shrink-0"
      />
    </div>
  );
};

const PostOperativeChecklistForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="POST_OP_CHECKLIST_10"
    title="Post-Operative Checklist"
    code="VH/NABH/OT/10/2026"
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
          automatically. Signatures print blank.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Under Dr." v={data.underDr} on={(x) => set('underDr', x)} />
          <Txt label="Surgeon" v={data.surgeon} on={(x) => set('surgeon', x)} />
          <Txt
            label="Name of Anesthetist"
            v={data.nameOfAnesthetist}
            on={(x) => set('nameOfAnesthetist', x)}
          />
          <Sel
            label="Operation"
            v={data.operationDone}
            on={(x) => set('operationDone', x)}
            options={['Done', 'Not Done']}
          />
          <Sel
            label="Type of Anesthesia"
            v={data.typeOfAnesthesia}
            on={(x) => set('typeOfAnesthesia', x)}
            options={['GA', 'LA']}
          />
          <Txt label="Date" v={data.date} on={(x) => set('date', x)} />
          <Txt label="Time" v={data.time} on={(x) => set('time', x)} />
        </div>

        <div className="mt-2 rounded-lg border border-gray-200">
          {GROUPS.map((g) => (
            <div key={g.name}>
              <div className="bg-gray-100 px-3 py-1 text-[11px] font-bold text-gray-700 uppercase tracking-wide">
                {g.name}
              </div>
              <div className="px-3">
                {g.items.map((it) => (
                  <ChecklistRow key={it.k} it={it} data={data} set={set} />
                ))}
              </div>
            </div>
          ))}
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 mt-2">
          <Txt label="Time" v={data.bTime} on={(x) => set('bTime', x)} />
          <Txt label="Date" v={data.bDate} on={(x) => set('bDate', x)} />
          <Txt label="Place" v={data.bPlace} on={(x) => set('bPlace', x)} />
          <Txt label="Place (2)" v={data.bPlace2} on={(x) => set('bPlace2', x)} />
        </div>
      </div>
    )}
  />
);

export default PostOperativeChecklistForm;
