import React, { useCallback, useEffect, useState } from 'react';
import ConfirmationModal from './ConfirmationModal';
import hospitalService from '../services/hospitalService';
import { useToast } from '../context/ToastContext';
import { safeLoadMessage } from '../utils/apiError';
import { formatDate } from '../utils/date';

const BUCKETS = [
  { id: 'DUE_TODAY', label: 'Due Today' },
  { id: 'OVERDUE', label: 'Overdue' },
  { id: 'UPCOMING', label: 'Upcoming' },
];

/** The server's default overdue window; "Older" asks for a wider one. */
const WIDE_OVERDUE_DAYS = 730;

/**
 * Outstanding follow-ups, and the four things anyone does with one.
 *
 * <p>One component for reception, doctors and admins rather than three copies: the list is the
 * same list, and only the permitted actions differ. Which actions those are is decided by the
 * server — the buttons below mirror it so nobody is offered something that will be refused, but
 * hiding a button is a courtesy, not the control.
 *
 * <p>Nothing here mutates a row locally. Every action re-reads the list from the server after it
 * succeeds, because the backend is where the concurrency rules live and a guessed local state
 * would quietly disagree with them the moment two people work the same list.
 */
const FollowUpPanel = ({ role, mine = false, refreshKey }) => {
  const { success, error: toastError } = useToast();

  const [bucket, setBucket] = useState('DUE_TODAY');
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(null);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  const [showingOlder, setShowingOlder] = useState(false);
  const [pending, setPending] = useState(null); // { type, row }

  const canComplete = role === 'DOCTOR' || role === 'HOSPITAL_ADMIN';

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = await hospitalService.getFollowUps({
        timing: bucket,
        mine,
        overdueDays: bucket === 'OVERDUE' && showingOlder ? WIDE_OVERDUE_DAYS : undefined,
      });
      setRows(Array.isArray(data) ? data : []);
      setHasLoadedOnce(true);
    } catch (err) {
      // The previous rows stay on screen. An empty list and a failed request mean very
      // different things to someone deciding whether to chase a patient.
      setLoadError(safeLoadMessage(err, "Couldn't load follow-ups."));
    } finally {
      setLoading(false);
    }
  }, [bucket, mine, showingOlder]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  /** Runs an action, then re-reads. A 409 means someone else got there first. */
  const runAction = async (fn, successMessage) => {
    try {
      await fn();
      success(successMessage);
      await load();
    } catch (err) {
      if (err?.response?.status === 409) {
        toastError(safeLoadMessage(err, 'Someone else has already dealt with this follow-up.'));
        await load(); // never leave a stale action on screen
        return;
      }
      throw err; // ConfirmationModal shows it inline and keeps the dialog open
    }
  };

  const closePending = () => setPending(null);

  const timingLabel = (row) => {
    if (row.timing === 'DUE_TODAY') return 'Today';
    if (row.timing === 'OVERDUE') {
      const days = row.daysOverdue;
      return days === 1 ? '1 day overdue' : `${days} days overdue`;
    }
    return formatDate(row.followUpDate);
  };

  const body = () => {
    if (loading && !hasLoadedOnce) {
      return (
        <tr>
          <td colSpan={6} className="py-12 text-center text-gray-400" aria-busy="true">
            Loading follow-ups...
          </td>
        </tr>
      );
    }

    if (loadError && rows.length === 0) {
      return (
        <tr>
          <td colSpan={6} className="py-12 text-center" role="alert">
            <p className="text-sm font-bold text-gray-900">Couldn't load follow-ups</p>
            <p className="mt-1 text-sm text-gray-600">{loadError}</p>
            <p className="mt-1 text-xs text-gray-500">This is not the same as having none.</p>
            <button
              type="button"
              onClick={load}
              className="mt-3 px-4 py-2 text-xs font-black uppercase tracking-widest bg-gray-900 text-white rounded"
            >
              Retry
            </button>
          </td>
        </tr>
      );
    }

    if (rows.length === 0) {
      return (
        <tr>
          <td colSpan={6} className="py-12 text-center text-gray-500 text-sm">
            No {BUCKETS.find((b) => b.id === bucket)?.label.toLowerCase()} follow-ups.
          </td>
        </tr>
      );
    }

    return rows.map((row) => (
      <tr key={row.medicalRecordId} className="hover:bg-gray-50/50">
        <td className="px-4 py-3">
          <div className="font-bold text-gray-900">{row.patientName}</div>
          <div className="text-xs text-gray-500">
            {row.patientCustomId || row.patientPublicId}
            {row.patientPhone ? ` · ${row.patientPhone}` : ''}
          </div>
        </td>
        <td className="px-4 py-3 text-gray-600">{row.doctorName || '-'}</td>
        <td className="px-4 py-3 text-gray-600">{formatDate(row.followUpDate)}</td>
        <td className="px-4 py-3">
          <span
            className={`px-2 py-0.5 rounded text-[10px] font-black uppercase ${
              row.timing === 'OVERDUE'
                ? 'bg-red-100 text-red-700'
                : row.timing === 'DUE_TODAY'
                  ? 'bg-amber-100 text-amber-700'
                  : 'bg-gray-100 text-gray-600'
            }`}
          >
            {timingLabel(row)}
          </span>
        </td>
        <td className="px-4 py-3 text-gray-600 max-w-xs">
          <div className="truncate" title={row.followUpInstructions || ''}>
            {row.followUpInstructions || '-'}
          </div>
          {row.diagnosis ? (
            <div className="text-xs text-gray-400 truncate" title={row.diagnosis}>
              {row.diagnosis}
            </div>
          ) : null}
        </td>
        <td className="px-4 py-3">
          <div className="flex flex-wrap gap-2 justify-end">
            <button
              type="button"
              onClick={() => setPending({ type: 'arrive', row })}
              className="px-3 py-1.5 text-xs font-bold bg-gray-900 text-white rounded"
            >
              Patient Arrived
            </button>
            <button
              type="button"
              onClick={() => setPending({ type: 'reschedule', row })}
              className="px-3 py-1.5 text-xs font-bold border border-gray-300 rounded"
            >
              Reschedule
            </button>
            {canComplete ? (
              <button
                type="button"
                onClick={() => setPending({ type: 'complete', row })}
                className="px-3 py-1.5 text-xs font-bold border border-gray-300 rounded"
              >
                Complete
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => setPending({ type: 'cancel', row })}
              className="px-3 py-1.5 text-xs font-bold border border-red-300 text-red-700 rounded"
            >
              Cancel
            </button>
          </div>
        </td>
      </tr>
    ));
  };

  return (
    <div className="bg-white rounded-lg border border-gray-100">
      <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 border-b border-gray-100">
        <div className="flex gap-1" role="tablist" aria-label="Follow-up timing">
          {BUCKETS.map((b) => (
            <button
              key={b.id}
              type="button"
              role="tab"
              aria-selected={bucket === b.id}
              onClick={() => {
                setBucket(b.id);
                setShowingOlder(false);
              }}
              className={`px-3 py-1.5 text-xs font-black uppercase tracking-widest rounded ${
                bucket === b.id ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-50'
              }`}
            >
              {b.label}
            </button>
          ))}
        </div>

        {bucket === 'OVERDUE' ? (
          <button
            type="button"
            onClick={() => setShowingOlder((v) => !v)}
            className="text-xs font-bold text-gray-600 underline"
          >
            {showingOlder ? 'Show recent only' : 'Show older follow-ups'}
          </button>
        ) : null}
      </div>

      {/* A failed refresh keeps the rows it already had, and says so. */}
      {loadError && rows.length > 0 ? (
        <div className="px-4 py-2 bg-amber-50 border-b border-amber-100 text-xs text-amber-800" role="alert">
          Couldn't refresh follow-ups: {loadError} The list below may be out of date.{' '}
          <button type="button" onClick={load} className="underline font-bold">
            Retry
          </button>
        </div>
      ) : null}

      {bucket === 'OVERDUE' && !showingOlder ? (
        <p className="px-4 pt-2 text-xs text-gray-500">
          Showing the last 90 days. Older follow-ups are still recorded.
        </p>
      ) : null}

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
            <tr>
              <th className="px-4 py-3">Patient</th>
              <th className="px-4 py-3">Doctor</th>
              <th className="px-4 py-3">Follow-up date</th>
              <th className="px-4 py-3">When</th>
              <th className="px-4 py-3">Instructions</th>
              <th className="px-4 py-3 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">{body()}</tbody>
        </table>
      </div>

      <ConfirmationModal
        isOpen={pending?.type === 'arrive'}
        title="Patient arrived?"
        message={`This creates a new follow-up OPD for ${pending?.row?.patientName}, adds them to the queue, and applies the normal OPD billing. Only do this once the patient is actually here.`}
        onConfirm={() =>
          runAction(
            () => hospitalService.arriveFollowUp(pending.row.medicalRecordId),
            'Follow-up visit created',
          )
        }
        onCancel={closePending}
      />

      <ConfirmationModal
        isOpen={pending?.type === 'complete'}
        title="Complete follow-up?"
        message={`This closes the follow-up for ${pending?.row?.patientName} without creating an OPD visit. Use "Patient Arrived" instead if they have come in.`}
        onConfirm={(reason) =>
          runAction(
            () => hospitalService.completeFollowUp(pending.row.medicalRecordId, reason),
            'Follow-up completed',
          )
        }
        onCancel={closePending}
      />

      <ConfirmationModal
        isOpen={pending?.type === 'cancel'}
        title="Cancel follow-up?"
        message={`This calls off the follow-up for ${pending?.row?.patientName}. The original consultation and its date are kept.`}
        showReasonInput
        inputPlaceholder="Why is this follow-up being cancelled?"
        onConfirm={(reason) =>
          runAction(
            () => hospitalService.cancelFollowUp(pending.row.medicalRecordId, reason),
            'Follow-up cancelled',
          )
        }
        onCancel={closePending}
      />

      {pending?.type === 'reschedule' ? (
        <RescheduleModal
          row={pending.row}
          onCancel={closePending}
          onSubmit={(payload) =>
            runAction(
              () => hospitalService.rescheduleFollowUp(pending.row.medicalRecordId, payload),
              'Follow-up rescheduled',
            )
          }
        />
      ) : null}
    </div>
  );
};

/** Its own dialog because it needs a date, which ConfirmationModal does not collect. */
const RescheduleModal = ({ row, onCancel, onSubmit }) => {
  const today = new Date().toISOString().slice(0, 10);
  const [date, setDate] = useState(today);
  const [instructions, setInstructions] = useState(row.followUpInstructions || '');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const submit = async (e) => {
    e.preventDefault();
    if (submitting) return;
    if (!date) {
      setError('Choose a new follow-up date.');
      return;
    }
    // Checked here for a quick answer; the server enforces it regardless.
    if (date < today) {
      setError('A follow-up cannot be moved into the past.');
      return;
    }
    setSubmitting(true);
    setError('');
    try {
      await onSubmit({ newFollowUpDate: date, instructions });
      onCancel();
    } catch (err) {
      setError(safeLoadMessage(err, 'The follow-up could not be rescheduled.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60] p-4">
      <form
        onSubmit={submit}
        role="dialog"
        aria-modal="true"
        aria-label="Reschedule follow-up"
        className="bg-white rounded-lg p-6 w-full max-w-md"
      >
        <h3 className="text-lg font-bold text-gray-900">Reschedule follow-up</h3>
        <p className="mt-1 text-sm text-gray-600">
          {row.patientName} — currently due {formatDate(row.followUpDate)}
        </p>

        <label className="block mt-4 text-xs font-black uppercase tracking-widest text-gray-500">
          New follow-up date
          <input
            type="date"
            value={date}
            min={today}
            onChange={(e) => setDate(e.target.value)}
            className="mt-1 w-full border border-gray-300 rounded px-3 py-2 text-sm font-normal normal-case tracking-normal text-gray-900"
          />
        </label>

        <label className="block mt-3 text-xs font-black uppercase tracking-widest text-gray-500">
          Instructions
          <textarea
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            rows={3}
            className="mt-1 w-full border border-gray-300 rounded px-3 py-2 text-sm font-normal normal-case tracking-normal text-gray-900"
          />
        </label>

        {error ? (
          <p className="mt-3 text-sm text-red-700" role="alert">
            {error}
          </p>
        ) : null}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={submitting}
            className="px-4 py-2 text-sm font-bold border border-gray-300 rounded disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="px-4 py-2 text-sm font-bold bg-gray-900 text-white rounded disabled:opacity-50"
          >
            {submitting ? 'Rescheduling...' : 'Reschedule'}
          </button>
        </div>
      </form>
    </div>
  );
};

export default FollowUpPanel;
