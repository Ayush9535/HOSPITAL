import React, { useCallback, useEffect, useMemo, useState } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import useEnabledVentilatorParams from '../../../hooks/useEnabledVentilatorParams';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';

/**
 * VentilatorPanel - timed ventilator snapshots (ICU Phase 7).
 *
 * Renders entirely from the hospital's parameter catalogue: there is no parameter name anywhere in
 * this file. Adding a custom parameter in Settings must make it chartable here with no code
 * change, or the catalogue is decorative.
 *
 * Recording APPENDS. The previous snapshot stays on the chart, so "what was the vent set to at
 * 4 a.m.?" is answerable from what is displayed.
 *
 * D-5's guarantee, made visible: a parameter the hospital has since switched off vanishes from the
 * entry form but its recorded values stay on every historical row, marked "no longer charted".
 * Hiding them would be silent data loss.
 *
 * Values only — no derived figure, no threshold, no colour by value.
 */
const STATUSES = [
  ['INVASIVE', 'Invasive'],
  ['NIV', 'Non-invasive (NIV)'],
  ['OFF', 'Not ventilated'],
];

const STATUS_LABEL = Object.fromEntries(STATUSES);

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

/** Trims what the API returns: 60 stays 60, 7.50 reads as 7.5, free text passes through. */
const showValue = (raw) => {
  if (raw === null || raw === undefined) return '—';
  const n = Number(raw);
  return Number.isFinite(n) && String(raw).trim() !== '' ? String(n) : String(raw);
};

const VentilatorPanel = ({ admissionId, readOnly = false, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const { settings, observations, modes, loaded } = useEnabledVentilatorParams(refreshKey);
  const [chart, setChart] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState('INVASIVE');
  const [values, setValues] = useState({});
  const [correcting, setCorrecting] = useState(null); // entry publicId
  const [correction, setCorrection] = useState({ status: 'INVASIVE', values: {} });
  const currentUserId = authService.getCurrentUser()?.id;

  const load = useCallback(() => {
    setLoading(true);
    icuService
      .getVentilatorChart(admissionId)
      .catch(() => null)
      .then(setChart)
      .finally(() => setLoading(false));
  }, [admissionId]);

  useEffect(() => {
    if (admissionId) load();
  }, [admissionId, load, refreshKey]);

  const entries = chart?.entries || [];
  /** Definitions for every key any entry holds, resolved server-side from the live catalogue. */
  const definitions = useMemo(() => chart?.parameters || {}, [chart]);
  const supersededIds = useMemo(() => new Set(chart?.supersededIds || []), [chart]);

  const current = useMemo(
    () => entries.find((e) => !supersededIds.has(e.id)) || null,
    [entries, supersededIds]
  );

  const setValue = (key, v) => setValues((x) => ({ ...x, [key]: v }));

  const nonEmpty = (map) =>
    Object.fromEntries(
      Object.entries(map).filter(([, v]) => v !== '' && v !== null && v !== undefined)
    );

  const submit = async () => {
    setSubmitting(true);
    try {
      await icuService.recordVentilatorSetting({
        ipdAdmissionId: admissionId,
        ventilationStatus: status,
        values: status === 'OFF' ? {} : nonEmpty(values),
      });
      success('Ventilator entry recorded');
      setValues({});
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the ventilator entry');
    } finally {
      setSubmitting(false);
    }
  };

  const submitCorrection = async (entry) => {
    setSubmitting(true);
    try {
      await icuService.correctVentilatorSetting(entry.publicId, {
        ventilationStatus: correction.status,
        values: correction.status === 'OFF' ? {} : nonEmpty(correction.values),
      });
      success('Correction recorded — the original entry is preserved');
      setCorrecting(null);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the correction');
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * A correction is offered only for an entry this user recorded and that nothing has already
   * superseded — the same rule the server enforces, so the button is not an invitation to a
   * request that will be refused.
   */
  const canCorrect = (e) =>
    !readOnly &&
    !supersededIds.has(e.id) &&
    (currentUserId == null || e.recordedByUserId === currentUserId);

  // ── generic parameter input ──────────────────────────────────────────────
  const renderInput = (p, value, onChange, idPrefix) => {
    const id = `${idPrefix}-${p.key}`;
    if (p.valueType === 'MODE') {
      return (
        <select
          id={id}
          value={value ?? ''}
          onChange={(e) => onChange(p.key, e.target.value)}
          className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
        >
          <option value="">—</option>
          {modes.map((m) => (
            <option key={m.key} value={m.key}>
              {m.label}
            </option>
          ))}
        </select>
      );
    }
    return (
      <input
        id={id}
        type={p.valueType === 'NUMBER' ? 'number' : 'text'}
        step="any"
        value={value ?? ''}
        onChange={(e) => onChange(p.key, e.target.value)}
        className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
      />
    );
  };

  const renderGroup = (title, group, map, onChange, idPrefix) => {
    if (group.length === 0) return null;
    return (
      <div className="mt-4 first:mt-0">
        <p className="text-xs font-semibold text-gray-700 uppercase tracking-wide mb-2">{title}</p>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
          {group.map((p) => (
            <div key={p.key}>
              <label
                htmlFor={`${idPrefix}-${p.key}`}
                className="block text-xs font-medium text-gray-600 mb-1"
              >
                {p.displayName}
                {p.unit ? ` (${p.unit})` : ''}
              </label>
              {renderInput(p, map[p.key], onChange, idPrefix)}
            </div>
          ))}
        </div>
      </div>
    );
  };

  /** One entry's values, grouped by the category each key resolves to right now. */
  const renderEntryValues = (entry) => {
    const keys = Object.keys(entry.values || {});
    if (keys.length === 0) {
      return <p className="text-xs text-gray-500 mt-1">No parameters recorded.</p>;
    }
    return (
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {keys.map((key) => {
          const def = definitions[key] || {};
          const label = def.displayName || key;
          const raw = entry.values[key];
          const shown =
            def.valueType === 'MODE'
              ? (modes.find((m) => m.key === raw)?.label ?? String(raw))
              : showValue(raw);
          return (
            <span key={key} className="text-sm text-gray-900">
              <span className="text-gray-500">{label}:</span>{' '}
              <span className="font-medium">{shown}</span>
              {def.unit ? <span className="text-gray-500"> {def.unit}</span> : null}
              {def.enabled === false && (
                <span
                  className="ml-1 text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-1.5 py-0.5"
                  title="This parameter is no longer charted. The value recorded at the time is kept."
                >
                  no longer charted
                </span>
              )}
            </span>
          );
        })}
      </div>
    );
  };

  const renderEntry = (e) => (
    <li
      key={e.publicId || e.id}
      className={`px-5 py-3 ${supersededIds.has(e.id) ? 'opacity-60 line-through' : ''}`}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2 min-w-0">
          <span className="text-xs font-semibold text-gray-500">{fmt(e.observedAt)}</span>
          <span
            className={`inline-flex items-center px-2 py-1 rounded text-xs font-medium ${
              e.ventilationStatus === 'OFF'
                ? 'bg-gray-100 text-gray-700'
                : 'bg-teal-100 text-teal-700'
            }`}
          >
            {STATUS_LABEL[e.ventilationStatus] || e.ventilationStatus}
          </span>
          {e.supersedesSettingId && (
            <span className="text-[11px] font-semibold text-blue-700 bg-blue-50 border border-blue-200 rounded px-2 py-0.5">
              Correction
            </span>
          )}
          {supersededIds.has(e.id) && (
            <span className="text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
              Superseded
            </span>
          )}
        </div>
        {canCorrect(e) && correcting !== e.publicId && (
          <button
            type="button"
            onClick={() => {
              setCorrecting(e.publicId);
              setCorrection({
                status: e.ventilationStatus,
                values: Object.fromEntries(
                  Object.entries(e.values || {}).map(([k, v]) => [k, String(v)])
                ),
              });
            }}
            className="shrink-0 text-xs font-semibold text-primary-700 hover:underline"
          >
            Correct
          </button>
        )}
      </div>

      {renderEntryValues(e)}
      {e.note && <p className="text-xs text-gray-500 mt-1">{e.note}</p>}

      {correcting === e.publicId && (
        <div className="mt-3 bg-gray-50 border border-gray-200 rounded-lg p-3 no-underline">
          <div>
            <label
              htmlFor={`vent-correct-status-${e.publicId}`}
              className="block text-xs font-medium text-gray-600 mb-1"
            >
              Ventilation
            </label>
            <select
              id={`vent-correct-status-${e.publicId}`}
              value={correction.status}
              onChange={(ev) => setCorrection((c) => ({ ...c, status: ev.target.value }))}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
            >
              {STATUSES.map(([k, l]) => (
                <option key={k} value={k}>
                  {l}
                </option>
              ))}
            </select>
          </div>
          {correction.status !== 'OFF' && (
            <>
              {renderGroup(
                'Ventilator Settings',
                settings,
                correction.values,
                (k, v) => setCorrection((c) => ({ ...c, values: { ...c.values, [k]: v } })),
                `vent-correct-${e.publicId}`
              )}
              {renderGroup(
                'Ventilator Observations / Measurements',
                observations,
                correction.values,
                (k, v) => setCorrection((c) => ({ ...c, values: { ...c.values, [k]: v } })),
                `vent-correct-${e.publicId}`
              )}
            </>
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
              The original entry stays on the chart, struck through. Only this corrected entry
              counts as what was running.
            </p>
          </div>
        </div>
      )}
    </li>
  );

  if (loading && !chart) return <LoadingSpinner />;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-bold text-gray-800 text-sm">Ventilator</h3>
        <p className="text-[11px] text-gray-500">
          Recorded values only. Which parameters appear here is set in Settings.
        </p>
      </div>

      {readOnly && (
        <div className="text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl p-5">
        <div className="text-xs font-medium text-gray-500 uppercase tracking-wide">Current</div>
        {current ? (
          <>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <span className="text-lg font-semibold text-gray-900">
                {STATUS_LABEL[current.ventilationStatus] || current.ventilationStatus}
              </span>
              <span className="text-xs text-gray-500">{fmt(current.observedAt)}</span>
            </div>
            {renderEntryValues(current)}
          </>
        ) : (
          <div className="mt-1 text-sm text-gray-500">Nothing recorded yet.</div>
        )}
      </div>

      {!readOnly && (
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="font-bold text-gray-800 text-sm mb-4">Record Ventilator Entry</h4>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div>
              <label htmlFor="vent-status" className="block text-xs font-medium text-gray-600 mb-1">
                Ventilation
              </label>
              <select
                id="vent-status"
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              >
                {STATUSES.map(([k, l]) => (
                  <option key={k} value={k}>
                    {l}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {status === 'OFF' ? (
            <p className="mt-4 text-xs text-gray-500">
              Recording &ldquo;Not ventilated&rdquo; closes the current settings. Everything charted
              before it stays on the chart.
            </p>
          ) : !loaded ? (
            <p className="mt-4 text-xs text-gray-500">Loading parameters…</p>
          ) : settings.length === 0 && observations.length === 0 ? (
            <p className="mt-4 text-xs text-gray-500">
              No ventilator parameters are switched on. An administrator can enable them in
              Settings.
            </p>
          ) : (
            <>
              {renderGroup('Ventilator Settings', settings, values, setValue, 'vent-new')}
              {renderGroup(
                'Ventilator Observations / Measurements',
                observations,
                values,
                setValue,
                'vent-new'
              )}
            </>
          )}

          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={submit}
              disabled={submitting}
              className="px-5 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
            >
              {submitting ? 'Saving…' : 'Record'}
            </button>
          </div>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <h4 className="font-bold text-gray-800 text-sm px-5 pt-5 pb-3">History</h4>
        {entries.length === 0 ? (
          <EmptyState
            icon={null}
            title="No ventilator entries"
            message="Nothing has been recorded for this admission yet."
          />
        ) : (
          <ul className="divide-y divide-gray-100">{entries.map(renderEntry)}</ul>
        )}
      </div>
    </div>
  );
};

export default VentilatorPanel;
