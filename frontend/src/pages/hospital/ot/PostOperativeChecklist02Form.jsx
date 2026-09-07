import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import { GROUPS, ChecklistRow, buildChecklistBody } from './PostOperativeChecklistForm';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Post Operative Check List — VH/NABH/OT/02/2026.
 * Same checklist content as the OT/10 variant but with the UHID-style header
 * ("To be Checked by ward Sister at the Time of Patient from OT") and an
 * attached blank Input/Output grid as page 2.
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
  const ln = (v, w = 90) => `<span class="line" style="min-width:${w}px">${esc(v)}</span>`;
  const pick = (opts, sel) =>
    opts.map((o) => (sel === o ? `<b class="pk">${o}</b>` : o)).join(' / ');
  const body = buildChecklistBody(d);

  // Page 2 — blank Input/Output grid (26 rows for bedside entry).
  const ioRows = Array.from({ length: 26 })
    .map(() => '<tr>' + '<td></td>'.repeat(10) + '</tr>')
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Post-Operative Checklist</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      html, body { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:9.5px; margin:0; line-height:1.3; }
      .head { text-align:center; }
      .hname { font-size:19px; font-weight:800; color:#1d4ed8; margin:4px 0 2px; }
      .haddr { font-size:11px; font-weight:600; color:#7c3aed; }
      .title { font-size:15px; font-weight:800; margin:10px 0 1px; text-transform:uppercase; }
      .subtitle { font-size:10px; color:#333; margin-bottom:6px; }
      .idbox { border:1px solid #111; padding:6px 10px; font-size:10.5px; }
      .idrow { display:flex; gap:16px; margin:5px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:5px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 3px; min-height:14px; }
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
      .signrow { display:flex; gap:40px; margin-top:16px; }
      .signrow > div { flex:1; text-align:center; border-top:1px solid #111; padding-top:3px; }
      .btm { margin-top:10px; }
      /* page 2 I/O grid */
      .io { width:100%; border-collapse:collapse; }
      .io th, .io td { border:1px solid #111; text-align:center; }
      .io td { height:22px; }
      .io thead th { font-size:10px; font-weight:700; padding:4px 3px; }
      .code { text-align:right; font-size:9px; color:#555; margin-top:5px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">Post Operative Check List</div>
        <div class="subtitle">(To be Checked by ward Sister at the Time of Patient from OT)</div>
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
          <span style="flex:1.6"><b>Under Dr. :</b> <span class="flexval">${esc(d.underDr)}</span></span>
        </div>
        <div class="idrow">
          <span><b>Surgeon :</b> <span class="flexval">${esc(d.surgeon)}</span></span>
          <span><b>Name of Anesthetist :</b> <span class="flexval">${esc(d.nameOfAnesthetist)}</span></span>
        </div>
      </div>

      <div class="meta">
        <div class="r"><b>Operation :</b> ${pick(['Done', 'Not Done'], d.operationDone)} &nbsp;&nbsp;&nbsp; <b>Type of Anesthesia :</b> ${pick(['GA', 'LA'], d.typeOfAnesthesia)}</div>
      </div>

      <table>
        <thead>
          <tr>
            <th></th><th>CHECKING CONTENT</th><th>IF PRESENT (TICK)</th><th>IF ABSENT (CROSS)</th><th>REMARKS / COMMENT</th>
          </tr>
        </thead>
        <tbody>${body}</tbody>
      </table>

      <div class="btm">Time : ${ln(d.bTime, 70)} &nbsp; Date : ${ln(d.bDate, 70)} &nbsp; Place ${ln(d.bPlace, 90)}</div>
      <div class="btm">Place : ${ln(d.bPlace2, 120)}</div>
      <div class="signrow"><div>Signature (OT Staff)</div><div>Signature (Ward Staff)</div></div>

      <div style="page-break-before:always"></div>
      <table class="io">
        <thead>
          <tr><th colspan="5"></th><th colspan="2">INPUT</th><th colspan="3">OUTPUT</th></tr>
          <tr><th>TIME</th><th>TEMP</th><th>PULSE</th><th>RESP.</th><th>B. P.</th><th>I.V. FLUIDS</th><th>ORAL</th><th>RYLES TUBE ASPIRATION</th><th>URINE O/P</th><th>VOMITING MONITION</th></tr>
        </thead>
        <tbody>${ioRows}</tbody>
      </table>

      <div class="code">VH/NABH/OT/02/2026</div>
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

const PostOperativeChecklist02Form = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="POST_OP_CHECKLIST_02"
    title="Post-Operative Checklist (with I/O page)"
    code="VH/NABH/OT/02/2026"
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
          automatically. Page 2 prints a blank I/O grid.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="MLC No." v={data.mlcNo} on={(x) => set('mlcNo', x)} />
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

export default PostOperativeChecklist02Form;
