import React from 'react';

/**
 * ViewLayout - Standardized container for pharmacy views.
 * Handles the main flex layout and automatic height calculation.
 */
export const ViewLayout = ({ children, header, toolbar, error, sidePanel }) => {
  return (
    <div className="h-full flex flex-col gap-4 -mt-2 animate-in fade-in duration-500">
      {header && (
        <div className="bg-white px-4 py-3 rounded-lg border border-gray-200 shadow-sm">
          {header}
        </div>
      )}

      {toolbar && (
        <div className="bg-white border border-gray-200 rounded-lg p-4 flex flex-wrap items-center justify-between gap-4 shadow-sm">
          {toolbar}
        </div>
      )}

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-2 rounded text-sm flex items-center gap-2">
          <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          {error}
        </div>
      )}

      <div className="flex-1 flex gap-4 overflow-hidden">
        <div className="flex-1 bg-white border border-gray-200 rounded-lg flex flex-col overflow-hidden shadow-sm">
          {children}
        </div>
        {sidePanel && (
          <div className="w-96 bg-white border border-gray-200 rounded-lg flex flex-col overflow-hidden shadow-sm animate-in slide-in-from-right duration-300">
            {sidePanel}
          </div>
        )}
      </div>
    </div>
  );
};

/**
 * ViewToolbar - Flexible toolbar container with left and right slots.
 */
export const ViewToolbar = ({ left, right }) => {
  return (
    <div className="w-full flex flex-wrap items-center justify-between gap-4">
      <div className="flex flex-wrap gap-3 items-center">{left}</div>
      <div className="flex items-center gap-2">{right}</div>
    </div>
  );
};

/**
 * SearchInput - Stylized and reusable search input with icon.
 */
export const SearchInput = ({ placeholder, value, onChange, onSearch }) => {
  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && onSearch) {
      onSearch();
    }
  };

  return (
    <div className="relative w-72">
      <input
        type="text"
        placeholder={placeholder}
        value={value}
        onChange={onChange}
        onKeyDown={handleKeyDown}
        className="pl-10 pr-4 py-2 border border-gray-300 rounded-lg w-full text-sm outline-none focus:ring-2 focus:ring-gray-900/5 focus:border-gray-900 transition-all"
      />
      <svg
        className="w-4 h-4 text-gray-400 absolute left-3 top-1/2 transform -translate-y-1/2"
        fill="none"
        viewBox="0 0 24 24"
        stroke="currentColor"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
        />
      </svg>
    </div>
  );
};
