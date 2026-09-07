import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Drug Administration Sheet — VH/NABH/OT/12/2026.
 * Grid of 11 drug rows, each split into T (time) / S (sign) sub-rows across
 * date columns. Drug names are editable/optional; the date-column grid prints
 * blank for bedside entry. Header auto-fills from admission data.
 */

const ROWS = 11;
const DATE_COLS = 7;
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
  const drugs = data.drugs || [];

  const dateHead = '<th></th>'.repeat(DATE_COLS);
  const emptyCells = '<td></td>'.repeat(DATE_COLS);
  const bodyRows = Array.from({ length: ROWS })
    .map((_, i) => {
      const name = esc(drugs[i] || '');
      return `<tr>
            <td rowspan="2" class="sr">${i + 1}</td>
            <td rowspan="2" class="drug">${name}</td>
            <td class="ts">T</td>${emptyCells}
          </tr>
          <tr><td class="ts">S</td>${emptyCells}</tr>`;
    })
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Drug Administration Sheet</title>
    <style>
      @page { size: A4; margin: 9mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; }
      .head { text-align:center; }
      .hname { font-size:22px; font-weight:800; color:#1d4ed8; margin:6px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:15px; font-weight:800; margin:12px 0 8px; text-transform:uppercase; }
      .idbox { border:1px solid #111; padding:8px 12px; font-size:12px; }
      .idrow { display:flex; gap:20px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:15px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      table { width:100%; border-collapse:collapse; margin-top:10px; }
      th, td { border:1px solid #111; }
      thead th { font-size:10.5px; font-weight:700; padding:4px 3px; }
      td { height:19px; }
      td.sr { width:32px; text-align:center; font-weight:600; }
      td.drug { width:210px; text-align:left; padding:0 5px; }
      td.ts, th.ts { width:26px; text-align:center; font-weight:600; }
      th.sr { width:32px; } th.drug { width:210px; }
      .legend { margin-top:6px; text-align:center; font-size:11px; font-weight:600; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">Drug Administration Sheet</div>
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

      <table>
        <thead>
          <tr>
            <th class="sr">Sr. No.</th>
            <th class="drug">IV / Drugs / Injections</th>
            <th class="ts">Date &rarr;</th>
            ${dateHead}
          </tr>
        </thead>
        <tbody>${bodyRows}</tbody>
      </table>
      <div class="legend">T - Time &nbsp;&nbsp;&nbsp;&nbsp; S - Sign.</div>

      <div style="text-align:right;font-size:10px;color:#555;margin-top:6px">VH/NABH/OT/12/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const DrugAdministrationSheet = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="DRUG_ADMIN_SHEET"
    title="Drug Administration Sheet"
    code="VH/NABH/OT/12/2026"
    defaults={{ mlcNo: '', date: '', time: '', drugs: Array(ROWS).fill('') }}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => {
      const drugs = data.drugs && data.drugs.length ? data.drugs : Array(ROWS).fill('');
      const setDrug = (i, v) => {
        const arr = [...drugs];
        arr[i] = v;
        set('drugs', arr);
      };
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
            {prefill.bedNo ? ` · Bed ${prefill.bedNo}` : ''} — the date grid (T/S) prints blank for
            bedside entry.
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div>
              <label htmlFor="fld-194" className="block text-xs font-semibold text-gray-600 mb-1">
                MLC No.
              </label>
              <input
                id="fld-194"
                value={data.mlcNo}
                onChange={(e) => set('mlcNo', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                placeholder="If applicable"
              />
            </div>
            <div>
              <label htmlFor="fld-193" className="block text-xs font-semibold text-gray-600 mb-1">
                Date
              </label>
              <DateSelect value={data.date} onChange={(v) => set('date', v)} />
            </div>
            <div>
              <label htmlFor="fld-192" className="block text-xs font-semibold text-gray-600 mb-1">
                Time
              </label>
              <input
                id="fld-192"
                type="time"
                value={data.time}
                onChange={(e) => set('time', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              />
            </div>
          </div>
          <div>
            <span className="block text-xs font-semibold text-gray-600 mb-2">
              IV / Drugs / Injections (optional — leave blank rows for bedside entry)
            </span>
            <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
              {drugs.map((val, i) => (
                <div key={i} className="flex items-center gap-2">
                  <span className="w-6 text-xs font-semibold text-gray-400">{i + 1}</span>
                  <input
                    value={val}
                    onChange={(e) => setDrug(i, e.target.value)}
                    className="flex-1 px-3 py-1.5 border border-gray-300 rounded-lg text-sm"
                    placeholder={`Drug / injection ${i + 1}`}
                  />
                </div>
              ))}
            </div>
          </div>
        </div>
      );
    }}
  />
);

export default DrugAdministrationSheet;
