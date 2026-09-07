import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';
import nurseService from '../../../services/nurseService';
import escapeHtml from '../../../utils/escapeHtml';
import { titleCase } from '../../../utils/text';

const esch = escapeHtml;

// Prints vitals as the NABH "INPUT & OUTPUT CHART". Vital columns are filled
// from our data; input/output columns are blank for offline entry. Flows to
// extra pages with repeating headers.
/**
 * ICU-5: bucket I/O entries into the five NABH columns, keyed by the reading they sit closest to.
 *
 * <p>Fed from `icu_io_entry` ONLY. `VitalsRecord.urine_output_ml` is a separate point-in-time
 * observation (D-2) and is deliberately NOT read here — the printed URINE O/P column reports
 * recorded fluid output, not the observation, and the two must never be conflated.
 *
 * <p>An entry is attributed to the latest reading at or before it, so the printed row shows the
 * fluids that occurred up to that observation. Entries superseded by a correction are excluded,
 * so a corrected volume replaces its original rather than being printed twice.
 */
export const bucketIoEntries = (ordered, ioEntries) => {
  const superseded = new Set(
    (ioEntries || []).map((e) => e.supersedesIoEntryId).filter((id) => id != null)
  );
  const live = (ioEntries || []).filter((e) => !superseded.has(e.id));
  const buckets = ordered.map(() => ({
    IV_FLUIDS: 0,
    ORAL: 0,
    RYLES_ASPIRATION: 0,
    URINE: 0,
    VOMIT: 0,
  }));
  if (buckets.length === 0) return buckets;

  live.forEach((e) => {
    const at = new Date(e.occurredAt).getTime();
    let idx = 0;
    for (let i = 0; i < ordered.length; i++) {
      if (new Date(ordered[i].recordedAt).getTime() <= at) idx = i;
      else break;
    }
    if (buckets[idx][e.route] != null) buckets[idx][e.route] += Number(e.volumeMl) || 0;
  });
  return buckets;
};

export const buildIoChartHtml = (rows, f, hospital, ioEntries) => {
  const hname = esch(titleCase(hospital.name)) || 'Hospital';
  const patientName = [f.patientSurname, f.patientFirstName, f.husbandFatherName]
    .filter(Boolean)
    .join(' ');
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M'),
    isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esch(hospital.logo)}" onerror="this.style.display='none'" style="height:56px;width:auto;object-fit:contain"/>`
    : '';
  const tm = (dt) =>
    dt ? new Date(dt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '';
  const ordered = [...(rows || [])].sort((a, b) => new Date(a.recordedAt) - new Date(b.recordedAt));
  const bp = (v) =>
    v.bpSystolic != null || v.bpDiastolic != null
      ? `${v.bpSystolic ?? ''}/${v.bpDiastolic ?? ''}`
      : '';
  // ICU-5: the five I/O columns come from icu_io_entry. With no entries they stay blank, exactly
  // as they printed before, so a hospital that records nothing sees no change.
  const io = bucketIoEntries(ordered, ioEntries);
  const cell = (n) => (n ? esch(n) : '');
  const dataRows = ordered
    .map(
      (v, i) =>
        `<tr><td>${esch(tm(v.recordedAt))}</td><td>${esch(v.temperature)}</td><td>${esch(v.pulse)}</td><td>${esch(v.respiratoryRate)}</td><td>${esch(bp(v))}</td>` +
        `<td>${cell(io[i]?.IV_FLUIDS)}</td><td>${cell(io[i]?.ORAL)}</td>` +
        `<td>${cell(io[i]?.RYLES_ASPIRATION)}</td><td>${cell(io[i]?.URINE)}</td><td>${cell(io[i]?.VOMIT)}</td></tr>`
    )
    .join('');
  const blanks = Math.max(4, 26 - ordered.length);
  const blankRows = Array.from({ length: blanks })
    .map(() => '<tr>' + '<td>&nbsp;</td>' + '<td></td>'.repeat(9) + '</tr>')
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Input & Output Chart</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:11px; margin:0; }
      .head { text-align:center; }
      .hname { font-size:22px; font-weight:800; color:#1d4ed8; margin:6px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:16px; font-weight:800; margin:12px 0 8px; }
      .idbox { border:1px solid #111; border-bottom:0; padding:8px 12px; font-size:12px; }
      .idrow { display:flex; gap:24px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:16px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      table { width:100%; border-collapse:collapse; }
      th, td { border:1px solid #111; padding:3px 4px; text-align:center; }
      thead { display: table-header-group; }
      thead th { background:#fff; font-size:10.5px; font-weight:700; }
      tr { page-break-inside: avoid; }
      td { height:22px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esch(hospital.address)}</div><div class="title">INPUT &amp; OUTPUT CHART</div></div>
      <div class="idbox">
        <div class="idrow">
          <span><b>UHID No. :</b> <span class="flexval">${esch(hospital.customId)}</span></span>
          <span><b>IPD No. :</b> <span class="flexval">${esch(f.ipdRegistrationNo)}</span></span>
          <span><b>MLC No. :</b> <span class="flexval"></span></span>
          <span><b>Bed No. :</b> <span class="flexval">${esch(f.bedNo)}</span></span>
        </div>
        <div class="idrow"><span style="flex:2"><b>Patient Name :</b> <span class="flexval">${esch(patientName)}</span></span></div>
        <div class="idrow">
          <span><b>Age :</b> <span class="flexval">${esch(f.age)}</span></span>
          <span style="flex:0.7"><b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></span>
          <span><b>Date :</b> <span class="flexval">${esch(f.admittedDate)}</span></span>
          <span><b>Time :</b> <span class="flexval">${esch(f.admittedTime)}</span></span>
        </div>
      </div>
      <table>
        <thead>
          <tr><th colspan="5"></th><th colspan="2">INPUT</th><th colspan="3">OUTPUT</th></tr>
          <tr><th>TIME</th><th>TEMP</th><th>PULSE</th><th>RESP.</th><th>B. P.</th><th>I.V. FLUIDS</th><th>ORAL</th><th>RYLES TUBE ASPIRATION</th><th>URINE O/P</th><th>VOMITING MONITION</th></tr>
        </thead>
        <tbody>${dataRows}${blankRows}</tbody>
      </table>
      <div style="margin-top:14px;display:flex;gap:60px;font-size:12px">
        <div>Ref. Doctor : <b>${esch(f.refDr || f.underCareOfDr)}</b></div>
        <div>Nurse : <b>${esch(hospital.nurse)}</b></div>
      </div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

/**
 * VitalsPanel - record + review IPD vitals for one admission (Phase 1 Nurse module, M4).
 *
 * Vitals have NO upper limit: any value from 0 upwards is accepted. The old "normal range"
 * bands (e.g. pulse 60–100) that turned the input red and flagged readings with a "!" were an
 * implicit upper limit, so they are gone — the only bound left is min=0 on the inputs, matching
 * the server (which now rejects negatives only).
 */

const Metric = ({ label, value, unit }) => {
  if (value == null || value === '') return null;
  return (
    <span className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium bg-gray-100 text-gray-700">
      {label}: {value}
      {unit}
    </span>
  );
};

// ICU-4. Only rendered while the patient is in critical care; a ward form never shows them.
const ICU_INPUTS = [
  ['mapMmhg', 'MAP (mmHg)'],
  ['cvpCmh2o', 'CVP (cmH₂O)'],
  ['urineOutputMl', 'Urine (mL)'],
  ['gcsEye', 'GCS Eye'],
  ['gcsVerbal', 'GCS Verbal'],
  ['gcsMotor', 'GCS Motor'],
];

const emptyForm = {
  temperature: '',
  mapMmhg: '',
  cvpCmh2o: '',
  urineOutputMl: '',
  gcsEye: '',
  gcsVerbal: '',
  gcsMotor: '',
  pulse: '',
  bpSystolic: '',
  bpDiastolic: '',
  respiratoryRate: '',
  spo2: '',
  weight: '',
  painScore: '',
  remarks: '',
};

const VitalsPanel = ({ admissionId, readOnly = false, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [rows, setRows] = useState([]);
  const [f, setF] = useState({}); // patient header data for the I/O chart print
  const [form, setForm] = useState(emptyForm);
  const [submitting, setSubmitting] = useState(false);
  // ICU-4: true while this admission has an OPEN ICU stay. A failed or forbidden lookup leaves
  // it false, which simply renders the ordinary ward form — the safe direction.
  const [inIcu, setInIcu] = useState(false);
  const user = authService.getCurrentUser();
  const currentRole = user?.role;
  const isNurse = currentRole === 'NURSE' || currentRole === 'NURSE_INCHARGE';

  // Separate Nurse Login OFF ("Shared Login") -> a required "Performed By
  // Nurse" dropdown is shown and its selection is sent with the payload.
  // Only relevant when the logged-in user is a nurse; non-nurses (e.g. a
  // doctor viewing this panel from the IPD case) record as themselves.
  const [separateLogin, setSeparateLogin] = useState(true);
  const [nurses, setNurses] = useState([]);
  const [performedByNurseId, setPerformedByNurseId] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    nurseService
      .getVitals(admissionId)
      .then((d) => setRows(Array.isArray(d) ? d : []))
      .catch(() => toastError('Failed to load vitals'))
      .finally(() => setLoading(false));
  }, [admissionId, toastError]);

  useEffect(() => {
    // ICU-4. Absent module or a refused/failed lookup both mean "render the ward form", which is
    // correct here: an ICU field on a ward patient would be wrong, not merely unexplained.
    if (!admissionId || !(user?.modules || []).includes('ICU')) {
      setInIcu(false);
      return;
    }
    icuService
      .getStaysForAdmission(admissionId)
      .then((stays) => setInIcu((stays || []).some((s) => s.status === 'ACTIVE')))
      .catch(() => setInIcu(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [admissionId]);

  useEffect(() => {
    // refreshKey rises when a WebSocket REFRESH_DATA arrives, so a reading another nurse just
    // charted appears here without anyone pressing reload.
    load();
  }, [load, refreshKey]);
  useEffect(() => {
    let active = true;
    nurseService
      .getAdmissionForm(admissionId)
      .then((d) => {
        if (active) setF(d || {});
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [admissionId]);

  useEffect(() => {
    let active = true;
    nurseService
      .getSeparateNurseLogin()
      .then((v) => {
        if (active) setSeparateLogin(v);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (isNurse && separateLogin === false && f.wardId) {
      nurseService
        .getWardStaffNurses(f.wardId)
        .then((list) => setNurses(Array.isArray(list) ? list : []))
        .catch(() => setNurses([]));
    }
  }, [isNurse, separateLogin, f.wardId]);

  const setField = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  const num = (v) => (v === '' || v == null ? null : Number(v));

  const handleSubmit = async () => {
    const payload = {
      ipdAdmissionId: admissionId,
      temperature: num(form.temperature),
      pulse: num(form.pulse),
      bpSystolic: num(form.bpSystolic),
      bpDiastolic: num(form.bpDiastolic),
      respiratoryRate: num(form.respiratoryRate),
      spo2: num(form.spo2),
      weight: num(form.weight),
      painScore: num(form.painScore),
      remarks: form.remarks || null,
      // ICU-4: sent only while in critical care, so a ward reading stores nulls exactly as before.
      ...(inIcu
        ? {
            mapMmhg: num(form.mapMmhg),
            cvpCmh2o: num(form.cvpCmh2o),
            urineOutputMl: num(form.urineOutputMl),
            gcsEye: num(form.gcsEye),
            gcsVerbal: num(form.gcsVerbal),
            gcsMotor: num(form.gcsMotor),
          }
        : {}),
    };
    const hasAny = [
      'temperature',
      'pulse',
      'bpSystolic',
      'bpDiastolic',
      'respiratoryRate',
      'spo2',
      'weight',
      'painScore',
      // ICU-4: an ICU reading may legitimately be MAP/CVP/urine/GCS alone.
      ...(inIcu ? ['mapMmhg', 'cvpCmh2o', 'urineOutputMl', 'gcsEye', 'gcsVerbal', 'gcsMotor'] : []),
    ].some((k) => payload[k] != null);
    if (!hasAny) {
      toastError('Enter at least one measurement');
      return;
    }
    if (isNurse && separateLogin === false) {
      if (!performedByNurseId) {
        toastError('Select the nurse who performed this');
        return;
      }
      payload.performedByNurseId = Number(performedByNurseId);
    }

    setSubmitting(true);
    try {
      await nurseService.createVitals(payload);
      success('Vitals recorded');
      setForm(emptyForm);
      setPerformedByNurseId('');
      load();
    } catch (err) {
      const data = err.response?.data;
      const msg =
        data?.error ||
        data?.message ||
        (typeof data === 'string' ? data : null) ||
        'Failed to record vitals';
      toastError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '—';

  // Derived, never a second source of truth: the score is what is stored and sent.
  //   null -> not assessed        0 -> assessed, no pain        >0 -> assessed, in pain
  const painPresent =
    form.painScore === '' || form.painScore === null || form.painScore === undefined
      ? null
      : Number(form.painScore) > 0;

  const setPain = (present) => {
    if (present === false) setField('painScore', '0');
    else setField('painScore', Number(form.painScore) > 0 ? form.painScore : '1');
  };
  // ICU-4: ids that a later correction replaced. Derived from the rows already loaded, so the
  // history needs no extra request.
  const supersededIds = new Set(
    (rows || []).map((r) => r.supersedesVitalsId).filter((id) => id != null)
  );

  const inputs = [
    ['temperature', 'Temp (°F)', 0.1],
    ['pulse', 'Pulse (bpm)', 1],
    ['bpSystolic', 'BP Sys', 1],
    ['bpDiastolic', 'BP Dia', 1],
    ['respiratoryRate', 'Resp (rpm)', 1],
    ['spo2', 'SpO₂ (%)', 1],
    ['weight', 'Weight (kg)', 0.1],
  ];

  return (
    <fieldset disabled={readOnly} style={{ display: 'contents' }}>
      {readOnly && (
        <div className="mb-3 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}
      <div className="space-y-5">
        {!readOnly && (
          <div className="bg-white border border-gray-200 rounded-xl p-5">
            <h3 className="font-bold text-gray-800 text-sm mb-4">Record Vitals</h3>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {[...inputs, ...(inIcu ? ICU_INPUTS.map(([k, l]) => [k, l, 1]) : [])].map(
                ([key, label, step]) => (
                  <div key={key}>
                    <label className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
                    <input
                      type="number"
                      step={step}
                      min="0"
                      value={form[key]}
                      onChange={(e) => setField(key, e.target.value)}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                    />
                  </div>
                )
              )}
            </div>

            {/* Pain.

                It was one more unlabelled number box in the grid above, which asked the nurse to
                know both that a scale existed and what its range was. It is the SAME stored field
                -- vitals_records.pain_score, 0-10 -- asked as the question a nurse actually
                answers first. "No" records 0, which is that scale's own value for no pain, not an
                invented one; "Yes" asks for the score. Leaving it untouched still records nothing,
                so "not assessed" stays distinct from "assessed as none". */}
            <div className="mt-3">
              <span className="block text-xs font-medium text-gray-600 mb-1">Pain</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  aria-pressed={painPresent === false}
                  onClick={() => setPain(false)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border ${painPresent === false ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-700 border-gray-300'}`}
                >
                  No
                </button>
                <button
                  type="button"
                  aria-pressed={painPresent === true}
                  onClick={() => setPain(true)}
                  className={`px-3 py-1.5 rounded-lg text-xs font-semibold border ${painPresent === true ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-700 border-gray-300'}`}
                >
                  Yes
                </button>
                {painPresent === true && (
                  <label className="flex items-center gap-2 text-xs text-gray-600">
                    Score (0–10)
                    <input
                      type="number"
                      min="0"
                      max="10"
                      step="1"
                      aria-label="Pain score"
                      value={form.painScore}
                      onChange={(e) => setField('painScore', e.target.value)}
                      className="w-20 px-2 py-1.5 border border-gray-300 rounded-lg text-sm"
                    />
                  </label>
                )}
                {painPresent === null && (
                  <span className="text-xs text-gray-400">Not assessed</span>
                )}
              </div>
            </div>
            {isNurse && separateLogin === false && (
              <div className="mt-3">
                <label htmlFor="fld-178" className="block text-xs font-medium text-gray-600 mb-1">
                  Performed By Nurse <span className="text-red-600">*</span>
                </label>
                <select
                  id="fld-178"
                  value={performedByNurseId}
                  onChange={(e) => setPerformedByNurseId(e.target.value)}
                  required
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                >
                  <option value="">Select nurse…</option>
                  {nurses.map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="mt-3">
              <label htmlFor="fld-177" className="block text-xs font-medium text-gray-600 mb-1">
                Remarks
              </label>
              <input
                id="fld-177"
                type="text"
                value={form.remarks}
                onChange={(e) => setField('remarks', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
            <div className="mt-4 flex justify-end">
              <button
                onClick={handleSubmit}
                disabled={submitting}
                className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
              >
                {submitting ? 'Saving…' : 'Record Vitals'}
              </button>
            </div>
          </div>
        )}

        <div className="bg-white border border-gray-200 rounded-xl">
          <div className="px-5 py-3 border-b border-gray-100">
            <h3 className="font-bold text-gray-800 text-sm">Vitals Timeline</h3>
          </div>
          {loading ? (
            <LoadingSpinner />
          ) : rows.length === 0 ? (
            <p className="px-5 py-6 text-sm text-gray-500">No vitals recorded yet.</p>
          ) : (
            <ul className="divide-y divide-gray-100">
              {rows.map((v) => (
                <li
                  key={v.publicId || v.id}
                  // ICU-4: a superseded observation stays visible, struck through. Hiding it would
                  // recreate exactly the loss the correction path exists to prevent.
                  className={`px-5 py-3 ${supersededIds.has(v.id) ? 'opacity-60 line-through' : ''}`}
                >
                  <div className="flex items-center justify-between mb-2">
                    <span className="text-xs font-semibold text-gray-500">{fmt(v.recordedAt)}</span>
                    {v.supersedesVitalsId && (
                      <span className="text-[11px] font-semibold text-blue-700 bg-blue-50 border border-blue-200 rounded px-2 py-0.5">
                        Correction
                      </span>
                    )}
                    {supersededIds.has(v.id) && (
                      <span className="text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                        Superseded
                      </span>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Metric label="Temp" value={v.temperature} unit="°F" />
                    <Metric label="Pulse" value={v.pulse} unit="" />
                    {(v.bpSystolic != null || v.bpDiastolic != null) && (
                      <span className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs font-medium bg-gray-100 text-gray-700">
                        BP: {v.bpSystolic ?? '—'}/{v.bpDiastolic ?? '—'}
                      </span>
                    )}
                    <Metric label="Resp" value={v.respiratoryRate} unit="" />
                    <Metric label="SpO₂" value={v.spo2} unit="%" />
                    <Metric label="Weight" value={v.weight} unit="kg" />
                    <Metric label="Pain" value={v.painScore === 0 ? 'None' : v.painScore} unit="" />
                    <Metric label="Pain" value={v.painScore} unit="" />
                    <Metric label="MAP" value={v.mapMmhg} unit="mmHg" />
                    <Metric label="CVP" value={v.cvpCmh2o} unit="cmH₂O" />
                    <Metric label="Urine" value={v.urineOutputMl} unit="mL" />
                    <Metric label="GCS" value={v.gcsTotal} unit="" />
                  </div>
                  {v.remarks && <p className="text-xs text-gray-500 mt-2">{v.remarks}</p>}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </fieldset>
  );
};

export default VitalsPanel;
