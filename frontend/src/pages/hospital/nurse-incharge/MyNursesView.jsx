import React, { useState, useEffect, useCallback } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseService from '../../../services/nurseService';
import { describeShift, isOnShiftNow } from '../../../utils/nurseShift';

/**
 * MyNursesView - the Nurse Incharge's roster (Nursing Mgmt).
 * Lists the staff nurses across the wards this incharge manages. The backend
 * scopes the list to the caller's wards, so each nurse appears under exactly one
 * incharge (isolation).
 */
const MyNursesView = ({ refreshKey }) => {
  const { error: toastError } = useToast();
  const [nurses, setNurses] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await nurseService.getMyNurses();
      setNurses(Array.isArray(data) ? data : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load nurses');
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  if (loading) return <LoadingSpinner />;

  if (nurses.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No nurses"
        message="No staff nurses are assigned to your ward(s) yet. The admin assigns nurses to wards."
      />
    );
  }

  return (
    <div className="bg-white border border-gray-200 rounded-2xl overflow-hidden shadow-sm">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">Nurse</th>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">ID</th>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">Ward</th>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">Phone</th>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">On shift</th>
              <th className="px-4 py-3 text-left font-semibold text-gray-600">Login</th>
            </tr>
          </thead>
          <tbody>
            {nurses.map((n) => (
              <tr
                key={n.nurseProfileId}
                className="border-b border-gray-100 last:border-0 hover:bg-gray-50/50"
              >
                <td className="px-4 py-3 font-semibold text-gray-900">{n.name}</td>
                <td className="px-4 py-3 text-gray-600">{n.customId || '—'}</td>
                <td className="px-4 py-3 text-gray-700">{n.wardName || `Ward ${n.wardId}`}</td>
                <td className="px-4 py-3 text-gray-600">{n.phone || '—'}</td>
                <td className="px-4 py-3">
                  {/* Same wording the admin's staff list uses. "Off" alone could not tell a nurse
                      who is rostered but not on yet from one with no shift at all. */}
                  <div className="leading-tight">
                    <span
                      className={
                        n.shiftName || n.shiftStartTime ? 'text-gray-800 text-sm' : 'text-gray-400 text-sm'
                      }
                    >
                      {describeShift(n)}
                    </span>
                    {isOnShiftNow(n) && (
                      <span className="ml-2 px-2 py-0.5 rounded-full text-[10px] font-bold bg-green-100 text-green-700">
                        ON SHIFT
                      </span>
                    )}
                  </div>
                </td>
                <td className="px-4 py-3">
                  {n.hasLogin ? (
                    <span className="text-xs text-gray-600">Yes</span>
                  ) : (
                    <span className="text-xs text-gray-400">No login</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default MyNursesView;
