import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import ConfirmationModal from './ConfirmationModal';

/**
 * Lists, adds, edits, deletes, and reorders quick-note presets for a given
 * fieldType (currently only 'TREATMENT_NOTES' is used). Self-contained: does
 * its own data fetching, so it can be dropped into a modal or a full page.
 */
const NotePresetsManager = ({
  fieldType,
  isAdmin = false,
  // Copy is parameterized so the same component serves any field type
  // (defaults preserve the original Quick Notes wording).
  title = 'Quick Notes',
  noun = 'quick note',
  placeholder = 'e.g. Avoid oily food',
  description = 'Common phrases that appear as one-click buttons under Treatment Notes during a consultation.',
}) => {
  const nounCap = noun.charAt(0).toUpperCase() + noun.slice(1);
  const { success, error: toastError } = useToast();
  const [presets, setPresets] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [loading, setLoading] = useState(true);
  const [newText, setNewText] = useState('');
  // '' = shared (all doctors); a doctor id = private to that doctor. Admin-only.
  const [newDoctorId, setNewDoctorId] = useState('');
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [editingText, setEditingText] = useState('');
  const [editingDoctorId, setEditingDoctorId] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, id: null });

  const loadPresets = useCallback(async () => {
    setLoading(true);
    try {
      const data = await hospitalService.getConsultationNotePresets(fieldType);
      setPresets(data || []);
    } catch (err) {
      toastError(`Failed to load ${noun}s`);
    } finally {
      setLoading(false);
    }
  }, [fieldType, toastError]);

  useEffect(() => {
    loadPresets();
  }, [loadPresets]);

  // Real-time sync: reload when any client in this hospital changes a preset.
  useEffect(() => {
    const handler = () => loadPresets();
    globalThis.addEventListener('hms:presets-updated', handler);
    return () => globalThis.removeEventListener('hms:presets-updated', handler);
  }, [loadPresets]);

  // Admin assigns notes to specific doctors, so it needs the doctor list.
  useEffect(() => {
    if (!isAdmin) return;
    hospitalService
      .getDoctors('', 0, 500)
      .then((data) => setDoctors(data?.content || []))
      .catch(() => {
        /* dropdown just stays empty */
      });
  }, [isAdmin]);

  const handleAdd = async (e) => {
    e.preventDefault();
    if (!newText.trim()) return;
    const assignment = isAdmin ? { doctorId: newDoctorId === '' ? null : Number(newDoctorId) } : {};
    setAdding(true);
    try {
      const created = await hospitalService.createConsultationNotePreset({
        fieldType,
        text: newText.trim(),
        ...assignment,
      });
      setPresets((prev) => [...prev, created]);
      setNewText('');
      setNewDoctorId('');
      success(`${nounCap} added`);
    } catch (err) {
      toastError(err?.response?.data || `Failed to add ${noun}`);
    } finally {
      setAdding(false);
    }
  };

  const handleStartEdit = (preset) => {
    setEditingId(preset.id);
    setEditingText(preset.text);
    setEditingDoctorId(preset.doctorId ?? '');
  };

  const handleSaveEdit = async (id) => {
    if (!editingText.trim()) return;
    const assignment = isAdmin
      ? { doctorId: editingDoctorId === '' ? null : Number(editingDoctorId) }
      : {};
    try {
      const updated = await hospitalService.updateConsultationNotePreset(id, {
        text: editingText.trim(),
        ...assignment,
      });
      setPresets((prev) => prev.map((p) => (p.id === id ? updated : p)));
      setEditingId(null);
      success(`${nounCap} updated`);
    } catch (err) {
      toastError(`Failed to update ${noun}`);
    }
  };

  const handleDelete = (id) => {
    setDeleteConfirm({ isOpen: true, id });
  };

  const confirmDelete = async () => {
    const id = deleteConfirm.id;
    try {
      await hospitalService.deleteConsultationNotePreset(id);
      setPresets((prev) => prev.filter((p) => p.id !== id));
      success(`${nounCap} deleted`);
    } catch (err) {
      toastError(`Failed to delete ${noun}`);
    }
  };

  const handleMove = async (index, direction) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= presets.length) return;

    const a = presets[index];
    const b = presets[targetIndex];
    try {
      const [updatedA, updatedB] = await Promise.all([
        hospitalService.updateConsultationNotePreset(a.id, { displayOrder: b.displayOrder }),
        hospitalService.updateConsultationNotePreset(b.id, { displayOrder: a.displayOrder }),
      ]);
      const next = [...presets];
      next[index] = updatedB;
      next[targetIndex] = updatedA;
      setPresets(next);
    } catch (err) {
      toastError(`Failed to reorder ${noun}s`);
      // One of the two PUTs may have already succeeded, leaving the
      // server's displayOrder out of sync with what's shown locally —
      // refetch to reconcile rather than risk a stale/duplicate order.
      loadPresets();
    }
  };

  return (
    <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-gray-900 mb-1">{title}</h3>
        <p className="text-xs text-gray-500">{description}</p>
      </div>

      <form onSubmit={handleAdd} className="flex gap-2">
        <input
          type="text"
          value={newText}
          onChange={(e) => setNewText(e.target.value)}
          placeholder={placeholder}
          maxLength={255}
          className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
        />
        {isAdmin && (
          <select
            value={newDoctorId}
            onChange={(e) => setNewDoctorId(e.target.value)}
            className="border border-gray-300 rounded-lg px-2 py-2 text-sm outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
          >
            <option value="">Shared (all doctors)</option>
            {doctors.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
        )}
        <button
          type="submit"
          disabled={adding || !newText.trim()}
          className="bg-gray-950 text-white text-xs font-semibold px-4 py-2 rounded-lg hover:bg-gray-800 transition disabled:opacity-50"
        >
          + Add
        </button>
      </form>

      {loading ? (
        <p className="text-sm text-gray-500">Loading...</p>
      ) : presets.length === 0 ? (
        <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
          <p className="text-sm text-gray-500">{`No ${noun}s added yet. Add your first one above.`}</p>
        </div>
      ) : (
        <div className="divide-y divide-gray-200">
          {presets.map((preset, index) => (
            <div key={preset.id} className="flex items-center gap-3 py-3">
              <div className="flex flex-col">
                <button
                  type="button"
                  onClick={() => handleMove(index, -1)}
                  disabled={index === 0}
                  className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs leading-none"
                  aria-label="Move up"
                >
                  ▲
                </button>
                <button
                  type="button"
                  onClick={() => handleMove(index, 1)}
                  disabled={index === presets.length - 1}
                  className="text-gray-400 hover:text-gray-700 disabled:opacity-30 text-xs leading-none"
                  aria-label="Move down"
                >
                  ▼
                </button>
              </div>

              {editingId === preset.id ? (
                <div className="flex-1 flex gap-2">
                  <input
                    type="text"
                    value={editingText}
                    onChange={(e) => setEditingText(e.target.value)}
                    maxLength={255}
                    autoFocus
                    className="flex-1 border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
                  />
                  {isAdmin && (
                    <select
                      value={editingDoctorId}
                      onChange={(e) => setEditingDoctorId(e.target.value)}
                      className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                    >
                      <option value="">Shared (all doctors)</option>
                      {doctors.map((d) => (
                        <option key={d.id} value={d.id}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              ) : (
                <span className="flex-1 text-sm text-gray-800 flex items-center gap-2">
                  {preset.text}
                  {isAdmin && (
                    <span
                      className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${preset.doctorId ? 'bg-indigo-50 text-indigo-700' : 'bg-gray-100 text-gray-500'}`}
                    >
                      {preset.doctorName || 'Shared'}
                    </span>
                  )}
                </span>
              )}

              <div className="flex gap-2 text-sm">
                {editingId === preset.id ? (
                  <>
                    <button
                      onClick={() => handleSaveEdit(preset.id)}
                      className="text-emerald-600 hover:text-emerald-800 font-medium"
                    >
                      Save
                    </button>
                    <button
                      onClick={() => setEditingId(null)}
                      className="text-gray-500 hover:text-gray-700 font-medium"
                    >
                      Cancel
                    </button>
                  </>
                ) : (
                  <>
                    <button
                      onClick={() => handleStartEdit(preset)}
                      className="text-indigo-600 hover:text-indigo-900 font-medium"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => handleDelete(preset.id)}
                      className="text-red-600 hover:text-red-900 font-medium"
                    >
                      Delete
                    </button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <ConfirmationModal
        isOpen={deleteConfirm.isOpen}
        title={`Delete ${nounCap}`}
        message={`Are you sure you want to delete this ${noun}? It will no longer appear as a one-click option.`}
        onConfirm={confirmDelete}
        onCancel={() => setDeleteConfirm({ isOpen: false, id: null })}
      />
    </div>
  );
};

export default NotePresetsManager;
