import React, { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import useOtPermissions from '../../../hooks/useOtPermissions';
import otService from '../../../services/otService';
import { extractApiError } from '../../../utils/apiError';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * Record the anaesthetist's pre-operative decision.
 *
 * <p>The endpoint existed and nothing in the product could reach it, so a case could not be
 * cleared for theatre without calling the API by hand.
 *
 * <p>There is deliberately no default outcome. The four values are the domain's own, and the
 * server states plainly that it never infers clinical fitness — preselecting "Cleared" would let
 * a slip of the hand record a decision nobody made. Conditions are required for a conditional
 * clearance, which the server enforces and this form asks for up front rather than after a
 * rejected submission.
 */
const OUTCOMES = [
  ['CLEARED', 'Cleared'],
  ['CLEARED_WITH_CONDITIONS', 'Cleared with conditions'],
  ['DEFERRED', 'Deferred'],
  ['NOT_CLEARED', 'Not cleared'],
];

const AnaesthesiaClearanceModal = ({ surgery, onClose, onRecorded }) => {
  const { success, error: toastError } = useToast();
  const { can } = useOtPermissions();
  const canRecord = can('OT_ANAESTHESIA_CLEARANCE');

  const [outcome, setOutcome] = useState('');
  const [conditionsComments, setConditions] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState(null);

  const needsConditions = outcome === 'CLEARED_WITH_CONDITIONS';
  const canSubmit =
    canRecord && !saving && !!outcome && (!needsConditions || conditionsComments.trim().length > 0);

  const submit = async (e) => {
    e.preventDefault();
    setFormError(null);
    setSaving(true);
    try {
      await otService.recordAnaesthesiaClearance(surgery.publicId, {
        outcome,
        conditionsComments: conditionsComments.trim() || undefined,
      });
      success('Anaesthesia clearance recorded');
      // Refresh before closing, so the board the user returns to already reflects the decision.
      await onRecorded?.();
      onClose?.();
    } catch (err) {
      // The modal stays open with what was typed: a clearance reported as saved when it was not
      // is worse than one that has to be entered twice.
      setFormError(extractApiError(err, 'Could not record the clearance.'));
      toastError(extractApiError(err, 'Could not record the clearance.'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div role="dialog" aria-modal="true" aria-label="Anaesthesia clearance" className="bg-white rounded-2xl w-full max-w-md my-8">
        <div className="px-6 py-4 border-b border-gray-100">
          <h2 className="text-lg font-bold text-gray-900">Anaesthesia Clearance</h2>
          <p className="text-xs text-gray-400">
            {surgery.patientName} · {surgery.procedureName}
          </p>
        </div>

        <form onSubmit={submit} className="px-6 py-4 space-y-4">
          <fieldset>
            <legend className="text-sm font-semibold text-gray-800 mb-2">Outcome</legend>
            <div className="space-y-2">
              {OUTCOMES.map(([value, label]) => (
                <label key={value} className="flex items-center gap-2 text-sm text-gray-700">
                  <input
                    type="radio"
                    name="outcome"
                    value={value}
                    checked={outcome === value}
                    disabled={!canRecord}
                    onChange={(e) => setOutcome(e.target.value)}
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          <div>
            <label htmlFor="clearance-conditions" className="block text-xs font-semibold text-gray-700">
              Conditions / comments
              {needsConditions ? <span className="text-red-600"> *</span> : null}
            </label>
            <textarea
              id="clearance-conditions"
              rows={3}
              value={conditionsComments}
              disabled={!canRecord}
              onChange={(e) => setConditions(e.target.value)}
              className="mt-1 w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
            {needsConditions && conditionsComments.trim().length === 0 ? (
              <p className="mt-1 text-xs text-gray-500">
                A conditional clearance has to say what the conditions are.
              </p>
            ) : null}
          </div>

          {!canRecord ? (
            <p className="text-xs text-gray-500">
              Anaesthesia clearance is recorded by the anaesthetist or theatre incharge.
            </p>
          ) : null}

          {formError ? (
            <p role="alert" className="text-sm text-red-600">
              {formError}
            </p>
          ) : null}

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="px-4 py-2 text-sm rounded-lg border border-gray-300 text-gray-700 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!canSubmit}
              className="px-4 py-2 text-sm rounded-lg font-semibold bg-gray-900 text-white disabled:bg-gray-300 disabled:cursor-not-allowed"
            >
              {saving ? 'Recording…' : 'Record clearance'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default AnaesthesiaClearanceModal;
