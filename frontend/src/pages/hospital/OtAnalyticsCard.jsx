import React, { useState, useEffect, useCallback } from 'react';
import DateSelect from '../../components/DateSelect';
import { useToast } from '../../context/ToastContext';
import otService from '../../services/otService';

/**
 * OtAnalyticsCard - the NABH surgical-care indicators over a date range.
 *
 * Every figure is a server query over what the earlier phases already record — the
 * transition audit, the WHO checklist columns, and the occupancy timeline. Nothing is
 * computed in the browser; a null means "no data yet", never a fabricated zero.
 */
const Metric = ({ label, value, suffix = '', hint }) => (
  <div className="bg-white border border-gray-200 rounded-xl px-4 py-3">
    <div className="text-[11px] uppercase tracking-wide text-gray-400">{label}</div>
    <div className="text-2xl font-bold text-gray-900">
      {value == null ? '—' : value}
      {value == null ? '' : suffix}
    </div>
    {hint && <div className="text-[11px] text-gray-400 mt-0.5">{hint}</div>}
  </div>
);

const iso = (d) => d.toISOString().slice(0, 10);

const OtAnalyticsCard = () => {
  const { error: toastError } = useToast();
  const today = new Date();
  const monthAgo = new Date(today.getTime() - 29 * 864e5);
  const [from, setFrom] = useState(iso(monthAgo));
  const [to, setTo] = useState(iso(today));
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(
    async (f, t) => {
      setLoading(true);
      try {
        setData(await otService.getOtNabh(f, t));
      } catch (e) {
        toastError(e?.response?.data?.error || 'Failed to load OT analytics');
      } finally {
        setLoading(false);
      }
    },
    [toastError]
  );

  useEffect(() => {
    load(from, to);
  }, [from, to]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 space-y-5">
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h3 className="text-lg font-semibold text-gray-900">OT Analytics (NABH)</h3>
          <p className="text-xs text-gray-500 mt-1">
            Surgical-care indicators over the selected period.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <DateSelect value={from} onChange={(v) => setFrom(v)} />
          <span className="text-gray-400 text-sm">to</span>
          <DateSelect value={to} onChange={(v) => setTo(v)} />
        </div>
      </div>

      {loading ? (
        <div className="text-center text-gray-400 py-8">Loading…</div>
      ) : (
        data && (
          <>
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <Metric label="Completed" value={data.completed} />
              <Metric label="Cancelled" value={data.cancelled} />
              <Metric
                label="WHO compliance"
                value={data.whoCompliancePercent}
                suffix="%"
                hint="all 3 phases signed"
              />
              <Metric label="Cancellation rate" value={data.cancellationRatePercent} suffix="%" />
              <Metric
                label="First-case on-time"
                value={data.firstCaseOnTimePercent}
                suffix="%"
                hint="within 15 min of schedule"
              />
              <Metric label="Avg turnover" value={data.averageTurnoverMinutes} suffix=" min" />
              <Metric
                label="Occupied theatre-hrs"
                value={
                  data.occupiedTheatreMinutes != null
                    ? (data.occupiedTheatreMinutes / 60).toFixed(1)
                    : null
                }
              />
              <Metric
                label="Unplanned returns"
                value={data.unplannedReturns}
                hint="within 30 days"
              />
            </div>

            {Array.isArray(data.cancellationsByReason) && data.cancellationsByReason.length > 0 && (
              <div className="border border-gray-200 rounded-lg p-3">
                <div className="text-sm font-semibold text-gray-900 mb-2">
                  Cancellations by reason
                </div>
                <div className="space-y-1">
                  {data.cancellationsByReason.map((r) => (
                    <div key={r.reason} className="flex justify-between text-sm">
                      <span className="text-gray-600">{String(r.reason).replace(/_/g, ' ')}</span>
                      <span className="font-semibold text-gray-800">{r.count}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        )
      )}
    </div>
  );
};

export default OtAnalyticsCard;
