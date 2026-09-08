import React, { useEffect, useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import otService from '../../../services/otService';
import wardService from '../../../services/wardService';
import { backdropProps } from '../../../utils/modalA11y';
import useOtPermissions from '../../../hooks/useOtPermissions';

/**
 * ScheduleSurgeryModal - assign a surgeon + date/time + theatre to a surgery.
 *
 * Theatres come from the server (ot_rooms), not a client-side filter over ward names.
 * A hospital that has not migrated its OT wards yet still sees them as a fallback, so
 * scheduling keeps working through the transition.
 */
const ScheduleSurgeryModal = ({ surgery, onClose, onScheduled }) => {
  const { success, error: toastError } = useToast();
  const [surgeons, setSurgeons] = useState([]);
  const [rooms, setRooms] = useState([]);
  const [legacyWards, setLegacyWards] = useState([]);
  const [form, setForm] = useState({
    surgeonDoctorId: '',
    surgeonName: '',
    anaesthetistName: '',
    scheduledAt: '',
    theatre: '',
    estimatedDurationMinutes: '',
  });
  // Scheduling is OT_SCHEDULE. The board only offers this modal to a caller who holds it, and
  // the submit repeats the check so the rule survives any other route into this component.
  const { can } = useOtPermissions();
  const canSchedule = can('OT_SCHEDULE');

  const [saving, setSaving] = useState(false);
  const isOther = form.surgeonDoctorId === '__OTHER__';

  useEffect(() => {
    otService
      .getSurgeons()
      .then((s) => setSurgeons(Array.isArray(s) ? s : []))
      .catch(() => setSurgeons([]));
    otService
      .getRooms()
      .then((r) => {
        const list = Array.isArray(r) ? r : [];
        setRooms(list);
        // Only fall back to the old ward-name heuristic while no real theatres exist.
        if (list.length === 0) {
          wardService
            .getWards()
            .then((w) => {
              const arr = Array.isArray(w) ? w : w?.content || w?.data || [];
              setLegacyWards(arr.filter((x) => (x.wardName || '').toUpperCase().includes('OT')));
            })
            .catch(() => setLegacyWards([]));
        }
      })
      .catch(() => setRooms([]));
  }, []);

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const usingRooms = rooms.length > 0;

  const submit = async () => {
    if (!form.surgeonDoctorId || !form.scheduledAt || !form.theatre) {
      toastError('Surgeon, date/time and theatre are required');
      return;
    }
    if (isOther && !form.surgeonName.trim()) {
      toastError("Enter the operator's name for 'Other'");
      return;
    }
    setSaving(true);
    try {
      await otService.schedule(surgery.publicId, {
        surgeonDoctorId: isOther ? null : Number(form.surgeonDoctorId),
        surgeonName: isOther ? form.surgeonName.trim() : null,
        anaesthetistName: form.anaesthetistName.trim() || null,
        scheduledAt: form.scheduledAt,
        expectedVersion: surgery.lifecycleVersion,
        // A real theatre sends otRoomId; the legacy fallback sends otWardId.
        ...(usingRooms ? { otRoomId: Number(form.theatre) } : { otWardId: Number(form.theatre) }),
        estimatedDurationMinutes: form.estimatedDurationMinutes
          ? Number(form.estimatedDurationMinutes)
          : null,
      });
      success('Surgery scheduled');
      onScheduled && onScheduled();
      onClose();
    } catch (e) {
      if (e?.response?.status === 409) {
        toastError('Surgery was modified by another request. Refresh and retry.');
      } else {
        toastError(e?.response?.data?.error || 'Failed to schedule surgery');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-lg p-6">
        <h2 className="text-xl font-bold text-gray-900 mb-1">Schedule Surgery</h2>
        <p className="text-sm text-gray-500 mb-4">
          {surgery.patientName} · {surgery.procedureName}
        </p>
        <div className="space-y-4">
          <div>
            <label htmlFor="fld-204" className="block text-sm font-semibold text-gray-700 mb-1">
              Assign Surgeon (Doctor) <span className="text-red-600">*</span>
            </label>
            <select
              id="fld-204"
              value={form.surgeonDoctorId}
              onChange={(e) => set('surgeonDoctorId', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            >
              <option value="">Select doctor…</option>
              {surgeons.map((s) => (
                <option key={s.doctorId} value={s.doctorId}>
                  {s.name} ({s.specialization})
                </option>
              ))}
              <option value="__OTHER__">Other (external / not listed)…</option>
            </select>
            {surgeons.length === 0 && (
              <p className="text-xs text-amber-600 mt-1">
                No listed doctors — use &quot;Other&quot; to enter a name.
              </p>
            )}
            {isOther && (
              <input
                value={form.surgeonName}
                onChange={(e) => set('surgeonName', e.target.value)}
                placeholder="Operator's name (e.g. Dr. anaesthetist / visiting surgeon)"
                className="w-full mt-2 px-3 py-2 border border-gray-300 rounded-lg"
              />
            )}
          </div>
          <div>
            <label htmlFor="fld-203" className="block text-sm font-semibold text-gray-700 mb-1">
              Anaesthetist <span className="font-normal text-gray-400">(optional)</span>
            </label>
            <input
              id="fld-203"
              value={form.anaesthetistName}
              onChange={(e) => set('anaesthetistName', e.target.value)}
              placeholder="Anaesthetist's name, if present"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="fld-202" className="block text-sm font-semibold text-gray-700 mb-1">
                Date &amp; Time <span className="text-red-600">*</span>
              </label>
              <input
                id="fld-202"
                type="datetime-local"
                value={form.scheduledAt}
                onChange={(e) => set('scheduledAt', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg"
              />
            </div>
            <div>
              <label htmlFor="fld-201" className="block text-sm font-semibold text-gray-700 mb-1">
                Est. duration (min)
              </label>
              <input
                id="fld-201"
                type="number"
                min="1"
                value={form.estimatedDurationMinutes}
                onChange={(e) => set('estimatedDurationMinutes', e.target.value)}
                placeholder="60"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg"
              />
            </div>
          </div>
          <div>
            <label htmlFor="fld-200" className="block text-sm font-semibold text-gray-700 mb-1">
              Theatre <span className="text-red-600">*</span>
            </label>
            <select
              id="fld-200"
              value={form.theatre}
              onChange={(e) => set('theatre', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            >
              <option value="">Select theatre…</option>
              {usingRooms
                ? rooms.map((r) => (
                    <option key={r.publicId} value={r.id}>
                      {r.name}
                    </option>
                  ))
                : legacyWards.map((w) => (
                    <option key={w.wardId} value={w.wardId}>
                      {w.wardName} (ward)
                    </option>
                  ))}
            </select>
            {usingRooms ? (
              <p className="text-xs text-gray-400 mt-1">
                Double-booking a theatre or surgeon is blocked, allowing for turnover time.
              </p>
            ) : (
              <p className="text-xs text-amber-600 mt-1">
                No theatres set up — add them under Settings › OT Theatres. Showing OT-named wards
                meanwhile.
              </p>
            )}
          </div>
        </div>
        <div className="flex justify-end gap-3 mt-6">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg font-semibold text-gray-600 hover:bg-gray-100"
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={saving || !canSchedule}
            title={canSchedule ? undefined : 'Scheduling is done by reception or the theatre incharge'}
            className={`px-4 py-2 rounded-lg font-semibold text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
          >
            {saving ? 'Scheduling…' : 'Schedule'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ScheduleSurgeryModal;
