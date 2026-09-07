import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import otService from '../../services/otService';

const ARCHETYPE_LABELS = {
  SMALL: 'Small hospital',
  MEDIUM: 'Medium hospital',
  LARGE: 'Large hospital',
  CORPORATE: 'Corporate / NABH',
};

/**
 * OtPoliciesCard - the workflow policy matrix.
 *
 * This is how one codebase serves a 10-bed nursing home and a 1000-bed chain: the same
 * state machine, gated by these values. An archetype preset is a one-click bulk write; a
 * hospital that then diverges is not a special case, just its own rows.
 */
const OtPoliciesCard = () => {
  const { success, error: toastError } = useToast();
  const [catalogue, setCatalogue] = useState([]);
  const [values, setValues] = useState({});
  const [emergency, setEmergency] = useState({});
  const [archetypes, setArchetypes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await otService.getPolicies();
      setCatalogue(data.catalogue || []);
      setValues(data.values || {});
      setEmergency(data.emergencyOverrides || {});
      setArchetypes(data.archetypes || []);
      setDirty(false);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load OT policies');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  // Load once on mount; useToast identities change per render.
  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const setValue = (key, v) => {
    setValues((prev) => ({ ...prev, [key]: v }));
    setDirty(true);
  };

  const save = async () => {
    setBusy(true);
    try {
      setValues(await otService.updatePolicies(values));
      setDirty(false);
      success('OT policies saved');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save OT policies');
    } finally {
      setBusy(false);
    }
  };

  const applyArchetype = async (name) => {
    setBusy(true);
    try {
      setValues(await otService.applyArchetype(name));
      await load();
      success(`Applied the ${ARCHETYPE_LABELS[name] || name} preset`);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to apply preset');
    } finally {
      setBusy(false);
    }
  };

  if (loading)
    return (
      <div className="bg-white rounded-2xl border border-gray-200 p-6 text-gray-400">Loading…</div>
    );

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 space-y-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-lg font-semibold text-gray-900">OT Workflow Policies</h3>
          <p className="text-xs text-gray-500 mt-1">
            Which steps your theatre requires. Turn off what you don&apos;t do; the system fills it
            in automatically and records that it did.
          </p>
        </div>
        <button
          type="button"
          onClick={save}
          disabled={busy || !dirty}
          className={`px-4 py-2 rounded-lg text-xs font-semibold text-white shrink-0 ${busy || !dirty ? 'bg-gray-300' : 'bg-gray-900 hover:bg-gray-800'}`}
        >
          {busy ? 'Saving…' : 'Save'}
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2 bg-slate-50 border border-gray-200 rounded-lg p-3">
        <span className="text-xs font-semibold text-gray-600">Quick setup:</span>
        {archetypes.map((a) => (
          <button
            key={a}
            type="button"
            disabled={busy}
            onClick={() => applyArchetype(a)}
            className="px-3 py-1 text-xs font-medium bg-white border border-gray-300 rounded-full hover:bg-gray-100"
          >
            {ARCHETYPE_LABELS[a] || a}
          </button>
        ))}
      </div>

      <div className="divide-y divide-gray-100">
        {catalogue.map((p) => (
          <div key={p.key} className="flex items-center justify-between py-3 gap-4">
            <div>
              <div className="text-sm font-medium text-gray-800">{p.label}</div>
              {emergency[p.key] && (
                <div className="text-[11px] text-orange-600 mt-0.5">
                  Emergencies: {emergency[p.key]}
                </div>
              )}
            </div>
            <select
              value={values[p.key] || p.defaultValue}
              onChange={(e) => setValue(p.key, e.target.value)}
              className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm min-w-[140px]"
            >
              {p.values.map((v) => (
                <option key={v} value={v}>
                  {v.replace(/_/g, ' ')}
                </option>
              ))}
            </select>
          </div>
        ))}
      </div>

      {dirty && <p className="text-xs text-amber-600">Unsaved changes.</p>}
    </div>
  );
};

export default OtPoliciesCard;
