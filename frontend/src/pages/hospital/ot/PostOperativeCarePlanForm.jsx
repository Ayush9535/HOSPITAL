import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Post Operative Care Plan — VH/NABH/OT/09/2026.
 * Two-box header + a ruled Notes area. The notes flow onto a second ruled page
 * automatically only when they overflow the first page.
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

  return `<!doctype html><html><head><meta charset="utf-8"><title>Post Operative Care Plan</title>
    <style>
      @page { size: A4; margin: 9mm; }
      * { box-sizing: border-box; }
      html, body { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; line-height:1.4; }
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
      .noteslabel { margin:10px 0 2px; font-weight:600; }
      .ruled { background-image: repeating-linear-gradient(transparent, transparent 25px, #bbb 25px, #bbb 26px); line-height:26px; white-space:pre-wrap; min-height:640px; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:6px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">POST OPERATIVE CARE PLAN</div>
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

      <div class="noteslabel">Notes :</div>
      <div class="ruled">${esc(d.notes)}</div>

      <div class="code">VH/NABH/OT/09/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const PostOperativeCarePlanForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="POST_OP_CARE_PLAN"
    title="Post Operative Care Plan"
    code="VH/NABH/OT/09/2026"
    defaults={{ date: '', time: '', notes: '' }}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => (
      <div className="space-y-3">
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
          automatically. A 2nd ruled page prints only if the notes overflow.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <div>
            <label htmlFor="fld-197" className="block text-xs font-semibold text-gray-600 mb-1">
              Date
            </label>
            <DateSelect value={data.date} onChange={(v) => set('date', v)} />
          </div>
          <div>
            <label htmlFor="fld-196" className="block text-xs font-semibold text-gray-600 mb-1">
              Time
            </label>
            <input
              id="fld-196"
              type="time"
              value={data.time}
              onChange={(e) => set('time', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </div>
        </div>
        <div>
          <label htmlFor="fld-195" className="block text-xs font-semibold text-gray-600 mb-1">
            Notes
          </label>
          <textarea
            id="fld-195"
            value={data.notes}
            onChange={(e) => set('notes', e.target.value)}
            rows={10}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            placeholder="Post-operative care plan / notes…"
          />
        </div>
        <p className="text-xs text-gray-400">Leave blank to print a ruled sheet for handwriting.</p>
      </div>
    )}
  />
);

export default PostOperativeCarePlanForm;
