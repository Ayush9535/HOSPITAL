import React, { useCallback, useEffect, useState } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import icuService from '../../../services/icuService';

/**
 * IcuDashboard - ICU capacity at a glance (ICU Phase 2).
 *
 * Read-only. Every number comes from the same single board response the bed board uses, so the
 * headline figures and the grid can never disagree: they are one snapshot of one set of records.
 *
 * Refreshes on the tenant's existing REFRESH_DATA broadcast via the host dashboard's
 * `refreshKey` — bed status changes already fire it, so ICU needs no realtime code of its own.
 */
const StatCard = ({ label, value, tone = 'default', hint }) => {
  const tones = {
    default: 'text-gray-900',
    occupied: 'text-blue-700',
    available: 'text-green-700',
    warn: 'text-amber-700',
    alert: 'text-red-700',
  };
  return (
    <div className="bg-white border border-gray-200 rounded-xl p-4">
      <div className="text-xs font-medium text-gray-500 uppercase tracking-wide">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${tones[tone] || tones.default}`}>{value}</div>
      {hint && <div className="mt-1 text-xs text-gray-500">{hint}</div>}
    </div>
  );
};

const IcuDashboard = ({ refreshKey = 0, onOpenBedBoard }) => {
  const { error: toastError } = useToast();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    setLoading(true);
    icuService
      .getUnits()
      .then(setData)
      .catch((e) => toastError(e?.response?.data?.error || 'Failed to load the ICU dashboard'))
      .finally(() => setLoading(false));
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  if (loading && !data) return <LoadingSpinner />;
  if (!data) return null;

  if (!data.hasCriticalCareUnits) {
    return (
      <EmptyState
        icon={null}
        title="No critical care units yet"
        message="Mark a ward as ICU, NICU, PICU, CCU, MICU, SICU or HDU in Wards & Beds, and it will appear here."
      />
    );
  }

  if (!data.units || data.units.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No ICU units in your scope"
        message="This hospital has critical care units, but none of them are assigned to you."
      />
    );
  }

  const t = data.totals || {};
  const occupancyPct = t.totalBeds ? Math.round((t.occupied / t.totalBeds) * 100) : 0;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3">
        <StatCard label="Total Beds" value={t.totalBeds ?? 0} hint={`${occupancyPct}% occupied`} />
        <StatCard label="Occupied" value={t.occupied ?? 0} tone="occupied" />
        <StatCard label="Available" value={t.available ?? 0} tone="available" />
        <StatCard
          label="Awaiting Cleaning"
          value={t.awaitingCleaning ?? 0}
          tone={t.awaitingCleaning ? 'warn' : 'default'}
        />
        <StatCard label="Maintenance" value={t.maintenance ?? 0} />
        <StatCard
          label="Patients"
          value={t.patients ?? 0}
          hint={`${t.newAdmissionsToday ?? 0} admitted today`}
        />
      </div>

      {(t.pendingConfirmation > 0 || t.occupancyMismatches > 0) && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {t.pendingConfirmation > 0 && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
              <div className="text-sm font-semibold text-amber-800">
                {t.pendingConfirmation} admission{t.pendingConfirmation === 1 ? '' : 's'} pending
                nurse confirmation
              </div>
              <div className="text-xs text-amber-700 mt-1">
                The bed is held, but the admission form has not been completed yet.
              </div>
            </div>
          )}
          {t.occupancyMismatches > 0 && (
            /* Surfaced, not smoothed over: the bed record and the admission record disagree,
               and a ward can only fix what it can see. */
            <div className="bg-red-50 border border-red-200 rounded-xl p-4">
              <div className="text-sm font-semibold text-red-800">
                {t.occupancyMismatches} bed{t.occupancyMismatches === 1 ? '' : 's'} need attention
              </div>
              <div className="text-xs text-red-700 mt-1">
                Bed status and admission records disagree. Open the bed board to see which.
              </div>
            </div>
          )}
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
              <th className="px-4 py-3">UNIT</th>
              <th className="px-4 py-3">TYPE</th>
              <th className="px-4 py-3 text-right">BEDS</th>
              <th className="px-4 py-3 text-right">OCCUPIED</th>
              <th className="px-4 py-3 text-right">AVAILABLE</th>
              <th className="px-4 py-3 text-right">CLEANING</th>
              <th className="px-4 py-3 text-right">PATIENTS</th>
              <th className="px-4 py-3 text-right">NEW TODAY</th>
            </tr>
          </thead>
          <tbody>
            {data.units.map((u) => (
              <tr
                key={u.wardId}
                className="border-b border-gray-100 last:border-0 hover:bg-gray-50 cursor-pointer"
                onClick={() => onOpenBedBoard && onOpenBedBoard(u.wardId)}
              >
                <td className="px-4 py-3 font-medium text-gray-900">{u.wardName}</td>
                <td className="px-4 py-3 text-gray-600">{u.unitTypeLabel}</td>
                <td className="px-4 py-3 text-right">{u.counts?.totalBeds ?? 0}</td>
                <td className="px-4 py-3 text-right text-blue-700">{u.counts?.occupied ?? 0}</td>
                <td className="px-4 py-3 text-right text-green-700">{u.counts?.available ?? 0}</td>
                <td className="px-4 py-3 text-right text-amber-700">{u.counts?.cleaning ?? 0}</td>
                <td className="px-4 py-3 text-right">{u.counts?.patients ?? 0}</td>
                <td className="px-4 py-3 text-right">{u.counts?.newAdmissionsToday ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default IcuDashboard;
