import React, { useState, useEffect, useCallback } from 'react';
import { SkeletonTableRow } from '../../../components/Skeleton';
import useDebounce from '../../../hooks/useDebounce';
import hospitalService from '../../../services/hospitalService';

const BranchAuditLogsView = ({ refreshKey = 0 }) => {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [roleFilter, setRoleFilter] = useState('ALL');
  const [page, setPage] = useState(0);
  const pageSize = 10;

  const debouncedSearch = useDebounce(searchTerm, 300);

  const loadLogs = useCallback(async () => {
    setLoading(true);
    try {
      const mappedRole = roleFilter === 'ALL' ? null : roleFilter;
      const data = await hospitalService.getPharmacyAuditLogs(debouncedSearch, mappedRole);
      setLogs(Array.isArray(data) ? data : []);
      setPage(0); // Reset page on new search/filter
    } catch (err) {
      console.error('Failed to load pharmacy audit logs:', err);
      setLogs([]);
    } finally {
      setLoading(false);
    }
  }, [debouncedSearch, roleFilter, refreshKey]);

  useEffect(() => {
    loadLogs();
  }, [loadLogs]);

  // Client-side pagination
  const totalPages = Math.ceil(logs.length / pageSize);
  const paginatedLogs = logs.slice(page * pageSize, (page + 1) * pageSize);

  const formatDate = (timestamp) => {
    return new Date(timestamp).toLocaleDateString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  };

  const formatTime = (timestamp) => {
    return new Date(timestamp)
      .toLocaleTimeString('en-IN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true,
      })
      .toUpperCase();
  };

  const getActionBadgeColor = (action) => {
    if (!action) return 'bg-gray-50 text-gray-600 border-gray-200';
    const act = action.toUpperCase();
    if (act.includes('CREATE') || act.includes('ADD') || act.includes('SAVE'))
      return 'bg-green-50 text-green-700 border-green-200';
    if (
      act.includes('DELETE') ||
      act.includes('REMOVE') ||
      act.includes('BLOCK') ||
      act.includes('DISPOSE')
    )
      return 'bg-red-50 text-red-700 border-red-200';
    if (act.includes('UPDATE') || act.includes('EDIT') || act.includes('RESET'))
      return 'bg-blue-50 text-blue-700 border-blue-200';
    return 'bg-gray-50 text-gray-700 border-gray-200';
  };

  return (
    <div className="bg-white rounded-2xl border border-gray-200/80 shadow-sm overflow-hidden">
      {/* Header and Filters */}
      <div className="px-6 py-5 border-b border-gray-100 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 bg-gray-50/50">
        <div>
          <h3 className="font-bold text-gray-900 text-base">Pharmacy Audit Trails</h3>
          <p className="text-xs text-gray-500 mt-1">
            Real-time log of pharmacy operations (suppliers, inventory, purchases, sales)
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* Search Input */}
          <div className="relative">
            <input
              type="text"
              placeholder="Search logs..."
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="pl-9 pr-4 py-2 border border-gray-300 rounded-xl text-xs outline-none focus:border-gray-900 w-48 sm:w-56 transition-all"
            />
            <svg
              className="w-4 h-4 text-gray-400 absolute left-3 top-2.5"
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

          {/* Role Filter - Pharmacy Only Roles */}
          <select
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-xl text-xs font-semibold outline-none focus:border-gray-900 bg-white transition-all cursor-pointer"
          >
            <option value="ALL">All Pharmacy Users</option>
            <option value="HOSPITAL_ADMIN">Pharmacy Admin</option>
            <option value="PHARMACIST">Pharmacists</option>
          </select>
        </div>
      </div>

      {/* Table Area */}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-gray-50 text-[10px] uppercase tracking-widest text-gray-400 font-black border-b border-gray-100">
            <tr>
              <th className="px-6 py-4 text-center w-16">S.No.</th>
              <th className="px-6 py-4">Timestamp</th>
              <th className="px-6 py-4">Action</th>
              <th className="px-6 py-4">Details</th>
              <th className="px-6 py-4">Performed By</th>
              <th className="px-6 py-4">Entity Type</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading ? (
              Array.from({ length: 6 }).map((_, i) => (
                <SkeletonTableRow key={i} cols={6} delay={i} />
              ))
            ) : paginatedLogs.length > 0 ? (
              paginatedLogs.map((log, index) => (
                <tr key={log.id || index} className="hover:bg-gray-50/50 transition-colors">
                  <td className="px-6 py-4 text-center font-semibold text-gray-500">
                    {page * pageSize + index + 1}
                  </td>
                  <td className="px-6 py-4">
                    <div>
                      <p className="text-sm font-bold text-gray-900">{formatDate(log.timestamp)}</p>
                      <p className="text-[10px] text-gray-400 font-bold mt-0.5">
                        {formatTime(log.timestamp)}
                      </p>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-black uppercase border ${getActionBadgeColor(log.action)}`}
                    >
                      {log.action}
                    </span>
                  </td>
                  <td
                    className="px-6 py-4 text-sm text-gray-700 font-medium max-w-xs truncate"
                    title={log.details}
                  >
                    {log.details || '-'}
                  </td>
                  <td className="px-6 py-4 text-sm text-gray-800 font-semibold">
                    {log.performedBy || 'System'}
                  </td>
                  <td className="px-6 py-4 text-xs font-bold text-gray-400 uppercase tracking-wider">
                    {log.entityType || 'N/A'}
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={6} className="py-20 text-center text-gray-400 italic bg-white text-sm">
                  No audit logs found matching the filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination Controls */}
      {totalPages > 1 && (
        <div className="px-6 py-4 border-t border-gray-100 bg-gray-50/50 flex items-center justify-between">
          <span className="text-xs text-gray-500 font-semibold">
            Showing {page * pageSize + 1} - {Math.min((page + 1) * pageSize, logs.length)} of{' '}
            {logs.length} trails
          </span>
          <div className="flex items-center gap-1">
            <button
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
              className="p-1.5 border border-gray-300 rounded-lg hover:bg-white disabled:opacity-50 disabled:hover:bg-transparent transition-all cursor-pointer"
            >
              <svg
                className="w-4 h-4 text-gray-600"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 19l-7-7 7-7"
                />
              </svg>
            </button>
            {Array.from({ length: totalPages }).map((_, i) => (
              <button
                key={i}
                onClick={() => setPage(i)}
                className={`px-2.5 py-1 text-xs font-bold rounded-lg transition-all cursor-pointer ${
                  page === i
                    ? 'bg-gray-900 text-white shadow-sm'
                    : 'border border-gray-300 hover:bg-white text-gray-600'
                }`}
              >
                {i + 1}
              </button>
            ))}
            <button
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              className="p-1.5 border border-gray-300 rounded-lg hover:bg-white disabled:opacity-50 disabled:hover:bg-transparent transition-all cursor-pointer"
            >
              <svg
                className="w-4 h-4 text-gray-600"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default BranchAuditLogsView;
