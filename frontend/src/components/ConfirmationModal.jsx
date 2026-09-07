import React, { useEffect, useRef } from 'react';
import { extractApiError } from '../utils/apiError';

/**
 * ConfirmationModal - Reusable modal for confirming destructive/important actions
 *
 * Accessibility (BUG-039): implements role="dialog", aria-modal, focus trap,
 * and Escape-key dismissal per WAI-ARIA modal dialog pattern.
 *
 * @param {boolean} isOpen - Whether the modal is visible
 * @param {string} title - Title of the modal
 * @param {string} message - Warning message to display
 * @param {function} onConfirm - Callback when user clicks "Yes, Proceed"
 * @param {function} onCancel - Callback when user clicks "Cancel"
 */
const ConfirmationModal = ({
  isOpen,
  title,
  message,
  onConfirm,
  onCancel,
  showReasonInput = false,
  inputPlaceholder = 'Please provide a reason...',
}) => {
  const [isLoading, setIsLoading] = React.useState(false);
  const [reason, setReason] = React.useState('');
  const [submitError, setSubmitError] = React.useState('');
  const cancelBtnRef = useRef(null);
  const isProcessingRef = useRef(false);

  // Reset reason and lock when modal opens/closes; auto-focus Cancel button
  React.useEffect(() => {
    if (isOpen) {
      isProcessingRef.current = false;
      setReason('');
      setSubmitError('');
      // Move focus into the modal on open for keyboard accessibility (BUG-039)
      setTimeout(() => cancelBtnRef.current?.focus(), 50);
    }
  }, [isOpen]);

  // BUG-039: Dismiss modal on Escape key
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape' && !isLoading) onCancel();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, isLoading, onCancel]);

  if (!isOpen) return null;

  const handleConfirm = async () => {
    if (isProcessingRef.current) return;
    if (showReasonInput && !reason.trim()) return;

    isProcessingRef.current = true;
    setIsLoading(true);
    setSubmitError('');
    try {
      await onConfirm(reason); // Pass reason to callback
      onCancel(); // Close on success
    } catch (error) {
      console.error('Confirmation action failed', error);
      setSubmitError(extractApiError(error, 'The action could not be completed. Please try again.'));
    } finally {
      isProcessingRef.current = false;
      setIsLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[60] p-4"
      onClick={(e) => {
        if (!isLoading && e.target === e.currentTarget) onCancel();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !isLoading) onCancel();
      }}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-modal-title"
        aria-describedby="confirm-modal-desc"
        className="bg-white rounded-xl shadow-2xl w-full max-w-md overflow-hidden transform transition-all scale-100"
      >
        <div className="p-6 text-center">
          <div
            className="mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-gray-100 mb-4"
            aria-hidden="true"
          >
            <svg
              className="h-6 w-6 text-gray-700"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth="2"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
              />
            </svg>
          </div>
          <h3 id="confirm-modal-title" className="text-lg font-bold text-gray-900 mb-2">
            {title || 'Confirm Action'}
          </h3>
          <p id="confirm-modal-desc" className="text-sm text-gray-500 mb-6">
            {message || 'Are you sure you want to proceed?'}
          </p>
          {submitError && (
            <p role="alert" className="mb-4 text-sm text-red-700">
              {submitError}
            </p>
          )}

          {showReasonInput && (
            <div className="mb-6 text-left">
              <label
                htmlFor="confirm-modal-reason"
                className="block text-sm font-medium text-gray-700 mb-2"
              >
                Reason{' '}
                <span className="text-red-500" aria-hidden="true">
                  *
                </span>
                <span className="sr-only">(required)</span>
              </label>
              <textarea
                id="confirm-modal-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder={inputPlaceholder}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-gray-900 focus:border-gray-900 text-sm"
                rows="3"
                required
                aria-required="true"
              />
            </div>
          )}

          <div className="flex gap-3 justify-center">
            <button
              ref={cancelBtnRef}
              onClick={onCancel}
              disabled={isLoading}
              aria-label="Cancel and close dialog"
              className={`bg-white border border-gray-300 text-gray-700 px-4 py-2 rounded-lg font-medium transition focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary-500 ${isLoading ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-50'}`}
            >
              Cancel
            </button>
            <button
              onClick={handleConfirm}
              disabled={isLoading || (showReasonInput && !reason.trim())}
              aria-label={isLoading ? 'Processing, please wait' : 'Confirm action'}
              aria-busy={isLoading}
              className={`bg-gray-900 text-white px-4 py-2 rounded-lg font-medium transition shadow-sm focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-900 flex items-center gap-2 ${isLoading || (showReasonInput && !reason.trim()) ? 'opacity-50 cursor-not-allowed' : 'hover:bg-gray-800'}`}
            >
              {isLoading && (
                <svg
                  className="animate-spin h-4 w-4 text-white"
                  xmlns="http://www.w3.org/2000/svg"
                  fill="none"
                  viewBox="0 0 24 24"
                  aria-hidden="true"
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
              {isLoading ? 'Processing...' : 'Yes, Proceed'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default ConfirmationModal;
