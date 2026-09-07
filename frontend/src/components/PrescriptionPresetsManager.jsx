import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import ConfirmationModal from './ConfirmationModal';
import FrequencyInput, { isFrequencyValid } from './FrequencyInput';
import MedicineAutocomplete from './MedicineAutocomplete';

const EMPTY_ITEM = {
  medicineName: '',
  quantity: 1,
  dosage: '',
  frequency: '',
  duration: '',
  instructions: '',
};

// Stable client-side key for a form row, so React keys don't fall back to the array index.
let itemKeySeq = 0;
const newItem = (base = EMPTY_ITEM) => ({ ...base, _key: `pi-${itemKeySeq++}` });

/**
 * Lists, creates, edits, deletes, and reorders prescription presets (each a
 * named bundle of one or more medicine rows). Self-contained: does its own
 * data fetching, so it can be dropped into a modal or a full page.
 */
const PrescriptionPresetsManager = ({ isAdmin = false }) => {
  const { success, error: toastError } = useToast();
  const [presets, setPresets] = useState([]);
  const [doctors, setDoctors] = useState([]);
  const [loading, setLoading] = useState(true);
  const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, id: null });

  // Form state shared by "create new" and "edit existing" — editingId is
  // null while creating, or the preset's id while editing.
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [formName, setFormName] = useState('');
  const [formItems, setFormItems] = useState([newItem()]);
  // '' = shared (all doctors); a doctor id = private to that doctor. Admin-only.
  const [formDoctorId, setFormDoctorId] = useState('');
  const [saving, setSaving] = useState(false);

  const loadPresets = useCallback(async () => {
    setLoading(true);
    try {
      const data = await hospitalService.getPrescriptionPresets();
      setPresets(data || []);
    } catch (err) {
      toastError('Failed to load prescription presets');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    loadPresets();
  }, [loadPresets]);

  // Real-time sync: reload when any client in this hospital changes a preset.
  useEffect(() => {
    const handler = () => loadPresets();
    globalThis.addEventListener('hms:presets-updated', handler);
    return () => globalThis.removeEventListener('hms:presets-updated', handler);
  }, [loadPresets]);

  // Admin assigns presets to specific doctors, so it needs the doctor list.
  useEffect(() => {
    if (!isAdmin) return;
    hospitalService
      .getDoctors('', 0, 500)
      .then((data) => setDoctors(data?.content || []))
      .catch(() => {
        /* dropdown just stays empty */
      });
  }, [isAdmin]);

  const openCreateForm = () => {
    setEditingId(null);
    setFormName('');
    setFormItems([newItem()]);
    setFormDoctorId('');
    setFormOpen(true);
  };

  const openEditForm = (preset) => {
    setEditingId(preset.id);
    setFormName(preset.name);
    setFormItems(
      preset.items && preset.items.length > 0
        ? preset.items.map((i) => newItem({ ...i, quantity: i.quantity ?? 1 }))
        : [newItem()]
    );
    setFormDoctorId(preset.doctorId ?? '');
    setFormOpen(true);
  };

  const closeForm = () => {
    setFormOpen(false);
    setEditingId(null);
  };

  const updateFormItem = (index, field, value) => {
    setFormItems((prev) => prev.map((it, i) => (i === index ? { ...it, [field]: value } : it)));
  };

  const addFormItemRow = () => {
    setFormItems((prev) => [...prev, newItem()]);
  };

  const removeFormItemRow = (index) => {
    setFormItems((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSaveForm = async (e) => {
    e.preventDefault();
    const cleanItems = formItems
      .filter((it) => it.medicineName.trim())
      .map((it) => ({
        ...it,
        medicineName: it.medicineName.trim(),
        quantity: Number(it.quantity),
      }));
    if (!formName.trim() || cleanItems.length === 0) return;

    // Quantity must always be a whole number greater than 0.
    const badQty = cleanItems.find((it) => !Number.isInteger(it.quantity) || it.quantity < 1);
    if (badQty) {
      toastError('Quantity must be a whole number greater than 0.');
      return;
    }

    // Frequency is optional in a preset (the doctor sets it when applying — the consultation
    // enforces it there); but if one IS provided it must be valid (a dose pattern like 1-0-1
    // with at least one dose, or "As Per Required" — never "0-0-0").
    const badFreq = cleanItems.find(
      (it) => it.frequency && it.frequency.trim() && !isFrequencyValid(it.frequency)
    );
    if (badFreq) {
      toastError(
        `Fix the frequency for ${badFreq.medicineName}: use a dose pattern like 1-0-1, or "As Per Required".`
      );
      return;
    }

    // Only admins assign a doctor; a doctor's own presets are auto-scoped by the backend.
    const assignment = isAdmin
      ? { doctorId: formDoctorId === '' ? null : Number(formDoctorId) }
      : {};
    setSaving(true);
    try {
      if (editingId) {
        const updated = await hospitalService.updatePrescriptionPreset(editingId, {
          name: formName.trim(),
          items: cleanItems,
          ...assignment,
        });
        setPresets((prev) => prev.map((p) => (p.id === editingId ? updated : p)));
        success('Preset updated');
      } else {
        const created = await hospitalService.createPrescriptionPreset({
          name: formName.trim(),
          items: cleanItems,
          ...assignment,
        });
        setPresets((prev) => [...prev, created]);
        success('Preset created');
      }
      closeForm();
    } catch (err) {
      toastError(err?.response?.data || 'Failed to save preset');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = (id) => {
    setDeleteConfirm({ isOpen: true, id });
  };

  const confirmDelete = async () => {
    const id = deleteConfirm.id;
    try {
      await hospitalService.deletePrescriptionPreset(id);
      setPresets((prev) => prev.filter((p) => p.id !== id));
      success('Preset deleted');
    } catch (err) {
      toastError('Failed to delete preset');
    }
  };

  const handleMove = async (index, direction) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= presets.length) return;

    const a = presets[index];
    const b = presets[targetIndex];
    try {
      const [updatedA, updatedB] = await Promise.all([
        hospitalService.updatePrescriptionPreset(a.id, { displayOrder: b.displayOrder }),
        hospitalService.updatePrescriptionPreset(b.id, { displayOrder: a.displayOrder }),
      ]);
      const next = [...presets];
      next[index] = updatedB;
      next[targetIndex] = updatedA;
      setPresets(next);
    } catch (err) {
      toastError('Failed to reorder presets');
      loadPresets();
    }
  };

  return (
    <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
      <div className="flex justify-between items-start">
        <div>
          <h3 className="text-lg font-semibold text-gray-900 mb-1">Prescription Presets</h3>
          <p className="text-xs text-gray-500">
            Named bundles of medicines a doctor can apply to a prescription in one click.
          </p>
        </div>
        {!formOpen && (
          <button
            type="button"
            onClick={openCreateForm}
            className="bg-gray-950 text-white text-xs font-semibold px-4 py-2 rounded-lg hover:bg-gray-800 transition"
          >
            + Create Preset
          </button>
        )}
      </div>

      {formOpen && (
        <form
          onSubmit={handleSaveForm}
          className="bg-slate-50 p-4 rounded-xl border border-gray-200 space-y-3"
        >
          <input
            type="text"
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            placeholder="Preset name (e.g. Fever Protocol)"
            maxLength={150}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-semibold focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
          />

          {isAdmin && (
            <div>
              <label htmlFor="fld-29" className="block text-xs font-medium text-gray-500 mb-1">
                Assign to doctor
              </label>
              <select
                id="fld-29"
                value={formDoctorId}
                onChange={(e) => setFormDoctorId(e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              >
                <option value="">Shared (all doctors)</option>
                {doctors.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          {formItems.map((item, index) => {
            const qtyNum = Number(item.quantity);
            const invalidQty =
              !!item.medicineName.trim() &&
              (String(item.quantity).trim() === '' || !Number.isInteger(qtyNum) || qtyNum < 1);
            return (
              <div
                key={item._key ?? index}
                className="grid grid-cols-2 gap-2 bg-white p-3 rounded-lg border border-gray-200"
              >
                <div className="col-span-2 flex gap-2">
                  <div className="flex-1 min-w-0">
                    <MedicineAutocomplete
                      value={item.medicineName}
                      onChange={(name) => updateFormItem(index, 'medicineName', name)}
                    />
                  </div>
                  <input
                    type="number"
                    min="1"
                    step="1"
                    value={item.quantity}
                    onChange={(e) =>
                      updateFormItem(index, 'quantity', e.target.value.replace(/[^0-9]/g, ''))
                    }
                    placeholder="Qty"
                    title="Quantity (must be greater than 0)"
                    className={`w-24 shrink-0 mt-1 border rounded-lg px-2 py-1.5 text-sm text-center ${invalidQty ? 'border-red-400 text-red-600' : 'border-gray-300'}`}
                  />
                </div>
                {invalidQty && (
                  <p className="col-span-2 text-[11px] text-red-600 -mt-1">
                    Enter a quantity greater than 0.
                  </p>
                )}
                <input
                  type="text"
                  value={item.dosage}
                  onChange={(e) => updateFormItem(index, 'dosage', e.target.value)}
                  placeholder="Dosage (e.g. 500mg)"
                  maxLength={50}
                  className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                />
                <div className="flex items-center gap-2 border border-gray-300 rounded-lg px-2 py-1.5">
                  <span className="text-xs text-gray-500 shrink-0">Freq</span>
                  <FrequencyInput
                    value={item.frequency}
                    onChange={(val) => updateFormItem(index, 'frequency', val)}
                  />
                </div>
                <input
                  type="text"
                  value={item.duration}
                  onChange={(e) => updateFormItem(index, 'duration', e.target.value)}
                  placeholder="Duration (e.g. 5 Days)"
                  maxLength={50}
                  className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                />
                <input
                  type="text"
                  value={item.instructions}
                  onChange={(e) => updateFormItem(index, 'instructions', e.target.value)}
                  placeholder="Instructions (e.g. After food)"
                  maxLength={200}
                  className="border border-gray-300 rounded-lg px-2 py-1.5 text-sm"
                />
                {formItems.length > 1 && (
                  <button
                    type="button"
                    onClick={() => removeFormItemRow(index)}
                    className="col-span-2 text-xs text-red-600 hover:text-red-800 text-left"
                  >
                    Remove this medicine
                  </button>
                )}
              </div>
            );
          })}

          <button
            type="button"
            onClick={addFormItemRow}
            className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
          >
            + Add another medicine
          </button>

          <div className="flex gap-2 pt-2">
            <button
              type="submit"
              disabled={
                saving || !formName.trim() || !formItems.some((it) => it.medicineName.trim())
              }
              className="bg-primary-600 text-white text-sm font-semibold px-4 py-2 rounded-lg hover:bg-primary-700 transition disabled:opacity-50"
            >
              {editingId ? 'Save Changes' : 'Create Preset'}
            </button>
            <button
              type="button"
              onClick={closeForm}
              className="text-sm text-gray-600 hover:text-gray-800 px-4 py-2"
            >
              Cancel
            </button>
          </div>
        </form>
      )}

      {loading ? (
        <p className="text-sm text-gray-500">Loading...</p>
      ) : presets.length === 0 ? (
        <div className="text-center py-8 border border-dashed border-gray-200 rounded-xl bg-gray-50">
          <p className="text-sm text-gray-500">
            No prescription presets yet. Create your first one above.
          </p>
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

              <div className="flex-1">
                <div className="text-sm font-semibold text-gray-800 flex items-center gap-2">
                  {preset.name}
                  {isAdmin && (
                    <span
                      className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${preset.doctorId ? 'bg-indigo-50 text-indigo-700' : 'bg-gray-100 text-gray-500'}`}
                    >
                      {preset.doctorName || 'Shared'}
                    </span>
                  )}
                </div>
                <div className="text-xs text-gray-500 mt-0.5">
                  {(preset.items || []).map((i) => i.medicineName).join(', ') || 'No medicines'}
                </div>
              </div>

              <div className="flex gap-2 text-sm">
                <button
                  onClick={() => openEditForm(preset)}
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
              </div>
            </div>
          ))}
        </div>
      )}

      <ConfirmationModal
        isOpen={deleteConfirm.isOpen}
        title="Delete Prescription Preset"
        message="Are you sure you want to delete this preset? It will no longer be available to apply during a consultation."
        onConfirm={confirmDelete}
        onCancel={() => setDeleteConfirm({ isOpen: false, id: null })}
      />
    </div>
  );
};

export default PrescriptionPresetsManager;
