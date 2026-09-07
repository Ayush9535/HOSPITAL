import React from 'react';
import DateSelect from '../../../components/DateSelect';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';
import SurgeryFormFrame from './SurgeryFormFrame';

/**
 * Informed Consent — Surgery — VH/NABH/OT/01/2026.
 * Two-box header auto-filled from admission data. 13 fixed clauses with a few
 * inline fill-in blanks (surgeon, assistant, procedure, anaesthetist) that are
 * editable; signature lines print blank.
 */

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
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:52px;width:auto;object-fit:contain"/>`
    : '';
  const surgeon = esc(titleCase(data.surgeonName || ''));
  const uli = (v, w = 120) => `<span class="uli" style="min-width:${w}px">${esc(v)}</span>`;

  return `<!doctype html><html><head><meta charset="utf-8"><title>Informed Consent Surgery</title>
    <style>
      @page { size: A4; margin: 9mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:10.5px; margin:0; line-height:1.35; }
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
      .ftitle { text-align:center; font-weight:800; font-size:11.5px; margin:10px 0 6px; }
      p { margin:5px 0; text-align:justify; }
      .num { margin:4px 0; padding-left:20px; text-indent:-20px; text-align:justify; }
      .num .n { display:inline-block; width:16px; font-weight:700; text-indent:0; }
      .uli { border-bottom:1px solid #666; display:inline-block; padding:0 3px; }
      .caption { text-align:center; font-size:9.5px; color:#555; }
      .sigrow { display:flex; gap:20px; margin:9px 0 3px; align-items:flex-end; }
      .sc { display:flex; align-items:flex-end; gap:5px; flex:1; }
      .sul { border-bottom:1px solid #666; flex:1; min-height:14px; padding:0 3px; }
      .note { font-size:9.5px; color:#444; margin:0 0 4px; }
      .code { text-align:right; font-size:10px; color:#555; margin-top:6px; }
    </style></head><body>
      <div class="top">
        <div class="brand">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div></div>
        <div class="tbar">INFORMED CONSENT SURGERY</div>
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
          <div class="idline"><b>Date :</b> <span class="ul">${esc(data.date)}</span> <b>Time :</b> <span class="ul">${esc(data.time)}</span></div>
        </div>
      </div>

      <div class="ftitle">AUTHORISED INFORMED CONSENT FOR PERFORMING SURGICAL OPERATION<br/>SURGICAL / MEDICAL PROCEDURES AND FOR ADMINISTRATION OF ANAESTHESIA</div>

      <div class="sigrow"><span class="sc"><b>Patient Name :</b> <span class="sul">${patientName}</span></span><span class="sc"><b>Date :</b> <span class="sul">${esc(data.date)}</span></span></div>

      <div class="num"><span class="n">1)</span> I ${uli(patientName, 140)} authorise Dr. ${uli(surgeon, 140)} his / her associate(s) or and such assistants as may be selected by him / her (Name of the Asst. Dr. ${uli(data.assistantDr, 120)}) to treat the following condition(s) : ${uli(data.conditions, 160)} and to perform surgical procedure, at ${uli(data.procedureDesc, 180)}
        <div class="caption">(Physician's description of procedure)</div>
      </div>
      <div class="num"><span class="n">2)</span> Dr. ${uli(surgeon, 140)} the surgeon / physician has explained the procedure(s) necessary for the treatment and I understand the same.</div>
      <div class="num"><span class="n">3)</span> I consent to the administration of any anaesthesia considered necessary or indicated in the judgement of the surgeon and / or members of Department Anaesthesiology. (Name of Anaesthetist ${uli(data.anaesthetistName, 140)})</div>
      <div class="num"><span class="n">4)</span> My physician / surgeon / anesthetist has adequately explained to me the risk and possible consequences associated with this procedure, the alternatives to the procedure, the probability the proposed treatment will be successful, and the prognosis if the proposed treatment is not given.</div>
      <div class="num"><span class="n">5)</span> I am aware that the practice of medicine and surgery is not an exact science, that there are risks involved, and I acknowledge that no guarantees can be given about the results of the operation or procedure.</div>
      <div class="num"><span class="n">6)</span> I have also been informed by treating Doctor that there are or may be other risks including, but not limited to, severe loss of blood, infection, in case of general anaesthesia cardiac arrest, pulmonary embolisms etc, that may be associated with surgical procedure or administration of anaesthesia.</div>
      <div class="num"><span class="n">7)</span> It has been explained to me by the surgeon physician that during the course of the operation, unforeseen conditions may be revealed that may necessitate an extension of the original procedure(s) than those set forth in paragraph. I therefore authorize and request the above named surgeon, his assistants, or his designee's to perform such surgical procedure(s) as are necessary in the exercise of his professional judgment. The authority granted under this paragraph shall extend to treating all conditions that require treatment and not known to him or myself at the time the operation is commenced.</div>
      <div class="num"><span class="n">8)</span> I authorize the examination by authorized individual of any tissues, organs removed during the procedure and the disposal of such tissue, organ or body parts in accordance with hospital policies.</div>
      <div class="num"><span class="n">9)</span> I consent to the taking and publication of any photographs in the course of this procedure for purpose of advancing medical education. I understand this remain confidential.</div>
      <div class="num"><span class="n">10)</span> I should be aware that sterility might result from this operation. I know that a sterile person is incapable of producing children.</div>
      <div class="num"><span class="n">11)</span> All my questions have been fully and clearly answered to my entire satisfaction.</div>
      <div class="num"><span class="n">12)</span> I have been explained clearly that the surgical procedures of brain &amp; spine would have some complications and are not totally safe.</div>
      <div class="num"><span class="n">13)</span> I have been explained clearly that the surgical procedures of bone &amp; soft tissues (Joint Replacements) would have some complications and are not totally safe.</div>

      <p>I certify that I have read and fully understood the above consent, that the explanations therein referred to were made, that all blanks or statements requiring insertion or completion were filled in before I signed.</p>

      <div class="sigrow"><span class="sc"><b>Relative's Name :</b> <span class="sul"></span></span><span class="sc"><b>Signature :</b> <span class="sul"></span></span></div>
      <div class="note">(Patient or person authorized to sign the consent on behalf of Patient)</div>
      <div class="sigrow"><span class="sc"><b>Relation with Patient :</b> <span class="sul"></span></span><span class="sc"><b>Date :</b> <span class="sul"></span></span></div>
      <div class="sigrow"><span class="sc"><b>Witness :</b> <span class="sul"></span></span><span class="sc"><b>Signature :</b> <span class="sul"></span></span></div>
      <div class="sigrow"><span class="sc"><b>Consultant Name :</b> <span class="sul">${surgeon}</span></span><span class="sc"><b>Signature :</b> <span class="sul"></span></span></div>

      <div class="code">VH/NABH/OT/01/2026</div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const Labelled = ({ label, children }) => (
  <div>
    <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
    {children}
  </div>
);
const inputCls = 'w-full px-3 py-2 border border-gray-300 rounded-lg text-sm';

const InformedConsentSurgeryForm = ({ admissionId, onClose, readOnly = false }) => (
  <SurgeryFormFrame
    admissionId={admissionId}
    readOnly={readOnly}
    formType="INFORMED_CONSENT_SURGERY"
    title="Informed Consent — Surgery"
    code="VH/NABH/OT/01/2026"
    defaults={{
      date: '',
      time: '',
      surgeonName: '',
      assistantDr: '',
      conditions: '',
      procedureDesc: '',
      anaesthetistName: '',
    }}
    buildPrintHtml={buildPrintHtml}
    onClose={onClose}
    renderFields={({ data, set, prefill }) => (
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
          {prefill.category ? ` · ${prefill.category}` : ''} — header fills automatically on the
          printout.
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Labelled label="Date">
            <DateSelect value={data.date} onChange={(v) => set('date', v)} />
          </Labelled>
          <Labelled label="Time">
            <input
              type="time"
              value={data.time}
              onChange={(e) => set('time', e.target.value)}
              className={inputCls}
            />
          </Labelled>
        </div>
        <Labelled label="Surgeon / Physician (Dr.)">
          <input
            value={data.surgeonName}
            onChange={(e) => set('surgeonName', e.target.value)}
            className={inputCls}
            placeholder="Operating surgeon's name"
          />
        </Labelled>
        <Labelled label="Assistant Doctor (optional)">
          <input
            value={data.assistantDr}
            onChange={(e) => set('assistantDr', e.target.value)}
            className={inputCls}
            placeholder="Name of the assistant doctor"
          />
        </Labelled>
        <Labelled label="Anaesthetist (optional)">
          <input
            value={data.anaesthetistName}
            onChange={(e) => set('anaesthetistName', e.target.value)}
            className={inputCls}
            placeholder="Name of anaesthetist"
          />
        </Labelled>
        <Labelled label="Condition(s) to treat (optional)">
          <input
            value={data.conditions}
            onChange={(e) => set('conditions', e.target.value)}
            className={inputCls}
            placeholder="Condition(s)"
          />
        </Labelled>
        <Labelled label="Procedure description (optional)">
          <input
            value={data.procedureDesc}
            onChange={(e) => set('procedureDesc', e.target.value)}
            className={inputCls}
            placeholder="Physician's description of the procedure"
          />
        </Labelled>
        <p className="text-xs text-gray-400">
          Signature lines print blank for offline signing. Empty fields print empty.
        </p>
      </div>
    )}
  />
);

export default InformedConsentSurgeryForm;
