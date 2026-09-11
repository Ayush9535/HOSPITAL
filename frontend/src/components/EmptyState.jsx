import React from 'react';

const EmptyState = ({ icon, title, message, actionLabel, onAction, variant = 'default' }) => {
  const variants = {
    default: {
      iconBg: 'bg-gray-100',
      iconText: 'text-gray-400',
      titleText: 'text-gray-900',
      messageText: 'text-gray-600',
    },
    primary: {
      iconBg: 'bg-gray-100',
      iconText: 'text-gray-400',
      titleText: 'text-gray-900',
      messageText: 'text-gray-600',
    },
    success: {
      iconBg: 'bg-gray-100',
      iconText: 'text-gray-400',
      titleText: 'text-gray-900',
      messageText: 'text-gray-600',
    },
  };

  const theme = variants[variant] || variants.default;

  return (
    <div className="flex flex-col items-center justify-center py-16 px-6 text-center bg-white rounded-lg border border-gray-200">
      {/* Icon container with clean styling */}
      {icon && (
        <div
          className={`w-24 h-24 ${theme.iconBg} rounded-lg flex items-center justify-center mb-6`}
        >
          <span className={`text-4xl ${theme.iconText}`}>{icon}</span>
        </div>
      )}

      {/* Content */}
      <div className="max-w-sm space-y-3">
        <h3 className={`text-xl font-medium ${theme.titleText}`}>{title}</h3>
        <p className={`${theme.messageText}`}>{message}</p>
      </div>

      {/* Action button */}
      {onAction && (
        <div className="mt-8">
          <button
            onClick={onAction}
            className="bg-gray-900 text-white px-4 py-2 rounded-lg font-medium hover:bg-gray-800 transition"
          >
            <span className="mr-2">+</span>
            {actionLabel}
          </button>
        </div>
      )}
    </div>
  );
};

export default EmptyState;
