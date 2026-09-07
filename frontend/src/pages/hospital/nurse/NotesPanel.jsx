import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';
import escapeHtml from '../../../utils/escapeHtml';
import { printHtml } from '../../../utils/printHtml';
import { titleCase } from '../../../utils/text';

/**
 * NotesPanel - running nursing observation notes for one admission (Phase 1
 * Nurse module, M5). Author can edit/soft-delete own notes within the window.
 * "Print Reassessment" prints the notes as the NABH Re-Assessment Sheet.
 */
const MAX = 2000;

const esc = escapeHtml;

// Prints the nursing notes as the "RE-ASSESSMENT SHEET" — a repeating table
// (Date/Time | Clinical Notes | Orders) that flows onto extra pages as needed.
export const buildReassessmentHtml = (notes, f, hospital) => {
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
  const fmt = (dt) =>
    dt
      ? new Date(dt).toLocaleString('en-IN', {
          day: '2-digit',
          month: 'short',
          year: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
        })
      : '';

  const rows = (notes || [])
    .map(
      (n) =>
        `<tr><td class="dt">${esc(fmt(n.recordedAt))}</td><td class="cn">${esc(n.noteText).replace(/\n/g, '<br/>')}</td><td>${esc(n.orders || '').replace(/\n/g, '<br/>')}</td></tr>`
    )
    .join('');
  // Pad with blank ruled rows so the sheet fills the page for handwritten entries.
  const blanks = Math.max(4, 22 - (notes || []).length);
  const blankRows = Array.from({ length: blanks })
    .map(() => '<tr><td class="dt">&nbsp;</td><td></td><td></td></tr>')
    .join('');

  return `<!doctype html><html><head><meta charset="utf-8"><title>Re-Assessment Sheet</title>
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
      thead th { background:#fff; text-align:center; font-size:12px; }
      tr { page-break-inside: avoid; }
      td { height:26px; }
      td.dt { width:14%; font-size:11px; white-space:nowrap; }
      td.cn { width:56%; }
      th.orders { width:30%; }
    </style></head><body>
      <div class="head">${logo}<div class="hname">${hname}</div><div class="haddr">${esc(hospital.address)}</div><div class="title">RE-ASSESSMENT SHEET</div></div>
      <div class="idbox">
        <div class="idrow">
          <span><b>UHID No. :</b> <span class="flexval">${esc(hospital.customId)}</span></span>
          <span><b>IPD No. :</b> <span class="flexval">${esc(f.ipdRegistrationNo)}</span></span>
          <span><b>MLC No. :</b> <span class="flexval"></span></span>
        </div>
        <div class="idrow">
          <span><b>Bed No. :</b> <span class="flexval">${esc(f.bedNo)}</span></span>
          <span><b>Patient Name :</b> <span class="flexval">${esc(patientName)}</span></span>
        </div>
        <div class="idrow">
          <span><b>Age :</b> <span class="flexval">${esc(f.age)}</span></span>
          <span style="flex:0.7"><b>Sex :</b> <span class="sex"><b class="${isM ? 'on' : ''}">M</b><b class="${isF ? 'on' : ''}">F</b></span></span>
          <span><b>Date :</b> <span class="flexval">${esc(f.admittedDate)}</span></span>
          <span><b>Time :</b> <span class="flexval">${esc(f.admittedTime)}</span></span>
        </div>
      </div>
      <table>
        <thead><tr><th style="width:14%">Date and Time</th><th style="width:56%">CLINICAL NOTES</th><th class="orders">ORDERS - DRUGS/IV FLUIDS<br/><span style="font-weight:400;font-size:10px">To be signed / stamped by Treating Consultant</span></th></tr></thead>
        <tbody>${rows}${blankRows}</tbody>
      </table>
      <div style="margin-top:14px;display:flex;gap:60px;font-size:12px">
        <div>Ref. Doctor : <b>${esc(f.refDr || f.underCareOfDr)}</b></div>
        <div>Nurse : <b>${esc(hospital.nurse)}</b></div>
      </div>
    <script>(function(){function go(){setTimeout(function(){window.print();},300);}if(document.readyState==='complete'){go();}else{window.addEventListener('load',go);}})();</script>
    </body></html>`;
};

/** readOnly: hide the Add Note form; the timeline stays visible and printable. */
const NotesPanel = ({ admissionId, readOnly = false }) => {
  const { success, error: toastError } = useToast();
  const user = authService.getCurrentUser();
  const currentUserId = user?.userId;
  // The "Performed By Nurse" flow only applies to nurses; a non-nurse (e.g. a
  // doctor adding a note from the IPD case) records as themselves.
  const isNurse = user?.role === 'NURSE' || user?.role === 'NURSE_INCHARGE';
  const [loading, setLoading] = useState(true);
  const [notes, setNotes] = useState([]);
  const [f, setF] = useState({}); // patient header data for the print
  const [text, setText] = useState('');
  const [orders, setOrders] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [editText, setEditText] = useState('');

  // Separate Nurse Login OFF ("Shared Login") -> a required "Performed By
  // Nurse" dropdown is shown and its selection is sent with the payload.
  const [separateLogin, setSeparateLogin] = useState(true);
  const [nurses, setNurses] = useState([]);
  const [performedByNurseId, setPerformedByNurseId] = useState('');

  const load = useCallback(() => {
    setLoading(true);
    nurseService
      .getNotes(admissionId)
      .then((d) => setNotes(Array.isArray(d) ? d : []))
      .catch(() => toastError('Failed to load notes'))
      .finally(() => setLoading(false));
  }, [admissionId, toastError]);

  useEffect(() => {
    load();
  }, [load]);

  // Patient identifiers + branding for the Re-Assessment Sheet header.
  useEffect(() => {
    let active = true;
    nurseService
      .getAdmissionForm(admissionId)
      .then((d) => {
        if (active) setF(d || {});
      })
      .catch(() => {
        /* header just prints blank if unavailable */
      });
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

  const handlePrintReassessment = () => {
    printHtml(
      buildReassessmentHtml(notes, f, {
        name: f.hospitalName || user?.hospitalName,
        address: f.hospitalAddress || user?.hospitalAddress,
        logo: f.hospitalLogoUrl || user?.logoUrl,
        customId: f.hospitalCustomId,
        nurse: user?.name,
      })
    );
  };

  const add = async () => {
    if (!text.trim()) {
      toastError('Enter a note');
      return;
    }
    if (isNurse && separateLogin === false && !performedByNurseId) {
      toastError('Select the nurse who performed this');
      return;
    }
    setSubmitting(true);
    try {
      const payload = { ipdAdmissionId: admissionId, noteText: text.trim(), orders: orders.trim() };
      if (isNurse && separateLogin === false)
        payload.performedByNurseId = Number(performedByNurseId);
      await nurseService.createNote(payload);
      success('Note added');
      setText('');
      setOrders('');
      setPerformedByNurseId('');
      load();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to add note';
      toastError(typeof msg === 'string' ? msg : 'Failed to add note');
    } finally {
      setSubmitting(false);
    }
  };

  const saveEdit = async (publicId) => {
    if (!editText.trim()) {
      toastError('Note cannot be empty');
      return;
    }
    try {
      await nurseService.updateNote(publicId, { noteText: editText.trim() });
      success('Note updated');
      setEditingId(null);
      load();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to update note';
      toastError(typeof msg === 'string' ? msg : 'Failed to update note');
    }
  };

  const remove = async (publicId) => {
    try {
      await nurseService.deleteNote(publicId);
      success('Note deleted');
      load();
    } catch (err) {
      const msg =
        err.response?.data?.error ||
        err.response?.data?.message ||
        err.response?.data ||
        'Failed to delete note';
      toastError(typeof msg === 'string' ? msg : 'Failed to delete note');
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

  return (
    <div className="space-y-5">
      {!readOnly && (
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h3 className="font-bold text-gray-800 text-sm mb-3">Add Note</h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label htmlFor="fld-173" className="block text-xs font-medium text-gray-600 mb-1">
                Clinical Notes
              </label>
              <textarea
                id="fld-173"
                rows={3}
                maxLength={MAX}
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder="Observation, patient condition, handover note…"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
            <div>
              <label htmlFor="fld-172" className="block text-xs font-medium text-gray-600 mb-1">
                Orders — Drugs / IV Fluids
              </label>
              <textarea
                id="fld-172"
                rows={3}
                maxLength={MAX}
                value={orders}
                onChange={(e) => setOrders(e.target.value)}
                placeholder="Drugs, IV fluids, instructions…"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              />
            </div>
          </div>
          {isNurse && separateLogin === false && (
            <div className="mt-3">
              <label htmlFor="fld-171" className="block text-xs font-medium text-gray-600 mb-1">
                Performed By Nurse <span className="text-red-600">*</span>
              </label>
              <select
                id="fld-171"
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
          <div className="mt-2 flex items-center justify-between">
            <span className="text-xs text-gray-400">
              {text.length}/{MAX}
            </span>
            <button
              onClick={add}
              disabled={submitting}
              className={`px-4 py-2 text-sm font-semibold text-white rounded-lg ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
            >
              {submitting ? 'Saving…' : 'Add Note'}
            </button>
          </div>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl">
        <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
          <h3 className="font-bold text-gray-800 text-sm">Notes Timeline</h3>
          <button
            onClick={handlePrintReassessment}
            className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-semibold hover:bg-blue-700"
          >
            Print Reassessment
          </button>
        </div>
        {loading ? (
          <LoadingSpinner />
        ) : notes.length === 0 ? (
          <p className="px-5 py-6 text-sm text-gray-500">No notes yet.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {notes.map((n) => {
              const mine = currentUserId != null && n.nurseUserId === currentUserId;
              const edited =
                n.updatedAt && n.createdAt && new Date(n.updatedAt) - new Date(n.createdAt) > 1000;
              return (
                <li key={n.publicId || n.id} className="px-5 py-3">
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-xs font-semibold text-gray-500">
                      {fmt(n.recordedAt)}
                      {edited ? ' · edited' : ''}
                    </span>
                    {!readOnly && mine && editingId !== n.publicId && (
                      <div className="flex gap-2">
                        <button
                          onClick={() => {
                            setEditingId(n.publicId);
                            setEditText(n.noteText);
                          }}
                          className="text-xs font-semibold text-gray-500 hover:text-gray-900"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => remove(n.publicId)}
                          className="text-xs font-semibold text-red-500 hover:text-red-700"
                        >
                          Delete
                        </button>
                      </div>
                    )}
                  </div>
                  {editingId === n.publicId ? (
                    <div>
                      <textarea
                        rows={3}
                        maxLength={MAX}
                        value={editText}
                        onChange={(e) => setEditText(e.target.value)}
                        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                      />
                      <div className="mt-2 flex gap-2 justify-end">
                        <button
                          onClick={() => setEditingId(null)}
                          className="px-3 py-1 text-xs font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
                        >
                          Cancel
                        </button>
                        <button
                          onClick={() => saveEdit(n.publicId)}
                          className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800"
                        >
                          Save
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                      <p className="text-sm text-gray-800 whitespace-pre-wrap">{n.noteText}</p>
                      {n.orders ? (
                        <div className="md:border-l md:pl-3 border-gray-100">
                          <p className="text-[10px] font-bold uppercase tracking-wider text-gray-400 mb-0.5">
                            Orders — Drugs / IV Fluids
                          </p>
                          <p className="text-sm text-gray-800 whitespace-pre-wrap">{n.orders}</p>
                        </div>
                      ) : null}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
};

export default NotesPanel;
