import React, { useCallback, useEffect, useMemo, useState } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import authService from '../../../services/authService';
import icuService from '../../../services/icuService';

/**
 * IoChartPanel - fluid intake and output (ICU Phase 5).
 *
 * <p>Records the five columns the hospital's NABH chart already prints, and totals them. The
 * balance is whatever the API computed from the stored entries; this panel never adds up its own
 * numbers, so what is shown cannot drift from what is recorded.
 *
 * <p>D-2: this chart reads `icu_io_entry` ONLY. The urine figure on the Vitals tab is a separate
 * point-in-time observation and is deliberately not shown or summed here.
 *
 * <p>Values only — no target, no threshold, no colour by value. Whether a balance is acceptable
 * is a clinical judgement the chart does not make.
 */
const ROUTES = {
  INTAKE: [
    ['IV_FLUIDS', 'I.V. Fluids'],
    ['ORAL', 'Oral'],
  ],
  OUTPUT: [
    ['RYLES_ASPIRATION', 'Ryles Tube Aspiration'],
    ['URINE', 'Urine Output'],
    ['VOMIT', 'Vomiting'],
  ],
};

const ROUTE_LABEL = Object.fromEntries(
  [...ROUTES.INTAKE, ...ROUTES.OUTPUT].map(([k, l]) => [k, l])
);

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

const Total = ({ label, value, tone = 'default' }) => {
  const tones = {
    default: 'text-gray-900',
    intake: 'text-blue-700',
    output: 'text-amber-700',
  };
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4">
      <div className="text-xs font-medium text-gray-500 uppercase tracking-wide">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${tones[tone] || tones.default}`}>
        {value ?? 0} <span className="text-sm font-normal text-gray-500">mL</span>
      </div>
    </div>
  );
};

const emptyForm = { direction: 'INTAKE', route: 'IV_FLUIDS', volumeMl: '', notes: '' };

const IoChartPanel = ({ admissionId, readOnly = false, refreshKey = 0 }) => {
  const { success, error: toastError } = useToast();
  const [entries, setEntries] = useState([]);
  const [balance, setBalance] = useState(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState(emptyForm);
  // publicId of the entry being corrected, plus the replacement volume/note.
  const [correcting, setCorrecting] = useState(null);
  const [correction, setCorrection] = useState({ volumeMl: '', notes: '' });
  const currentUserId = authService.getCurrentUser()?.id;

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      icuService.getIoEntries(admissionId).catch(() => []),
      icuService.getIoBalance(admissionId).catch(() => null),
    ])
      .then(([e, b]) => {
        setEntries(Array.isArray(e) ? e : []);
        setBalance(b);
      })
      .finally(() => setLoading(false));
  }, [admissionId]);

  useEffect(() => {
    if (admissionId) load();
  }, [admissionId, load, refreshKey]);

  // An entry replaced by a later correction. It stays visible, struck through — hiding it would
  // lose the original value, which is the whole point of the append-only model.
  const supersededIds = useMemo(
    () => new Set(entries.map((e) => e.supersedesIoEntryId).filter((id) => id != null)),
    [entries]
  );

  const setField = (k, v) =>
    setForm((f) => {
      if (k !== 'direction') return { ...f, [k]: v };
      // Switching direction must also move to a route that belongs to it.
      return { ...f, direction: v, route: ROUTES[v][0][0] };
    });

  const submit = async () => {
    const volume = Number(form.volumeMl);
    if (!Number.isFinite(volume) || volume <= 0) {
      toastError('Enter a volume greater than zero');
      return;
    }
    setSubmitting(true);
    try {
      await icuService.recordIoEntry({
        ipdAdmissionId: admissionId,
        direction: form.direction,
        route: form.route,
        volumeMl: volume,
        notes: form.notes || null,
      });
      success('I/O entry recorded');
      setForm(emptyForm);
      load();
    } catch (err) {
      toastError(err?.response?.data?.error || 'Failed to record I/O entry');
    } finally {
      setSubmitting(false);
    }
  };

  const submitCorrection = async (entry) => {
    const volume = Number(correction.volumeMl);
    if (!Number.isFinite(volume) || volume <= 0) {
      toastError('Enter a corrected volume greater than zero');
      return;
    }
    setSubmitting(true);
    try {
      await icuService.correctIoEntry(entry.publicId, {
        direction: entry.direction,
        route: entry.route,
        volumeMl: volume,
        notes: correction.notes || entry.notes || null,
      });
      success('Correction recorded — the original entry is preserved');
      setCorrecting(null);
      setCorrection({ volumeMl: '', notes: '' });
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

  if (loading && entries.length === 0) return <LoadingSpinner />;

  return (
    <fieldset disabled={readOnly} style={{ display: 'contents' }}>
      {readOnly && (
        <div className="mb-3 text-xs font-semibold text-amber-700 bg-amber-50 border border-amber-100 rounded-lg px-3 py-2">
          Read-only — editing this form is disabled for your role (Files &amp; Access).
        </div>
      )}

      <div className="space-y-5">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <Total label="Total Intake" value={balance?.totalIntakeMl} tone="intake" />
          <Total label="Total Output" value={balance?.totalOutputMl} tone="output" />
          <Total label="Net Balance" value={balance?.netBalanceMl} />
        </div>

        {!readOnly && (
          <div className="bg-white border border-gray-200 rounded-xl p-5">
            <h3 className="font-bold text-gray-800 text-sm mb-4">Record Intake / Output</h3>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <div>
                <label
                  htmlFor="io-direction"
                  className="block text-xs font-medium text-gray-600 mb-1"
                >
                  Direction
                </label>
                <select
                  id="io-direction"
                  value={form.direction}
                  onChange={(e) => setField('direction', e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                >
                  <option value="INTAKE">Intake</option>
                  <option value="OUTPUT">Output</option>
                </select>
              </div>
              <div>
                <label htmlFor="io-route" className="block text-xs font-medium text-gray-600 mb-1">
                  Type
                </label>
                <select
                  id="io-route"
                  value={form.route}
                  onChange={(e) => setField('route', e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                >
                  {ROUTES[form.direction].map(([k, l]) => (
                    <option key={k} value={k}>
                      {l}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor="io-volume" className="block text-xs font-medium text-gray-600 mb-1">
                  Volume (mL)
                </label>
                <input
                  id="io-volume"
                  type="number"
                  min="1"
                  value={form.volumeMl}
                  onChange={(e) => setField('volumeMl', e.target.value)}
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                />
              </div>
              <div>
                <label htmlFor="io-notes" className="block text-xs font-medium text-gray-600 mb-1">
                  Note (optional)
                </label>
                <input
                  id="io-notes"
                  value={form.notes}
                  onChange={(e) => setField('notes', e.target.value)}
                  placeholder="e.g. fluid name"
                  className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                />
              </div>
            </div>
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
          <h3 className="font-bold text-gray-800 text-sm px-5 pt-5 pb-3">Entries</h3>
          {entries.length === 0 ? (
            <EmptyState
              icon={null}
              title="No entries"
              message="No intake or output recorded yet."
            />
          ) : (
            <ul className="divide-y divide-gray-100">
              {entries.map((e) => (
                <li
                  key={e.publicId || e.id}
                  className={`px-5 py-3 ${supersededIds.has(e.id) ? 'opacity-60 line-through' : ''}`}
                >
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-xs font-semibold text-gray-500">{fmt(e.occurredAt)}</span>
                    <div className="flex gap-2">
                      {e.supersedesIoEntryId && (
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
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex flex-wrap items-center gap-2 min-w-0">
                      <span
                        className={`inline-flex items-center px-2 py-1 rounded text-xs font-medium ${
                          e.direction === 'INTAKE'
                            ? 'bg-blue-100 text-blue-700'
                            : 'bg-amber-100 text-amber-700'
                        }`}
                      >
                        {e.direction === 'INTAKE' ? 'Intake' : 'Output'}
                      </span>
                      <span className="text-sm text-gray-900">
                        {ROUTE_LABEL[e.route] || e.route}: {e.volumeMl} mL
                      </span>
                    </div>
                    {canCorrect(e) && correcting !== e.publicId && (
                      <button
                        type="button"
                        onClick={() => {
                          setCorrecting(e.publicId);
                          setCorrection({
                            volumeMl: String(e.volumeMl ?? ''),
                            notes: e.notes || '',
                          });
                        }}
                        className="shrink-0 text-xs font-semibold text-primary-700 hover:underline"
                      >
                        Correct
                      </button>
                    )}
                  </div>
                  {e.notes && <p className="text-xs text-gray-500 mt-1">{e.notes}</p>}

                  {correcting === e.publicId && (
                    <div className="mt-2 flex flex-wrap items-end gap-2 bg-gray-50 border border-gray-200 rounded-lg p-3">
                      <div>
                        <label
                          htmlFor={`io-correct-${e.publicId}`}
                          className="block text-xs font-medium text-gray-600 mb-1"
                        >
                          Corrected volume (mL)
                        </label>
                        <input
                          id={`io-correct-${e.publicId}`}
                          type="number"
                          min="1"
                          value={correction.volumeMl}
                          onChange={(ev) =>
                            setCorrection((c) => ({ ...c, volumeMl: ev.target.value }))
                          }
                          className="px-3 py-2 border border-gray-300 rounded-lg text-sm w-36"
                        />
                      </div>
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
                        The original entry stays on the chart, struck through. Only this corrected
                        value counts towards the balance.
                      </p>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </fieldset>
  );
};

export default IoChartPanel;
