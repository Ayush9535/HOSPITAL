import React, { useEffect, useMemo, useState } from 'react';
import DateSelect from '../../../components/DateSelect';
import { useToast } from '../../../context/ToastContext';
import hospitalService from '../../../services/hospitalService';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';
import useOtPermissions from '../../../hooks/useOtPermissions';

/**
 * DayCareSurgeryModal - request a procedure for a patient who is NOT admitted.
 *
 * A day-care case (cataract, endoscopy, minor orthopaedics) has no IPD admission:
 * the surgery is anchored on the patient. Whoever holds OT_CREATE may open this —
 * doctors by default, reception once the hospital grants it in the OT permission
 * matrix. Nothing here checks a role.
 */
const DayCareSurgeryModal = ({ isOpen, onClose, onSuccess }) => {
  const { success, error: toastError } = useToast();
  // Requesting a surgery is OT_CREATE. Stated here as well as at the call site so the rule holds
  // wherever this modal is mounted, rather than depending on each dashboard to remember it.
  const { can } = useOtPermissions();
  const canRequest = can('OT_CREATE');

  const [search, setSearch] = useState('');
  const [patients, setPatients] = useState([]);
  const [patient, setPatient] = useState(null);
  const [procedureName, setProcedureName] = useState('');
  const [clinicalNotes, setClinicalNotes] = useState('');
  const [priority, setPriority] = useState('ELECTIVE');
  const [preferredDate, setPreferredDate] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!isOpen) return;
    setSearch('');
    setPatients([]);
    setPatient(null);
    setProcedureName('');
    setClinicalNotes('');
    setPriority('ELECTIVE');
    setPreferredDate('');
    setSubmitting(false);
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen || search.trim().length < 2) {
      setPatients([]);
      return;
    }
    let active = true;
    const timer = setTimeout(() => {
      hospitalService
        .getPatients(search.trim(), 0, 8)
        .then((res) => {
          if (active) setPatients(res?.content || []);
        })
        .catch(() => {
          if (active) setPatients([]);
        });
    }, 250); // debounce: the picker types faster than the server answers
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [isOpen, search]);

  const canSubmit = useMemo(
    () => !!patient && procedureName.trim().length > 0 && !submitting,
    [patient, procedureName, submitting]
  );

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    try {
      await otService.createRequest({
        patientId: patient.id, // no ipdAdmissionId => DAY_CARE
        procedureName: procedureName.trim(),
        clinicalNotes: clinicalNotes.trim() || null,
        priority,
        preferredDate: preferredDate || null,
      });
      success('Day-care surgery requested');
      onSuccess?.();
      onClose();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to request day-care surgery');
    } finally {
      setSubmitting(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-xl my-8 max-h-[90vh] overflow-y-auto">
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Book Day-Care Surgery</h2>
            <p className="text-xs text-gray-400 mt-0.5">
              A same-day procedure — the patient is not admitted.
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="px-6 py-5 space-y-4">
          <div>
            <span className="block text-xs font-medium text-gray-600 mb-1">
              Patient <span className="text-red-600">*</span>
            </span>
            {patient ? (
              <div className="flex items-center justify-between border border-gray-300 rounded-lg px-3 py-2">
                <span className="text-sm font-semibold text-gray-800">
                  {patient.name}{' '}
                  <span className="text-gray-400 font-normal">
                    · {patient.customId || patient.id}
                  </span>
                </span>
                <button
                  type="button"
                  onClick={() => setPatient(null)}
                  className="text-xs text-gray-500 hover:text-gray-700 underline"
                >
                  Change
                </button>
              </div>
            ) : (
              <>
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search patient by name, ID or phone…"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
                {patients.length > 0 && (
                  <div className="mt-1 border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-48 overflow-y-auto">
                    {patients.map((p) => (
                      <button
                        key={p.id}
                        type="button"
                        onClick={() => {
                          setPatient(p);
                          setPatients([]);
                        }}
                        className="w-full text-left px-3 py-2 text-sm hover:bg-slate-50 flex justify-between"
                      >
                        <span className="font-medium text-gray-800">{p.name}</span>
                        <span className="text-xs text-gray-400">{p.customId || p.id}</span>
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>

          <div>
            <label htmlFor="fld-191" className="block text-xs font-medium text-gray-600 mb-1">
              Procedure <span className="text-red-600">*</span>
            </label>
            <input
              id="fld-191"
              type="text"
              value={procedureName}
              onChange={(e) => setProcedureName(e.target.value)}
              placeholder="e.g. Cataract extraction"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="fld-190" className="block text-xs font-medium text-gray-600 mb-1">
                Priority
              </label>
              <select
                id="fld-190"
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              >
                <option value="ELECTIVE">Elective</option>
                <option value="EMERGENCY">Emergency</option>
              </select>
            </div>
            <div>
              <label htmlFor="fld-189" className="block text-xs font-medium text-gray-600 mb-1">
                Preferred date
              </label>
              <DateSelect value={preferredDate} onChange={(v) => setPreferredDate(v)} />
            </div>
          </div>

          <div>
            <label htmlFor="fld-188" className="block text-xs font-medium text-gray-600 mb-1">
              Clinical notes
            </label>
            <textarea
              id="fld-188"
              rows={3}
              value={clinicalNotes}
              onChange={(e) => setClinicalNotes(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </div>
        </div>

        <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-gray-100">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-lg font-semibold text-gray-600 hover:bg-gray-100"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={!canSubmit || !canRequest}
            title={canRequest ? undefined : 'Surgery requests are raised by the surgeon or theatre incharge'}
            className={`px-4 py-2 rounded-lg font-semibold text-white ${canSubmit ? 'bg-gray-900 hover:bg-gray-800' : 'bg-gray-300 cursor-not-allowed'}`}
          >
            {submitting ? 'Requesting…' : 'Request Surgery'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default DayCareSurgeryModal;
