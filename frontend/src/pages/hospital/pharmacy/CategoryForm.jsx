import React, { useState, useEffect } from 'react';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * CategoryForm – pure UI component for adding/editing medicine categories.
 *
 * Props:
 *   - isOpen: boolean - controls visibility
 *   - onClose: function - called when cancel button is clicked
 *   - onSubmit: function - called with validated formData
 *   - isSubmitting: boolean - indicates parent is performing async operation
 *   - mode: 'create' | 'edit' - UI title and button label
 *   - initialData: object | null - pre-fill data for edit mode
 */
const CategoryForm = ({
  isOpen = false,
  onClose,
  onSubmit,
  isSubmitting = false,
  mode = 'create',
  initialData = null,
}) => {
  const [formData, setFormData] = useState({
    categoryName: '',
    description: '',
    isActive: true,
  });

  const [errors, setErrors] = useState({});

  useEffect(() => {
    if (!isOpen) return;

    if (mode === 'edit' && initialData) {
      setFormData({
        categoryName: initialData.categoryName || '',
        description: initialData.description || '',
        isActive: initialData.isActive !== false,
      });
    } else {
      setFormData({
        categoryName: '',
        description: '',
        isActive: true,
      });
    }
    setErrors({});
  }, [isOpen, mode, initialData]);

  if (!isOpen) return null;

  const validate = () => {
    const newErrors = {};
    if (!formData.categoryName.trim()) {
      newErrors.categoryName = 'Category name is required';
    }
    return newErrors;
  };

  const handleChange = (e) => {
    const { name, value, type, checked } = e.target;
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
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

  const title = mode === 'edit' ? 'Edit Category' : 'Add Category';
  const submitBtnLabel =
    mode === 'edit'
      ? isSubmitting
        ? 'Updating...'
        : 'Update Category'
      : isSubmitting
        ? 'Saving...'
        : 'Save Category';

  return (
    <div
      className="fixed inset-0 bg-black/60 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl shadow-lg w-full max-w-lg max-h-[90vh] overflow-auto p-6">
        <h3 className="text-2xl font-bold mb-4">{title}</h3>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-1 gap-4">
            {/* --- SECTION A: Category Details --- */}
            <div className="col-span-full mt-2 mb-1 pb-1 border-b border-gray-200 text-sm font-semibold text-gray-600 uppercase tracking-wider">
              Category Details
            </div>

            {/* Category Name */}
            <div className="col-span-full">
              <input
                type="text"
                name="categoryName"
                value={formData.categoryName}
                onChange={handleChange}
                placeholder="Category Name (e.g. Antibiotics) *"
                className={`w-full px-3 py-2 border rounded focus:outline-none transition-all ${
                  errors.categoryName
                    ? 'border-red-300 focus:border-red-500'
                    : 'border-gray-300 focus:border-gray-900'
                }`}
                disabled={isSubmitting}
              />
              {errors.categoryName && (
                <p className="mt-1 text-xs text-red-600">{errors.categoryName}</p>
              )}
            </div>

            {/* Description */}
            <div className="col-span-full">
              <textarea
                name="description"
                value={formData.description}
                onChange={handleChange}
                rows="3"
                placeholder="Enter category details..."
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

export default CategoryForm;
