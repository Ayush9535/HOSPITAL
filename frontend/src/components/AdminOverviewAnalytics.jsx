import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Area,
  AreaChart,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import hospitalService from '../services/hospitalService';
import { SkeletonRect, SkeletonStatsGrid } from './Skeleton';

/**
 * The Hospital Admin's operational overview.
 *
 * <h3>What decides what is on screen</h3>
 *
 * The response is the source of truth, and the only one. A block the hospital's plan does not
 * include is absent from the JSON, and an absent block is simply not rendered — no disabled card,
 * no "upgrade to unlock", no reserved space. Reconstructing that decision here from the JWT's
 * module claims would be a second copy of the entitlement rules, free to drift from the first and
 * working from a snapshot taken at login; the server already resolved it against the live plan.
 *
 * Three states have to stay distinguishable, because an administrator acts differently on each:
 * the module is absent (nothing rendered), the module is owned and quiet (rendered, showing a real
 * zero), or the request failed (an error with a retry, never a zero). Collapsing the third into
 * the second is the failure mode worth naming — a dashboard showing 0 when it actually knows
 * nothing is worse than one that admits it is broken.
 *
 * <h3>Layout</h3>
 *
 * Column counts are derived from how many blocks came back rather than fixed in the markup. A
 * two-column grid holding one card is half an empty screen — the defect that made the Doctor
 * Overview look broken for hospitals without appointments — and this page cannot assume any
 * particular set of modules.
 */

const RANGES = [
  { value: 'TODAY', label: 'Today' },
  { value: 'LAST_7_DAYS', label: 'Last 7 Days' },
  { value: 'LAST_30_DAYS', label: 'Last 30 Days' },
];

const SPECIALITY_COLORS = ['#0284c7', '#0891b2', '#7c3aed', '#c026d3', '#ea580c'];
const VISIT_TYPE_COLORS = ['#0284c7', '#14b8a6', '#f59e0b', '#8b5cf6', '#ef4444', '#64748b'];

/** Grid columns from the number of items, so a short list never leaves the row half empty. */
const colsFor = (count) => {
  if (count <= 1) return 'grid-cols-1';
  if (count === 2) return 'grid-cols-1 sm:grid-cols-2';
  if (count === 3) return 'grid-cols-1 sm:grid-cols-3';
  if (count === 4) return 'grid-cols-2 lg:grid-cols-4';
  if (count === 5) return 'grid-cols-2 md:grid-cols-3 xl:grid-cols-5';
  return 'grid-cols-2 md:grid-cols-3 xl:grid-cols-6';
};

const panelColsFor = (count) => {
  if (count <= 1) return 'grid-cols-1';
  return 'grid-cols-1 lg:grid-cols-2';
};

const formatNumber = (value) => new Intl.NumberFormat('en-IN').format(value ?? 0);

/**
 * Money, at the precision the backend actually sent.
 *
 * <p>Collection is a sum of payments and arrives as a decimal. Rounding it to whole units — which
 * an earlier version did — turns 0.49 into 0 and 1234.56 into 1,235: a day's takings reported as
 * nothing, and a figure that will not reconcile against the billing screen. Fractions are shown
 * when there are any and omitted when there are none, so a round number still reads as a round
 * number. The currency is the one the response carried; hard-coding INR would be wrong the day it
 * is not.
 */
const formatMoney = (amount, currency) => {
  const value = Number(amount ?? 0);
  if (!Number.isFinite(value)) return '—';
  const hasFraction = !Number.isInteger(value);
  const options = {
    style: 'currency',
    currency: currency || 'INR',
    minimumFractionDigits: hasFraction ? 2 : 0,
    maximumFractionDigits: 2,
  };
  try {
    return new Intl.NumberFormat('en-IN', options).format(value);
  } catch {
    // An unrecognised or malformed currency code must not take the card — or the page — down.
    // Intl throws on a bad code, so fall back to the number with the code beside it.
    const plain = new Intl.NumberFormat('en-IN', {
      minimumFractionDigits: hasFraction ? 2 : 0,
      maximumFractionDigits: 2,
    }).format(value);
    return `${currency || ''} ${plain}`.trim();
  }
};

/** "2026-09-06" → "6 Sep", for axis ticks that have to fit thirty of themselves. */
const shortDay = (iso) => {
  if (!iso) return '';
  const parsed = new Date(`${iso}T00:00:00`);
  if (Number.isNaN(parsed.getTime())) return iso;
  return parsed.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
};

const titleCase = (raw) =>
  String(raw ?? '')
    .toLowerCase()
    .replace(/(^|[\s_-])(\w)/g, (_, sep, ch) => `${sep === '_' ? ' ' : sep}${ch.toUpperCase()}`);

const KpiCard = ({ label, value, hint }) => (
  <div className="bg-white rounded-xl border border-neutral-200 p-4 shadow-sm">
    <p className="text-[11px] font-semibold uppercase tracking-wider text-slate-500">{label}</p>
    <p className="mt-1.5 text-2xl font-bold leading-none text-slate-900">{value}</p>
    {hint ? <p className="mt-1.5 text-xs text-slate-500">{hint}</p> : null}
  </div>
);

const Panel = ({ title, subtitle, children }) => (
  <section className="bg-white rounded-2xl border border-neutral-200 shadow-sm flex flex-col">
    <header className="px-5 py-4 border-b border-neutral-100">
      <h3 className="text-sm font-bold text-slate-800">{title}</h3>
      {subtitle ? <p className="text-xs text-slate-500 mt-0.5">{subtitle}</p> : null}
    </header>
    <div className="p-5 flex-1">{children}</div>
  </section>
);

const EmptyNote = ({ children }) => (
  <p className="text-sm text-slate-500 py-8 text-center">{children}</p>
);

/** A proportional bar row. Used wherever a count reads better against its peers than alone. */
const BarRow = ({ label, value, percent, color, colorClass }) => (
  <li>
    <div className="flex justify-between items-baseline text-sm mb-1 gap-3">
      <span className="text-slate-700 truncate">{label}</span>
      <span className="font-semibold text-slate-900 tabular-nums">{formatNumber(value)}</span>
    </div>
    <div className="h-2 rounded-full bg-neutral-100 overflow-hidden">
      <div
        className={`h-full rounded-full ${colorClass || ''}`}
        style={{ width: `${percent}%`, backgroundColor: color }}
      />
    </div>
  </li>
);

const TrendChart = ({ data, label, color }) => {
  const points = (data || []).map((b) => ({ ...b, day: shortDay(b.date) }));
  const hasActivity = points.some((p) => p.count > 0);
  return (
    <div>
      <div className="h-52">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={points} margin={{ top: 5, right: 8, left: -22, bottom: 0 }}>
            <defs>
              <linearGradient id={`trend-${label}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={color} stopOpacity={0.35} />
                <stop offset="95%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <XAxis
              dataKey="day"
              tick={{ fontSize: 11, fill: '#64748b' }}
              interval="preserveStartEnd"
              axisLine={false}
              tickLine={false}
            />
            <YAxis
              allowDecimals={false}
              tick={{ fontSize: 11, fill: '#64748b' }}
              axisLine={false}
              tickLine={false}
              width={40}
            />
            <Tooltip formatter={(value) => [formatNumber(value), label]} />
            <Area
              type="monotone"
              dataKey="count"
              name={label}
              stroke={color}
              strokeWidth={2}
              fill={`url(#trend-${label})`}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      {!hasActivity ? (
        <p className="text-xs text-slate-500 text-center mt-1">No activity in this period.</p>
      ) : null}
    </div>
  );
};

const AdminOverviewAnalytics = () => {
  const [range, setRange] = useState('TODAY');
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Every load takes a ticket, and only the holder of the latest one may write state. Requests
  // are not guaranteed to come back in the order they were sent: switch from Today to Last 7 Days
  // on a slow link and the Today response can land second, overwriting the newer numbers under
  // the newer label. That applies to failures too — a stale rejection must not replace a current
  // success with an error — and to the loading flag, which a stale request must not clear while a
  // newer one is still in flight. StrictMode's double-invoked effects are the same problem in
  // development, and the same ticket resolves them: the first pass simply loses.
  const requestId = useRef(0);

  const load = useCallback(async (selectedRange) => {
    const ticket = (requestId.current += 1);
    const isCurrent = () => requestId.current === ticket;

    setLoading(true);
    setError(null);
    try {
      const overview = await hospitalService.getDashboardOverview(selectedRange);
      if (!isCurrent()) return;
      setData(overview);
    } catch (err) {
      if (!isCurrent()) return;
      // Deliberately no partial data: a failed load must not leave the previous range's numbers
      // on screen under a new label, and must never be mistaken for a quiet day.
      setData(null);
      setError(err?.response?.data?.error || 'Could not load the overview.');
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(range);
  }, [load, range]);

  const rangeSelector = (
    <div
      className="inline-flex rounded-lg border border-neutral-200 bg-white p-0.5"
      role="group"
      aria-label="Date range"
    >
      {RANGES.map((option) => {
        const selected = option.value === range;
        return (
          <button
            key={option.value}
            type="button"
            onClick={() => setRange(option.value)}
            aria-pressed={selected}
            className={`px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
              selected ? 'bg-sky-600 text-white shadow-sm' : 'text-slate-600 hover:bg-neutral-100'
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );

  const kpis = useMemo(() => {
    if (!data) return [];
    const cards = [
      {
        key: 'active-patients',
        // The backend field is totalRegisteredPatients, but the query counts active patients
        // only. The label follows the query, not the field name.
        label: 'Active Patients',
        value: formatNumber(data.core?.totalRegisteredPatients),
      },
    ];
    if (data.opd) {
      cards.push({ key: 'opd-visits', label: 'OPD Visits', value: formatNumber(data.opd.count) });
      cards.push({
        key: 'consultations',
        label: 'Consultations',
        value: formatNumber(data.opd.consultations),
      });
    }
    if (data.ipd) {
      cards.push({
        key: 'admissions',
        label: 'Admissions',
        value: formatNumber(data.ipd.admissions),
      });
    }
    if (data.beds) {
      const rate = data.beds.occupancyRate;
      cards.push({
        key: 'occupancy',
        label: 'Bed Occupancy',
        // null is not zero: it means there is no usable capacity to be a percentage of.
        value: rate === null || rate === undefined ? '—' : `${rate}%`,
        hint: `${formatNumber(data.beds.occupied)} of ${formatNumber(
          data.beds.usableCapacity
        )} usable`,
      });
    }
    if (data.billing) {
      cards.push({
        key: 'collection',
        label: 'Collection',
        value: formatMoney(data.billing.collection, data.billing.currency),
      });
    }
    if (data.pharmacy) {
      cards.push({
        key: 'pharmacy-sales',
        label: 'Pharmacy Sales',
        value: formatNumber(data.pharmacy.pharmacySales),
      });
    }
    return cards;
  }, [data]);

  const panels = useMemo(() => {
    if (!data) return [];
    const list = [];

    if (data.opd) {
      const visitTypes = data.opd.visitTypes || [];
      const specialities = data.opd.busiestSpecialities || [];
      const busiest = specialities.length ? Math.max(...specialities.map((s) => s.count)) : 0;

      list.push({
        key: 'opd-trend',
        node: (
          <Panel title="OPD Trend" subtitle="Visits per day">
            <TrendChart data={data.opd.trend} label="OPD Visits" color="#0284c7" />
          </Panel>
        ),
      });

      list.push({
        key: 'visit-types',
        node: (
          <Panel title="Visit Types" subtitle="Breakdown of OPD visits">
            {visitTypes.length ? (
              <div className="flex flex-col sm:flex-row items-center gap-4">
                <div className="h-44 w-full sm:w-1/2">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={visitTypes}
                        dataKey="count"
                        nameKey="type"
                        innerRadius={38}
                        outerRadius={64}
                        paddingAngle={2}
                      >
                        {visitTypes.map((entry, index) => (
                          <Cell
                            key={entry.type}
                            fill={VISIT_TYPE_COLORS[index % VISIT_TYPE_COLORS.length]}
                          />
                        ))}
                      </Pie>
                      <Tooltip
                        formatter={(value, name) => [formatNumber(value), titleCase(name)]}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
                <ul className="w-full sm:w-1/2 space-y-2">
                  {visitTypes.map((entry, index) => (
                    <li key={entry.type} className="flex items-center justify-between text-sm">
                      <span className="flex items-center gap-2 text-slate-600">
                        <span
                          className="inline-block w-2.5 h-2.5 rounded-sm"
                          style={{
                            backgroundColor: VISIT_TYPE_COLORS[index % VISIT_TYPE_COLORS.length],
                          }}
                        />
                        {titleCase(entry.type)}
                      </span>
                      <span className="font-semibold text-slate-800 tabular-nums">
                        {formatNumber(entry.count)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <EmptyNote>No OPD visits in this period.</EmptyNote>
            )}
          </Panel>
        ),
      });

      list.push({
        key: 'busiest-specialities',
        node: (
          <Panel title="Busiest Specialities" subtitle="Top specialities by visits">
            {specialities.length ? (
              // Rendered in the order the server returned. It already ranked these, broke the
              // ties and truncated to the top few; re-sorting would quietly disagree with it, and
              // regrouping would undo the canonical Unassigned bucket.
              <ol className="space-y-3">
                {specialities.map((entry, index) => (
                  <BarRow
                    key={entry.speciality}
                    label={entry.speciality}
                    value={entry.count}
                    percent={busiest > 0 ? (entry.count / busiest) * 100 : 0}
                    color={SPECIALITY_COLORS[index % SPECIALITY_COLORS.length]}
                  />
                ))}
              </ol>
            ) : (
              <EmptyNote>No speciality activity in this period.</EmptyNote>
            )}
          </Panel>
        ),
      });
    }

    if (data.ipd) {
      list.push({
        key: 'ipd-trend',
        node: (
          <Panel title="IPD Trend" subtitle="Admissions per day">
            <TrendChart data={data.ipd.trend} label="Admissions" color="#7c3aed" />
          </Panel>
        ),
      });
    }

    if (data.beds) {
      const beds = data.beds;
      const share = (value) =>
        beds.usableCapacity > 0 ? Math.min(100, (value / beds.usableCapacity) * 100) : 0;
      const rows = [
        { label: 'Occupied', value: beds.occupied, colorClass: 'bg-sky-600' },
        { label: 'Available', value: beds.currentlyAvailable, colorClass: 'bg-emerald-500' },
        { label: 'Cleaning', value: beds.cleaning, colorClass: 'bg-amber-500' },
        { label: 'Maintenance', value: beds.maintenance, colorClass: 'bg-slate-400' },
      ];
      // Surfaced only when it exists. An always-present "Unknown: 0" invites a question that has
      // no answer on a hospital whose bed statuses are all recognised.
      if (beds.unknownStatusCount > 0) {
        rows.push({
          label: 'Unknown Status',
          value: beds.unknownStatusCount,
          colorClass: 'bg-rose-400',
        });
      }
      list.push({
        key: 'beds',
        node: (
          <Panel
            title="Bed Status"
            subtitle={`Usable capacity ${formatNumber(beds.usableCapacity)} · excludes maintenance`}
          >
            <ul className="space-y-3">
              {rows.map((row) => (
                <BarRow
                  key={row.label}
                  label={row.label}
                  value={row.value}
                  percent={share(row.value)}
                  colorClass={row.colorClass}
                />
              ))}
            </ul>
            {beds.usableCapacity === 0 ? (
              <p className="text-xs text-slate-500 pt-3">
                No usable capacity, so there is no occupancy rate to report.
              </p>
            ) : null}
          </Panel>
        ),
      });
    }

    return list;
  }, [data]);

  // The header is outside every branch below. The range selector has to stay on screen and keep
  // its focus while a request is in flight — swapping it out for a skeleton on each fetch makes
  // changing your mind mid-load impossible and moves focus out from under the keyboard.
  const header = (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="text-2xl font-bold text-gray-900">Overview</h2>
      {rangeSelector}
    </div>
  );

  const body = () => {
    if (loading) {
      // Skeletons rather than zeros: a zero that is really "not loaded yet" is a number someone
      // could act on.
      return (
        <div className="space-y-6" data-testid="overview-analytics-loading">
          <SkeletonStatsGrid count={4} />
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <SkeletonRect width="w-full" height="h-64" rounded="rounded-2xl" />
            <SkeletonRect width="w-full" height="h-64" rounded="rounded-2xl" />
          </div>
        </div>
      );
    }

    if (error) {
      return (
        <div
          className="bg-white rounded-2xl border border-neutral-200 shadow-sm p-8 text-center"
          role="alert"
        >
          <p className="text-sm font-bold text-gray-900">Couldn&apos;t load the overview</p>
          <p className="mt-1 text-sm text-gray-600">{error}</p>
          <p className="mt-1 text-xs text-gray-500">This is not the same as having no activity.</p>
          <button
            type="button"
            onClick={() => load(range)}
            className="mt-4 px-4 py-2 text-xs font-black uppercase tracking-widest bg-gray-900 text-white rounded"
          >
            Retry
          </button>
        </div>
      );
    }

    return (
      <>
        <div className={`grid ${colsFor(kpis.length)} gap-4`}>
          {kpis.map((card) => (
            <KpiCard key={card.key} label={card.label} value={card.value} hint={card.hint} />
          ))}
        </div>

        {panels.length > 0 ? (
          <div className={`grid ${panelColsFor(panels.length)} gap-6`}>
            {panels.map((panel) => (
              <React.Fragment key={panel.key}>{panel.node}</React.Fragment>
            ))}
          </div>
        ) : null}
      </>
    );
  };

  return (
    <div className="space-y-6">
      {header}
      {body()}
    </div>
  );
};

export default AdminOverviewAnalytics;
