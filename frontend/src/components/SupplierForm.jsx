import React, { useState, useEffect } from 'react';
import { useToast } from '../context/ToastContext';
import suppliersApi from '../services/pharmacy/suppliersApi';
import { backdropProps } from '../utils/modalA11y';
import { extractApiError } from '../utils/apiError';

/**
 * SupplierForm – modal used for both adding and editing a supplier.
 *
 * Props:
 *   - isOpen: boolean – controls modal visibility.
 *   - onClose: function – called to close the modal.
 *   - onSuccess: function – called after a successful create or update (e.g., refresh list).
 *   - mode: 'create' | 'edit' – determines whether we are creating or editing.
 *   - supplier: object – the supplier data to pre‑fill when mode === 'edit'.
 */
const SupplierForm = ({ isOpen = false, onClose, onSuccess, mode = 'create', supplier = null }) => {
  const { success, error: toastError } = useToast();
  const [formData, setFormData] = useState({
    supplierName: '',
    contactPerson: '',
    phone: '',
    email: '',
    address: '',
    gstNumber: '',
    panNumber: '',
    drugLicenseNumber: '',
    creditDays: '',
    isActive: true,
  });
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Populate form when entering edit mode or when the modal is opened anew.
  useEffect(() => {
    if (!isOpen) return;
    if (mode === 'edit' && supplier) {
      setFormData({
        supplierName: supplier.supplierName || '',
        contactPerson: supplier.contactPerson || '',
        phone: supplier.phone || '',
        email: supplier.email || '',
        address: supplier.address || '',
        gstNumber: supplier.gstNumber || '',
        panNumber: supplier.panNumber || '',
        drugLicenseNumber: supplier.drugLicenseNumber || '',
        creditDays: supplier.creditDays != null ? supplier.creditDays : '',
        isActive: supplier.isActive != null ? supplier.isActive : true,
      });
    } else {
      // reset to blanks for create mode
      setFormData({
        supplierName: '',
        contactPerson: '',
        phone: '',
        email: '',
        address: '',
        gstNumber: '',
        panNumber: '',
        drugLicenseNumber: '',
        creditDays: '',
        isActive: true,
      });
    }
  }, [mode, supplier, isOpen]);

  if (!isOpen) return null;

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    let next = type === 'checkbox' ? checked : value;
    // PAN (10) and GST (15) are uppercase alphanumeric codes — strip anything else as typed.
    if (name === 'panNumber')
      next = value
        .toUpperCase()
        .replace(/[^A-Z0-9]/g, '')
        .slice(0, 10);
    if (name === 'gstNumber')
      next = value
        .toUpperCase()
        .replace(/[^A-Z0-9]/g, '')
        .slice(0, 15);
    setFormData((prev) => ({
      ...prev,
      [name]: next,
    }));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();

    // PAN/GST are optional, but when provided must be exactly 10 / 15 alphanumeric characters.
    const pan = (formData.panNumber || '').trim();
    const gst = (formData.gstNumber || '').trim();
    if (pan && !/^[A-Z0-9]{10}$/.test(pan)) {
      toastError('PAN number must be exactly 10 alphanumeric characters.');
      return;
    }
    if (gst && !/^[A-Z0-9]{15}$/.test(gst)) {
      toastError('GST number must be exactly 15 alphanumeric characters.');
      return;
    }

    setIsSubmitting(true);
    try {
      if (mode === 'create') {
        await suppliersApi.create(formData);
        success('Supplier added successfully');
      } else {
        await suppliersApi.update(supplier.id, formData);
        success('Supplier updated successfully');
      }
      if (onSuccess) onSuccess();
      if (onClose) onClose();
    } catch (err) {
      toastError(extractApiError(err, 'Failed to save supplier'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const modalTitle = mode === 'create' ? 'Add Supplier' : 'Edit Supplier';
  const submitLabel =
    mode === 'create'
      ? isSubmitting
        ? 'Saving...'
        : 'Save'
      : isSubmitting
        ? 'Updating...'
        : 'Update';

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl shadow-lg w-full max-w-2xl max-h-[90vh] overflow-auto p-6">
        <h3 className="text-2xl font-bold mb-4">{modalTitle}</h3>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <input
              name="supplierName"
              required
              placeholder="Supplier Name"
              value={formData.supplierName}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="contactPerson"
              placeholder="Contact Person"
              value={formData.contactPerson}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="phone"
              placeholder="Phone"
              value={formData.phone}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="email"
              type="email"
              placeholder="Email"
              value={formData.email}
              onChange={handleChange}
              className="input-field"
            />
          </div>
          <input
            name="address"
            placeholder="Address"
            value={formData.address}
            onChange={handleChange}
            className="input-field w-full"
          />
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <input
              name="gstNumber"
              placeholder="GST Number (15 characters)"
              title="GST number must be exactly 15 alphanumeric characters"
              maxLength={15}
              value={formData.gstNumber}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="panNumber"
              placeholder="PAN Number (10 characters)"
              title="PAN number must be exactly 10 alphanumeric characters"
              maxLength={10}
              value={formData.panNumber}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="drugLicenseNumber"
              placeholder="Drug License No."
              value={formData.drugLicenseNumber}
              onChange={handleChange}
              className="input-field"
            />
            <input
              name="creditDays"
              type="number"
              placeholder="Credit Days"
              value={formData.creditDays}
              onChange={handleChange}
              className="input-field"
            />
            <label className="flex items-center space-x-2">
              <input
                type="checkbox"
                name="isActive"
                checked={formData.isActive}
                onChange={handleChange}
                className="h-4 w-4 text-primary-600 border-gray-300 rounded"
              />
              <span className="text-sm">Active</span>
            </label>
          </div>
          <div className="flex justify-end space-x-3 mt-4">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 border border-gray-300 rounded text-sm hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-4 py-2 bg-gray-900 text-white rounded text-sm font-medium hover:bg-gray-800 disabled:opacity-50"
            >
              {submitLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default SupplierForm;
