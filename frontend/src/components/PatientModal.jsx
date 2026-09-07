import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import { validateForm } from '../utils/validation';
import Button from './Button';
import CharCountInput from './CharCountInput';
import DobPicker from './DobPicker';
import { extractApiError } from '../utils/apiError';

const PatientModal = ({ isOpen, onClose, onSuccess, initialData }) => {
  const [formData, setFormData] = useState({});
  const [errors, setErrors] = useState({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const { success, error: toastError } = useToast();
  const isEdit = !!initialData;

  useEffect(() => {
    if (isOpen) {
      if (initialData) {
        setFormData({ insurance: 'NO', ...initialData });
      } else {
        setFormData({ insurance: 'NO' });
      }
      setErrors({});
      setIsSubmitting(false);
    }
  }, [isOpen, initialData]);

  const handleChange = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: null }));
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setErrors({});
    setIsSubmitting(true);

    const rules = {
      name: ['required', 'name'],
      dateOfBirth: ['required', 'dob'],
      gender: ['required'],
      phone: ['required', 'phone'],
      email: ['email'], // optional but valid if present
    };

    const validationErrors = validateForm(formData, rules);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      setIsSubmitting(false);
      return;
    }

    try {
      // Strip insurance field so it is not sent to backend/database
      const { insurance, ...savePayload } = formData;

      if (isEdit) {
        await hospitalService.updatePatient(formData.id, savePayload);
        success('Patient updated successfully');
        console.log('[PatientModal] Patient updated');
      } else {
        const result = await hospitalService.addPatient(savePayload);
        success('Patient added successfully');
        console.log('[PatientModal] Patient added, calling onSuccess');
      }
      onSuccess();
      onClose();
    } catch (err) {
      console.error('Failed to save patient', err);
      toastError(extractApiError(err, 'Operation failed'));
    } finally {
      setIsSubmitting(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-2xl shadow-organic w-full max-w-3xl animate-scale-in overflow-hidden max-h-[90vh]">
        {/* Header */}
        <div className="bg-white px-8 py-6 border-b border-gray-200">
          <div className="flex justify-between items-center">
            <div>
              <h3 className="text-2xl font-bold text-neutral-800">
                {isEdit ? 'Edit Patient' : 'Add New Patient'}
              </h3>
              <p className="text-sm text-neutral-600 mt-1">
                {isEdit
                  ? 'Update patient information'
                  : 'Enter patient details to create a new record'}
              </p>
            </div>
            <button
              onClick={onClose}
              className="w-10 h-10 rounded-xl bg-white/80 hover:bg-white flex items-center justify-center text-neutral-400 hover:text-neutral-600 transition-all duration-200 hover:scale-105"
            >
              <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4 max-h-[76vh] overflow-auto">
          {/* Row: Name + Phone */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
            <CharCountInput
              label="Full Name"
              required
              value={formData.name || ''}
              onChange={(e) => handleChange('name', e.target.value)}
              maxLength={50}
              placeholder="Enter patient's full name"
              error={errors.name}
            />

            <CharCountInput
              label="Phone Number"
              required
              type="tel"
              value={formData.phone || ''}
              onChange={(e) => handleChange('phone', e.target.value)}
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
                value={formData.dateOfBirth || ''}
                onChange={(v) => handleChange('dateOfBirth', v)}
                hasError={!!errors.dateOfBirth}
              />
              {formData.dateOfBirth && !errors.dateOfBirth && (
                <p className="text-neutral-500 text-xs mt-1">
                  Age:{' '}
                  {Math.max(
                    0,
                    new Date().getFullYear() -
                      new Date(formData.dateOfBirth + 'T00:00:00').getFullYear() -
                      (new Date().getMonth() <
                        new Date(formData.dateOfBirth + 'T00:00:00').getMonth() ||
                      (new Date().getMonth() ===
                        new Date(formData.dateOfBirth + 'T00:00:00').getMonth() &&
                        new Date().getDate() <
                          new Date(formData.dateOfBirth + 'T00:00:00').getDate())
                        ? 1
                        : 0)
                  )}{' '}
                  years
                </p>
              )}
              {errors.dateOfBirth && (
                <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
                  {errors.dateOfBirth}
                </p>
              )}
            </div>
            <div>
              <label
                htmlFor="fld-10004"
                className="block text-sm font-semibold text-neutral-700 mb-2"
              >
                Gender <span className="text-red-600">*</span>
              </label>
              <select
                id="fld-10004"
                value={formData.gender || ''}
                onChange={(e) => handleChange('gender', e.target.value)}
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
              value={formData.email || ''}
              onChange={(e) => handleChange('email', e.target.value)}
              maxLength={50}
              placeholder="Enter email address"
              error={errors.email}
            />
            <div>
              <label
                htmlFor="fld-10003"
                className="block text-sm font-semibold text-neutral-700 mb-2"
              >
                Insurance
              </label>
              <select
                id="fld-10003"
                value={formData.insurance || 'NO'}
                onChange={(e) => handleChange('insurance', e.target.value)}
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
            value={formData.address || ''}
            onChange={(e) => handleChange('address', e.target.value)}
            maxLength={500}
            placeholder="Enter complete address"
          />

          {/* Medical History - Full width */}
          <CharCountInput
            label="Medical History / Allergies"
            textarea
            rows={4}
            value={formData.medicalHistory || ''}
            onChange={(e) => handleChange('medicalHistory', e.target.value)}
            maxLength={500}
            placeholder="Any medical conditions, allergies, or important notes..."
          />

          {/* Action Buttons */}
          <div className="flex gap-4 pt-4">
            <Button
              type="button"
              variant="secondary"
              onClick={onClose}
              className="flex-1"
              disabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button type="submit" variant="primary" className="flex-1" loading={isSubmitting}>
              {isEdit ? 'Update Patient' : 'Save Patient'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default PatientModal;
