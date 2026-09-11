import React, { useCallback, useEffect, useMemo, useState } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';

/**
 * InfusionPanel - continuous infusions and their rate history (ICU Phase 6).
 *
 * Sits inside the existing Medication workspace rather than on a page of its own: an infusion is
 * medication being given, and splitting it away from the MAR would make the nurse look in two
 * places for one patient's drugs.
 *
 * A titration APPENDS. The previous rate stays on the chart, so "what was it running at when the
 * BP dropped?" is answerable from what is displayed here.
 *
 * D-1: an infusion is drug delivery, never a fluid-balance event. Nothing shown here is counted
 * on the Intake / Output tab, and the panel says so rather than leaving the nurse to assume it.
 *
 * Values only — the rate is displayed in the unit it was entered in and is never converted, and
 * there is no maximum, no threshold and no colour by value.
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

/** Trims the DECIMAL(12,3) the API returns: 5.000 reads as 5, 0.050 as 0.05. */
const rateText = (rate, unitLabel) => {
  if (!rate) return '—';
  const n = Number(rate.rateValue);
  const value = Number.isFinite(n) ? String(n) : String(rate.rateValue);
  return `${value} ${unitLabel(rate.rateUnit)}`;
};

const emptyStart = { medicineName: '', rateValue: '', rateUnit: 'ML_HR' };

const InfusionPanel = ({ admissionId, readOnly = false, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [infusions, setInfusions] = useState([]);
  // publicId -> full rate history, newest first.
  const [rates, setRates] = useState({});
  const [units, setUnits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState(emptyStart);
  const [titrating, setTitrating] = useState(null); // infusion publicId
  const [titration, setTitration] = useState({ rateValue: '', rateUnit: 'ML_HR' });
  const [stopping, setStopping] = useState(null); // infusion publicId
  const [stopReason, setStopReason] = useState('');
  const [correcting, setCorrecting] = useState(null); // rate publicId
  const [correction, setCorrection] = useState({ rateValue: '', rateUnit: 'ML_HR' });
  const [expanded, setExpanded] = useState({}); // infusion publicId -> bool
  const currentUserId = authService.getCurrentUser()?.id;

  const unitLabel = useCallback(
    (key) => units.find((u) => u.key === key)?.label || key || '',
    [units]
  );

  const load = useCallback(() => {
    setLoading(true);
    icuService
      .getInfusions(admissionId)
      .catch(() => [])
      .then(async (list) => {
        const rows = Array.isArray(list) ? list : [];
        setInfusions(rows);
        const histories = await Promise.all(
          rows.map((i) => icuService.getInfusionRates(i.publicId).catch(() => []))
        );
        setRates(
          Object.fromEntries(
            rows.map((i, idx) => [i.publicId, Array.isArray(histories[idx]) ? histories[idx] : []])
          )
        );
      })
      .finally(() => setLoading(false));
  }, [admissionId]);

  useEffect(() => {
    if (admissionId) load();
  }, [admissionId, load, refreshKey]);

  useEffect(() => {
    icuService
      .getInfusionRateUnits()
      .then((u) => setUnits(Array.isArray(u) ? u : []))
      .catch(() => setUnits([]));
  }, []);

  // Rates replaced by a correction. They stay visible, struck through — hiding one would lose
  // the value that was originally charted, which is the point of the append-only model.
  const supersededIds = useMemo(() => {
    const s = new Set();
    Object.values(rates).forEach((history) =>
      history.forEach((r) => r.supersedesRateId != null && s.add(r.supersedesRateId))
    );
    return s;
  }, [rates]);

  /**
   * The rate in force: the newest row nothing has superseded. Selection, not calculation — the
   * same row the server would pick, chosen from the history already on screen.
   */
  const currentRateOf = useCallback(
    (infusion) => (rates[infusion.publicId] || []).find((r) => !supersededIds.has(r.id)) || null,
    [rates, supersededIds]
  );

  const running = infusions.filter((i) => !i.stoppedAt);
  const stopped = infusions.filter((i) => i.stoppedAt);

  const positive = (v) => {
    const n = Number(v);
    return Number.isFinite(n) && n > 0;
  };

  const submitStart = async () => {
    if (!form.medicineName.trim()) {
      toastError('Enter the drug name');
      return;
    }
    if (!positive(form.rateValue)) {
      toastError('Enter a rate greater than zero');
      return;
    }
    setSubmitting(true);
    try {
      await icuService.startInfusion({
        ipdAdmissionId: admissionId,
        medicineName: form.medicineName.trim(),
        rateValue: form.rateValue,
        rateUnit: form.rateUnit,
      });
      success('Infusion started');
      setForm(emptyStart);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to start the infusion');
    } finally {
      setSubmitting(false);
    }
  };

  const submitTitration = async (infusion) => {
    if (!positive(titration.rateValue)) {
      toastError('Enter a rate greater than zero');
      return;
    }
    setSubmitting(true);
    try {
      await icuService.titrateInfusion(infusion.publicId, {
        rateValue: titration.rateValue,
        rateUnit: titration.rateUnit,
      });
      success('Rate change recorded — the previous rate stays on the chart');
      setTitrating(null);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the rate change');
    } finally {
      setSubmitting(false);
    }
  };

  const submitStop = async (infusion) => {
    setSubmitting(true);
    try {
      await icuService.stopInfusion(infusion.publicId, { stopReason: stopReason || null });
      success('Infusion stopped');
      setStopping(null);
      setStopReason('');
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to stop the infusion');
    } finally {
      setSubmitting(false);
    }
  };

  const submitCorrection = async (rate) => {
    if (!positive(correction.rateValue)) {
      toastError('Enter a corrected rate greater than zero');
      return;
    }
    setSubmitting(true);
    try {
      await icuService.correctInfusionRate(rate.publicId, {
        rateValue: correction.rateValue,
        rateUnit: correction.rateUnit,
      });
      success('Correction recorded — the original rate is preserved');
      setCorrecting(null);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record the correction');
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * A correction is offered only for a rate this user recorded and that nothing has already
   * superseded — the same rule the server enforces, so the button is not an invitation to a
   * request that will be refused.
   */
  const canCorrect = (r) =>
    !readOnly &&
    !supersededIds.has(r.id) &&
    (currentUserId == null || r.recordedByUserId === currentUserId);

  // Render functions, not nested components: a nested component is a new type on every render,
  // so React would remount it and the open rate input would lose focus mid-keystroke.
  const renderRateHistory = (infusion) => {
    const history = rates[infusion.publicId] || [];
    if (history.length === 0) {
      return <p className="text-xs text-gray-500 px-5 pb-4">No rate recorded.</p>;
    }
    return (
      <ul className="divide-y divide-gray-100 border-t border-gray-100">
        {history.map((r) => (
          <li
            key={r.publicId || r.id}
            className={`px-5 py-2 ${supersededIds.has(r.id) ? 'opacity-60 line-through' : ''}`}
          >
            <div className="flex items-center justify-between gap-3">
              <div className="flex flex-wrap items-center gap-2 min-w-0">
                <span className="text-xs font-semibold text-gray-500">{fmt(r.effectiveFrom)}</span>
                <span className="text-sm text-gray-900">{rateText(r, unitLabel)}</span>
                {r.supersedesRateId && (
                  <span className="text-[11px] font-semibold text-blue-700 bg-blue-50 border border-blue-200 rounded px-2 py-0.5">
                    Correction
                  </span>
                )}
                {supersededIds.has(r.id) && (
                  <span className="text-[11px] font-semibold text-gray-500 bg-gray-100 border border-gray-200 rounded px-2 py-0.5">
                    Superseded
                  </span>
                )}
              </div>
              {canCorrect(r) && correcting !== r.publicId && (
                <button
                  type="button"
                  onClick={() => {
                    setCorrecting(r.publicId);
                    setCorrection({
                      rateValue: String(Number(r.rateValue)),
                      rateUnit: r.rateUnit,
                    });
                  }}
                  className="shrink-0 text-xs font-semibold text-primary-700 hover:underline"
                >
                  Correct
                </button>
              )}
            </div>

            {correcting === r.publicId && (
              <div className="mt-2 flex flex-wrap items-end gap-2 bg-gray-50 border border-gray-200 rounded-lg p-3 no-underline">
                <div>
                  <label
                    htmlFor={`inf-correct-${r.publicId}`}
                    className="block text-xs font-medium text-gray-600 mb-1"
                  >
                    Corrected rate
                  </label>
                  <input
                    id={`inf-correct-${r.publicId}`}
                    type="number"
                    min="0"
                    step="any"
                    value={correction.rateValue}
                    onChange={(ev) => setCorrection((c) => ({ ...c, rateValue: ev.target.value }))}
                    className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-32"
                  />
                </div>
                <div>
                  <label
                    htmlFor={`inf-correct-unit-${r.publicId}`}
                    className="block text-xs font-medium text-gray-600 mb-1"
                  >
                    Unit
                  </label>
                  <select
                    id={`inf-correct-unit-${r.publicId}`}
                    value={correction.rateUnit}
                    onChange={(ev) => setCorrection((c) => ({ ...c, rateUnit: ev.target.value }))}
                    className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
                  >
                    {units.map((u) => (
                      <option key={u.key} value={u.key}>
                        {u.label}
                      </option>
                    ))}
                  </select>
                </div>
                <button
                  type="button"
                  onClick={() => submitCorrection(r)}
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
                  The original rate stays on the chart, struck through, and is removed from the
                  timeline.
                </p>
              </div>
            )}
          </li>
        ))}
      </ul>
    );
  };

  const renderInfusionCard = (infusion) => {
    const current = currentRateOf(infusion);
    const isRunning = !infusion.stoppedAt;
    const open = !!expanded[infusion.publicId];
    return (
      <div
        key={infusion.publicId}
        className="bg-white border border-gray-200 rounded-xl overflow-hidden"
      >
        <div className="px-5 py-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h4 className="text-sm font-bold text-gray-900">{infusion.medicineName}</h4>
                <span
                  className={`text-[11px] font-semibold rounded px-2 py-0.5 border ${
                    isRunning
                      ? 'text-green-700 bg-green-50 border-green-200'
                      : 'text-gray-600 bg-gray-100 border-gray-200'
                  }`}
                >
                  {isRunning ? 'Running' : 'Stopped'}
                </span>
                {infusion.prescriptionId == null && (
                  <span className="text-[11px] font-semibold text-gray-500 bg-gray-50 border border-gray-200 rounded px-2 py-0.5">
                    No linked prescription
                  </span>
                )}
              </div>
              <p className="mt-1 text-xs text-gray-500">
                Started {fmt(infusion.startedAt)}
                {infusion.stoppedAt ? ` · Stopped ${fmt(infusion.stoppedAt)}` : ''}
                {infusion.stopReason ? ` · ${infusion.stopReason}` : ''}
              </p>
            </div>
            <div className="shrink-0 text-right">
              <div className="text-[11px] font-medium text-gray-500 uppercase tracking-wide">
                {isRunning ? 'Current rate' : 'Last rate'}
              </div>
              <div className="text-lg font-semibold text-gray-900">
                {rateText(current, unitLabel)}
              </div>
            </div>
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() =>
                setExpanded((e) => ({ ...e, [infusion.publicId]: !e[infusion.publicId] }))
              }
              className="text-xs font-semibold text-gray-600 hover:underline"
            >
              {open
                ? 'Hide rate history'
                : `Rate history (${(rates[infusion.publicId] || []).length})`}
            </button>
            {isRunning && !readOnly && (
              <>
                <button
                  type="button"
                  onClick={() => {
                    setTitrating(infusion.publicId);
                    setTitration({
                      rateValue: current ? String(Number(current.rateValue)) : '',
                      rateUnit: current?.rateUnit || 'ML_HR',
                    });
                    setStopping(null);
                  }}
                  className="text-xs font-semibold text-primary-700 hover:underline"
                >
                  Change rate
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setStopping(infusion.publicId);
                    setStopReason('');
                    setTitrating(null);
                  }}
                  className="text-xs font-semibold text-red-700 hover:underline"
                >
                  Stop
                </button>
              </>
            )}
          </div>

          {titrating === infusion.publicId && (
            <div className="mt-3 flex flex-wrap items-end gap-2 bg-gray-50 border border-gray-200 rounded-lg p-3">
              <div>
                <label
                  htmlFor={`inf-titrate-${infusion.publicId}`}
                  className="block text-xs font-medium text-gray-600 mb-1"
                >
                  New rate
                </label>
                <input
                  id={`inf-titrate-${infusion.publicId}`}
                  type="number"
                  min="0"
                  step="any"
                  value={titration.rateValue}
                  onChange={(ev) => setTitration((t) => ({ ...t, rateValue: ev.target.value }))}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-32"
                />
              </div>
              <div>
                <label
                  htmlFor={`inf-titrate-unit-${infusion.publicId}`}
                  className="block text-xs font-medium text-gray-600 mb-1"
                >
                  Unit
                </label>
                <select
                  id={`inf-titrate-unit-${infusion.publicId}`}
                  value={titration.rateUnit}
                  onChange={(ev) => setTitration((t) => ({ ...t, rateUnit: ev.target.value }))}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
                >
                  {units.map((u) => (
                    <option key={u.key} value={u.key}>
                      {u.label}
                    </option>
                  ))}
                </select>
              </div>
              <button
                type="button"
                onClick={() => submitTitration(infusion)}
                disabled={submitting}
                className="px-4 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
              >
                {submitting ? 'Saving…' : 'Record rate change'}
              </button>
              <button
                type="button"
                onClick={() => setTitrating(null)}
                className="px-3 py-2 rounded-lg text-sm text-gray-600 hover:underline"
              >
                Cancel
              </button>
            </div>
          )}

          {stopping === infusion.publicId && (
            <div className="mt-3 flex flex-wrap items-end gap-2 bg-gray-50 border border-gray-200 rounded-lg p-3">
              <div className="grow">
                <label
                  htmlFor={`inf-stop-${infusion.publicId}`}
                  className="block text-xs font-medium text-gray-600 mb-1"
                >
                  Reason (optional)
                </label>
                <input
                  id={`inf-stop-${infusion.publicId}`}
                  value={stopReason}
                  onChange={(ev) => setStopReason(ev.target.value)}
                  placeholder="e.g. weaned off"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                />
              </div>
              <button
                type="button"
                onClick={() => submitStop(infusion)}
                disabled={submitting}
                className="px-4 py-2 rounded-lg text-sm font-semibold bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
              >
                {submitting ? 'Saving…' : 'Stop infusion'}
              </button>
              <button
                type="button"
                onClick={() => setStopping(null)}
                className="px-3 py-2 rounded-lg text-sm text-gray-600 hover:underline"
              >
                Cancel
              </button>
            </div>
          )}
        </div>

        {open && renderRateHistory(infusion)}
      </div>
    );
  };

  if (loading && infusions.length === 0) return <LoadingSpinner />;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-bold text-gray-800 text-sm">Continuous Infusions</h3>
        <p className="text-[11px] text-gray-500">
          Drug delivery. Volumes here are not counted in the Intake / Output balance.
        </p>
      </div>

      {readOnly && (
        <div className="text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}

      {!readOnly && (
        <div className="bg-white border border-gray-200 rounded-xl p-5">
          <h4 className="font-bold text-gray-800 text-sm mb-4">Start an Infusion</h4>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div>
              <label
                htmlFor="inf-medicine"
                className="block text-xs font-medium text-gray-600 mb-1"
              >
                Drug
              </label>
              <input
                id="inf-medicine"
                value={form.medicineName}
                onChange={(e) => setForm((f) => ({ ...f, medicineName: e.target.value }))}
                placeholder="e.g. Noradrenaline"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              />
            </div>
            <div>
              <label htmlFor="inf-rate" className="block text-xs font-medium text-gray-600 mb-1">
                Rate
              </label>
              <input
                id="inf-rate"
                type="number"
                min="0"
                step="any"
                value={form.rateValue}
                onChange={(e) => setForm((f) => ({ ...f, rateValue: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              />
            </div>
            <div>
              <label htmlFor="inf-unit" className="block text-xs font-medium text-gray-600 mb-1">
                Unit
              </label>
              <select
                id="inf-unit"
                value={form.rateUnit}
                onChange={(e) => setForm((f) => ({ ...f, rateUnit: e.target.value }))}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
              >
                {units.map((u) => (
                  <option key={u.key} value={u.key}>
                    {u.label}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="mt-4 flex items-center justify-between gap-3">
            <p className="text-[11px] text-gray-500">
              The rate is recorded in the unit you choose and is never converted.
            </p>
            <button
              type="button"
              onClick={submitStart}
              disabled={submitting}
              className="px-5 py-2 rounded-lg text-sm font-semibold bg-primary-600 text-white hover:bg-primary-700 disabled:opacity-50"
            >
              {submitting ? 'Saving…' : 'Start'}
            </button>
          </div>
        </div>
      )}

      {infusions.length === 0 ? (
        <EmptyState
          icon={null}
          title="No infusions"
          message="No continuous infusion has been started for this admission."
        />
      ) : (
        <>
          <div className="space-y-3">
            {running.length === 0 ? (
              <p className="text-xs text-gray-500">No infusion is running.</p>
            ) : (
              running.map(renderInfusionCard)
            )}
          </div>

          {stopped.length > 0 && (
            <div className="space-y-3">
              <h4 className="font-bold text-gray-800 text-sm">Stopped</h4>
              {stopped.map(renderInfusionCard)}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default InfusionPanel;
