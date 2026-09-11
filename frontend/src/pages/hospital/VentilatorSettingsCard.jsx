import React, { useCallback, useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import icuService from '../../services/icuService';

/**
 * VentilatorSettingsCard - the per-hospital ventilator parameter catalogue (ICU Phase 7, D-5).
 *
 * Mirrors VitalsSettingsCard, with two differences the ventilator chart needs: every parameter
 * carries a category (dialled INTO the machine, or read OFF it), and a display name is editable.
 *
 * There is deliberately no Delete. A parameter is switched off, not removed, so every key ever
 * charted still resolves to a name and no historical value is ever left captioned by a raw key.
 *
 * The identity rule this screen exists to respect: renaming changes the label only. `key` is
 * assigned once and never re-derived, so a rename cannot orphan a recorded value.
 */
const CATEGORIES = [
  ['SETTING', 'Ventilator Settings'],
  ['OBSERVATION', 'Ventilator Observations / Measurements'],
];

const VentilatorSettingsCard = ({ refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [params, setParams] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null); // param key
  const [edit, setEdit] = useState({ displayName: '', unit: '', category: 'SETTING' });
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState({ displayName: '', unit: '', category: 'SETTING' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await icuService.getVentilatorParams();
      setParams(Array.isArray(list) ? list : []);
    } catch {
      toastError('Could not load ventilator parameters');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    // refreshKey rises on a realtime REFRESH_DATA, so an admin in a second tab (or another
    // admin) toggling a parameter shows up here instead of two screens disagreeing about what
    // the ventilator chart captures.
    load();
  }, [load, refreshKey]);

  const toggle = async (p) => {
    const next = !p.enabled;
    // Optimistic, with rollback — the vitals card's pattern.
    setParams((list) => list.map((x) => (x.key === p.key ? { ...x, enabled: next } : x)));
    try {
      await icuService.updateVentilatorParam(p.key, { enabled: next });
      success(next ? `${p.displayName} enabled` : `${p.displayName} disabled — history is kept`);
    } catch (err) {
      setParams((list) => list.map((x) => (x.key === p.key ? { ...x, enabled: p.enabled } : x)));
      toastError(err?.response?.data?.error || 'Could not update the parameter');
    }
  };

  const saveEdit = async (p) => {
    if (!edit.displayName.trim()) {
      toastError('Enter a display name');
      return;
    }
    try {
      await icuService.updateVentilatorParam(p.key, {
        displayName: edit.displayName.trim(),
        unit: edit.unit.trim(),
        category: edit.category,
      });
      success('Parameter updated — recorded values are unchanged');
      setEditing(null);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Could not update the parameter');
    }
  };

  const add = async () => {
    if (!draft.displayName.trim()) {
      toastError('Enter a parameter name');
      return;
    }
    setAdding(true);
    try {
      await icuService.addVentilatorParam({
        displayName: draft.displayName.trim(),
        unit: draft.unit.trim(),
        category: draft.category,
      });
      success('Parameter added');
      setDraft({ displayName: '', unit: '', category: 'SETTING' });
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Could not add the parameter');
    } finally {
      setAdding(false);
    }
  };

  const renderRow = (p) => (
    <li key={p.key} className="py-3">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium text-gray-900">{p.displayName}</span>
            {p.unit && <span className="text-xs text-gray-500">{p.unit}</span>}
            {p.isCustom && (
              <span className="text-[11px] font-semibold text-blue-700 bg-blue-50 border border-blue-200 rounded px-2 py-0.5">
                Custom
              </span>
            )}
            {p.valueType === 'MODE' && (
              <span className="text-[11px] font-semibold text-gray-600 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                Fixed value list
              </span>
            )}
          </div>
          <p className="text-[11px] text-gray-400 mt-0.5">{p.key}</p>
        </div>
        <div className="shrink-0 flex items-center gap-3">
          {editing !== p.key && (
            <button
              type="button"
              onClick={() => {
                setEditing(p.key);
                setEdit({
                  displayName: p.displayName || '',
                  unit: p.unit || '',
                  category: p.category,
                });
              }}
              className="text-xs font-semibold text-primary-700 hover:underline"
            >
              Edit
            </button>
          )}
          <button
            type="button"
            onClick={() => toggle(p)}
            className={`text-xs font-semibold px-3 py-1.5 rounded-lg border ${
              p.enabled
                ? 'text-green-700 bg-green-50 border-green-200'
                : 'text-gray-500 bg-gray-50 border-gray-200'
            }`}
          >
            {p.enabled ? 'On' : 'Off'}
          </button>
        </div>
      </div>

      {editing === p.key && (
        <div className="mt-3 flex flex-wrap items-end gap-2 bg-gray-50 border border-gray-200 rounded-lg p-3">
          <div>
            <label
              htmlFor={`vp-name-${p.key}`}
              className="block text-xs font-medium text-gray-600 mb-1"
            >
              Display name
            </label>
            <input
              id={`vp-name-${p.key}`}
              value={edit.displayName}
              onChange={(e) => setEdit((x) => ({ ...x, displayName: e.target.value }))}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-48"
            />
          </div>
          <div>
            <label
              htmlFor={`vp-unit-${p.key}`}
              className="block text-xs font-medium text-gray-600 mb-1"
            >
              Unit
            </label>
            <input
              id={`vp-unit-${p.key}`}
              value={edit.unit}
              onChange={(e) => setEdit((x) => ({ ...x, unit: e.target.value }))}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-28"
            />
          </div>
          <div>
            <label
              htmlFor={`vp-cat-${p.key}`}
              className="block text-xs font-medium text-gray-600 mb-1"
            >
              Category
            </label>
            <select
              id={`vp-cat-${p.key}`}
              value={edit.category}
              onChange={(e) => setEdit((x) => ({ ...x, category: e.target.value }))}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
            >
              {CATEGORIES.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            onClick={() => saveEdit(p)}
            className="px-4 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700"
          >
            Save
          </button>
          <button
            type="button"
            onClick={() => setEditing(null)}
            className="px-3 py-2 rounded-lg text-sm text-gray-600 hover:underline"
          >
            Cancel
          </button>
          <p className="w-full text-[11px] text-gray-500">
            Renaming changes the label only. Values already recorded keep their identity and stay
            readable.
          </p>
        </div>
      )}
    </li>
  );

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <h3 className="font-bold text-gray-800 text-sm">Ventilator Parameters</h3>
      <p className="text-xs text-gray-500 mt-1">
        Choose what the ventilator chart captures. Turning a parameter off removes it from new
        charting only — values already recorded stay on the chart.
      </p>

      {loading ? (
        <p className="text-sm text-gray-500 mt-4">Loading…</p>
      ) : (
        CATEGORIES.map(([key, label]) => {
          const rows = params.filter((p) => p.category === key);
          return (
            <div key={key} className="mt-5">
              <p className="text-xs font-semibold text-gray-700 uppercase tracking-wide">{label}</p>
              {rows.length === 0 ? (
                <p className="text-xs text-gray-400 mt-2">No parameters in this group.</p>
              ) : (
                <ul className="divide-y divide-gray-100 mt-1">{rows.map(renderRow)}</ul>
              )}
            </div>
          );
        })
      )}

      <div className="mt-6 pt-5 border-t border-gray-100">
        <p className="text-xs font-semibold text-gray-700 uppercase tracking-wide mb-3">
          Add a parameter
        </p>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div>
            <label htmlFor="vp-new-name" className="block text-xs font-medium text-gray-600 mb-1">
              Name
            </label>
            <input
              id="vp-new-name"
              value={draft.displayName}
              onChange={(e) => setDraft((d) => ({ ...d, displayName: e.target.value }))}
              placeholder="e.g. Minute Ventilation"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </div>
          <div>
            <label htmlFor="vp-new-unit" className="block text-xs font-medium text-gray-600 mb-1">
              Unit
            </label>
            <input
              id="vp-new-unit"
              value={draft.unit}
              onChange={(e) => setDraft((d) => ({ ...d, unit: e.target.value }))}
              placeholder="e.g. L/min"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
          </div>
          <div>
            <label htmlFor="vp-new-cat" className="block text-xs font-medium text-gray-600 mb-1">
              Category
            </label>
            <select
              id="vp-new-cat"
              value={draft.category}
              onChange={(e) => setDraft((d) => ({ ...d, category: e.target.value }))}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            >
              {CATEGORIES.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="mt-4 flex justify-end">
          <button
            type="button"
            onClick={add}
            disabled={adding}
            className="px-5 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
          >
            {adding ? 'Saving…' : 'Add parameter'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default VentilatorSettingsCard;
