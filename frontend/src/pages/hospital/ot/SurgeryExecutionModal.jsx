import React, { useEffect, useState, useCallback } from 'react';
import { useToast } from '../../../context/ToastContext';
import useOtPermissions from '../../../hooks/useOtPermissions';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * SurgeryExecutionModal - the theatre-side of a case: WHO checklist, clinical milestones
 * and the operative note.
 *
 * The three WHO phases are one-way, ordered signatures. With a blocking policy the server
 * refuses to start a case whose Time-Out is unsigned.
 *
 * This screen used to say it "does not decide access" and render every control enabled for
 * whoever could open it. Reception can open it — it is mounted on the reception dashboard — but
 * reception does not hold OT_TIME_OUT, which is a theatre-team permission. So the Sign buttons
 * looked available, were pressed, and returned Access Denied: the screen offered an action the
 * caller was never allowed to take. Each control is now gated on the permission its own endpoint
 * requires, and where a permission is missing the control is replaced by a line saying who does
 * hold it, rather than by a failure after the fact.
 */
const MILESTONES = [
  ['PATIENT_ENTERED_OT', 'Patient entered OT'],
  ['ANAESTHESIA_START', 'Anaesthesia start'],
  ['INCISION', 'Incision'],
  ['CLOSURE', 'Closure'],
  ['ANAESTHESIA_END', 'Anaesthesia end'],
  ['LEFT_THEATRE', 'Left theatre'],
];

const fmt = (dt) =>
  dt ? new Date(dt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : null;

const SurgeryExecutionModal = ({ surgery, onClose }) => {
  const { success, error: toastError } = useToast();
  const sid = surgery.surgeryId;
  const [who, setWho] = useState(null);
  const [milestones, setMilestones] = useState([]);
  const [note, setNote] = useState(surgery.operativeNote || '');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [c, m] = await Promise.all([
        otService.getWhoChecklist(sid).catch(() => null),
        otService.getMilestones(sid).catch(() => []),
      ]);
      setWho(c);
      setMilestones(Array.isArray(m) ? m : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load execution details');
    } finally {
      setLoading(false);
    }
  }, [sid, toastError]);

  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const { can, loaded: permsLoaded } = useOtPermissions();
  // Each control mirrors exactly what its endpoint asks for; see SurgeryExecutionController.
  const canSign = can('OT_TIME_OUT');
  const canRecordMilestone = can('OT_START') || can('OT_COMPLETE') || can('OT_PRE_OP');
  const canWriteNote = can('OT_COMPLETE');

  const signPhase = async (phase, payload) => {
    setBusy(true);
    try {
      setWho(await otService.signWhoPhase(sid, phase, payload));
      success(`${phase.replace('_', ' ')} signed`);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to sign');
    } finally {
      setBusy(false);
    }
  };

  const record = async (milestone) => {
    setBusy(true);
    try {
      const created = await otService.recordMilestone(sid, { milestone });
      setMilestones((prev) => [...prev, created]);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to record');
    } finally {
      setBusy(false);
    }
  };

  const saveNote = async () => {
    setBusy(true);
    try {
      await otService.saveOperativeNote(sid, note);
      success('Operative note saved');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save note');
    } finally {
      setBusy(false);
    }
  };

  const done = (m) => milestones.find((x) => x.milestone === m);

  const phaseRow = (phase, label, at, enabled, payload) => (
    <div className="flex items-center justify-between py-2">
      <span className="text-sm font-medium text-gray-800">{label}</span>
      {at ? (
        <span className="text-xs text-emerald-700">Signed {fmt(at)}</span>
      ) : !canSign ? (
        <span className="text-xs text-gray-500">Signed by the theatre team</span>
      ) : (
        <button
          disabled={busy || !enabled}
          onClick={() => signPhase(phase, payload)}
          className={`px-3 py-1 rounded-lg text-xs font-semibold text-white ${enabled ? 'bg-emerald-600 hover:bg-emerald-700' : 'bg-gray-300 cursor-not-allowed'}`}
        >
          Sign
        </button>
      )}
    </div>
  );

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-xl my-8">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Theatre Execution</h2>
            <p className="text-xs text-gray-400">
              {surgery.patientName} · {surgery.procedureName}
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        {loading || !permsLoaded ? (
          <div className="px-6 py-8 text-center text-gray-400">Loading…</div>
        ) : (
          <div className="px-6 py-4 space-y-5">
            <div className="border border-gray-200 rounded-lg p-3">
              <h3 className="text-sm font-semibold text-gray-900 mb-1">
                WHO Surgical Safety Checklist
              </h3>
              <div className="divide-y divide-gray-100">
                {phaseRow('SIGN_IN', 'Sign-In (before induction)', who?.signInAt, true, {
                  siteMarked: true,
                })}
                {phaseRow(
                  'TIME_OUT',
                  'Time-Out (before incision)',
                  who?.timeOutAt,
                  !!who?.signInAt
                )}
                {phaseRow(
                  'SIGN_OUT',
                  'Sign-Out (counts correct)',
                  who?.signOutAt,
                  !!who?.timeOutAt,
                  { countsCorrect: true }
                )}
              </div>
            </div>

            <div className="border border-gray-200 rounded-lg p-3">
              <h3 className="text-sm font-semibold text-gray-900 mb-2">Milestones</h3>
              {!canRecordMilestone && (
                <p className="text-xs text-gray-500 mb-2">
                  Recorded by the theatre team. You can see them here as they happen.
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                {MILESTONES.map(([code, label]) => {
                  const d = done(code);
                  return (
                    <button
                      key={code}
                      disabled={busy || !!d || !canRecordMilestone}
                      title={canRecordMilestone ? undefined : 'Recorded by the theatre team'}
                      onClick={() => record(code)}
                      className={`px-3 py-1 rounded-full text-xs font-medium border ${d ? 'bg-gray-100 text-gray-500 border-gray-200' : 'bg-white text-gray-700 border-gray-300 hover:bg-gray-50'}`}
                    >
                      {d ? `✓ ${label}` : `+ ${label}`}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="border border-gray-200 rounded-lg p-3">
              <h3 className="text-sm font-semibold text-gray-900 mb-2">Operative Note</h3>
              <textarea
                rows={4}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                readOnly={!canWriteNote}
                placeholder={
                  canWriteNote
                    ? 'Findings, procedure performed, closure, blood loss…'
                    : 'Written by the operating surgeon.'
                }
                className={`w-full px-3 py-2 border border-gray-300 rounded-lg text-sm ${canWriteNote ? '' : 'bg-gray-50 text-gray-600'}`}
              />
              {canWriteNote && (
              <div className="flex justify-end mt-2">
                <button
                  disabled={busy || !note.trim()}
                  onClick={saveNote}
                  className="px-4 py-2 rounded-lg text-xs font-semibold bg-gray-900 text-white hover:bg-gray-800 disabled:bg-gray-300"
                >
                  Save note
                </button>
              </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default SurgeryExecutionModal;
