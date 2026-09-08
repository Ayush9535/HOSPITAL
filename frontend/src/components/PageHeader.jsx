import React from 'react';

/**
 * Standardized Page Header Component
 *
 * Provides a consistent layout for:
 * - Page Title / Subtitle
 * - Search Bar
 * - Action Buttons (Add, Filter, etc.)
 */
const PageHeader = ({
  title,
  subtitle,
  onSearch,
  searchValue,
  searchPlaceholder = 'Search...',
  onAdd,
  addLabel,
  filter,
  className = '',
}) => {
  return (
    <div
      className={`flex flex-col md:flex-row justify-between items-start md:items-center gap-4 mb-6 ${className}`}
    >
      {/* Title Section */}
      <div>
        <h1 className="text-2xl font-bold text-slate-800">{title}</h1>
        {subtitle && <p className="text-slate-500 text-sm mt-1">{subtitle}</p>}
      </div>

      {/* Actions Section */}
      <div className="flex flex-col sm:flex-row sm:items-center gap-3 w-full md:w-auto">
        {/* Search Bar */}
        {onSearch && (
          <div className="relative group">
            <input
              type="text"
              placeholder={searchPlaceholder}
              aria-label={searchPlaceholder}
              value={searchValue}
              onChange={onSearch} // Expecting event handler
              className="pl-10 pr-4 py-2.5 border border-neutral-300 rounded-xl focus:ring-2 focus:ring-primary-500 focus:border-transparent w-full sm:w-64 transition-all shadow-soft group-hover:shadow-soft-lg bg-neutral-50 focus:bg-white text-slate-800 placeholder-slate-400"
            />
            <span className="absolute left-3 top-3 text-slate-400 group-focus-within:text-primary-500 transition-colors">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="h-5 w-5"
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
            </span>
          </div>
        )}

        {/* Additional Filters */}
        {filter}

        {/* Add/Primary Action Button */}
        {onAdd && (
          <button
            onClick={onAdd}
            aria-label={addLabel || 'Add New'}
            className="bg-sky-600 hover:bg-sky-700 text-white px-5 py-2.5 rounded-xl font-semibold shadow-soft hover:shadow-soft-lg transform hover:-translate-y-0.5 transition-all flex items-center justify-center gap-2 focus:ring-2 focus:ring-sky-500 focus:ring-offset-2"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              className="h-5 w-5"
              viewBox="0 0 20 20"
              fill="currentColor"
            >
              <path
                fillRule="evenodd"
                d="M10 3a1 1 0 011 1v5h5a1 1 0 110 2h-5v5a1 1 0 11-2 0v-5H4a1 1 0 110-2h5V4a1 1 0 011-1z"
                clipRule="evenodd"
              />
            </svg>
            <span>{addLabel || 'Add New'}</span>
          </button>
        )}
      </div>
    </div>
  );
};

export default PageHeader;
