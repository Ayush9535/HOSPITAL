import React, { useEffect, useState, useCallback } from 'react';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import nurseService from '../../../services/nurseService';

/**
 * OtNotesSection - intra-operative notes the nurse records while assisting a
 * surgery. Only renders when the admission has a scheduled/live surgery.
 * Reuses the nursing-notes flow (category = "OT", linked by surgeryId).
 */
const OtNotesSection = ({ admissionId }) => {
  const { success, error: toastError } = useToast();
  const [surgery, setSurgery] = useState(null);
  const [notes, setNotes] = useState([]);
  const [text, setText] = useState('');
  const [saving, setSaving] = useState(false);
  // Only tenants with the OT module have the surgeries endpoints; skip otherwise
  // to avoid a guaranteed-failing request on every patient view.
  const hasOT = (authService.getCurrentUser()?.modules || []).includes('OT');

  const refresh = useCallback(() => {
    if (!admissionId || !hasOT) return;
    nurseService
      .getActiveSurgery(admissionId)
      .then((s) => {
        setSurgery(s || null);
        if (s && s.surgeryId) {
          nurseService
            .getSurgeryNotes(s.surgeryId)
            .then((n) => setNotes(Array.isArray(n) ? n : []))
            .catch(() => setNotes([]));
        } else {
          setNotes([]);
        }
      })
      .catch(() => {
        setSurgery(null);
        setNotes([]);
      });
  }, [admissionId, hasOT]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  if (!surgery) return null;

  const add = async () => {
    if (!text.trim()) return;
    setSaving(true);
    try {
      await nurseService.addSurgeryNote(admissionId, surgery.surgeryId ?? null, text.trim());
      setText('');
      success('OT note added');
      refresh();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to add OT note');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="rounded-2xl border border-indigo-200 bg-indigo-50/40 p-5 mb-6">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-bold text-indigo-900">
          OT Notes — {surgery.procedureName} ({surgery.status?.replace('_', ' ')})
        </h3>
      </div>
      <div className="flex gap-2 mb-4">
        <input
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Add intra-operative note…"
          className="flex-1 px-3 py-2 border border-gray-300 rounded-lg"
          onKeyDown={(e) => e.key === 'Enter' && add()}
        />
        <button
          onClick={add}
          disabled={saving}
          className={`px-4 py-2 rounded-lg font-semibold text-white ${saving ? 'bg-gray-400' : 'bg-indigo-600 hover:bg-indigo-700'}`}
        >
          Add
        </button>
      </div>
      <div className="space-y-2">
        {notes.map((n) => (
          <div key={n.publicId} className="bg-white rounded-lg px-3 py-2 text-sm">
            <div className="text-gray-800">{n.noteText}</div>
            <div className="text-xs text-gray-400 mt-0.5">
              {n.recordedAt ? new Date(n.recordedAt).toLocaleString('en-IN') : ''}
            </div>
          </div>
        ))}
        {notes.length === 0 && <div className="text-xs text-gray-400">No OT notes yet.</div>}
      </div>
    </div>
  );
};

export default OtNotesSection;
