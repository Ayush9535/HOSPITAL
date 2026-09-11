import React, { useCallback, useEffect, useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import hospitalService from '../../../services/hospitalService';
import icuService from '../../../services/icuService';

/**
 * IcuStayCard - the ICU stay and who is consulting on it (ICU Phase 10, D-1 option c).
 *
 * The backend for this shipped in ICU-3: the column, the tenant-checked doctor lookup, the
 * endpoint and the realtime push all existed and were correct — there was simply no screen, so
 * an intensivist could never actually be set. This is that screen and nothing more.
 *
 * ONE consultant, deliberately. D-3/D-4: no consultant list, no assignment history, and the
 * admission's own doctorId is never touched — that would move the case off the admitting
 * doctor's dashboard.
 *
 * A closed stay is shown but not editable, which is the ICU-1 rule the server already enforces.
 */
const fmt = (v) => {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString('en-IN', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return String(v);
  }
};

const IcuStayCard = ({ admissionId, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [stays, setStays] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [editing, setEditing] = useState(false);
  const [choice, setChoice] = useState('');
  const [saving, setSaving] = useState(false);

  const role = authService.getCurrentUser()?.role;
  // The same two roles the existing endpoint already allows; nothing new is granted here.
  const mayEdit = role === 'HOSPITAL_ADMIN' || role === 'DOCTOR';

  const load = useCallback(() => {
    icuService
      .getStaysForAdmission(admissionId)
      .then((list) => setStays(Array.isArray(list) ? list : []))
      .catch(() => setStays([]));
  }, [admissionId]);

  useEffect(() => {
    if (admissionId) load();
  }, [admissionId, load, refreshKey]);

  // Newest first, as the API returns them.
  const current = stays[0] || null;
  const isOpen = current?.status === 'ACTIVE';

  const openEditor = async () => {
    setChoice(current?.intensivistDoctorId ? String(current.intensivistDoctorId) : '');
    setEditing(true);
    if (doctors.length === 0) {
      try {
        // Tenant-scoped by the server, like every other doctor list in the app.
        const res = await hospitalService.getDoctors('', 0, 200);
        const list = res?.content || res?.data || res || [];
        setDoctors(Array.isArray(list) ? list : []);
      } catch {
        toastError('Could not load doctors');
      }
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      // Empty clears it, and the admitting doctor stays responsible — the ICU-3 rule.
      await icuService.setIntensivist(current.publicId, choice === '' ? null : Number(choice));
      success(choice === '' ? 'Intensivist cleared' : 'Intensivist updated');
      setEditing(false);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Could not update the intensivist');
    } finally {
      setSaving(false);
    }
  };

  if (stays.length === 0) return null;

  return (
    <>
      <h3 className="font-semibold mb-2">ICU Stay</h3>
      <div className="text-sm space-y-1">
        <span
          className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-semibold ${
            isOpen ? 'bg-teal-50 text-teal-700' : 'bg-gray-100 text-gray-600'
          }`}
        >
          {isOpen ? 'In ICU' : `Closed · ${current.disposition || '—'}`}
        </span>
        <div className="text-xs text-gray-500">Admitted {fmt(current.admittedAt)}</div>
        {current.admissionReason && (
          <div className="text-xs text-gray-500">{current.admissionReason}</div>
        )}

        <div className="pt-2">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Intensivist</p>
          {!editing && (
            <div className="flex items-center justify-between gap-2 mt-0.5">
              <span className="text-sm text-gray-900">{current.intensivistName || 'Not set'}</span>
              {mayEdit && isOpen && (
                <button
                  type="button"
                  onClick={openEditor}
                  className="shrink-0 text-xs font-semibold text-primary-700 hover:underline"
                >
                  {current.intensivistDoctorId ? 'Change' : 'Set'}
                </button>
              )}
            </div>
          )}
          {!current.intensivistDoctorId && !editing && (
            <p className="text-[11px] text-gray-500 mt-0.5">
              The admitting doctor remains responsible.
            </p>
          )}

          {editing && (
            <div className="mt-2 space-y-2">
              <label htmlFor="icu-intensivist" className="sr-only">
                Intensivist
              </label>
              <select
                id="icu-intensivist"
                value={choice}
                onChange={(e) => setChoice(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              >
                <option value="">Not set — admitting doctor</option>
                {doctors.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={save}
                  disabled={saving}
                  className="px-4 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
                >
                  {saving ? 'Saving…' : 'Save'}
                </button>
                <button
                  type="button"
                  onClick={() => setEditing(false)}
                  className="px-3 py-2 rounded-lg text-sm text-gray-600 hover:underline"
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>

        {stays.length > 1 && (
          <p className="text-[11px] text-gray-500 pt-2">
            {stays.length} ICU stays on this admission. Earlier stays stay readable and are not
            editable.
          </p>
        )}
      </div>
      <hr className="my-4" />
    </>
  );
};

export default IcuStayCard;
