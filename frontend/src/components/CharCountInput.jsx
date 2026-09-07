import React from 'react';

/**
 * CharCountInput - Text input/textarea with character counter
 *
 * @param {string} value - Current value
 * @param {function} onChange - Change handler
 * @param {number} maxLength - Maximum character limit
 * @param {string} label - Input label
 * @param {boolean} required - Is field required
 * @param {string} error - Error message
 * @param {boolean} textarea - Use textarea instead of input
 * @param {number} rows - Number of rows for textarea
 * @param {string} placeholder - Placeholder text
 * @param {string} type - Input type (text, email, number, etc.)
 * @param {boolean} showCount - Whether to display the character counter (default true)
 * @param {object} rest - Other props
 */
const CharCountInput = ({
  value = '',
  onChange,
  maxLength,
  label,
  required = false,
  error = '',
  textarea = false,
  rows = 3,
  placeholder = '',
  type = 'text',
  className = '',
  showCount = true,
  ...rest
}) => {
  const currentLength = value?.length || 0;
  const isNearLimit = maxLength && currentLength > maxLength * 0.8;
  const isOverLimit = maxLength && currentLength > maxLength;

  const inputClasses = `w-full px-4 py-3 text-sm border rounded-xl bg-neutral-50 placeholder-slate-400 text-slate-800 focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent focus:bg-white transition-all duration-200 ease-in-out ${
    error ? 'border-red-300 bg-red-50 focus:ring-red-500' : 'border-neutral-300'
  } ${className}`;

  const InputComponent = textarea ? 'textarea' : 'input';

  return (
    <div>
      {label && (
        <label className="block text-sm font-semibold text-neutral-700 mb-2">
          {label} {required && <span className="text-red-600">*</span>}
        </label>
      )}

      <div className="relative">
        <InputComponent
          type={textarea ? undefined : type}
          value={value}
          onChange={onChange}
          maxLength={maxLength}
          placeholder={placeholder}
          rows={textarea ? rows : undefined}
          className={inputClasses}
          required={required}
          {...rest}
        />

        {maxLength && showCount && (
          <div
            className={`absolute bottom-2 right-2 text-xs font-medium ${
              isOverLimit ? 'text-red-600' : isNearLimit ? 'text-orange-600' : 'text-gray-500'
            }`}
          >
            {currentLength} / {maxLength}
          </div>
        )}
      </div>

      {error && (
        <p className="text-red-600 text-sm mt-1 flex items-center gap-1">
          <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z"
              clipRule="evenodd"
            />
          </svg>
          {error}
        </p>
      )}
    </div>
  );
};

export default CharCountInput;
