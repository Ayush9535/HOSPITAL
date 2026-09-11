import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import ConfirmationModal from './ConfirmationModal';
import FrequencyInput, { isFrequencyValid } from './FrequencyInput';
import MedicineAutocomplete from './MedicineAutocomplete';

/**
 * InClinicPresetsManager — the in-clinic twin of PrescriptionPresetsManager.
 *
 * Prescription = medicines the patient buys OUTSIDE (from any pharmacy). In-Clinic = medicines
 * the DOCTOR dispenses himself. Both search the global medicine catalog by name; in-clinic items
 * additionally carry a quantity. When an in-clinic preset is applied during a consultation the
 * medicine is resolved to the clinic's stock by name, and stock is deducted only when the
 * consultation is completed (or the patient is admitted to IPD) — not when the preset is built.
 */
const EMPTY_ITEM = {
  medicineName: '',
  quantity: 1,
  dosage: '',
  frequency: '',
  duration: '',
  instructions: '',
};

const InClinicPresetsManager = () => {
  const { success, error: toastError } = useToast();

  const [presets, setPresets] = useState([]);
  const [loading, setLoading] = useState(true);
  const [deleteConfirm, setDeleteConfirm] = useState({ isOpen: false, id: null });

  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [formName, setFormName] = useState('');
  const [formItems, setFormItems] = useState([{ ...EMPTY_ITEM }]);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const presetData = await hospitalService.getInClinicPresets();
      setPresets(presetData || []);
    } catch {
      toastError('Failed to load in-clinic presets.');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load]);

  // Presets are hospital-wide, so another user's change should show up here too.
  useEffect(() => {
    const onChanged = () => load();
    globalThis.addEventListener('hms:presets-updated', onChanged);
    return () => globalThis.removeEventListener('hms:presets-updated', onChanged);
  }, [load]);

  const openCreateForm = () => {
    setEditingId(null);
    setFormName('');
    setFormItems([{ ...EMPTY_ITEM }]);
    setFormOpen(true);
  };

  const openEditForm = (preset) => {
    setEditingId(preset.id);
    setFormName(preset.name || '');
    setFormItems(
      (preset.items || []).length
        ? preset.items.map((it) => ({
            medicineName: it.medicineName || '',
            quantity: it.quantity ?? 1,
            dosage: it.dosage || '',
            frequency: it.frequency || '',
            duration: it.duration || '',
            instructions: it.instructions || '',
          }))
        : [{ ...EMPTY_ITEM }]
    );
    setFormOpen(true);
  };

  const closeForm = () => {
    setFormOpen(false);
    setEditingId(null);
    setFormItems([{ ...EMPTY_ITEM }]);
    setFormName('');
  };

  const updateFormItem = (index, field, value) => {
    setFormItems((prev) => prev.map((it, i) => (i === index ? { ...it, [field]: value } : it)));
  };

  const addFormItemRow = () => setFormItems((prev) => [...prev, { ...EMPTY_ITEM }]);
  const removeFormItemRow = (index) => setFormItems((prev) => prev.filter((_, i) => i !== index));

  const handleSaveForm = async (e) => {
    e.preventDefault();
    if (!formName.trim()) {
      toastError('Preset name is required.');
      return;
    }

    // Only rows with a medicine chosen are saved; a blank trailing row is normal.
    const selectedRows = formItems.filter((it) => it.medicineName.trim());
    if (selectedRows.length === 0) {
      toastError('Add at least one medicine.');
      return;
    }

    // Quantity must always be a whole number greater than 0.
    const hasInvalidQty = selectedRows.some((it) => {
      const q = Number(it.quantity);
      return !Number.isInteger(q) || q < 1;
    });
    if (hasInvalidQty) {
      toastError('Quantity must be a whole number greater than 0.');
      return;
    }

    // Frequency is optional in a preset (the doctor sets it when applying — the consultation
    // enforces it there); but if one IS provided it must be valid (a dose pattern like 1-0-1
    // with at least one dose, or "As Per Required" — never "0-0-0").
    const badFreq = selectedRows.find(
      (it) => it.frequency && it.frequency.trim() && !isFrequencyValid(it.frequency)
    );
    if (badFreq) {
      toastError(
        `Fix the frequency for ${badFreq.medicineName || 'the selected medicine'}: use a dose pattern like 1-0-1, or "As Per Required".`
      );
      return;
    }

    const cleanItems = selectedRows.map((it) => ({
      medicineName: it.medicineName.trim(),
      quantity: Number(it.quantity),
      dosage: it.dosage.trim(),
      frequency: it.frequency.trim(),
      duration: it.duration.trim(),
      instructions: it.instructions.trim(),
    }));

    setSaving(true);
    try {
      const payload = { name: formName.trim(), items: cleanItems };
      if (editingId) {
        await hospitalService.updateInClinicPreset(editingId, payload);
        success('In-clinic preset updated.');
      } else {
        await hospitalService.createInClinicPreset(payload);
        success('In-clinic preset created.');
      }
      closeForm();
      await load();
    } catch (err) {
      toastError(err?.response?.data?.error || err?.response?.data || 'Failed to save preset.');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id) => {
    try {
      await hospitalService.deleteInClinicPreset(id);
      success('In-clinic preset deleted.');
      await load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to delete preset.');
    } finally {
      setDeleteConfirm({ isOpen: false, id: null });
    }
  };

  /** "Betadine ×2 — 500mg · 1-0-1 · 5 Days" */
  const describeItem = (i) => {
    const detail = [i.dosage, i.frequency, i.duration].filter(Boolean).join(' · ');
    return `${i.medicineName} ×${i.quantity ?? 1}${detail ? ` — ${detail}` : ''}`;
  };

  return (
    <div className="bg-white p-6 rounded-2xl border border-gray-200 shadow-sm space-y-6">
      <div className="flex justify-between items-start">
        <div>
          <h3 className="text-lg font-semibold text-gray-900 mb-1">In-Clinic Presets</h3>
          <p className="text-xs text-gray-500">
            Named bundles of medicines the doctor dispenses from the clinic&apos;s own stock,
            applied to a consultation in one click. (Prescription presets are for medicines the
            patient buys outside.)
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
            placeholder="Preset name (e.g. Dressing Kit)"
            maxLength={150}
            className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-semibold focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none"
          />

          {formItems.map((item, index) => {
            const qtyNum = Number(item.quantity);
            const invalidQty =
              !!item.medicineName.trim() &&
              (String(item.quantity).trim() === '' || !Number.isInteger(qtyNum) || qtyNum < 1);
            return (
              <div
                key={index}
                className="grid grid-cols-2 gap-2 bg-white p-3 rounded-lg border border-gray-200"
              >
                {/* Medicines are searched from the global catalog; stock is resolved and
                                    deducted when the consultation is completed, not when building the preset. */}
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
                      updateFormItem(index, 'quantity', e.target.value.replace(/\D/g, ''))
                    }
                    placeholder="Qty"
                    title="Quantity dispensed (must be greater than 0)"
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
            No in-clinic presets yet. Create your first one above.
          </p>
        </div>
      ) : (
        <div className="divide-y divide-gray-200">
          {presets.map((preset) => (
            <div key={preset.id} className="flex items-center gap-3 py-3">
              <div className="flex-1 min-w-0">
                <div className="text-sm font-semibold text-gray-800">{preset.name}</div>
                <div className="text-xs text-gray-500 mt-0.5 break-words">
                  {(preset.items || []).map(describeItem).join(', ') || 'No medicines'}
                </div>
              </div>

              <div className="flex gap-2 text-sm shrink-0">
                <button
                  onClick={() => openEditForm(preset)}
                  className="text-indigo-600 hover:text-indigo-900 font-medium"
                >
                  Edit
                </button>
                <button
                  onClick={() => setDeleteConfirm({ isOpen: true, id: preset.id })}
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
        title="Delete In-Clinic Preset"
        message="Delete this preset? Medicines already administered are not affected."
        onConfirm={() => handleDelete(deleteConfirm.id)}
        onCancel={() => setDeleteConfirm({ isOpen: false, id: null })}
      />
    </div>
  );
};

export default InClinicPresetsManager;
