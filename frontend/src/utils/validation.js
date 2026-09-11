/**
 * Validation Utility
 * Centralized validation logic for the application.
 */

export const validators = {
  required: (value) => {
    if (value === null || value === undefined) return 'This field is required';
    if (typeof value === 'string' && value.trim() === '') return 'This field is required';
    return null;
  },

  email: (value) => {
    if (!value) return null; // Allow empty if not required (chain with required validation)
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return emailRegex.test(value) ? null : 'Invalid email address';
  },

  phone: (value) => {
    if (!value) return null;
    const phoneRegex = /^\d{10}$/;
    return phoneRegex.test(value) ? null : 'Phone number must be exactly 10 digits';
  },

  password: (value) => {
    if (!value) return null;
    return value.length >= 6 ? null : 'Password must be at least 6 characters';
  },

  age: (value) => {
    if (!value) return null;
    const num = Number(value);
    if (Number.isNaN(num)) return 'Age must be a number';
    if (num < 0 || num > 120) return 'Age must be between 0 and 120';
    return null;
  },

  dob: (value) => {
    if (!value) return null;
    const date = new Date(value + 'T00:00:00');
    if (Number.isNaN(date.getTime())) return 'Invalid date of birth';
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    if (date > today) return 'Date of birth cannot be in the future';
    const minDate = new Date(today);
    minDate.setFullYear(minDate.getFullYear() - 120);
    if (date < minDate) return 'Date of birth cannot be more than 120 years ago';
    return null;
  },

  number: (value) => {
    if (!value) return null;
    return !Number.isNaN(Number(value)) ? null : 'Must be a valid number';
  },

  positiveNumber: (value) => {
    if (!value) return null;
    const num = Number(value);
    return !Number.isNaN(num) && num > 0 ? null : 'Must be a positive number';
  },

  name: (value) => {
    if (!value) return null;
    const nameRegex = /^[a-zA-Z\s]+$/;
    if (!nameRegex.test(value)) return 'Name must contain only letters and spaces';
    if (value.trim().length < 2) return 'Name must be at least 2 characters long';
    return null;
  },

  text: (value) => {
    if (!value) return null;
    // Allow letters, numbers, spaces, and common punctuation (., -)
    const textRegex = /^[a-zA-Z0-9\s.-]+$/;
    if (!textRegex.test(value)) return 'Field contains invalid characters';
    return null;
  },

  minLength: (min) => (value) => (value && value.length < min ? `Minimum ${min} characters` : null),
  maxLength: (max) => (value) => (value && value.length > max ? `Maximum ${max} characters` : null),
};

/**
 * Alias for validateForm — supports the same signature.
 * Rules values are arrays of validator functions or string keys.
 */
export function validate(values, rules) {
  return validateForm(values, rules);
}

/**
 * Run validations against a form data object.
 * @param {Object} formData - Key-value pairs of form data
 * @param {Object} rules - Key-value pairs of validation rules (e.g., { email: ['required', 'email'] })
 * @returns {Object} errors - Key-value pairs of error messages (empty if valid)
 */
export const validateForm = (formData, rules) => {
  const errors = {};

  Object.keys(rules).forEach((field) => {
    const fieldRules = rules[field];
    const value = formData[field];

    for (const rule of fieldRules) {
      let error = null;

      if (typeof rule === 'function') {
        error = rule(value, formData);
      } else if (typeof rule === 'string' && validators[rule]) {
        error = validators[rule](value);
      }

      if (error) {
        errors[field] = error;
        break; // Stop at first error for this field
      }
    }
  });

  return errors;
};
