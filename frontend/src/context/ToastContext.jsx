import React, { createContext, useContext, useState, useCallback, useMemo } from 'react';

const ToastContext = createContext();

export const useToast = () => useContext(ToastContext);

// API handlers do not all return the same error shape. Keep an unexpected body
// from becoming a React child and masking the original business error.
export const toToastMessage = (message) => {
  if (typeof message === 'string' && message.trim()) return message;
  if (message && typeof message === 'object') {
    if (typeof message.error === 'string' && message.error.trim()) return message.error;
    if (typeof message.message === 'string' && message.message.trim()) return message.message;
    if (message.errors && typeof message.errors === 'object') {
      const errors = Object.values(message.errors).filter(
        (value) => typeof value === 'string' && value.trim()
      );
      if (errors.length) return errors.join(', ');
    }
  }
  return 'Something went wrong. Please try again.';
};

export const ToastProvider = ({ children }) => {
  const [toasts, setToasts] = useState([]);

  // success/error/info MUST be referentially stable: components put them in
  // useEffect/useCallback dependency lists. If their identity changed on every
  // provider render, an effect that toasts on failure would refetch forever.
  const removeToast = useCallback((id) => {
    setToasts((prev) => prev.filter((toast) => toast.id !== id));
  }, []);

  const addToast = useCallback(
    (message, type = 'info') => {
      const id = Date.now() + Math.random();
      setToasts((prev) => [...prev, { id, message: toToastMessage(message), type }]);
      // Auto-remove after 3 seconds
      setTimeout(() => removeToast(id), 3000);
    },
    [removeToast]
  );

  const success = useCallback((message) => addToast(message, 'success'), [addToast]);
  const error = useCallback((message) => addToast(message, 'error'), [addToast]);
  const info = useCallback((message) => addToast(message, 'info'), [addToast]);

  const value = useMemo(() => ({ success, error, info }), [success, error, info]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className="px-4 py-3 rounded-lg shadow-lg bg-gray-900 text-white font-medium transform transition-all duration-300 ease-in-out flex items-center gap-2 min-w-[300px]"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              {toast.type === 'success' ? (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              ) : toast.type === 'error' ? (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                />
              ) : (
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              )}
            </svg>
            {toast.message}
            <button
              onClick={() => removeToast(toast.id)}
              className="ml-auto text-white hover:text-gray-200"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};
