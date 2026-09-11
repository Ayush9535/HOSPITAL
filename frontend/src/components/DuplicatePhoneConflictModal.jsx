import React from 'react';
import Button from './Button';

/**
 * The question the server refuses to answer on its own: this mobile number already belongs to a
 * patient here — is the person at the desk one of them, or somebody else on the same number?
 *
 * A parent and a child share one mobile. Guessing wrong attaches the child's appointment, case
 * and prescriptions to the parent's record, which is why neither the server nor this component
 * ever picks for the user. Every match is listed, in registration order, and both outcomes are
 * an explicit click:
 *
 *   • Use This Patient       — continue the workflow with that exact patient; creates nobody.
 *   • Register Different Patient — resubmit acknowledging the shared number; creates a second
 *                                  patient, and the server records who acknowledged what.
 *
 * The list carries only what identifies a person to a human — name, age, patient id. Not the
 * phone (the user just typed it), not the date of birth, address, email or history.
 */
const DuplicatePhoneConflictModal = ({
  isOpen,
  conflicts = [],
  onUseExisting,
  onRegisterDifferent,
  onCancel,
  busy = false,
}) => {
  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60] p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="duplicate-phone-title"
    >
      <div className="bg-white rounded-2xl shadow-organic w-full max-w-lg animate-scale-in overflow-hidden max-h-[90vh] flex flex-col">
        <div className="px-6 py-5 border-b border-gray-200">
          <h3 id="duplicate-phone-title" className="text-xl font-bold text-neutral-800">
            Mobile number already registered
          </h3>
          <p className="text-sm text-neutral-600 mt-1">
            This number belongs to {conflicts.length === 1 ? 'a patient' : 'patients'} already
            registered here. Choose the patient you are treating, or confirm this is a different
            person who shares the same number.
          </p>
        </div>

        <div className="px-6 py-4 space-y-3 overflow-auto">
          {conflicts.map((patient) => (
            <div
              key={patient.publicId || patient.id}
              className="flex items-center justify-between gap-4 border border-neutral-200 rounded-xl px-4 py-3"
            >
              <div className="min-w-0">
                <p className="font-semibold text-neutral-800 truncate">{patient.name}</p>
                <p className="text-sm text-neutral-600">
                  {patient.age !== null && patient.age !== undefined
                    ? `Age ${patient.age}`
                    : 'Age not recorded'}
                </p>
                <p className="text-sm text-neutral-500">
                  Patient ID {patient.customId || patient.publicId}
                </p>
              </div>
              <Button
                type="button"
                variant="primary"
                size="sm"
                disabled={busy}
                onClick={() => onUseExisting(patient)}
              >
                Use This Patient
              </Button>
            </div>
          ))}
        </div>

        <div className="px-6 py-4 border-t border-gray-200 flex flex-wrap justify-end gap-3">
          <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="button" variant="secondary" onClick={onRegisterDifferent} disabled={busy}>
            Register Different Patient
          </Button>
        </div>
      </div>
    </div>
  );
};

export default DuplicatePhoneConflictModal;
