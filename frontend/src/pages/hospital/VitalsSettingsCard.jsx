import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import vitalsService from '../../services/vitalsService';

/**
 * VitalsSettingsCard - admin control over which vitals are captured at OPD entry.
 * Built-in vitals can be toggled but not deleted; hospitals may add their own
 * custom vitals (name + unit) and delete those. Off vitals are hidden from the
 * OPD form and omitted from the printed case paper.
 */
const VitalsSettingsCard = () => {
  const { success, error: toastError } = useToast();
  const [vitals, setVitals] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [unit, setUnit] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setVitals(await vitalsService.list());
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load vitals');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  // Load once on mount. Do NOT key this on `load`/toast identities.
  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = async (v) => {
    const next = !v.enabled;
    setVitals((list) => list.map((x) => (x.key === v.key ? { ...x, enabled: next } : x)));
    try {
      await vitalsService.toggle(v.key, next);
      success('Vitals updated');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to update');
      setVitals((list) => list.map((x) => (x.key === v.key ? { ...x, enabled: v.enabled } : x)));
    }
  };

  const addCustom = async () => {
    if (!name.trim()) {
      toastError('Enter a vital name');
      return;
    }
    setAdding(true);
    try {
      const created = await vitalsService.addCustom(name.trim(), unit.trim());
      setVitals((list) => [
        ...list,
        {
          key: created.vitalKey,
          label: created.label,
          unit: created.unit,
          type: 'TEXT',
          isCustom: true,
          enabled: true,
          publicId: created.publicId,
        },
      ]);
      setName('');
      setUnit('');
      success('Vital added');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to add vital');
    } finally {
      setAdding(false);
    }
  };

  const remove = async (v) => {
    const prev = vitals;
    setVitals((list) => list.filter((x) => x.key !== v.key));
    try {
      await vitalsService.deleteCustom(v.publicId);
      success('Vital deleted');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to delete');
      setVitals(prev);
    }
  };

  if (loading) return <div className="p-6 text-gray-500 text-sm">Loading vitals…</div>;

  return (
    <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm p-6 mt-6">
      <h3 className="text-lg font-bold text-gray-900 mb-1">Vitals</h3>
      <p className="text-sm text-gray-500 mb-5">
        Choose which vitals are taken when creating an OPD entry. Vitals that are off are hidden
        from the OPD form and are not printed on the case paper. You can add your own vitals and
        delete them later.
      </p>

      <div className="border border-gray-200 rounded-xl overflow-hidden mb-5">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Vital</th>
              <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Unit</th>
              <th className="px-4 py-2.5 text-left font-semibold text-gray-600">Status</th>
              <th className="px-4 py-2.5 text-left font-semibold text-gray-600"></th>
            </tr>
          </thead>
          <tbody>
            {vitals.map((v) => (
              <tr
                key={v.key}
                className={`border-b border-gray-100 last:border-0 ${!v.enabled ? 'bg-gray-50/60' : ''}`}
              >
                <td
                  className={`px-4 py-3 font-medium ${v.enabled ? 'text-gray-900' : 'text-gray-400'}`}
                >
                  {v.label}
                  {v.isCustom && (
                    <span className="ml-2 text-[10px] font-bold uppercase tracking-wider text-indigo-500">
                      custom
                    </span>
                  )}
                </td>
                <td className="px-4 py-3 text-gray-500">{v.unit || '—'}</td>
                <td className="px-4 py-3">
                  <button
                    type="button"
                    onClick={() => toggle(v)}
                    aria-label={v.enabled ? 'On' : 'Off'}
                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${v.enabled ? 'bg-gray-900' : 'bg-gray-300'}`}
                  >
                    <span
                      className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${v.enabled ? 'translate-x-6' : 'translate-x-1'}`}
                    />
                  </button>
                </td>
                <td className="px-4 py-3">
                  {v.isCustom ? (
                    <button
                      onClick={() => remove(v)}
                      className="text-xs font-semibold text-red-600 hover:text-red-700"
                    >
                      Delete
                    </button>
                  ) : (
                    <span className="text-xs text-gray-300">built-in</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="fld-144" className="block text-xs font-medium text-gray-600 mb-1">
            New vital name
          </label>
          <input
            id="fld-144"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Random Sugar"
            className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
        </div>
        <div>
          <label htmlFor="fld-143" className="block text-xs font-medium text-gray-600 mb-1">
            Unit (optional)
          </label>
          <input
            id="fld-143"
            value={unit}
            onChange={(e) => setUnit(e.target.value)}
            placeholder="e.g. mg/dL"
            className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-32"
          />
        </div>
        <button
          onClick={addCustom}
          disabled={adding}
          className="px-4 py-2 bg-gray-900 text-white text-sm font-semibold rounded-lg hover:bg-gray-800 disabled:opacity-50"
        >
          {adding ? 'Adding…' : '+ Add Vital'}
        </button>
      </div>
    </div>
  );
};

export default VitalsSettingsCard;
