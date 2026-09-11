import React from 'react';

// ─── PRIMITIVES ───────────────────────────────────────────────────────────────

/**
 * SkeletonLine — A single animated bar (text line placeholder).
 * @param {string} width   — Tailwind width class (e.g. 'w-32', 'w-full')
 * @param {string} height  — Tailwind height class (default: 'h-4')
 * @param {string} className — Extra classes
 * @param {number} delay   — Stagger delay index (0–4)
 */
export const SkeletonLine = ({ width = 'w-full', height = 'h-4', className = '', delay = 0 }) => (
  <div
    className={`${width} ${height} rounded-md loading-shimmer ${className}`}
    style={delay ? { animationDelay: `${delay * 150}ms` } : undefined}
  />
);

/**
 * SkeletonCircle — Avatar / icon placeholder.
 * @param {string} size — Tailwind size class (default: 'w-10 h-10')
 */
export const SkeletonCircle = ({ size = 'w-10 h-10', className = '', delay = 0 }) => (
  <div
    className={`${size} rounded-full loading-shimmer flex-shrink-0 ${className}`}
    style={delay ? { animationDelay: `${delay * 150}ms` } : undefined}
  />
);

/**
 * SkeletonRect — Generic rectangular block.
 * @param {string} width  — Tailwind width
 * @param {string} height — Tailwind height
 * @param {string} rounded — Tailwind border-radius (default: 'rounded-lg')
 */
export const SkeletonRect = ({
  width = 'w-full',
  height = 'h-24',
  rounded = 'rounded-lg',
  className = '',
  delay = 0,
}) => (
  <div
    className={`${width} ${height} ${rounded} loading-shimmer ${className}`}
    style={delay ? { animationDelay: `${delay * 150}ms` } : undefined}
  />
);

// ─── COMPOSITE TEMPLATES ──────────────────────────────────────────────────────

/**
 * SkeletonStatCard — Dashboard stat card skeleton.
 */
export const SkeletonStatCard = ({ delay = 0 }) => (
  <div
    className="bg-white rounded-lg border border-gray-200 p-6 space-y-3"
    style={delay ? { animationDelay: `${delay * 100}ms` } : undefined}
  >
    <div className="flex items-center justify-between">
      <SkeletonRect width="w-10" height="h-10" rounded="rounded-lg" />
      <SkeletonLine width="w-16" height="h-5" />
    </div>
    <SkeletonLine width="w-24" height="h-3" delay={1} />
    <SkeletonLine width="w-20" height="h-8" delay={2} />
    <SkeletonLine width="w-full" height="h-1.5" rounded="rounded-full" delay={3} />
  </div>
);

/**
 * SkeletonStatsGrid — Grid of N stat card skeletons.
 * @param {number} count — Number of stat cards (default: 3)
 * @param {string} gridCols — Tailwind grid-cols class
 */
export const SkeletonStatsGrid = ({ count = 3, gridCols = '' }) => {
  const cols =
    gridCols ||
    (count <= 3
      ? 'md:grid-cols-3'
      : count <= 4
        ? 'md:grid-cols-4'
        : count <= 5
          ? 'md:grid-cols-5'
          : 'md:grid-cols-3 lg:grid-cols-6');
  return (
    <div className={`grid grid-cols-1 ${cols} gap-6`}>
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonStatCard key={i} delay={i} />
      ))}
    </div>
  );
};

/**
 * SkeletonTableRow — Single table row skeleton.
 * @param {number} cols — Number of columns
 */
export const SkeletonTableRow = ({ cols = 5, delay = 0 }) => (
  <tr>
    {Array.from({ length: cols }).map((_, i) => (
      <td key={i} className="px-6 py-4">
        <SkeletonLine
          width={i === 0 ? 'w-8' : i === cols - 1 ? 'w-20' : 'w-24'}
          height="h-4"
          delay={delay}
        />
      </td>
    ))}
  </tr>
);

/**
 * SkeletonTable — Full table with header and rows.
 * @param {number} rows — Number of skeleton rows (default: 5)
 * @param {number} cols — Number of columns (default: 5)
 */
export const SkeletonTable = ({ rows = 5, cols = 5 }) => (
  <div className="overflow-hidden border border-neutral-200 rounded-xl">
    <table className="min-w-full divide-y divide-neutral-200">
      <thead className="bg-neutral-50">
        <tr>
          {Array.from({ length: cols }).map((_, i) => (
            <th key={i} className="px-6 py-3">
              <SkeletonLine width={i === 0 ? 'w-10' : 'w-20'} height="h-3" />
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="bg-white divide-y divide-neutral-100">
        {Array.from({ length: rows }).map((_, i) => (
          <SkeletonTableRow key={i} cols={cols} delay={i} />
        ))}
      </tbody>
    </table>
  </div>
);

/**
 * SkeletonDashboard — Complete dashboard skeleton (stats grid + table).
 * @param {number} statCount — Number of stat cards
 * @param {number} tableRows — Number of table rows
 * @param {number} tableCols — Number of table columns
 * @param {string} gridCols  — Custom grid-cols class for stats
 */
export const SkeletonDashboard = ({
  statCount = 3,
  tableRows = 5,
  tableCols = 5,
  gridCols = '',
}) => (
  <div className="space-y-8 animate-fade-in-up">
    <SkeletonStatsGrid count={statCount} gridCols={gridCols} />
    <SkeletonTable rows={tableRows} cols={tableCols} />
  </div>
);

/**
 * SkeletonFormCard — Settings / form section skeleton.
 */
export const SkeletonFormCard = ({ fields = 3 }) => (
  <div className="bg-white rounded-2xl border border-gray-200 p-6 space-y-5 max-w-md animate-fade-in-up">
    <SkeletonLine width="w-40" height="h-6" />
    <SkeletonLine width="w-64" height="h-3" delay={1} />
    <div className="space-y-4 pt-2">
      {Array.from({ length: fields }).map((_, i) => (
        <div key={i} className="space-y-2">
          <SkeletonLine width="w-28" height="h-3" delay={i + 1} />
          <SkeletonRect width="w-full" height="h-10" rounded="rounded-lg" delay={i + 1} />
        </div>
      ))}
    </div>
    <div className="flex gap-3 pt-2">
      <SkeletonRect width="flex-1" height="h-10" rounded="rounded-lg" delay={fields + 1} />
      <SkeletonRect width="flex-1" height="h-10" rounded="rounded-lg" delay={fields + 2} />
    </div>
  </div>
);

/**
 * SkeletonDetailCard — Detail panel skeleton (e.g., IPD, patient details).
 */
export const SkeletonDetailCard = () => (
  <div className="p-6 space-y-6 animate-fade-in-up">
    {/* Title area */}
    <div className="flex items-center gap-4">
      <SkeletonCircle size="w-14 h-14" />
      <div className="space-y-2 flex-1">
        <SkeletonLine width="w-48" height="h-6" />
        <SkeletonLine width="w-32" height="h-3" delay={1} />
      </div>
    </div>
    {/* Detail rows */}
    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="space-y-2">
          <SkeletonLine width="w-24" height="h-3" delay={i} />
          <SkeletonLine width="w-40" height="h-5" delay={i} />
        </div>
      ))}
    </div>
    {/* Table section */}
    <SkeletonTable rows={3} cols={4} />
  </div>
);

/**
 * SkeletonFeedItem — Single activity feed item skeleton.
 */
export const SkeletonFeedItem = ({ delay = 0 }) => (
  <div
    className="flex items-start gap-3 py-3"
    style={delay ? { animationDelay: `${delay * 150}ms` } : undefined}
  >
    <SkeletonCircle size="w-8 h-8" delay={delay} />
    <div className="flex-1 space-y-2">
      <SkeletonLine width="w-3/4" height="h-3" delay={delay} />
      <SkeletonLine width="w-1/2" height="h-3" delay={delay + 1} />
    </div>
    <SkeletonLine width="w-12" height="h-3" delay={delay} />
  </div>
);

/**
 * SkeletonFeed — Activity feed skeleton with multiple items.
 * @param {number} count — Number of feed items
 */
export const SkeletonFeed = ({ count = 4 }) => (
  <div className="divide-y divide-gray-100">
    {Array.from({ length: count }).map((_, i) => (
      <SkeletonFeedItem key={i} delay={i} />
    ))}
  </div>
);

/**
 * SkeletonSettingsCard — Two-column settings card skeleton (for Operations Settings).
 */
export const SkeletonSettingsCard = () => (
  <div className="grid grid-cols-1 md:grid-cols-2 gap-8 animate-fade-in-up">
    {[0, 1].map((i) => (
      <div key={i} className="bg-slate-50/50 rounded-2xl border border-gray-200 p-6 space-y-4">
        <div className="flex items-center justify-between">
          <SkeletonRect width="w-12" height="h-12" rounded="rounded-xl" delay={i} />
          <SkeletonLine width="w-28" height="h-6" rounded="rounded-full" delay={i} />
        </div>
        <SkeletonLine width="w-40" height="h-5" delay={i + 1} />
        <div className="space-y-2">
          <SkeletonLine width="w-full" height="h-3" delay={i + 2} />
          <SkeletonLine width="w-3/4" height="h-3" delay={i + 3} />
        </div>
        <div className="flex items-center justify-between pt-4 border-t border-gray-100">
          <SkeletonLine width="w-36" height="h-4" delay={i + 3} />
          <SkeletonRect width="w-11" height="h-6" rounded="rounded-full" delay={i + 3} />
        </div>
      </div>
    ))}
  </div>
);

/**
 * SkeletonOverviewDual — Two-column panel skeleton (for overview pages with side-by-side lists).
 */
export const SkeletonOverviewDual = ({ leftTitle = true, rightTitle = true }) => (
  <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 animate-fade-in-up">
    {[0, 1].map((i) => (
      <div
        key={i}
        className="bg-white rounded-2xl border border-gray-200 shadow-sm flex flex-col overflow-hidden"
      >
        {/* Header */}
        <div className="px-6 py-5 border-b border-gray-100 bg-neutral-50/50 flex justify-between items-center">
          <div className="space-y-2">
            <SkeletonLine width="w-36" height="h-5" delay={i} />
            <SkeletonLine width="w-48" height="h-3" delay={i + 1} />
          </div>
          <SkeletonRect width="w-28" height="h-9" rounded="rounded-xl" delay={i + 1} />
        </div>
        {/* Body */}
        <div className="p-6">
          <SkeletonTable rows={4} cols={4} />
        </div>
      </div>
    ))}
  </div>
);

// Default export with all components for convenience
const Skeleton = {
  Line: SkeletonLine,
  Circle: SkeletonCircle,
  Rect: SkeletonRect,
  StatCard: SkeletonStatCard,
  StatsGrid: SkeletonStatsGrid,
  TableRow: SkeletonTableRow,
  Table: SkeletonTable,
  Dashboard: SkeletonDashboard,
  FormCard: SkeletonFormCard,
  DetailCard: SkeletonDetailCard,
  FeedItem: SkeletonFeedItem,
  Feed: SkeletonFeed,
  SettingsCard: SkeletonSettingsCard,
  OverviewDual: SkeletonOverviewDual,
};

export default Skeleton;
