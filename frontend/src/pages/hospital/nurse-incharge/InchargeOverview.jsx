import React, { useEffect, useState } from 'react';
import nurseService from '../../../services/nurseService';

/**
 * InchargeOverview - the Nurse Incharge dashboard landing: Patients / Nurses /
 * Beds tiles aggregated across the incharge's wards, plus quick actions that
 * jump to the relevant tab.
 */
const Tile = ({ label, value, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl px-4 py-3">
    <div className={`text-2xl font-extrabold ${accent || 'text-gray-900'}`}>{value ?? 0}</div>
    <div className="text-xs font-semibold text-gray-500 uppercase tracking-wide mt-0.5">
      {label}
    </div>
  </div>
);

const Group = ({ title, children }) => (
  <div className="mb-6">
    <h3 className="text-sm font-bold text-gray-800 mb-2">{title}</h3>
    <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">{children}</div>
  </div>
);

const InchargeOverview = ({ onNavigate, refreshKey }) => {
  const [d, setD] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    nurseService
      .getInchargeDashboard()
      .then((res) => setD(res || null))
      .catch(() => setD(null))
      .finally(() => setLoading(false));
  }, [refreshKey]);

  if (loading) return <div className="text-center text-gray-400 py-16">Loading…</div>;

  const p = d?.patients || {},
    n = d?.nurses || {},
    b = d?.beds || {};

  const actions = [
    { label: 'Create Nurse', tab: 'my-nurses', cls: 'bg-gray-900 hover:bg-gray-800' },
    { label: 'Mark Attendance', tab: 'attendance', cls: 'bg-indigo-600 hover:bg-indigo-700' },
    { label: 'Manage Beds', tab: 'beds', cls: 'bg-teal-600 hover:bg-teal-700' },
    { label: 'View Schedule', tab: 'schedule', cls: 'bg-blue-600 hover:bg-blue-700' },
  ];

  return (
    <div>
      <Group title="Patients">
        <Tile label="Total Patients" value={p.total} />
        <Tile label="New Admissions" value={p.newAdmissionsToday} accent="text-green-600" />
        <Tile label="Discharges Today" value={p.dischargesToday} accent="text-blue-600" />
      </Group>

      <Group title="Nurses">
        <Tile label="Total Nurses" value={n.total} />
        <Tile label="Present" value={n.present} accent="text-green-600" />
        <Tile label="Absent" value={n.absent} accent="text-red-600" />
        <Tile label="On Leave" value={n.onLeave} accent="text-amber-600" />
      </Group>

      <Group title="Beds">
        <Tile label="Total Beds" value={b.total} />
        <Tile label="Available" value={b.available} accent="text-green-600" />
        <Tile label="Occupied" value={b.occupied} accent="text-blue-600" />
        <Tile label="Cleaning Required" value={b.cleaningRequired} accent="text-amber-600" />
        <Tile label="Under Maintenance" value={b.underMaintenance} accent="text-gray-500" />
      </Group>

      <div>
        <h3 className="text-sm font-bold text-gray-800 mb-2">Quick Actions</h3>
        <div className="flex flex-wrap gap-3">
          {actions.map((a) => (
            <button
              key={a.tab}
              onClick={() => onNavigate && onNavigate(a.tab)}
              className={`px-4 py-2 rounded-lg text-sm font-semibold text-white ${a.cls}`}
            >
              {a.label}
            </button>
          ))}
          <button
            onClick={() => onNavigate && onNavigate('calendar')}
            className="px-4 py-2 rounded-lg text-sm font-semibold text-gray-700 bg-white border border-gray-300 hover:bg-gray-50"
          >
            View Calendar
          </button>
        </div>
      </div>
    </div>
  );
};

export default InchargeOverview;
