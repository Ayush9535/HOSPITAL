import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import { extractApiError } from '../utils/apiError';
import { validateForm } from '../utils/validation';
import Button from './Button';
import PatientFormFields, { patientFormRules, stripPatientPayload } from './PatientFormFields';

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

    const validationErrors = validateForm(formData, patientFormRules);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      setIsSubmitting(false);
      return;
    }

    try {
      // Strip insurance field so it is not sent to backend/database
      const savePayload = stripPatientPayload(formData);

      let saved;
      if (isEdit) {
        saved = await hospitalService.updatePatient(formData.id, savePayload);
        success('Patient updated successfully');
        console.log('[PatientModal] Patient updated');
      } else {
        saved = await hospitalService.addPatient(savePayload);
        success('Patient added successfully');
        console.log('[PatientModal] Patient added, calling onSuccess');
      }
      // The saved patient is handed to the caller so a flow that needs it (the OPD
      // modal's "New Patient" option) can select it straight away. Callers that do
      // not take an argument are unaffected.
      onSuccess(saved);
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
          <PatientFormFields values={formData} errors={errors} onChange={handleChange} />

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
