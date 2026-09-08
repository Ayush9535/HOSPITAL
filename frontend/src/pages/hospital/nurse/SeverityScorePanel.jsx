import React, { useCallback, useEffect, useMemo, useState } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import useEnabledScoreTypes from '../../../hooks/useEnabledScoreTypes';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';

/**
 * SeverityScorePanel - timed severity scores (ICU Phase 8).
 *
 * Renders entirely from the score types the API returns: there is no component name anywhere in
 * this file, and the SOFA total shown while typing is the sum of the boxes on screen.
 *
 * Recording APPENDS, so the trend the ward round discusses stays intact — "SOFA was 9 on Monday,
 * 6 today" needs both rows.
 *
 * D-1: GCS is deliberately absent. It lives on the Vitals tab where ICU-4 put it, because it is a
 * bedside observation taken with the pulse, not a daily scoring exercise. A second GCS here would
 * split one patient's neuro observations across two screens.
 *
 * Values only — no risk band, no predicted mortality, no colour by value, no improving/worsening
 * label. A trend the reader draws from dated numbers is theirs; one the system asserts would be
 * interpretation.
 */
const fmt = (v) => {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString('en-IN', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return String(v);
  }
};

const SeverityScorePanel = ({ admissionId, readOnly = false, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const { types, loaded } = useEnabledScoreTypes(refreshKey);
  const [chart, setChart] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [scoreType, setScoreType] = useState('');
  const [components, setComponents] = useState({});
  const [total, setTotal] = useState('');
  const [correcting, setCorrecting] = useState(null); // entry publicId
  const [correction, setCorrection] = useState({ components: {}, total: '' });
  const currentUserId = authService.getCurrentUser()?.id;

  const load = useCallback(() => {
    setLoading(true);
    icuService
      .getScoreChart(admissionId)
      .catch(() => null)
      .then(setChart)
      .finally(() => setLoading(false));
  }, [admissionId]);

  useEffect(() => {
    if (admissionId) load();
  }, [admissionId, load, refreshKey]);

  // Default to the first enabled type once the catalogue arrives.
  useEffect(() => {
    if (loaded && !scoreType && types.length > 0) setScoreType(types[0].key);
  }, [loaded, types, scoreType]);

  const entries = chart?.entries || [];
  /** Every type the API knows, enabled or not, so a disabled score's history still has a label. */
  const allTypes = useMemo(() => chart?.types || [], [chart]);
  const supersededIds = useMemo(() => new Set(chart?.supersededIds || []), [chart]);

  const typeOf = useCallback(
    (key) => allTypes.find((t) => t.key === key) || types.find((t) => t.key === key) || null,
    [allTypes, types]
  );

  const selected = types.find((t) => t.key === scoreType) || null;

  const latest = useMemo(() => {
    const out = [];
    // Newest first, so the first surviving row per type is that type's latest.
    entries.forEach((e) => {
      if (supersededIds.has(e.id)) return;
      if (!out.some((x) => x.scoreType === e.scoreType)) out.push(e);
    });
    return out;
  }, [entries, supersededIds]);

  /**
   * The running total shown beside the inputs: the sum of the boxes on screen. Displaying it is
   * the same arithmetic the server performs, not a second opinion — the server's figure is what
   * gets stored.
   */
  const sumOf = (map) =>
    Object.values(map).reduce((acc, v) => {
      const n = Number(v);
      return acc + (Number.isFinite(n) && String(v).trim() !== '' ? n : 0);
    }, 0);

  const nonEmpty = (map) =>
    Object.fromEntries(
      Object.entries(map).filter(([, v]) => v !== '' && v !== null && v !== undefined)
    );

  const submit = async () => {
    if (!selected) return;
    setSubmitting(true);
    try {
      await icuService.recordScore({
        ipdAdmissionId: admissionId,
        scoreType: selected.key,
        components: selected.totalOnly ? {} : nonEmpty(components),
        totalScore: selected.totalOnly ? Number(total) : null,
      });
      success(`${selected.label} recorded`);
      setComponents({});
      setTotal('');
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the score');
    } finally {
      setSubmitting(false);
    }
  };

  const submitCorrection = async (entry) => {
    const type = typeOf(entry.scoreType);
    setSubmitting(true);
    try {
      await icuService.correctScore(entry.publicId, {
        components: type?.totalOnly ? {} : nonEmpty(correction.components),
        totalScore: type?.totalOnly ? Number(correction.total) : null,
      });
      success('Correction recorded — the original scoring is preserved');
      setCorrecting(null);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the correction');
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * A correction is offered only for a scoring this user recorded and that nothing has already
   * superseded — the same rule the server enforces, so the button is not an invitation to a
   * request that will be refused.
   */
  const canCorrect = (e) =>
    !readOnly &&
    !supersededIds.has(e.id) &&
    (currentUserId == null || e.recordedByUserId === currentUserId);

  // ── generic component inputs, driven by the type ─────────────────────────
  const renderComponentInputs = (type, map, onChange, idPrefix) => (
    <div className="mt-3 grid grid-cols-2 sm:grid-cols-3 gap-3">
      {type.components.map((c) => (
        <div key={c.key}>
          <label
            htmlFor={`${idPrefix}-${c.key}`}
            className="block text-xs font-medium text-gray-600 mb-1"
          >
            {c.label} ({c.min}–{c.max})
          </label>
          <input
            id={`${idPrefix}-${c.key}`}
            type="number"
            min={c.min}
            max={c.max}
            step="1"
            value={map[c.key] ?? ''}
            onChange={(e) => onChange(c.key, e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
        </div>
      ))}
    </div>
  );

  /** One scoring's components, labelled from the type it was recorded under. */
  const renderEntryComponents = (entry) => {
    const type = typeOf(entry.scoreType);
    const keys = Object.keys(entry.components || {});
    if (keys.length === 0) return null;
    return (
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {keys.map((key) => {
          const c = (type?.components || []).find((x) => x.key === key);
          return (
            <span key={key} className="text-sm text-gray-900">
              <span className="text-gray-500">{c?.label || key}:</span>{' '}
              <span className="font-medium">{String(entry.components[key])}</span>
            </span>
          );
        })}
      </div>
    );
  };

  const renderEntry = (e) => {
    const type = typeOf(e.scoreType);
    return (
      <li
        key={e.publicId || e.id}
        className={`px-5 py-3 ${supersededIds.has(e.id) ? 'opacity-60 line-through' : ''}`}
      >
        <div className="flex items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2 min-w-0">
            <span className="text-xs font-semibold text-gray-500">{fmt(e.scoredAt)}</span>
            <span className="inline-flex items-center px-2 py-1 rounded text-xs font-medium bg-indigo-100 text-indigo-700">
              {type?.label || e.scoreType}
            </span>
            <span className="text-sm text-gray-900">
              <span className="text-gray-500">Total:</span>{' '}
              <span className="font-semibold">{e.totalScore}</span>
            </span>
            {e.supersedesScoreId && (
              <span className="text-[11px] font-semibold text-blue-700 bg-blue-50 border border-blue-200 rounded px-2 py-0.5">
                Correction
              </span>
            )}
            {supersededIds.has(e.id) && (
              <span className="text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                Superseded
              </span>
            )}
            {type && type.enabled === false && (
              <span
                className="text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5"
                title="This score is no longer recorded here. What was charted at the time is kept."
              >
                no longer recorded
              </span>
            )}
          </div>
          {canCorrect(e) && correcting !== e.publicId && (
            <button
              type="button"
              onClick={() => {
                setCorrecting(e.publicId);
                setCorrection({
                  components: Object.fromEntries(
                    Object.entries(e.components || {}).map(([k, v]) => [k, String(v)])
                  ),
                  total: String(e.totalScore ?? ''),
                });
              }}
              className="shrink-0 text-xs font-semibold text-primary-700 hover:underline"
            >
              Correct
            </button>
          )}
        </div>

        {renderEntryComponents(e)}
        {e.note && <p className="text-xs text-gray-500 mt-1">{e.note}</p>}

        {correcting === e.publicId && (
          <div className="mt-3 bg-gray-50 border border-gray-200 rounded-lg p-3 no-underline">
            {type && !type.totalOnly ? (
              <>
                {renderComponentInputs(
                  type,
                  correction.components,
                  (k, v) =>
                    setCorrection((c) => ({ ...c, components: { ...c.components, [k]: v } })),
                  `score-correct-${e.publicId}`
                )}
                <p className="mt-2 text-sm text-gray-700">
                  Corrected total:{' '}
                  <span className="font-semibold">{sumOf(correction.components)}</span>
                </p>
              </>
            ) : (
              <div>
                <label
                  htmlFor={`score-correct-total-${e.publicId}`}
                  className="block text-xs font-medium text-gray-600 mb-1"
                >
                  Corrected total
                </label>
                <input
                  id={`score-correct-total-${e.publicId}`}
                  type="number"
                  step="1"
                  value={correction.total}
                  onChange={(ev) => setCorrection((c) => ({ ...c, total: ev.target.value }))}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-32"
                />
              </div>
            )}
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => submitCorrection(e)}
                disabled={submitting}
                className="px-4 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
              >
                {submitting ? 'Saving…' : 'Save correction'}
              </button>
              <button
                type="button"
                onClick={() => setCorrecting(null)}
                className="px-3 py-2 rounded-lg text-sm text-gray-600 hover:underline"
              >
                Cancel
              </button>
              <p className="w-full text-[11px] text-gray-500">
                The original scoring stays on the chart, struck through. Only this corrected scoring
                counts as what was recorded.
              </p>
            </div>
          </div>
        )}
      </li>
    );
  };

  if (loading && !chart) return <LoadingSpinner />;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-bold text-gray-800 text-sm">Severity Scores</h3>
        <p className="text-[11px] text-gray-500">
          Scores as recorded by a clinician. GCS is on the Vitals tab.
        </p>
      </div>

      {readOnly && (
        <div className="text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {latest.length === 0 ? (
          <div className="bg-white border border-gray-200 rounded-xl p-4 text-sm text-gray-500">
            Nothing recorded yet.
          </div>
        ) : (
          latest.map((e) => (
            <div key={e.publicId} className="bg-white border border-gray-200 rounded-xl p-4">
              <div className="text-xs font-medium text-gray-500 uppercase tracking-wide">
                Latest {typeOf(e.scoreType)?.label || e.scoreType}
              </div>
              <div className="mt-1 text-2xl font-semibold text-gray-900">{e.totalScore}</div>
              <div className="text-xs text-gray-500">{fmt(e.scoredAt)}</div>
            </div>
          ))
        )}
      </div>

      {!readOnly && (
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="font-bold text-gray-800 text-sm mb-4">Record a Score</h4>

          {!loaded ? (
            <p className="text-xs text-gray-500">Loading score types…</p>
          ) : types.length === 0 ? (
            <p className="text-xs text-gray-500">
              No severity scores are switched on. An administrator can enable them in Settings.
            </p>
          ) : (
            <>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div>
                  <label
                    htmlFor="score-type"
                    className="block text-xs font-medium text-gray-600 mb-1"
                  >
                    Score
                  </label>
                  <select
                    id="score-type"
                    value={scoreType}
                    onChange={(e) => {
                      setScoreType(e.target.value);
                      setComponents({});
                      setTotal('');
                    }}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                  >
                    {types.map((t) => (
                      <option key={t.key} value={t.key}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                </div>
                {selected?.totalOnly && (
                  <div>
                    <label
                      htmlFor="score-total"
                      className="block text-xs font-medium text-gray-600 mb-1"
                    >
                      Total ({selected.totalMin}–{selected.totalMax})
                    </label>
                    <input
                      id="score-total"
                      type="number"
                      min={selected.totalMin}
                      max={selected.totalMax}
                      step="1"
                      value={total}
                      onChange={(e) => setTotal(e.target.value)}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                    />
                  </div>
                )}
              </div>

              {selected && !selected.totalOnly && (
                <>
                  {renderComponentInputs(
                    selected,
                    components,
                    (k, v) => setComponents((x) => ({ ...x, [k]: v })),
                    'score-new'
                  )}
                  <p className="mt-3 text-sm text-gray-700">
                    Total: <span className="font-semibold">{sumOf(components)}</span>
                  </p>
                </>
              )}

              <div className="mt-4 flex items-center justify-between gap-3">
                <p className="text-[11px] text-gray-500">
                  The total is the sum of the components you enter. Nothing is derived from vitals,
                  fluids, infusions, ventilator settings or labs.
                </p>
                <button
                  type="button"
                  onClick={submit}
                  disabled={submitting}
                  className="px-5 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
                >
                  {submitting ? 'Saving…' : 'Record'}
                </button>
              </div>
            </>
          )}
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <h4 className="font-bold text-gray-800 text-sm px-5 pt-5 pb-3">History</h4>
        {entries.length === 0 ? (
          <EmptyState
            icon={null}
            title="No scores"
            message="No severity score has been recorded for this admission."
          />
        ) : (
          <ul className="divide-y divide-gray-100">{entries.map(renderEntry)}</ul>
        )}
      </div>
    </div>
  );
};

export default SeverityScorePanel;
