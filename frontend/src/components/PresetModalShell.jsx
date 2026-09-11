import React, { useEffect, useRef } from 'react';

/**
 * Shared modal shell for the preset-management modals (prescription presets and
 * quick notes). Centralises the overlay/dialog chrome, Escape-to-close, and
 * close-button auto-focus so the individual modals don't duplicate it.
 */
const PresetModalShell = ({
  isOpen,
  onClose,
  title,
  titleId,
  maxWidthClass = 'max-w-lg',
  children,
}) => {
  const closeBtnRef = useRef(null);

  // Auto-focus the close button on open (accessibility, BUG-039).
  useEffect(() => {
    if (isOpen) {
      setTimeout(() => closeBtnRef.current?.focus(), 50);
    }
  }, [isOpen]);

  // Dismiss on Escape (BUG-039).
  useEffect(() => {
    if (!isOpen) return;
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-[70] p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose();
      }}
      role="presentation"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`bg-gray-50 rounded-2xl shadow-2xl w-full ${maxWidthClass} max-h-[85vh] overflow-y-auto`}
      >
        <div className="flex justify-between items-center p-4 border-b border-gray-200 bg-white rounded-t-2xl sticky top-0">
          <h2 id={titleId} className="text-base font-bold text-gray-900">
            {title}
          </h2>
          <button
            ref={closeBtnRef}
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-xl leading-none"
            aria-label="Close"
          >
            &times;
          </button>
        </div>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
};

export default PresetModalShell;
