import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * WHO Surgical Safety Checklist — always printed landscape.
 * Three phases (SIGN IN / TIME OUT / SIGN OUT), each a column of checkboxes the
 * team ticks. Every item is a toggle that prints ticked. Header auto-fills.
 */

const esc = escapeHtml;

const CHECKLIST = [
  {
    phase: 'SIGN IN',
    title: 'Before induction of anaesthesia',
    sub: '(with at least nurse and anaesthetist)',
    footer: 'Anesthesiologist & Nurse SNDT',
    blocks: [
      {
        q: 'Has the patient confirmed his/her identity, site, procedure, and consent?',
        items: [['si_confirm', 'Yes']],
      },
      {
        q: 'Is the site marked?',
        items: [
          ['si_site_yes', 'Yes'],
          ['si_site_na', 'Not applicable'],
        ],
      },
      {
        q: 'Is the anaesthesia machine and medication check complete?',
        items: [['si_anes', 'Yes']],
      },
      {
        q: 'Is the pulse oximeter on the patient and functioning?',
        items: [['si_oximeter', 'Yes']],
      },
      { q: 'Does the patient have a:', items: [] },
      {
        q: 'Known allergy?',
        sub: true,
        items: [
          ['si_allergy_no', 'No'],
          ['si_allergy_yes', 'Yes'],
        ],
      },
      {
        q: 'Difficult airway or aspiration risk?',
        sub: true,
        items: [
          ['si_airway_no', 'No'],
          ['si_airway_yes', 'Yes, and equipment/assistance available'],
        ],
      },
      {
        q: 'Risk of >500ml blood loss (7ml/kg in children)?',
        sub: true,
        items: [
          ['si_blood_no', 'No'],
          ['si_blood_yes', 'Yes, and two IVs/central access and fluids planned'],
        ],
      },
    ],
  },
  {
    phase: 'TIME OUT',
    title: 'Before skin incision',
    sub: '(with nurse, anaesthetist and surgeon)',
    footer: 'Surgeon, Anaesthesiologist and OT nurse SNDT',
    blocks: [
      {
        items: [
          ['to_intro', 'Confirm all team members have introduced themselves by name and role.'],
        ],
        bold: true,
      },
      {
        items: [
          [
            'to_name',
            "Confirm the patient's name, procedure, and where the incision will be made.",
          ],
        ],
        bold: true,
      },
      {
        q: 'Has antibiotic prophylaxis been given within the last 60 minutes?',
        items: [
          ['to_abx_yes', 'Yes'],
          ['to_abx_na', 'Not applicable'],
        ],
      },
      { heading: 'Anticipated Critical Events' },
      {
        label: 'To Surgeon:',
        items: [
          ['to_surg_steps', 'What are the critical or non-routine steps?'],
          ['to_surg_time', 'How long will the case take?'],
          ['to_surg_blood', 'What is the anticipated blood loss?'],
        ],
      },
      {
        label: 'To Anaesthetist:',
        items: [['to_anes_concern', 'Are there any patient-specific concerns?']],
      },
      {
        label: 'To Nursing Team:',
        items: [
          ['to_nurse_sterility', 'Has sterility (including indicator results) been confirmed?'],
          ['to_nurse_equip', 'Are there equipment issues or any concerns?'],
        ],
      },
      {
        q: 'Is essential imaging displayed?',
        items: [
          ['to_imaging_yes', 'Yes'],
          ['to_imaging_na', 'Not applicable'],
        ],
      },
    ],
  },
  {
    phase: 'SIGN OUT',
    title: 'Before patient leaves operating room',
    sub: '(with nurse, anaesthetist and surgeon)',
    footer: 'Surgeon, Aneathesiologist and OT nurse SNDT',
    blocks: [
      {
        label: 'Nurse Verbally Confirms:',
        items: [
          ['so_name', 'The name of the procedure'],
          ['so_counts', 'Completion of instrument, sponge and needle counts'],
          [
            'so_specimen',
            'Specimen labelling (read specimen labels aloud, including patient name)',
          ],
          ['so_equip', 'Whether there are any equipment problems to be addressed'],
        ],
      },
      {
        label: 'To Surgeon, Anaesthetist and Nurse:',
        items: [
          ['so_concerns', 'What are the key concerns for recovery and management of this patient?'],
        ],
      },
    ],
  },
];

const ALL_ITEMS = CHECKLIST.flatMap((c) =>
  c.blocks.flatMap((b) => (b.items || []).map(([k, label]) => ({ k, label, phase: c.phase })))
);

const buildPrintHtml = (data, prefill, hospital) => {
  const f = prefill || {};
  const d = data || {};
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = esc(
    titleCase([f.patientSurname, f.patientFirstName, f.husbandFatherName].filter(Boolean).join(' '))
  );
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:44px;width:auto;object-fit:contain"/>`
    : '';
  const cb = (k) => `<b class="cb">${d[k] ? '✓' : '&nbsp;'}</b>`;

  const col = (c) => {
    let inner = '';
    c.blocks.forEach((b) => {
      if (b.heading) inner += `<div class="bh">${esc(b.heading)}</div>`;
      if (b.label) inner += `<div class="lbl">${esc(b.label)}</div>`;
      if (b.q) inner += `<div class="q ${b.sub ? 'sub' : ''}">${esc(b.q)}</div>`;
      (b.items || []).forEach(([k, label]) => {
        inner += `<div class="it ${b.bold ? 'bold' : ''}">${cb(k)} <span>${esc(label)}</span></div>`;
      });
    });
    return `<div class="col">
            <div class="phhead">${esc(c.phase)}</div>
            <div class="ctitle">${esc(c.title)}</div>
            <div class="csub">${esc(c.sub)}</div>
            <div class="cbody">${inner}</div>
            <div class="cfoot">${esc(c.footer)}</div>
          </div>`;
  };

  return `<!doctype html><html><head><meta charset="utf-8"><title>WHO Surgical Safety Checklist</title>
    <style>
      @page { size: A4 landscape; margin: 8mm; }
      * { box-sizing: border-box; }
      html, body { -webkit-print-color-adjust:exact; print-color-adjust:exact; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:9px; margin:0; line-height:1.3; }
      .top { display:flex; align-items:center; gap:14px; }
      .hname { font-size:15px; font-weight:800; color:#1d4ed8; }
      .haddr { font-size:9.5px; font-weight:600; color:#7c3aed; }
      .title { flex:1; text-align:center; font-size:17px; font-weight:800; }
      .pline { font-size:9px; margin:4px 0 6px; }
      .cols { display:flex; gap:8px; }
      .col { flex:1; border:1px solid #999; display:flex; flex-direction:column; }
      .phhead { background:#4b5563; color:#fff; font-weight:800; font-size:11px; padding:4px 8px; }
      .ctitle { font-weight:800; padding:5px 8px 0; }
      .csub { color:#444; padding:0 8px 4px; font-size:8.5px; }
      .cbody { padding:4px 8px; flex:1; }
      .q { font-weight:700; margin:6px 0 2px; }
      .q.sub { font-weight:700; margin:4px 0 1px; }
      .bh { font-weight:800; margin:6px 0 2px; }
      .lbl { font-weight:700; margin:5px 0 1px; }
      .it { margin:2px 0 2px 6px; display:flex; gap:5px; align-items:flex-start; }
      .it.bold span { font-weight:700; }
      .cb { border:1px solid #111; width:11px; height:11px; min-width:11px; display:inline-block; text-align:center; line-height:10px; }
      .cfoot { border-top:1px solid #ccc; padding:4px 8px; font-size:8.5px; color:#333; }
      .arrowrow { display:flex; }
    </style></head><body>
      <div class="top">
        ${logo}<div><div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="title">WHO Surgical Safety Checklist</div>
        <div style="width:60px"></div>
      </div>
      <div class="pline"><b>Patient :</b> ${patientName || '—'} &nbsp; <b>IPD No. :</b> ${esc(f.ipdRegistrationNo)} &nbsp; <b>Date :</b> ${esc(d.date)}</div>

      <div class="cols">
        ${CHECKLIST.map(col).join('')}
      </div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const WhoSafetyChecklistForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="WHO_CHECKLIST"
    title="WHO Surgical Safety Checklist"
    code="Prints landscape"
    defaults={{}}
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
          {prefill.ipdRegistrationNo ? ` · IPD ${prefill.ipdRegistrationNo}` : ''} — prints in
          landscape. Tick the items completed.
        </div>
        <div>
          <label htmlFor="fld-210" className="block text-xs font-semibold text-gray-600 mb-1">
            Date
          </label>
          <input
            id="fld-210"
            value={data.date || ''}
            onChange={(e) => set('date', e.target.value)}
            className="w-full sm:w-60 px-3 py-2 border border-gray-300 rounded-lg text-sm"
            placeholder="Date"
          />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          {CHECKLIST.map((c) => (
            <div key={c.phase} className="rounded-lg border border-gray-200">
              <div className="bg-gray-700 text-white text-xs font-bold px-3 py-1.5 rounded-t-lg">
                {c.phase} — {c.title}
              </div>
              <div className="p-3 space-y-1">
                {c.blocks.map((b, bi) => (
                  <div key={bi}>
                    {b.heading && (
                      <div className="text-xs font-extrabold text-gray-800 mt-2">{b.heading}</div>
                    )}
                    {b.label && (
                      <div className="text-xs font-bold text-gray-700 mt-2">{b.label}</div>
                    )}
                    {b.q && <div className="text-xs font-semibold text-gray-700 mt-2">{b.q}</div>}
                    {(b.items || []).map(([k, label]) => (
                      <label
                        key={k}
                        className="flex items-start gap-2 text-xs text-gray-700 py-0.5 ml-1"
                      >
                        <input
                          type="checkbox"
                          checked={!!data[k]}
                          onChange={(e) => set(k, e.target.checked)}
                          className="mt-0.5"
                        />
                        <span>{label}</span>
                      </label>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    )}
  />
);

export default WhoSafetyChecklistForm;
