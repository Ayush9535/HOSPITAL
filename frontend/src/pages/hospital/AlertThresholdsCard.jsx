import React, { useCallback, useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import icuService from '../../services/icuService';

/**
 * AlertThresholdsCard - alert thresholds for ICU vitals (ICU Phase 9).
 *
 * Every row starts empty and off. Nothing here suggests a value, because a suggested threshold
 * would be the system telling a hospital what a normal MAP is — and ICU records values rather
 * than judging them.
 *
 * Per hospital only. Alerts arrive in the existing notification bell; nothing is coloured or
 * badged on the patient chart.
 */
const AlertThresholdsCard = ({ refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [metrics, setMetrics] = useState([]);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState({}); // key -> { minValue, maxValue }
  const [savingKey, setSavingKey] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await icuService.getAlertThresholds();
      const rows = Array.isArray(list) ? list : [];
      setMetrics(rows);
      setDraft(
        Object.fromEntries(
          rows.map((m) => [m.key, { minValue: m.minValue ?? '', maxValue: m.maxValue ?? '' }])
        )
      );
    } catch {
      toastError('Could not load alert thresholds');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    // refreshKey rises on a realtime REFRESH_DATA, so a second admin's change shows up here.
    load();
  }, [load, refreshKey]);

  const setField = (key, field, value) =>
    setDraft((d) => ({ ...d, [key]: { ...d[key], [field]: value } }));

  const numberOrNull = (v) => (v === '' || v === null || v === undefined ? null : Number(v));

  const save = async (m, enabled) => {
    const d = draft[m.key] || {};
    setSavingKey(m.key);
    try {
      await icuService.saveAlertThreshold(m.key, {
        minValue: numberOrNull(d.minValue),
        maxValue: numberOrNull(d.maxValue),
        enabled,
      });
      success(enabled ? `${m.label} alert saved` : `${m.label} alert turned off`);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Could not save the threshold');
    } finally {
      setSavingKey(null);
    }
  };

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <h3 className="font-bold text-gray-800 text-sm">ICU Alert Thresholds</h3>
      <p className="text-xs text-gray-500 mt-1">
        Notify the assigned nurse and the ward incharge when an ICU vitals observation falls outside
        the range you set. Nothing is configured until you set it, and no threshold is suggested.
        Alerts appear in the notification bell.
      </p>

      {loading ? (
        <p className="text-sm text-gray-500 mt-4">Loading…</p>
      ) : (
        <ul className="divide-y divide-gray-100 mt-4">
          {metrics.map((m) => (
            <li key={m.key} className="py-3">
              <div className="flex flex-wrap items-end justify-between gap-3">
                <div className="min-w-[8rem]">
                  <span className="text-sm font-medium text-gray-900">{m.label}</span>
                  {m.unit && <span className="text-xs text-gray-500 ml-1">{m.unit}</span>}
                  {!m.enabled && (
                    <span className="ml-2 text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                      Off
                    </span>
                  )}
                </div>
                <div className="flex flex-wrap items-end gap-2">
                  <div>
                    <label
                      htmlFor={`alert-min-${m.key}`}
                      className="block text-xs font-medium text-gray-600 mb-1"
                    >
                      Alert below
                    </label>
                    <input
                      id={`alert-min-${m.key}`}
                      type="number"
                      step="any"
                      value={draft[m.key]?.minValue ?? ''}
                      onChange={(e) => setField(m.key, 'minValue', e.target.value)}
                      className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-28"
                    />
                  </div>
                  <div>
                    <label
                      htmlFor={`alert-max-${m.key}`}
                      className="block text-xs font-medium text-gray-600 mb-1"
                    >
                      Alert above
                    </label>
                    <input
                      id={`alert-max-${m.key}`}
                      type="number"
                      step="any"
                      value={draft[m.key]?.maxValue ?? ''}
                      onChange={(e) => setField(m.key, 'maxValue', e.target.value)}
                      className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-28"
                    />
                  </div>
                  <button
                    type="button"
                    onClick={() => save(m, true)}
                    disabled={savingKey === m.key}
                    className="px-4 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
                  >
                    {savingKey === m.key ? 'Saving…' : 'Save'}
                  </button>
                  {m.enabled && (
                    <button
                      type="button"
                      onClick={() => save(m, false)}
                      disabled={savingKey === m.key}
                      className="px-3 py-2 rounded-lg text-sm font-semibold text-gray-600 hover:underline disabled:opacity-50"
                    >
                      Turn off
                    </button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default AlertThresholdsCard;
