import React, { useState, useEffect } from 'react';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * ManufacturerForm – pure UI component for adding/editing medicine manufacturers.
 *
 * Props:
 *   - isOpen: boolean - controls visibility
 *   - onClose: function - called when cancel/close button is clicked
 *   - onSubmit: function - called with validated formData
 *   - isSubmitting: boolean - indicates parent is performing async operation
 *   - mode: 'create' | 'edit' - UI title and button label
 *   - initialData: object | null - pre-fill data for edit mode
 */
const ManufacturerForm = ({
  isOpen = false,
  onClose,
  onSubmit,
  isSubmitting = false,
  mode = 'create',
  initialData = null,
}) => {
  const [formData, setFormData] = useState({
    manufacturerName: '',
    contactPerson: '',
    phone: '',
    email: '',
    address: '',
    licenseNumber: '',
    isActive: true,
  });

  const [errors, setErrors] = useState({});

  useEffect(() => {
    if (!isOpen) return;

    if (mode === 'edit' && initialData) {
      setFormData({
        manufacturerName: initialData.manufacturerName || '',
        contactPerson: initialData.contactPerson || '',
        phone: initialData.phone || '',
        email: initialData.email || '',
        address: initialData.address || '',
        licenseNumber: initialData.licenseNumber || '',
        isActive: initialData.isActive !== false,
      });
    } else {
      setFormData({
        manufacturerName: '',
        contactPerson: '',
        phone: '',
        email: '',
        address: '',
        licenseNumber: '',
        isActive: true,
      });
    }
    setErrors({});
  }, [isOpen, mode, initialData]);

  if (!isOpen) return null;

  const validate = () => {
    const newErrors = {};
    if (!formData.manufacturerName.trim()) {
      newErrors.manufacturerName = 'Manufacturer name is required';
    }
    // Phone is optional, but when given it must be exactly 10 digits (matches the backend).
    const phone = (formData.phone || '').trim();
    if (phone && !/^\d{10}$/.test(phone)) {
      newErrors.phone = 'Phone must be exactly 10 digits';
    }
    const email = (formData.email || '').trim();
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      newErrors.email = 'Enter a valid email address';
    }
    return newErrors;
  };

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    let next = type === 'checkbox' ? checked : value;
    // Keep phone to digits only, capped at 10 — so a +91/spaces entry can't reach the backend.
    if (name === 'phone') next = value.replace(/\D/g, '').slice(0, 10);
    setFormData((prev) => ({
      ...prev,
      [name]: next,
    }));
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    const validationErrors = validate();
    setErrors(validationErrors);

    if (Object.keys(validationErrors).length === 0) {
      onSubmit(formData);
    }
  };

  const title = mode === 'edit' ? 'Edit Manufacturer' : 'Add Manufacturer';
  const submitBtnLabel =
    mode === 'edit'
      ? isSubmitting
        ? 'Updating...'
        : 'Update Manufacturer'
      : isSubmitting
        ? 'Saving...'
        : 'Save Manufacturer';

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl shadow-lg w-full max-w-2xl max-h-[90vh] overflow-auto p-6">
        <h3 className="text-2xl font-bold mb-4">{title}</h3>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {/* --- SECTION A: Manufacturer Identity --- */}
            <div className="col-span-full mt-2 mb-1 pb-1 border-b border-gray-200 text-sm font-semibold text-gray-600 uppercase tracking-wider">
              Manufacturer Identity
            </div>

            {/* Manufacturer Name */}
            <div className="col-span-1 sm:col-span-2">
              <input
                type="text"
                name="manufacturerName"
                value={formData.manufacturerName}
                onChange={handleChange}
                placeholder="Manufacturer Name (e.g. Cipla Ltd) *"
                className={`w-full px-3 py-2 border rounded focus:outline-none transition-all ${
                  errors.manufacturerName
                    ? 'border-red-300 focus:border-red-500'
                    : 'border-gray-300 focus:border-gray-900'
                }`}
                disabled={isSubmitting}
              />
              {errors.manufacturerName && (
                <p className="mt-1 text-xs text-red-600">{errors.manufacturerName}</p>
              )}
            </div>

            {/* License Number */}
            <div className="col-span-1 sm:col-span-2">
              <input
                type="text"
                name="licenseNumber"
                value={formData.licenseNumber}
                onChange={handleChange}
                placeholder="License Number (e.g. DL-123456)"
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all"
                disabled={isSubmitting}
              />
            </div>

            {/* --- SECTION B: Contact Information --- */}
            <div className="col-span-full mt-4 mb-1 pb-1 border-b border-gray-200 text-sm font-semibold text-gray-600 uppercase tracking-wider">
              Contact Information
            </div>

            {/* Contact Person */}
            <div className="col-span-1">
              <input
                type="text"
                name="contactPerson"
                value={formData.contactPerson}
                onChange={handleChange}
                placeholder="Contact Person (e.g. Manager Name)"
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all"
                disabled={isSubmitting}
              />
            </div>

            {/* Phone */}
            <div className="col-span-1">
              <input
                type="text"
                name="phone"
                value={formData.phone}
                onChange={handleChange}
                placeholder="Phone Number (10 digits)"
                maxLength={10}
                inputMode="numeric"
                className={`w-full px-3 py-2 border rounded focus:outline-none transition-all ${
                  errors.phone
                    ? 'border-red-300 focus:border-red-500'
                    : 'border-gray-300 focus:border-gray-900'
                }`}
                disabled={isSubmitting}
              />
              {errors.phone && <p className="mt-1 text-xs text-red-600">{errors.phone}</p>}
            </div>

            {/* Email */}
            <div className="col-span-1 sm:col-span-2">
              <input
                type="email"
                name="email"
                value={formData.email}
                onChange={handleChange}
                placeholder="Email Address"
                className={`w-full px-3 py-2 border rounded focus:outline-none transition-all ${
                  errors.email
                    ? 'border-red-300 focus:border-red-500'
                    : 'border-gray-300 focus:border-gray-900'
                }`}
                disabled={isSubmitting}
              />
              {errors.email && <p className="mt-1 text-xs text-red-600">{errors.email}</p>}
            </div>

            {/* --- SECTION C: Business Details --- */}
            <div className="col-span-full mt-4 mb-1 pb-1 border-b border-gray-200 text-sm font-semibold text-gray-600 uppercase tracking-wider">
              Business Details
            </div>

            {/* Address */}
            <div className="col-span-full">
              <textarea
                name="address"
                value={formData.address}
                onChange={handleChange}
                rows="2"
                placeholder="Full manufacturer address..."
                className="w-full px-3 py-2 border border-gray-300 rounded focus:outline-none focus:border-gray-900 transition-all resize-none"
                disabled={isSubmitting}
              />
            </div>

            {/* Active Status */}
            <div className="col-span-full flex items-center mt-2">
              <label className="flex items-center space-x-2 cursor-pointer">
                <input
                  type="checkbox"
                  name="isActive"
                  checked={formData.isActive}
                  onChange={handleChange}
                  className="h-4 w-4 text-gray-900 border-gray-300 rounded focus:ring-gray-900"
                  disabled={isSubmitting}
                />
                <span className="text-sm font-medium">Active Status</span>
              </label>
            </div>
          </div>

          {/* Actions / Footer */}
          <div className="flex justify-end space-x-3 mt-6 pt-4 border-t border-gray-100">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 border border-gray-300 rounded text-sm hover:bg-gray-50 transition-colors"
              disabled={isSubmitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-4 py-2 bg-gray-900 text-white rounded text-sm font-medium hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              {submitBtnLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default ManufacturerForm;
