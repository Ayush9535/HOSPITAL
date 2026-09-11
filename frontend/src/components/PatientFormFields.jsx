import React, { useId } from 'react';
import CharCountInput from './CharCountInput';
import DobPicker from './DobPicker';

/**
 * The patient form, as fields only — no modal chrome, no submit, no service call.
 *
 * Extracted from PatientModal so the OPD entry form can collect a new patient inline
 * (one form, one submit) while PatientModal keeps using the exact same fields. There is
 * deliberately only one definition of these inputs and one definition of the rules that
 * validate them, so the two entry points can never drift apart.
 *
 * Controlled: the caller owns `values` and `errors` and handles `onChange(field, value)`.
 */

/** The client rules for a patient, shared by every caller. Feed to validateForm(values, ...). */
export const patientFormRules = {
  name: ['required', 'name'],
  dateOfBirth: ['required', 'dob'],
  gender: ['required'],
  phone: ['required', 'phone'],
  email: ['email'], // optional but valid if present
};

/**
 * `insurance` is a UI-only field and must never reach the backend. Callers send
 * stripPatientPayload(values) rather than the raw form state.
 */
export const stripPatientPayload = (values) => {
  const { insurance: _insurance, ...payload } = values || {};
  return payload;
};

/** Whole years between a "YYYY-MM-DD" date of birth and today. */
const ageFrom = (dateOfBirth) => {
  const dob = new Date(dateOfBirth + 'T00:00:00');
  const now = new Date();
  const beforeBirthday =
    now.getMonth() < dob.getMonth() ||
    (now.getMonth() === dob.getMonth() && now.getDate() < dob.getDate());
  return Math.max(0, now.getFullYear() - dob.getFullYear() - (beforeBirthday ? 1 : 0));
};

const PatientFormFields = ({ values = {}, errors = {}, onChange }) => {
  // Unique per instance so an inline copy and a modal copy can never collide on the page.
  const genderId = useId();
  const insuranceId = useId();

  return (
    <>
      {/* Row: Name + Phone */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
        <CharCountInput
          label="Full Name"
          required
          value={values.name || ''}
          onChange={(e) => onChange('name', e.target.value)}
          maxLength={50}
          placeholder="Enter patient's full name"
          error={errors.name}
        />

        <CharCountInput
          label="Phone Number"
          required
          type="tel"
          value={values.phone || ''}
          onChange={(e) => onChange('phone', e.target.value)}
          maxLength={15}
          placeholder="Enter phone number"
          error={errors.phone}
          showCount={false}
        />
      </div>

      {/* Row: Date of Birth + Gender */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
        <div>
          <span className="block text-sm font-semibold text-neutral-700 mb-2">
            Date of Birth <span className="text-red-600">*</span>
          </span>
          <DobPicker
            value={values.dateOfBirth || ''}
            onChange={(v) => onChange('dateOfBirth', v)}
            hasError={!!errors.dateOfBirth}
          />
          {values.dateOfBirth && !errors.dateOfBirth && (
            <p className="text-neutral-500 text-xs mt-1">
              Age: {ageFrom(values.dateOfBirth)} years
            </p>
          )}
          {errors.dateOfBirth && (
            <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
              {errors.dateOfBirth}
            </p>
          )}
        </div>
        <div>
          <label htmlFor={genderId} className="block text-sm font-semibold text-neutral-700 mb-2">
            Gender <span className="text-red-600">*</span>
          </label>
          <select
            id={genderId}
            value={values.gender || ''}
            onChange={(e) => onChange('gender', e.target.value)}
            className={`input-field ${errors.gender ? 'border-error-300 focus:ring-error-500' : ''}`}
          >
            <option value="">Select gender</option>
            <option value="MALE">Male</option>
            <option value="FEMALE">Female</option>
            <option value="OTHER">Other</option>
          </select>
          {errors.gender && (
            <p className="text-red-600 text-sm mt-1 flex items-center gap-1">{errors.gender}</p>
          )}
        </div>
      </div>

      {/* Row: Email + Insurance */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
        <CharCountInput
          label="Email Address"
          type="email"
          value={values.email || ''}
          onChange={(e) => onChange('email', e.target.value)}
          maxLength={50}
          placeholder="Enter email address"
          error={errors.email}
        />
        <div>
          <label
            htmlFor={insuranceId}
            className="block text-sm font-semibold text-neutral-700 mb-2"
          >
            Insurance
          </label>
          <select
            id={insuranceId}
            value={values.insurance || 'NO'}
            onChange={(e) => onChange('insurance', e.target.value)}
            className="input-field cursor-pointer bg-neutral-50 border border-neutral-300 rounded-xl"
          >
            <option value="NO">No</option>
            <option value="YES">Yes</option>
          </select>
        </div>
      </div>

      {/* Address - Full width */}
      <CharCountInput
        label="Address"
        textarea
        rows={4}
        value={values.address || ''}
        onChange={(e) => onChange('address', e.target.value)}
        maxLength={500}
        placeholder="Enter complete address"
      />

      {/* Medical History - Full width */}
      <CharCountInput
        label="Medical History / Allergies"
        textarea
        rows={4}
        value={values.medicalHistory || ''}
        onChange={(e) => onChange('medicalHistory', e.target.value)}
        maxLength={500}
        placeholder="Any medical conditions, allergies, or important notes..."
      />
    </>
  );
};

export default PatientFormFields;
