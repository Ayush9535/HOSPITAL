import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import hospitalService from '../services/hospitalService';
import { availableSlotsFor } from '../utils/appointmentSlots';
import { validateForm } from '../utils/validation';
import DateSelect from './DateSelect';
import DobPicker from './DobPicker';

/**
 * AppointmentModal - Shared modal for creating appointments
 */
const AppointmentModal = ({ isOpen, onClose, onSuccess, doctors, patients }) => {
  const [formData, setFormData] = useState({});
  const [isNewPatient, setIsNewPatient] = useState(false);
  const [errors, setErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const { success, error: toastError } = useToast();

  // Custom Combobox State
  const [filteredPatients, setFilteredPatients] = useState([]);
  const [showPatientDropdown, setShowPatientDropdown] = useState(false);

  // Time Slots State
  const [selectedSlot, setSelectedSlot] = useState(null);
  const [bookedSlots, setBookedSlots] = useState([]);
  const [loadingSlots, setLoadingSlots] = useState(false);


  // Get today's date and current time. Use LOCAL date parts (not toISOString, which is UTC and
  // can be a day off) so "today" matches the clinic's calendar day for slot filtering + the
  // appointment-date floor.
  const today = new Date();
  const todayString = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  const currentTime =
    today.getHours().toString().padStart(2, '0') +
    ':' +
    today.getMinutes().toString().padStart(2, '0'); // HH:mm format

  // The bookable day, and the past-time filter, both live in utils/appointmentSlots.
  const availableSlots = availableSlotsFor(formData.appointmentDate, todayString, currentTime);

  // Reset when modal opens
  useEffect(() => {
    if (isOpen) {
      setFormData({});
      setIsNewPatient(false);
      setErrors({});
      setFilteredPatients([]);
      setSelectedSlot(null);
      setBookedSlots([]);
      if (patients) setFilteredPatients(patients);
    }
  }, [isOpen, patients]);

  // Auto-select doctor if only one is available
  useEffect(() => {
    if (isOpen && doctors && doctors.length === 1) {
      handleChange('doctorId', doctors[0].id);
    }
  }, [isOpen, doctors]);

  // Fetch slots when Doctor + Date are selected
  useEffect(() => {
    const fetchSlots = async () => {
      if (formData.doctorId && formData.appointmentDate) {
        setLoadingSlots(true);
        try {
          // Fetch all appointments for doctor to find booked slots
          // In a real app, we would have a specific endpoint for 'slots'
          const appointments = await hospitalService.getAppointmentsByDoctor(formData.doctorId);

          const booked = appointments
            .filter(
              (app) =>
                app.appointmentDate === formData.appointmentDate && app.status !== 'CANCELLED'
            )
            .map((app) => (app.appointmentTime ? app.appointmentTime.substring(0, 5) : null))
            .filter(Boolean);

          setBookedSlots(booked);
        } catch (err) {
          console.error('Failed to fetch slots', err);
        } finally {
          setLoadingSlots(false);
        }
      }
    };

    fetchSlots();
  }, [formData.doctorId, formData.appointmentDate]);

  const handleSlotSelect = (time) => {
    setSelectedSlot(time);
    handleChange('appointmentTime', time); // Send HH:mm format, backend expects LocalTime pattern "HH:mm"
    if (errors.appointmentTime) {
      setErrors((prev) => ({ ...prev, appointmentTime: null }));
    }
  };

  const handleChange = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: null }));
    }
  };

  const handlePatientSearch = (searchValue) => {
    handleChange('patientName', searchValue);

    if (!searchValue) {
      setFilteredPatients(patients || []);
      handleChange('patientId', null);
      setShowPatientDropdown(true);
      return;
    }

    const lowerTerm = searchValue.toLowerCase();
    const filtered = (patients || []).filter(
      (p) => p.name.toLowerCase().includes(lowerTerm) || (p.phone && p.phone.includes(lowerTerm))
    );
    setFilteredPatients(filtered);
    setShowPatientDropdown(true);

    // Auto-select exact match
    const exactMatch = filtered.find(
      (p) => `${p.name} - ${p.phone}` === searchValue || p.name === searchValue
    );
    if (exactMatch) {
      handleChange('patientId', exactMatch.id);
    } else {
      handleChange('patientId', null);
    }
  };

  const selectPatient = (patient) => {
    handleChange('patientName', `${patient.name} - ${patient.phone}`);
    handleChange('patientId', patient.id);
    setShowPatientDropdown(false);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (submitting) return;
    setErrors({});

    // Define validation rules
    const rules = {
      doctorId: ['required'],
      appointmentDate: ['required'],
      appointmentTime: ['required'],
    };

    if (isNewPatient) {
      Object.assign(rules, {
        patientName: ['required', 'name'],
        patientPhone: ['required', 'phone'],
        patientDateOfBirth: ['required', 'dob'],
        patientGender: ['required'],
      });
    } else {
      Object.assign(rules, {
        patientId: ['required'],
      });
    }

    // Run validation
    const validationErrors = validateForm(formData, rules);
    // An appointment can only be for today or a future date.
    if (formData.appointmentDate && formData.appointmentDate < todayString) {
      validationErrors.appointmentDate = 'Appointment date cannot be in the past';
    }
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    setSubmitting(true);
    try {
      await hospitalService.createAppointment({
        ...formData,
        doctorId: Number(formData.doctorId),
      });
      success('Appointment scheduled successfully');
      onSuccess();
      onClose();
    } catch (err) {
      // Never pass an object to the toast: validation failures come back as { errors: {field: msg} }
      // and rendering that object crashes React (#31). Extract a plain string in every shape.
      const data = err.response?.data;
      let errorMsg = 'Failed to create appointment';
      if (typeof data === 'string') errorMsg = data;
      else if (data?.error) errorMsg = data.error;
      else if (data?.message) errorMsg = data.message;
      else if (data?.errors && typeof data.errors === 'object')
        errorMsg = Object.values(data.errors).join(', ');
      toastError(errorMsg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleClose = () => {
    setIsNewPatient(false);
    setFormData({});
    setErrors({});
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-xl shadow-2xl w-full max-w-md p-6 m-4 max-h-[90vh] overflow-y-auto scrollbar-thin">
        <div className="flex justify-between items-center mb-6">
          <h3 className="text-xl font-bold text-gray-800">Add Appointment</h3>
          <button
            onClick={handleClose}
            aria-label="Close modal"
            className="text-gray-400 hover:text-gray-600 transition text-2xl leading-none"
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

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Toggle for Existing/New Patient */}
          <div className="mb-4 p-3 bg-gray-50 rounded-lg">
            <label className="flex items-center cursor-pointer" htmlFor="is-new-patient">
              <input
                id="is-new-patient"
                type="checkbox"
                checked={isNewPatient}
                onChange={(e) => setIsNewPatient(e.target.checked)}
                className="mr-3 h-4 w-4 text-primary-600 focus:ring-primary-500 border-gray-300 rounded"
              />
              <span className="text-sm font-medium text-gray-700">
                New Patient (not registered yet)
              </span>
            </label>
          </div>

          {/* Conditional Patient Fields */}
          {isNewPatient ? (
            <>
              <div>
                <label
                  className="block text-sm font-medium text-gray-700 mb-1"
                  htmlFor="patient-name"
                >
                  Patient Name <span className="text-red-600">*</span>
                </label>
                <input
                  id="patient-name"
                  type="text"
                  placeholder="Enter patient name"
                  value={formData.patientName || ''}
                  onChange={(e) => handleChange('patientName', e.target.value)}
                  className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientName ? 'border-red-500' : 'border-gray-300'}`}
                />
                {errors.patientName && (
                  <p className="text-red-500 text-xs mt-1">{errors.patientName}</p>
                )}
              </div>
              <div>
                <label
                  className="block text-sm font-medium text-gray-700 mb-1"
                  htmlFor="patient-phone"
                >
                  Patient Phone <span className="text-red-600">*</span>
                </label>
                <input
                  id="patient-phone"
                  type="tel"
                  placeholder="Enter 10-digit phone number"
                  value={formData.patientPhone || ''}
                  onChange={(e) => handleChange('patientPhone', e.target.value)}
                  className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientPhone ? 'border-red-500' : 'border-gray-300'}`}
                />
                {errors.patientPhone && (
                  <p className="text-red-500 text-xs mt-1">{errors.patientPhone}</p>
                )}
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label
                    className="block text-sm font-medium text-gray-700 mb-1"
                    htmlFor="patient-dob"
                  >
                    Date of Birth <span className="text-red-600">*</span>
                  </label>
                  <DobPicker
                    value={formData.patientDateOfBirth || ''}
                    onChange={(v) => handleChange('patientDateOfBirth', v)}
                    hasError={!!errors.patientDateOfBirth}
                  />
                  {errors.patientDateOfBirth && (
                    <p className="text-red-500 text-xs mt-1">{errors.patientDateOfBirth}</p>
                  )}
                </div>
                <div>
                  <label
                    className="block text-sm font-medium text-gray-700 mb-1"
                    htmlFor="patient-gender"
                  >
                    Gender <span className="text-red-600">*</span>
                  </label>
                  <select
                    id="patient-gender"
                    value={formData.patientGender || ''}
                    onChange={(e) => handleChange('patientGender', e.target.value)}
                    className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientGender ? 'border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Select</option>
                    <option value="Male">Male</option>
                    <option value="Female">Female</option>
                    <option value="Other">Other</option>
                  </select>
                  {errors.patientGender && (
                    <p className="text-red-500 text-xs mt-1">{errors.patientGender}</p>
                  )}
                </div>
              </div>
              <div>
                <label
                  className="block text-sm font-medium text-gray-700 mb-1"
                  htmlFor="patient-email"
                >
                  Email
                </label>
                <input
                  id="patient-email"
                  type="email"
                  placeholder="Enter email (optional)"
                  value={formData.patientEmail || ''}
                  onChange={(e) => handleChange('patientEmail', e.target.value)}
                  className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
                />
              </div>
            </>
          ) : (
            <div>
              <label
                className="block text-sm font-medium text-gray-700 mb-1"
                htmlFor="patient-search"
              >
                Patient <span className="text-red-600">*</span>
              </label>

              <div className="relative">
                <div className="relative">
                  <span className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-400">
                    <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth="2"
                        d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                      />
                    </svg>
                  </span>
                  <input
                    id="patient-search"
                    type="text"
                    placeholder="Search by name or phone..."
                    value={formData.patientName || ''}
                    onChange={(e) => handlePatientSearch(e.target.value)}
                    onFocus={() => setShowPatientDropdown(true)}
                    onBlur={() => setTimeout(() => setShowPatientDropdown(false), 200)}
                    className={`w-full pl-10 pr-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.patientId ? 'border-red-500' : 'border-gray-300'}`}
                    autoComplete="off"
                  />
                </div>

                {showPatientDropdown && (
                  <div className="absolute z-10 w-full mt-1 bg-white border border-gray-200 rounded-lg shadow-lg max-h-60 overflow-y-auto scrollbar-thin">
                    {filteredPatients.length > 0 ? (
                      <ul className="py-1">
                        {filteredPatients.map((p) => (
                          <li key={p.id}>
                            <button
                              type="button"
                              onMouseDown={() => selectPatient(p)}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter' || e.key === ' ') {
                                  e.preventDefault();
                                  selectPatient(p);
                                }
                              }}
                              className="w-full text-left px-4 py-2 hover:bg-primary-50 cursor-pointer transition-colors duration-150 flex flex-col group"
                            >
                              <span className="text-sm font-medium text-gray-900 group-hover:text-primary-700">
                                {p.name}
                              </span>
                              <span className="text-xs text-gray-500 flex justify-between">
                                <span>{p.phone}</span>
                                <span>
                                  {p.age} yrs / {p.gender}
                                </span>
                              </span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <div className="px-4 py-3 text-sm text-gray-500 text-center">
                        No patients found.
                        <button
                          onClick={() => setIsNewPatient(true)}
                          className="text-primary-600 hover:text-primary-800 font-medium ml-1"
                          onMouseDown={(e) => e.preventDefault()}
                        >
                          Create New?
                        </button>
                      </div>
                    )}
                  </div>
                )}
              </div>
              {errors.patientId && <p className="text-red-500 text-xs mt-1">{errors.patientId}</p>}
            </div>
          )}

          {/* Doctor Selection */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1" htmlFor="doctor-select">
              Doctor <span className="text-red-600">*</span>
            </label>
            {doctors && doctors.length === 1 ? (
              <div className="w-full px-4 py-2.5 bg-gray-50 border border-gray-200 text-gray-800 rounded-lg text-sm font-semibold flex items-center justify-between">
                <span>
                  {doctors[0].name} - {doctors[0].specialization}
                </span>
                <span className="text-xs px-2 py-0.5 bg-green-50 text-green-700 border border-green-200 rounded-full font-medium">
                  Assigned
                </span>
              </div>
            ) : (
              <select
                id="doctor-select"
                value={formData.doctorId || ''}
                onChange={(e) => handleChange('doctorId', e.target.value)}
                className={`w-full px-4 py-2 border rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent ${errors.doctorId ? 'border-red-500' : 'border-gray-300'}`}
              >
                <option value="">Select Doctor</option>
                {doctors.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name} - {d.specialization}
                  </option>
                ))}
              </select>
            )}
            {errors.doctorId && <p className="text-red-500 text-xs mt-1">{errors.doctorId}</p>}
          </div>

          {/* Date */}
          <div>
            <label
              className="block text-sm font-medium text-gray-700 mb-1"
              htmlFor="appointment-date"
            >
              Date <span className="text-red-600">*</span>
            </label>
            <DateSelect
              value={formData.appointmentDate || ''}
              onChange={(v) => handleChange('appointmentDate', v)}
              hasError={!!errors.appointmentDate}
              min={todayString}
            />
            {errors.appointmentDate && (
              <p className="text-red-500 text-xs mt-1">{errors.appointmentDate}</p>
            )}
          </div>

          {/* Time Slots */}
          {formData.appointmentDate && formData.doctorId && (
            <div>
              <span className="block text-sm font-medium text-gray-700 mb-2">
                Available Slots <span className="text-red-600">*</span>
              </span>
              {loadingSlots ? (
                <div className="text-sm text-gray-500">Checking availability...</div>
              ) : availableSlots.length === 0 ? (
                <div className="text-sm text-gray-500 text-center py-4">
                  No available slots for today. Please select a future date.
                </div>
              ) : (
                <div className="grid grid-cols-4 gap-2">
                  {availableSlots.map((time) => {
                    const isBooked = bookedSlots.includes(time);
                    const isSelected = selectedSlot === time;
                    return (
                      <button
                        key={time}
                        type="button"
                        onClick={() => !isBooked && handleSlotSelect(time)}
                        disabled={isBooked}
                        aria-label={`Select slot for ${time}${isBooked ? ' (booked)' : ''}`}
                        aria-pressed={isSelected}
                        className={`
                                                    py-2 px-1 text-sm rounded-lg border transition-all
                                                    ${
                                                      isBooked
                                                        ? 'bg-gray-100 text-gray-400 border-gray-200 cursor-not-allowed decoration-slice'
                                                        : isSelected
                                                          ? 'bg-gray-900 text-white border-gray-900 shadow-md transform scale-105'
                                                          : 'bg-white text-gray-700 border-gray-300 hover:border-gray-900 hover:text-gray-900'
                                                    }
                                                `}
                      >
                        {time}
                      </button>
                    );
                  })}
                </div>
              )}
              {errors.appointmentTime && (
                <p className="text-red-500 text-xs mt-1">Please select a time slot</p>
              )}
            </div>
          )}

          {/* Notes */}
          <div>
            <label
              className="block text-sm font-medium text-gray-700 mb-1"
              htmlFor="appointment-notes"
            >
              Notes
            </label>
            <textarea
              id="appointment-notes"
              value={formData.notes || ''}
              onChange={(e) => handleChange('notes', e.target.value)}
              className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-transparent"
              rows="2"
              placeholder="Reason for visit, symptoms, etc."
            />
          </div>

          {/* Action Buttons */}
          <div className="flex gap-3 pt-4">
            <button
              type="button"
              onClick={handleClose}
              disabled={submitting}
              className={`flex-1 px-4 py-2 border border-gray-300 text-gray-700 rounded-lg font-medium transition ${submitting ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50'}`}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className={`flex-1 px-4 py-2 rounded-lg font-medium transition flex items-center justify-center gap-2 ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 text-white hover:bg-gray-800'}`}
            >
              {submitting && (
                <svg
                  className="animate-spin h-4 w-4 text-white"
                  xmlns="http://www.w3.org/2000/svg"
                  fill="none"
                  viewBox="0 0 24 24"
                >
                  <circle
                    className="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    strokeWidth="4"
                  ></circle>
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                  ></path>
                </svg>
              )}
              {submitting ? 'Scheduling...' : 'Schedule'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default AppointmentModal;
