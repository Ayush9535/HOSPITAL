import React, { useState, useEffect } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import escapeHtml from '../../../utils/escapeHtml';
import { printHtml } from '../../../utils/printHtml';
import { titleCase } from '../../../utils/text';

/**
 * InitialAssessmentPanel - NABH "Admission History & Initial Assessment"
 * (Phase 1 Nurse module). Patient identifiers + prescribed medicines are
 * pre-filled; the clinical fields, structured Yes/No histories, and pain score
 * are captured here and printed as the 2-page form.
 */

const esc = escapeHtml;
const parse = (s, fb) => {
  try {
    return s ? JSON.parse(s) : fb;
  } catch {
    return fb;
  }
};

const PAST_ROWS = [
  ['hypertension', 'Hypertension'],
  ['diabetes', 'Diabetes'],
  ['tuberculosis', 'Tuberculosis'],
  ['epilepsy', 'Epilepsy'],
  ['ihd', 'IHD'],
];
const FAMILY_ROWS = [
  ['hypertension', 'Hypertension'],
  ['heartDisease', 'Heart Disease'],
  ['diabetes', 'Diabetes'],
  ['tuberculosis', 'Tuberculosis'],
  ['epilepsy', 'Epilepsy'],
  ['asthma', 'Asthma'],
  ['stroke', 'Stroke'],
  ['arthritis', 'Arthritis / Gout'],
  ['cancer', 'Cancer'],
  ['otherChronic', 'Any Other Chronic Disease'],
];
const PERSONAL_ROWS = [
  ['smoking', 'Smoking', 'Per Day'],
  ['alcohol', 'Alcohol', 'Frequency'],
  ['drugs', 'Drugs', 'Frequency'],
  ['tobacco', 'Tobacco', 'Frequency'],
  ['diet', 'Diet', ''],
];
const PAIN = [
  [0, 'No Pain'],
  [1, 'Just Noticeable'],
  [2, 'Mild Pain'],
  [3, 'Uncomfortable'],
  [4, 'Annoying'],
  [5, 'Just bearable'],
  [6, 'Moderate'],
  [7, 'Strong'],
  [8, 'Severe'],
  [9, 'Horrible'],
  [10, 'Worst Pain'],
];

const faceSvg = (level, selected) => {
  const t = Math.max(0, Math.min(10, level)) / 10;
  const cq = 16 - t * 6;
  const bg = selected ? '#111' : '#fff';
  const fg = selected ? '#fff' : '#111';
  return `<svg width="26" height="26" viewBox="0 0 20 20"><circle cx="10" cy="10" r="8.5" fill="${bg}" stroke="#111" stroke-width="0.8"/><circle cx="7" cy="8" r="1" fill="${fg}"/><circle cx="13" cy="8" r="1" fill="${fg}"/><path d="M6 13 Q10 ${cq} 14 13" fill="none" stroke="${fg}" stroke-width="1" stroke-linecap="round"/></svg>`;
};

export const buildAssessmentHtml = (f, meds, hospital, a) => {
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = [f.patientSurname, f.patientFirstName, f.husbandFatherName]
    .filter(Boolean)
    .join(' ');
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M'),
    isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:52px;width:auto;object-fit:contain"/>`
    : '';
  const past = parse(a.pastHistory, {});
  const family = parse(a.familyHistory, {});
  const personal = parse(a.personalHistory, {});

  // Ruled free-text block: lines always present so the page never goes blank.
  const lined = (value, n) =>
    `<div class="lined" style="height:${n * 20}px">${esc(value || '').replace(/\n/g, '<br/>')}</div>`;
  const mark = (v) =>
    v === true
      ? '<b class="on">Yes</b>&nbsp;/&nbsp;No'
      : v === false
        ? 'Yes&nbsp;/&nbsp;<b class="on">No</b>'
        : 'Yes&nbsp;/&nbsp;No';

  const medRows = (meds || [])
    .map((m) => {
      const stopped = (m.status || '').toUpperCase() === 'STOPPED';
      return `<tr><td>${esc(m.medicineName)}${stopped ? ' <i>(stopped)</i>' : ''}</td><td>${esc(m.dosage)}</td><td>${esc(m.route)}</td><td>${esc(m.frequency)}</td></tr>`;
    })
    .join('');
  const emptyMedRows = Array.from({ length: Math.max(0, 6 - (meds || []).length) })
    .map(() => '<tr><td>&nbsp;</td><td></td><td></td><td></td></tr>')
    .join('');

  const pastRows = PAST_ROWS.map(
    ([k, l]) =>
      `<tr><td>${l}</td><td class="c">${mark(past[k]?.yes)}</td><td>${esc(past[k]?.since)}</td></tr>`
  ).join('');
  const familyRows = [];
  for (let i = 0; i < FAMILY_ROWS.length; i += 2) {
    const [k1, l1] = FAMILY_ROWS[i];
    const pair = FAMILY_ROWS[i + 1];
    const c2 = pair
      ? `<td>${pair[1]}</td><td class="c">${mark(family[pair[0]])}</td>`
      : '<td></td><td></td>';
    familyRows.push(`<tr><td>${l1}</td><td class="c">${mark(family[k1])}</td>${c2}</tr>`);
  }
  const personalRows = PERSONAL_ROWS.map(([k, l, fq]) => {
    const p = personal[k] || {};
    return `<tr><td>${l}</td><td class="c">${p.yes === true ? '<b class="on">Yes</b>' : 'Yes'}</td><td class="c">${p.yes === false ? '<b class="on">No</b>' : 'No'}</td><td>Since ${esc(p.since)}</td><td>${fq ? `${fq}: ${esc(p.freq)}` : ''}</td></tr>`;
  }).join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Admission History & Initial Assessment</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:12px; margin:0; }
      .page { border:1px solid #111; padding:10px 14px; min-height:281mm; }
      .page.two { page-break-before: always; }
      .top { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; }
      .brand { display:flex; gap:12px; align-items:center; }
      .hname { font-size:19px; font-weight:800; color:#1d4ed8; margin:0; }
      .haddr { font-size:11px; font-weight:600; color:#7c3aed; margin-top:2px; }
      .title { background:#111; color:#fff; font-weight:800; padding:6px 12px; font-size:13px; white-space:nowrap; }
      .rule { border:0; border-top:2px solid #111; margin:8px 0; }
      .idbox { display:flex; gap:12px; margin-top:6px; }
      .idbox > div { flex:1; border:1px solid #111; padding:8px 10px; }
      .fld { margin:5px 0; }
      .val { border-bottom:1px solid #666; display:inline-block; min-width:120px; padding:0 4px; }
      .sec { font-weight:700; margin:15px 0 4px; }
      .lined { background-image: repeating-linear-gradient(transparent, transparent 19px, #999 19px, #999 20px); line-height:20px; margin-bottom:6px; }
      table { width:100%; border-collapse:collapse; margin:4px 0; }
      td, th { border:1px solid #111; padding:4px 6px; font-size:11px; vertical-align:top; }
      th { background:#f0f0f0; text-align:center; }
      .c { text-align:center; white-space:nowrap; }
      .on { background:#111; color:#fff; padding:0 4px; border-radius:2px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; }
      .sex .on { background:#111; color:#fff; }
      .pain td { text-align:center; font-size:9px; }
      .sign { display:flex; gap:30px; margin-top:10px; }
      .sign > div { flex:1; }
    </style></head><body>

    <div class="page">
      <div class="top">
        <div class="brand">${logo}<div><h1 class="hname">${hname}</h1><div class="haddr">${esc(hospital.address)}</div></div></div>
        <div class="title">ADMISSION HISTORY &amp; INITIAL ASSESSMENT</div>
      </div>
      <hr class="rule"/>
      <div class="idbox">
        <div>
          <div class="fld"><b>Patient's Name :</b> <span class="val" style="min-width:220px">${esc(patientName)}</span></div>
          <div class="fld"><b>Patient's Address :</b> <span class="val" style="min-width:200px">${esc(f.patientAddress)}</span></div>
          <div class="fld"><b>Age :</b> <span class="val">${esc(f.age)}</span> &nbsp; <b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b>/<b class="${isF ? 'on' : ''}">F</b></span></div>
        </div>
        <div>
          <div class="fld"><b>PRN No. :</b> <span class="val">${esc(f.prnNo)}</span></div>
          <div class="fld"><b>IPD No. :</b> <span class="val">${esc(f.ipdRegistrationNo)}</span></div>
          <div class="fld"><b>Category :</b> <span class="val" style="min-width:80px">${esc(f.category)}</span> <b>Bed No. :</b> <span class="val" style="min-width:60px">${esc(f.bedNo)}</span></div>
          <div class="fld"><b>Date :</b> <span class="val" style="min-width:80px">${esc(f.admittedDate)}</span> <b>Time :</b> <span class="val" style="min-width:60px">${esc(f.admittedTime)}</span></div>
        </div>
      </div>

      <div class="sec">Chief Complaints :</div>${lined(a.chiefComplaints, 5)}
      <div class="sec">Associated illness :</div>${lined(a.associatedIllness, 2)}
      <div class="sec">Relevant Investigations :</div>${lined(a.relevantInvestigations, 3)}
      <div class="sec">Allergies :</div>${lined(a.allergies, 2)}
      <div class="sec">Vaccination History :</div>${lined(a.vaccinationHistory, 2)}
      <div class="sec">Others :</div>${lined(a.others, 1)}

      <div class="sec">Current Treatment :</div>
      <table><thead><tr><th>Name of Medication</th><th>Dose</th><th>Route</th><th>Frequency</th></tr></thead><tbody>${medRows}${emptyMedRows}</tbody></table>
    </div>

    <div class="page two">
      <div class="sec">Past History :</div>
      <table>
        <tr><td style="width:45%"></td><td class="c" style="width:22%"></td><th>If Yes Since When</th></tr>
        ${pastRows}
        <tr><td>Others</td><td colspan="2">${esc(past.others)}</td></tr>
        <tr><td>Surgical History</td><td class="c">${mark(past.surgical?.yes)}</td><th>If Yes Describe : ${esc(past.surgical?.describe)}</th></tr>
      </table>

      <div class="sec">Family History :</div>
      <table>${familyRows.join('')}</table>

      <div class="sec">Personal History :</div>
      <table>${personalRows}</table>

      <div class="sec">Pain Assessment Scale :</div>
      <table class="pain">
        <tr>${PAIN.map(([n]) => `<th>${n}</th>`).join('')}</tr>
        <tr>${PAIN.map(([, l]) => `<td>${l}</td>`).join('')}</tr>
        <tr>${PAIN.map(([n]) => `<td>${faceSvg(n, a.painScore === n)}</td>`).join('')}</tr>
      </table>
      ${a.painScore != null ? `<div style="font-size:11px;margin-top:2px"><b>Recorded Pain Score:</b> ${a.painScore} / 10</div>` : ''}

      <div class="sec">Provisional Diagnosis :</div>${lined(a.provisionalDiagnosis, 1)}
      <div class="sec">Admission informed to Consultant in Charge by Dr. <span class="val">${esc(f.underCareOfDr)}</span> &nbsp; At Time :</div>
      <div class="sec">Care Plan :</div>${lined(a.carePlan, 3)}

      <div class="sign">
        <div>Signature of the Clinician in Charge<div class="lined" style="height:20px"></div>Date : ______ Time : ______</div>
        <div>Signature and Name of R.M.O.<div class="lined" style="height:20px"></div>Date : ______ Time : ______</div>
      </div>
      <table style="margin-top:10px"><tr>
        <td style="width:50%">Declaration by the Patient / Relative / Accompanying Person<br/>I hereby declare that the facts recorded above are based on my narration and are accurate to the best of my Knowledge.</td>
        <td>Name of Patient / Relative / Accompanying Person :<br/><br/>Relation with Patient :<br/><br/>Signature :<br/><br/>Date :</td>
      </tr></table>
    </div>

    <script>(function(){function go(){setTimeout(function(){window.print();},350);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const TEXT_DEFS = [
  ['chiefComplaints', 'Chief Complaints', 3],
  ['associatedIllness', 'Associated Illness', 2],
  ['relevantInvestigations', 'Relevant Investigations', 2],
  ['allergies', 'Allergies', 2],
  ['vaccinationHistory', 'Vaccination History', 2],
  ['others', 'Others', 2],
  ['provisionalDiagnosis', 'Provisional Diagnosis', 2],
  ['carePlan', 'Care Plan', 3],
];

// Yes / No toggle (three states). value = true | false | undefined
const YN = ({ value, onChange }) => (
  <span className="inline-flex gap-1">
    {[
      ['Yes', true],
      ['No', false],
    ].map(([lbl, v]) => (
      <button
        key={lbl}
        type="button"
        onClick={() => onChange(value === v ? undefined : v)}
        className={`px-2 py-0.5 text-xs font-semibold rounded border ${value === v ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-600 border-gray-300'}`}
      >
        {lbl}
      </button>
    ))}
  </span>
);

const InitialAssessmentPanel = ({ admissionId, readOnly = false }) => {
  const { success, error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [f, setF] = useState({});
  const [meds, setMeds] = useState([]);
  const [a, setA] = useState({});
  const [past, setPast] = useState({});
  const [family, setFamily] = useState({});
  const [personal, setPersonal] = useState({});
  const [painScore, setPainScore] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    Promise.all([
      nurseService.getAdmissionForm(admissionId),
      nurseService.getMedicationChart(admissionId),
      nurseService.getInitialAssessment(admissionId),
    ])
      .then(([form, chart, assess]) => {
        if (!active) return;
        const as = assess || {};
        setF(form || {});
        setMeds(Array.isArray(chart) ? chart : []);
        setA(as);
        setPast(parse(as.pastHistory, {}));
        setFamily(parse(as.familyHistory, {}));
        setPersonal(parse(as.personalHistory, {}));
        setPainScore(as.painScore ?? null);
      })
      .catch(() => {
        if (active) toastError('Failed to load initial assessment');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [admissionId, toastError]);

  const setText = (k) => (e) => setA((p) => ({ ...p, [k]: e.target.value }));
  const setPastRow = (k, patch) => setPast((p) => ({ ...p, [k]: { ...(p[k] || {}), ...patch } }));
  const setPersonalRow = (k, patch) =>
    setPersonal((p) => ({ ...p, [k]: { ...(p[k] || {}), ...patch } }));

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload = {
        ...a,
        pastHistory: JSON.stringify(past),
        familyHistory: JSON.stringify(family),
        personalHistory: JSON.stringify(personal),
        painScore,
      };
      const saved = await nurseService.saveInitialAssessment(admissionId, payload);
      setA(saved || payload);
      success('Initial assessment saved');
    } catch (err) {
      const d = err.response?.data;
      toastError(d?.error || d?.message || 'Failed to save initial assessment');
    } finally {
      setSaving(false);
    }
  };

  const handlePrint = () => {
    const merged = {
      ...a,
      pastHistory: JSON.stringify(past),
      familyHistory: JSON.stringify(family),
      personalHistory: JSON.stringify(personal),
      painScore,
    };
    printHtml(
      buildAssessmentHtml(
        f,
        meds,
        {
          name: f.hospitalName || user?.hospitalName,
          address: f.hospitalAddress || user?.hospitalAddress,
          logo: f.hospitalLogoUrl || user?.logoUrl,
          nurse: user?.name,
        },
        merged
      )
    );
  };

  if (loading) return <LoadingSpinner />;
  const patientName = [f.patientSurname, f.patientFirstName, f.husbandFatherName]
    .filter(Boolean)
    .join(' ');

  return (
    <fieldset disabled={readOnly} style={{ display: 'contents' }}>
      {readOnly && (
        <div className="mb-3 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}
      <div className="space-y-5">
        <div className="flex items-center justify-between">
          <h3 className="font-bold text-gray-800 text-sm">
            Admission History &amp; Initial Assessment
          </h3>
          <div className="flex gap-2">
            <button
              onClick={handleSave}
              disabled={saving}
              className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
            <button
              onClick={handlePrint}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700"
            >
              Print
            </button>
          </div>
        </div>

        <div className="bg-white border border-gray-200 rounded-xl p-4 text-sm text-gray-600">
          <span className="font-semibold text-gray-900">{patientName || '—'}</span>
          {` · ${f.age ?? '—'}${f.sex ? '/' + f.sex : ''} · PRN ${f.prnNo || '—'} · IPD ${f.ipdRegistrationNo || '—'} · Bed ${f.bedNo || '—'}`}
        </div>

        {/* Free-text clinical fields */}
        <div className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
          {TEXT_DEFS.slice(0, 6).map(([key, label, rows]) => (
            <div key={key}>
              <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
              <textarea
                rows={rows}
                value={a[key] || ''}
                onChange={setText(key)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
          ))}
        </div>

        {/* Past History */}
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="text-sm font-bold text-gray-800 mb-3">Past History</h4>
          <div className="space-y-2">
            {PAST_ROWS.map(([k, l]) => (
              <div key={k} className="flex items-center gap-3 flex-wrap">
                <span className="w-32 text-sm text-gray-700">{l}</span>
                <YN value={past[k]?.yes} onChange={(v) => setPastRow(k, { yes: v })} />
                <input
                  placeholder="Since when"
                  value={past[k]?.since || ''}
                  onChange={(e) => setPastRow(k, { since: e.target.value })}
                  className="px-2 py-1 border border-gray-300 rounded text-sm flex-1 min-w-[140px]"
                />
              </div>
            ))}
            <div className="flex items-center gap-3">
              <span className="w-32 text-sm text-gray-700">Others</span>
              <input
                value={past.others || ''}
                onChange={(e) => setPast((p) => ({ ...p, others: e.target.value }))}
                className="px-2 py-1 border border-gray-300 rounded text-sm flex-1"
              />
            </div>
            <div className="flex items-center gap-3 flex-wrap">
              <span className="w-32 text-sm text-gray-700">Surgical History</span>
              <YN
                value={past.surgical?.yes}
                onChange={(v) =>
                  setPast((p) => ({ ...p, surgical: { ...(p.surgical || {}), yes: v } }))
                }
              />
              <input
                placeholder="If yes, describe"
                value={past.surgical?.describe || ''}
                onChange={(e) =>
                  setPast((p) => ({
                    ...p,
                    surgical: { ...(p.surgical || {}), describe: e.target.value },
                  }))
                }
                className="px-2 py-1 border border-gray-300 rounded text-sm flex-1 min-w-[140px]"
              />
            </div>
          </div>
        </div>

        {/* Family History */}
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="text-sm font-bold text-gray-800 mb-3">Family History</h4>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {FAMILY_ROWS.map(([k, l]) => (
              <div key={k} className="flex items-center justify-between gap-2">
                <span className="text-sm text-gray-700">{l}</span>
                <YN value={family[k]} onChange={(v) => setFamily((p) => ({ ...p, [k]: v }))} />
              </div>
            ))}
          </div>
        </div>

        {/* Personal History */}
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="text-sm font-bold text-gray-800 mb-3">Personal History</h4>
          <div className="space-y-2">
            {PERSONAL_ROWS.map(([k, l, fq]) => (
              <div key={k} className="flex items-center gap-3 flex-wrap">
                <span className="w-24 text-sm text-gray-700">{l}</span>
                <YN value={personal[k]?.yes} onChange={(v) => setPersonalRow(k, { yes: v })} />
                <input
                  placeholder="Since"
                  value={personal[k]?.since || ''}
                  onChange={(e) => setPersonalRow(k, { since: e.target.value })}
                  className="px-2 py-1 border border-gray-300 rounded text-sm w-28"
                />
                {fq && (
                  <input
                    placeholder={fq}
                    value={personal[k]?.freq || ''}
                    onChange={(e) => setPersonalRow(k, { freq: e.target.value })}
                    className="px-2 py-1 border border-gray-300 rounded text-sm flex-1 min-w-[100px]"
                  />
                )}
              </div>
            ))}
          </div>
        </div>

        {/* Pain Assessment */}
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="text-sm font-bold text-gray-800 mb-3">
            Pain Assessment Scale{' '}
            {painScore != null && <span className="text-blue-600">— {painScore}/10</span>}
          </h4>
          <div className="flex flex-wrap gap-1">
            {PAIN.map(([n, l]) => (
              <button
                key={n}
                type="button"
                title={l}
                onClick={() => setPainScore(painScore === n ? null : n)}
                className={`w-9 h-9 rounded-full border text-sm font-bold ${painScore === n ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-100'}`}
              >
                {n}
              </button>
            ))}
          </div>
          <p className="text-[11px] text-gray-400 mt-2">
            The selected score&apos;s face prints inverted (black &amp; white) on the form.
          </p>
        </div>

        {/* Diagnosis & Care Plan */}
        <div className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
          {TEXT_DEFS.slice(6).map(([key, label, rows]) => (
            <div key={key}>
              <label className="block text-xs font-semibold text-gray-600 mb-1">{label}</label>
              <textarea
                rows={rows}
                value={a[key] || ''}
                onChange={setText(key)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
          ))}
        </div>

        {/* Current Treatment */}
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100">
            <h4 className="text-sm font-bold text-gray-800">
              Current Treatment (prescribed medicines)
            </h4>
          </div>
          {meds.length === 0 ? (
            <p className="px-5 py-6 text-sm text-gray-500">No prescribed medicines.</p>
          ) : (
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50">
                <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                  <th className="px-5 py-3">MEDICATION</th>
                  <th className="px-5 py-3">DOSE</th>
                  <th className="px-5 py-3">ROUTE</th>
                  <th className="px-5 py-3">FREQUENCY</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {meds.map((m) => {
                  const stopped = (m.status || '').toUpperCase() === 'STOPPED';
                  return (
                    <tr key={m.prescriptionId}>
                      <td
                        className={`px-5 py-3 font-medium ${stopped ? 'line-through text-gray-500' : 'text-gray-900'}`}
                      >
                        {m.medicineName}
                        {stopped && (
                          <span className="ml-2 text-[10px] font-bold text-red-600">STOPPED</span>
                        )}
                      </td>
                      <td className="px-5 py-3 text-gray-600">{m.dosage || '—'}</td>
                      <td className="px-5 py-3 text-gray-600">{m.route || '—'}</td>
                      <td className="px-5 py-3 text-gray-600">{m.frequency || '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </fieldset>
  );
};

export default InitialAssessmentPanel;
