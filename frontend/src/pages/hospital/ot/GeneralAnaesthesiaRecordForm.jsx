import React from 'react';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * General Anaesthesia (intra-operative record).
 * Reference has no letterhead; we add the hospital header + a compact patient
 * ID line (per house style), then the anaesthesia record. Choice items print
 * boxed around the selected option.
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
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:50px;width:auto;object-fit:contain"/>`
    : '';
  const ln = (v, w = 70) => `<span class="line" style="min-width:${w}px">${esc(v)}</span>`;
  const box = (opts, sel) =>
    opts.map((o) => (sel === o ? `<b class="pick">${esc(o)}</b>` : esc(o))).join(' / ');
  const row = (label, right) =>
    `<div class="row"><span class="lab">${label}</span><span class="dash">-</span><span class="val">${right}</span></div>`;
  const sub = (right) =>
    `<div class="row"><span class="lab"></span><span class="dash"></span><span class="val">${right}</span></div>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>General Anaesthesia</title>
    <style>
      @page { size: A4; margin: 10mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; line-height:1.4; }
      .head { text-align:center; }
      .hname { font-size:20px; font-weight:800; color:#1d4ed8; margin:4px 0 2px; }
      .haddr { font-size:11px; font-weight:600; color:#7c3aed; }
      .title { font-size:17px; font-weight:800; margin:12px 0 6px; text-transform:uppercase; letter-spacing:1px; }
      .idbox { border:1px solid #111; padding:6px 10px; font-size:11px; display:flex; gap:20px; }
      .idbox > span { flex:1; display:flex; align-items:flex-end; gap:6px; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:14px; }
      .row { display:flex; margin:4px 0; align-items:flex-end; }
      .lab { width:160px; font-weight:600; }
      .dash { width:14px; }
      .val { flex:1; }
      .line { border-bottom:1px solid #666; display:inline-block; padding:0 3px; min-height:13px; }
      .pick { border:1px solid #111; padding:0 5px; font-weight:700; }
      .gap { height:10px; }
      .three { display:flex; gap:18px; margin:5px 0; }
      .three > span { flex:1; }
      .sign { margin-top:26px; text-align:right; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:8px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div>
        <div class="title">General Anaesthesia</div>
      </div>

      <div class="idbox">
        <span><b>Patient :</b> <span class="flexval">${patientName}</span></span>
        <span><b>IPD No. :</b> <span class="flexval">${esc(f.ipdRegistrationNo)}</span></span>
        <span><b>Bed :</b> <span class="flexval">${esc(f.bedNo)}</span></span>
        <span><b>Date :</b> <span class="flexval">${esc(d.date)}</span></span>
      </div>

      <div style="margin-top:10px">
        ${row('Premedication', ln(d.premedication, 260))}
        ${row('Preoxygenation', ln(d.preoxygenation, 260))}
        ${row('Preinduction', ln(d.preinduction, 260))}
        ${row('Induction', ln(d.induction, 260))}
        <div class="gap"></div>
        ${row('Intubation', ln(d.intubation, 260))}
        ${row('Done with', `${box(['Direct Laryngoscopy', 'Video Laryngoscopy'], d.doneWith)} ${ln(d.doneWithNote, 100)}`)}
        ${row('MCLS Grade', ln(d.mclsGrade, 120))}
        ${row('ETT No.', ln(d.ettNo, 120))}
        ${row('Type', box(['Oral', 'nasal'], d.typeRoute))}
        ${sub(box(['Portex', 'flexometallic'], d.typeTube))}
        ${sub(box(['Cuffed', 'Uncuffed'], d.typeCuff))}
        ${row('Fixed at', `${ln(d.fixedAt, 70)} cm`)}
        ${row('Throat pack', box(['Y', 'N'], d.throatPack))}
        ${row('Circuit used', box(['Closed', 'Bains', 'JR'], d.circuit))}
        <div class="gap"></div>
        <div class="row"><span class="val">${box(['IPPV', 'Spontaneously Breathing on facemask', 'LMA'], d.ventMode)}</span></div>
        <div class="gap"></div>
        ${row('Reversal', `Throat Pack Removed - ${box(['Y', 'N'], d.throatPackRemoved)}`)}
        <div class="row"><span class="val">Inj Neostigmine ${ln(d.neostigmine, 60)} mg IV + Inj Glycopyrrolate ${ln(d.glycopyrrolate, 60)} mg IV</span></div>
        <div class="gap"></div>
        ${row('Extubation', ln(d.extubation, 260))}
        <div class="gap"></div>
        ${row('Post-op condition', ln(d.postOpCondition, 240))}
        <div class="three">
          <span>P - ${ln(d.p, 70)}</span>
          <span>BP - ${ln(d.bp, 70)}</span>
          <span>SpO2 - ${ln(d.spo2, 70)}</span>
        </div>
        <div class="three">
          <span>Tone - ${ln(d.tone, 60)}</span>
          <span>Reflexes ${ln(d.reflexes, 60)}</span>
          <span>Obeying commands - ${ln(d.obeying, 50)}</span>
        </div>
        <div class="gap"></div>
        <div class="row"><span class="val">Shifted to ${box(['ICU', 'Ward', 'Room'], d.shiftedTo)} at - ${ln(d.shiftedAt, 120)}</span></div>
      </div>

      <div class="sign">${esc(titleCase(d.anaesthetistName))}<div>Name and Signature of Anaesthesiologist</div></div>

      <div class="code">General Anaesthesia Record</div>
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
const H = ({ children }) => (
  <div className="text-xs font-bold text-gray-800 uppercase tracking-wide mt-2 mb-1">
    {children}
  </div>
);

const GeneralAnaesthesiaRecordForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="GENERAL_ANAESTHESIA"
    title="General Anaesthesia"
    code=""
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
          automatically.
        </div>
        <Txt label="Date" v={data.date} on={(x) => set('date', x)} />

        <H>Induction</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Premedication" v={data.premedication} on={(x) => set('premedication', x)} />
          <Txt
            label="Preoxygenation"
            v={data.preoxygenation}
            on={(x) => set('preoxygenation', x)}
          />
          <Txt label="Preinduction" v={data.preinduction} on={(x) => set('preinduction', x)} />
          <Txt label="Induction" v={data.induction} on={(x) => set('induction', x)} />
        </div>

        <H>Intubation</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Txt label="Intubation" v={data.intubation} on={(x) => set('intubation', x)} />
          <Sel
            label="Done with"
            v={data.doneWith}
            on={(x) => set('doneWith', x)}
            options={['Direct Laryngoscopy', 'Video Laryngoscopy']}
          />
          <Txt label="MCLS Grade" v={data.mclsGrade} on={(x) => set('mclsGrade', x)} />
          <Txt label="ETT No." v={data.ettNo} on={(x) => set('ettNo', x)} />
          <Sel
            label="Type — route"
            v={data.typeRoute}
            on={(x) => set('typeRoute', x)}
            options={['Oral', 'nasal']}
          />
          <Sel
            label="Type — tube"
            v={data.typeTube}
            on={(x) => set('typeTube', x)}
            options={['Portex', 'flexometallic']}
          />
          <Sel
            label="Type — cuff"
            v={data.typeCuff}
            on={(x) => set('typeCuff', x)}
            options={['Cuffed', 'Uncuffed']}
          />
          <Txt label="Fixed at (cm)" v={data.fixedAt} on={(x) => set('fixedAt', x)} />
          <Sel
            label="Throat pack"
            v={data.throatPack}
            on={(x) => set('throatPack', x)}
            options={['Y', 'N']}
          />
          <Sel
            label="Circuit used"
            v={data.circuit}
            on={(x) => set('circuit', x)}
            options={['Closed', 'Bains', 'JR']}
          />
          <Sel
            label="Ventilation"
            v={data.ventMode}
            on={(x) => set('ventMode', x)}
            options={['IPPV', 'Spontaneously Breathing on facemask', 'LMA']}
          />
        </div>

        <H>Reversal &amp; Extubation</H>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Sel
            label="Throat Pack Removed"
            v={data.throatPackRemoved}
            on={(x) => set('throatPackRemoved', x)}
            options={['Y', 'N']}
          />
          <Txt
            label="Inj Neostigmine (mg IV)"
            v={data.neostigmine}
            on={(x) => set('neostigmine', x)}
          />
          <Txt
            label="Inj Glycopyrrolate (mg IV)"
            v={data.glycopyrrolate}
            on={(x) => set('glycopyrrolate', x)}
          />
          <Txt label="Extubation" v={data.extubation} on={(x) => set('extubation', x)} />
        </div>

        <H>Post-op</H>
        <Txt
          label="Post-op condition"
          v={data.postOpCondition}
          on={(x) => set('postOpCondition', x)}
        />
        <div className="grid grid-cols-3 gap-2">
          <Txt label="P" v={data.p} on={(x) => set('p', x)} />
          <Txt label="BP" v={data.bp} on={(x) => set('bp', x)} />
          <Txt label="SpO2" v={data.spo2} on={(x) => set('spo2', x)} />
          <Txt label="Tone" v={data.tone} on={(x) => set('tone', x)} />
          <Txt label="Reflexes" v={data.reflexes} on={(x) => set('reflexes', x)} />
          <Txt label="Obeying commands" v={data.obeying} on={(x) => set('obeying', x)} />
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          <Sel
            label="Shifted to"
            v={data.shiftedTo}
            on={(x) => set('shiftedTo', x)}
            options={['ICU', 'Ward', 'Room']}
          />
          <Txt label="Shifted at (time)" v={data.shiftedAt} on={(x) => set('shiftedAt', x)} />
        </div>
        <Txt
          label="Anaesthesiologist Name"
          v={data.anaesthetistName}
          on={(x) => set('anaesthetistName', x)}
        />
        <p className="text-xs text-gray-400">Signature prints blank for offline signing.</p>
      </div>
    )}
  />
);

export default GeneralAnaesthesiaRecordForm;
