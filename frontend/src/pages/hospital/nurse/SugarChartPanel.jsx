import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import escapeHtml from '../../../utils/escapeHtml';
import { printHtml } from '../../../utils/printHtml';
import { titleCase } from '../../../utils/text';

/**
 * SugarChartPanel - blood-sugar chart for diabetic IPD patients (Phase 1 Nurse
 * module). Each entry captures Blood Sugar + Treatment, timestamped on save.
 * "Print Sugar Chart" prints the NABH form; entries flow to extra pages.
 */

const esc = escapeHtml;

export const buildSugarChartHtml = (entries, f, hospital) => {
  const hname = esc(titleCase(hospital.name)) || 'Hospital';
  const patientName = [f.patientSurname, f.patientFirstName, f.husbandFatherName]
    .filter(Boolean)
    .join(' ');
  const sex = (f.sex || '').toUpperCase();
  const isM = sex.startsWith('M'),
    isF = sex.startsWith('F');
  const logo = hospital.logo
    ? `<img src="${esc(hospital.logo)}" onerror="this.style.display='none'" style="height:56px;width:auto;object-fit:contain"/>`
    : '';
  const d = (dt) =>
    dt
      ? new Date(dt).toLocaleDateString('en-IN', {
          day: '2-digit',
          month: 'short',
          year: '2-digit',
        })
      : '';
  const t = (dt) =>
    dt ? new Date(dt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) : '';

  // Oldest first for a chronological chart.
  const ordered = [...(entries || [])].sort(
    (a, b) => new Date(a.recordedAt) - new Date(b.recordedAt)
  );
  const rows = ordered
    .map(
      (e) =>
        `<tr><td class="dt">${esc(d(e.recordedAt))}</td><td class="dt">${esc(t(e.recordedAt))}</td><td>${esc(e.bloodSugar)}</td><td>${esc(e.treatment).replace(/\n/g, '<br/>')}</td><td></td></tr>`
    )
    .join('');
  const blanks = Math.max(4, 24 - ordered.length);
  const blankRows = Array.from({ length: blanks })
    .map(() => '<tr><td class="dt">&nbsp;</td><td></td><td></td><td></td><td></td></tr>')
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Sugar Chart</title>
    <style>
      @page { size: A4; margin: 8mm; }
      * { box-sizing: border-box; }
      body { font-family: Arial, "Noto Sans", sans-serif; color:#111; font-size:12px; margin:0; }
      .head { text-align:center; }
      .hname { font-size:22px; font-weight:800; color:#1d4ed8; margin:6px 0 2px; }
      .haddr { font-size:12px; font-weight:600; color:#7c3aed; }
      .title { font-size:16px; font-weight:800; margin:12px 0 8px; }
      .idbox { border:1px solid #111; border-bottom:0; padding:8px 12px; }
      .idrow { display:flex; gap:24px; margin:6px 0; }
      .idrow > span { flex:1; display:flex; align-items:flex-end; gap:6px; min-width:0; }
      .flexval { border-bottom:1px solid #666; flex:1; padding:0 4px; min-height:16px; }
      .sex b { border:1px solid #111; padding:0 6px; margin:0 2px; } .sex .on { background:#111; color:#fff; }
      table { width:100%; border-collapse:collapse; }
      th, td { border:1px solid #111; padding:5px 7px; vertical-align:top; }
      thead { display: table-header-group; }
      thead th { background:#fff; text-align:center; }
      tr { page-break-inside: avoid; }
      td { height:26px; }
      td.dt { white-space:nowrap; font-size:11px; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div><div class="title">SUGAR CHART</div></div>
      <div class="idbox">
        <div class="idrow">
          <span><b>UHID No. :</b> <span class="flexval">${esc(hospital.customId)}</span></span>
          <span><b>IPD No. :</b> <span class="flexval">${esc(f.ipdRegistrationNo)}</span></span>
          <span><b>MLC No. :</b> <span class="flexval"></span></span>
        </div>
        <div class="idrow">
          <span><b>Bed No. :</b> <span class="flexval">${esc(f.bedNo)}</span></span>
          <span><b>PRN :</b> <span class="flexval">${esc(f.prnNo)}</span></span>
        </div>
        <div class="idrow">
          <span style="flex:2"><b>Patient Name :</b> <span class="flexval">${esc(patientName)}</span></span>
        </div>
        <div class="idrow">
          <span><b>Age :</b> <span class="flexval">${esc(f.age)}</span></span>
          <span style="flex:0.7"><b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></span>
          <span><b>Date :</b> <span class="flexval">${esc(f.admittedDate)}</span></span>
        </div>
      </div>
      <table>
        <thead><tr><th style="width:12%">Date</th><th style="width:12%">Time</th><th style="width:26%">Blood Sugar</th><th style="width:34%">Treatment</th><th style="width:16%">Signature</th></tr></thead>
        <tbody>${rows}${blankRows}</tbody>
      </table>
      <div style="margin-top:14px;display:flex;gap:60px;font-size:12px">
        <div>Ref. Doctor : <b>${esc(f.refDr || f.underCareOfDr)}</b></div>
        <div>Nurse : <b>${esc(hospital.nurse)}</b></div>
      </div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

const SugarChartPanel = ({ admissionId, readOnly = false }) => {
  const { success, error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const currentUserId = user?.userId;
  // The "Performed By Nurse" flow only applies to nurses; a non-nurse (e.g. a
  // doctor opening this panel from the IPD case) records as themselves.
  const isNurse = user?.role === 'NURSE' || user?.role === 'NURSE_INCHARGE';
  const [loading, setLoading] = useState(true);
  const [entries, setEntries] = useState([]);
  const [f, setF] = useState({});
  const [bloodSugar, setBloodSugar] = useState('');
  const [treatment, setTreatment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [editSugar, setEditSugar] = useState('');
  const [editTreatment, setEditTreatment] = useState('');

  // Separate Nurse Login OFF ("Shared Login") -> a required "Performed By
  // Nurse" dropdown is shown and its selection is sent with the payload.
  const [separateLogin, setSeparateLogin] = useState(true);
  const [nurses, setNurses] = useState([]);
  const [performedByNurseId, setPerformedByNurseId] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    nurseService
      .getSugarEntries(admissionId)
      .then((d) => setEntries(Array.isArray(d) ? d : []))
      .catch(() => toastError('Failed to load sugar chart'))
      .finally(() => setLoading(false));
  }, [admissionId, toastError]);

  useEffect(() => {
    load();
  }, [load]);
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

  const errMsg = (err, fb) => {
    const data = err.response?.data;
    return data?.error || data?.message || (typeof data === 'string' ? data : null) || fb;
  };

  const add = async () => {
    if (!bloodSugar.trim() && !treatment.trim()) {
      toastError('Enter a blood sugar value or treatment');
      return;
    }
    if (isNurse && separateLogin === false && !performedByNurseId) {
      toastError('Select the nurse who performed this');
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        ipdAdmissionId: admissionId,
        bloodSugar: bloodSugar.trim(),
        treatment: treatment.trim(),
      };
      if (isNurse && separateLogin === false)
        payload.performedByNurseId = Number(performedByNurseId);
      await nurseService.createSugarEntry(payload);
      success('Entry added');
      setBloodSugar('');
      setTreatment('');
      setPerformedByNurseId('');
      load();
    } catch (err) {
      toastError(errMsg(err, 'Failed to add entry'));
    } finally {
      setSubmitting(false);
    }
  };

  const saveEdit = async (publicId) => {
    try {
      await nurseService.updateSugarEntry(publicId, {
        bloodSugar: editSugar.trim(),
        treatment: editTreatment.trim(),
      });
      success('Entry updated');
      setEditingId(null);
      load();
    } catch (err) {
      toastError(errMsg(err, 'Failed to update entry'));
    }
  };

  const remove = async (publicId) => {
    try {
      await nurseService.deleteSugarEntry(publicId);
      success('Entry deleted');
      load();
    } catch (err) {
      toastError(errMsg(err, 'Failed to delete entry'));
    }
  };

  const handlePrint = () => {
    printHtml(
      buildSugarChartHtml(entries, f, {
        name: f.hospitalName || user?.hospitalName,
        address: f.hospitalAddress || user?.hospitalAddress,
        logo: f.hospitalLogoUrl || user?.logoUrl,
        customId: f.hospitalCustomId,
        nurse: user?.name,
      })
    );
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
            <h3 className="font-bold text-gray-800 text-sm mb-3">Add Sugar Reading</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label htmlFor="fld-176" className="block text-xs font-medium text-gray-600 mb-1">
                  Blood Sugar
                </label>
                <input
                  id="fld-176"
                  value={bloodSugar}
                  onChange={(e) => setBloodSugar(e.target.value)}
                  placeholder="e.g., 180 mg/dL (F) / 240 (PP)"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
              <div>
                <label htmlFor="fld-175" className="block text-xs font-medium text-gray-600 mb-1">
                  Treatment
                </label>
                <input
                  id="fld-175"
                  value={treatment}
                  onChange={(e) => setTreatment(e.target.value)}
                  placeholder="e.g., Inj. Actrapid 6 units S/C"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
            </div>
            {isNurse && separateLogin === false && (
              <div className="mt-3">
                <label htmlFor="fld-174" className="block text-xs font-medium text-gray-600 mb-1">
                  Performed By Nurse <span className="text-red-600">*</span>
                </label>
                <select
                  id="fld-174"
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
            <div className="mt-3 flex justify-end">
              <button
                onClick={add}
                disabled={submitting}
                className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${submitting ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
              >
                {submitting ? 'Saving…' : 'Add Entry'}
              </button>
            </div>
          </div>
        )}

        <div className="bg-white border border-gray-200 rounded-xl">
          <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
            <h3 className="font-bold text-gray-800 text-sm">Sugar Chart</h3>
            <button
              onClick={handlePrint}
              className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-semibold hover:bg-blue-700"
            >
              Print Sugar Chart
            </button>
          </div>
          {loading ? (
            <LoadingSpinner />
          ) : entries.length === 0 ? (
            <p className="px-5 py-6 text-sm text-gray-500">No readings yet.</p>
          ) : (
            <table className="min-w-full text-sm">
              <thead className="bg-gray-50">
                <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                  <th className="px-5 py-3">DATE / TIME</th>
                  <th className="px-5 py-3">BLOOD SUGAR</th>
                  <th className="px-5 py-3">TREATMENT</th>
                  <th className="px-5 py-3 text-right">ACTIONS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {entries.map((e) => {
                  const mine = currentUserId != null && e.nurseUserId === currentUserId;
                  if (editingId === e.publicId) {
                    return (
                      <tr key={e.publicId}>
                        <td className="px-5 py-3 text-gray-500 text-xs">{fmt(e.recordedAt)}</td>
                        <td className="px-5 py-3">
                          <input
                            value={editSugar}
                            onChange={(ev) => setEditSugar(ev.target.value)}
                            className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
                          />
                        </td>
                        <td className="px-5 py-3">
                          <input
                            value={editTreatment}
                            onChange={(ev) => setEditTreatment(ev.target.value)}
                            className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
                          />
                        </td>
                        <td className="px-5 py-3 text-right whitespace-nowrap">
                          <button
                            onClick={() => saveEdit(e.publicId)}
                            className="text-xs font-semibold text-gray-900 mr-2"
                          >
                            Save
                          </button>
                          <button
                            onClick={() => setEditingId(null)}
                            className="text-xs font-semibold text-gray-500"
                          >
                            Cancel
                          </button>
                        </td>
                      </tr>
                    );
                  }
                  return (
                    <tr key={e.publicId}>
                      <td className="px-5 py-3 text-gray-500 text-xs">{fmt(e.recordedAt)}</td>
                      <td className="px-5 py-3 font-medium text-gray-900">{e.bloodSugar || '—'}</td>
                      <td className="px-5 py-3 text-gray-600">{e.treatment || '—'}</td>
                      <td className="px-5 py-3 text-right whitespace-nowrap">
                        {mine && (
                          <>
                            <button
                              onClick={() => {
                                setEditingId(e.publicId);
                                setEditSugar(e.bloodSugar || '');
                                setEditTreatment(e.treatment || '');
                              }}
                              className="text-xs font-semibold text-gray-500 hover:text-gray-900 mr-2"
                            >
                              Edit
                            </button>
                            <button
                              onClick={() => remove(e.publicId)}
                              className="text-xs font-semibold text-red-500 hover:text-red-700"
                            >
                              Delete
                            </button>
                          </>
                        )}
                      </td>
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

export default SugarChartPanel;
