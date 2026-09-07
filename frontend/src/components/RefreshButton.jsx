import React from 'react';

const RefreshButton = ({ onRefresh, loading = false }) => {
  return (
    <button
      onClick={onRefresh}
      disabled={loading}
      className="p-2 text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-lg transition-colors disabled:opacity-50"
      title="Refresh data"
    >
      <svg
        className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`}
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
        />
      </svg>
    </button>
  );
};

export default RefreshButton;
