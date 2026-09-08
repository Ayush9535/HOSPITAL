import React, { useState, useEffect, useCallback } from 'react';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseScheduleService from '../../../services/nurseScheduleService';

const toYMD = (d) => {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${dd}`;
};

const hhmm = (t) => (t ? String(t).slice(0, 5) : '');

/**
 * MyShiftsView - the logged-in nurse's own shift schedule for the next 14 days
 * (Nursing Mgmt Phase B2).
 */
const MyShiftsView = ({ refreshKey }) => {
  const { error: toastError } = useToast();
  const [loading, setLoading] = useState(true);
  const [shifts, setShifts] = useState([]);

  const loadShifts = useCallback(async () => {
    setLoading(true);
    try {
      const today = new Date();
      const to = new Date();
      to.setDate(to.getDate() + 14);
      const data = await nurseScheduleService.getMine(toYMD(today), toYMD(to));
      const list = Array.isArray(data) ? data : [];
      list.sort((a, b) => (a.shiftDate < b.shiftDate ? -1 : a.shiftDate > b.shiftDate ? 1 : 0));
      setShifts(list);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load your shifts');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    loadShifts();
  }, [loadShifts, refreshKey]);

  const fmtDate = (dateStr) => {
    if (!dateStr) return '—';
    const d = new Date(`${dateStr}T00:00:00`);
    return d.toLocaleDateString('en-IN', { weekday: 'short', day: '2-digit', month: 'short' });
  };

  if (loading) return <LoadingSpinner />;

  return (
    <div className="space-y-4">
      <div className="bg-white border border-gray-200 rounded-xl p-5">
        <h3 className="font-bold text-gray-800 text-sm mb-1">My Shifts</h3>
        <p className="text-xs text-gray-500">Your scheduled shifts for the next 14 days.</p>
      </div>

      {shifts.length === 0 ? (
        <div className="bg-white border border-gray-200 rounded-xl p-10 text-center text-gray-500">
          No shifts scheduled.
        </div>
      ) : (
        <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">DATE</th>
                <th className="px-4 py-3">SHIFT TIME</th>
              </tr>
            </thead>
            <tbody>
              {shifts.map((s) => (
                <tr key={s.publicId} className="border-b border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">{fmtDate(s.shiftDate)}</td>
                  <td className="px-4 py-3 text-gray-600">
                    {s.startTime && s.endTime ? `${hhmm(s.startTime)}–${hhmm(s.endTime)}` : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default MyShiftsView;
