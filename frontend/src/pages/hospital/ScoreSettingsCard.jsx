import React, { useCallback, useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import icuService from '../../services/icuService';

/**
 * ScoreSettingsCard - which severity scores this hospital uses (ICU Phase 8, D-2).
 *
 * Deliberately the smallest settings card in the module, and smaller than
 * VentilatorSettingsCard on purpose: a hospital chooses whether it runs SOFA, not what SOFA is.
 * There is no rename, no unit, no add and no delete, because SOFA's six organ systems are
 * standardised — a renamed component would produce a score nobody could compare against.
 *
 * Switching a score off stops it being recorded next. Everything already recorded stays on the
 * patient's chart, readable, and marked as no longer recorded.
 */
const ScoreSettingsCard = ({ refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [types, setTypes] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await icuService.getScoreTypes();
      setTypes(Array.isArray(list) ? list : []);
    } catch {
      toastError('Could not load severity scores');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    // refreshKey rises on a realtime REFRESH_DATA, so a second admin's change shows up here
    // instead of two screens disagreeing about what the chart offers.
    load();
  }, [load, refreshKey]);

  const toggle = async (t) => {
    const next = !t.enabled;
    // Optimistic, with rollback — the pattern the other settings cards use.
    setTypes((list) => list.map((x) => (x.key === t.key ? { ...x, enabled: next } : x)));
    try {
      await icuService.toggleScoreType(t.key, { enabled: next });
      success(next ? `${t.label} enabled` : `${t.label} disabled — recorded scores are kept`);
    } catch (err) {
      setTypes((list) => list.map((x) => (x.key === t.key ? { ...x, enabled: t.enabled } : x)));
      toastError(err?.response?.data?.error || 'Could not update the score');
    }
  };

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-5">
      <h3 className="font-bold text-gray-800 text-sm">Severity Scores</h3>
      <p className="text-xs text-gray-500 mt-1">
        Choose which severity scores the ICU chart records. Turning one off removes it from new
        charting only — scores already recorded stay on the patient&rsquo;s chart. GCS is part of
        Vitals and is configured there.
      </p>

      {loading ? (
        <p className="text-sm text-gray-500 mt-4">Loading…</p>
      ) : (
        <ul className="divide-y divide-gray-100 mt-4">
          {types.map((t) => (
            <li key={t.key} className="py-3 flex items-center justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium text-gray-900">{t.label}</span>
                  <span className="text-[11px] font-semibold text-gray-600 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                    {t.totalOnly
                      ? 'Total only'
                      : `${t.components.length} components · ${t.totalMin}–${t.totalMax}`}
                  </span>
                </div>
                {!t.totalOnly && (
                  <p className="text-[11px] text-gray-400 mt-0.5">
                    {t.components.map((c) => c.label).join(' · ')}
                  </p>
                )}
              </div>
              <button
                type="button"
                onClick={() => toggle(t)}
                className={`shrink-0 text-xs font-semibold px-3 py-1.5 rounded-lg border ${
                  t.enabled
                    ? 'text-green-700 bg-green-50 border-green-200'
                    : 'text-gray-500 bg-gray-50 border-gray-200'
                }`}
              >
                {t.enabled ? 'On' : 'Off'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};

export default ScoreSettingsCard;
