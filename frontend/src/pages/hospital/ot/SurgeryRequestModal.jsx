import React, { useState } from 'react';
import DateSelect from '../../../components/DateSelect';
import { useToast } from '../../../context/ToastContext';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';
import useOtPermissions from '../../../hooks/useOtPermissions';

/**
 * SurgeryRequestModal - doctor creates an OT request from the IPD case.
 * Captures procedure, clinical notes, priority, preferred date.
 */
const SurgeryRequestModal = ({ admissionId, onClose, onCreated }) => {
  const { success, error: toastError } = useToast();
  const [form, setForm] = useState({
    procedureName: '',
    clinicalNotes: '',
    priority: 'ELECTIVE',
    preferredDate: '',
  });
  // Requesting a surgery is OT_CREATE. Stated here as well as at the call site so the rule holds
  // wherever this modal is mounted, rather than depending on each dashboard to remember it.
  const { can } = useOtPermissions();
  const canRequest = can('OT_CREATE');

  const [saving, setSaving] = useState(false);

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  const submit = async () => {
    if (!form.procedureName.trim()) {
      toastError('Procedure name is required');
      return;
    }
    setSaving(true);
    try {
      await otService.createRequest({
        ipdAdmissionId: admissionId,
        procedureName: form.procedureName.trim(),
        clinicalNotes: form.clinicalNotes.trim() || null,
        priority: form.priority,
        preferredDate: form.preferredDate || null,
      });
      success('Surgery request created');
      onCreated && onCreated();
      onClose();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to create surgery request');
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
        <h2 className="text-xl font-bold text-gray-900 mb-4">Create Surgery Request</h2>
        <div className="space-y-4">
          <div>
            <label htmlFor="fld-209" className="block text-sm font-semibold text-gray-700 mb-1">
              Procedure / Surgery Name <span className="text-red-600">*</span>
            </label>
            <input
              id="fld-209"
              value={form.procedureName}
              onChange={(e) => set('procedureName', e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
              placeholder="e.g. Appendectomy"
            />
          </div>
          <div>
            <label htmlFor="fld-208" className="block text-sm font-semibold text-gray-700 mb-1">
              Clinical Notes / Diagnosis
            </label>
            <textarea
              id="fld-208"
              value={form.clinicalNotes}
              onChange={(e) => set('clinicalNotes', e.target.value)}
              rows={3}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            />
          </div>
          <div className="flex gap-4">
            <div className="flex-1">
              <label htmlFor="fld-207" className="block text-sm font-semibold text-gray-700 mb-1">
                Priority
              </label>
              <select
                id="fld-207"
                value={form.priority}
                onChange={(e) => set('priority', e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg"
              >
                <option value="ELECTIVE">Elective</option>
                <option value="EMERGENCY">Emergency</option>
              </select>
            </div>
            <div className="flex-1">
              <label htmlFor="fld-206" className="block text-sm font-semibold text-gray-700 mb-1">
                Preferred Date
              </label>
              <DateSelect value={form.preferredDate} onChange={(v) => set('preferredDate', v)} />
            </div>
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
            disabled={saving || !canRequest}
            title={canRequest ? undefined : 'Surgery requests are raised by the surgeon or theatre incharge'}
            className={`px-4 py-2 rounded-lg font-semibold text-white ${saving ? 'bg-gray-400' : 'bg-gray-900 hover:bg-gray-800'}`}
          >
            {saving ? 'Saving…' : 'Create Request'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default SurgeryRequestModal;
