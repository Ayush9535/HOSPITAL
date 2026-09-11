import React, { useEffect, useState } from 'react';
import attendanceService from '../../../services/attendanceService';

/** MyAttendanceView - the logged-in nurse's own attendance for the last 30 days. */
const STATUS_STYLE = {
  PRESENT: 'bg-green-100 text-green-700',
  ABSENT: 'bg-red-100 text-red-700',
  HALF_DAY: 'bg-amber-100 text-amber-700',
  LEAVE: 'bg-blue-100 text-blue-700',
  HOLIDAY: 'bg-purple-100 text-purple-700',
  LATE: 'bg-orange-100 text-orange-700',
};
const LABEL = {
  PRESENT: 'Present',
  ABSENT: 'Absent',
  HALF_DAY: 'Half Day',
  LEAVE: 'Leave',
  HOLIDAY: 'Holiday',
  LATE: 'Late',
};
const hhmm = (t) => (t ? String(t).slice(0, 5) : '');
const fmtDate = (d) =>
  d
    ? new Date(d).toLocaleDateString('en-IN', { weekday: 'short', day: '2-digit', month: 'short' })
    : '';

const MyAttendanceView = ({ refreshKey }) => {
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - 30);
    const iso = (d) => d.toISOString().slice(0, 10);
    setLoading(true);
    attendanceService
      .getMine(iso(from), iso(to))
      .then((d) => setRows(Array.isArray(d) ? d : []))
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, [refreshKey]);

  if (loading) return <div className="text-center text-gray-400 py-16">Loading…</div>;
  if (rows.length === 0)
    return <div className="text-center text-gray-400 py-16">No attendance recorded.</div>;

  return (
    <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead>
          <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
            <th className="px-4 py-3">DATE</th>
            <th className="px-4 py-3">STATUS</th>
            <th className="px-4 py-3">SHIFT</th>
            <th className="px-4 py-3">CHECK-IN</th>
            <th className="px-4 py-3">CHECK-OUT</th>
            <th className="px-4 py-3">REMARKS</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.publicId} className="border-b border-gray-100">
              <td className="px-4 py-3 font-medium text-gray-900">{fmtDate(r.attendanceDate)}</td>
              <td className="px-4 py-3">
                <span
                  className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${STATUS_STYLE[r.status] || 'bg-gray-100 text-gray-600'}`}
                >
                  {LABEL[r.status] || r.status}
                </span>
              </td>
              <td className="px-4 py-3 text-gray-500">
                {r.shiftStartTime ? `${hhmm(r.shiftStartTime)}–${hhmm(r.shiftEndTime)}` : '—'}
              </td>
              <td className="px-4 py-3 text-gray-600">{hhmm(r.checkInTime) || '—'}</td>
              <td className="px-4 py-3 text-gray-600">{hhmm(r.checkOutTime) || '—'}</td>
              <td className="px-4 py-3 text-gray-600">{r.remarks || '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default MyAttendanceView;
