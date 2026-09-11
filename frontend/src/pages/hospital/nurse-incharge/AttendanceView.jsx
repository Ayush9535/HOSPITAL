import React, { useEffect, useState, useCallback } from 'react';
import DateSelect from '../../../components/DateSelect';
import { useToast } from '../../../context/ToastContext';
import attendanceService from '../../../services/attendanceService';
import nurseService from '../../../services/nurseService';

/**
 * AttendanceView - the Nurse Incharge's daily attendance sheet for a ward.
 * Pre-lists rostered nurses (shift window from the schedule) and any already
 * marked; the incharge can add another ward nurse. Each row is marked/edited
 * inline and saved (upsert). A summary strip shows the day's counts.
 */
const STATUSES = ['PRESENT', 'ABSENT', 'HALF_DAY', 'LEAVE', 'HOLIDAY', 'LATE'];
const STATUS_LABEL = {
  PRESENT: 'Present',
  ABSENT: 'Absent',
  HALF_DAY: 'Half Day',
  LEAVE: 'Leave',
  HOLIDAY: 'Holiday',
  LATE: 'Late',
};
const hhmm = (t) => (t ? String(t).slice(0, 5) : '');
const todayStr = () => new Date().toISOString().slice(0, 10);

const AttendanceView = () => {
  const { success, error: toastError } = useToast();
  const [wards, setWards] = useState([]);
  const [wardId, setWardId] = useState('');
  const [date, setDate] = useState(todayStr());
  const [rows, setRows] = useState([]);
  const [summary, setSummary] = useState(null);
  const [staff, setStaff] = useState([]);
  const [loading, setLoading] = useState(false);
  const [savingId, setSavingId] = useState(null);

  useEffect(() => {
    nurseService
      .getMyWards()
      .then((w) => {
        const list = Array.isArray(w) ? w : [];
        setWards(list);
        if (list.length && !wardId) setWardId(String(list[0].wardId));
      })
      .catch(() => setWards([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const load = useCallback(() => {
    if (!wardId || !date) return;
    setLoading(true);
    Promise.all([
      attendanceService.getSheet(wardId, date),
      attendanceService.getSummary(wardId, date),
      nurseService.getWardStaffNurses(wardId).catch(() => []),
    ])
      .then(([sheet, sum, nurses]) => {
        setRows(
          (Array.isArray(sheet) ? sheet : []).map((r) => ({
            ...r,
            _status: r.status || '',
            _checkIn: hhmm(r.checkInTime),
            _checkOut: hhmm(r.checkOutTime),
            _remarks: r.remarks || '',
          }))
        );
        setSummary(sum || null);
        setStaff(Array.isArray(nurses) ? nurses : []);
      })
      .catch(() => toastError('Failed to load attendance'))
      .finally(() => setLoading(false));
  }, [wardId, date, toastError]);

  useEffect(() => {
    load();
  }, [load]);

  const setRowField = (nurseProfileId, key, value) =>
    setRows((rs) =>
      rs.map((r) => (r.nurseProfileId === nurseProfileId ? { ...r, [key]: value } : r))
    );

  const saveRow = async (r) => {
    if (!r._status) {
      toastError('Pick a status first');
      return;
    }
    setSavingId(r.nurseProfileId);
    try {
      await attendanceService.mark({
        nurseProfileId: r.nurseProfileId,
        date,
        status: r._status,
        checkInTime: r._checkIn || null,
        checkOutTime: r._checkOut || null,
        remarks: r._remarks || null,
      });
      success('Attendance saved');
      load();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to save attendance');
    } finally {
      setSavingId(null);
    }
  };

  const addable = staff.filter((n) => !rows.some((r) => r.nurseProfileId === n.id));
  const addNurse = (id) => {
    const n = staff.find((s) => String(s.id) === String(id));
    if (!n || rows.some((r) => r.nurseProfileId === n.id)) return;
    setRows((rs) => [
      ...rs,
      {
        nurseProfileId: n.id,
        nurseName: n.name,
        _status: '',
        _checkIn: '',
        _checkOut: '',
        _remarks: '',
      },
    ]);
  };

  const Tile = ({ label, value, cls }) => (
    <div className={`px-3 py-2 rounded-lg text-center ${cls}`}>
      <div className="text-lg font-bold">{value}</div>
      <div className="text-[10px] font-semibold uppercase tracking-wide">{label}</div>
    </div>
  );

  return (
    <div>
      <div className="flex flex-wrap items-end gap-3 mb-4">
        <div>
          <label htmlFor="fld-147" className="block text-xs font-semibold text-gray-600 mb-1">
            Ward
          </label>
          <select
            id="fld-147"
            value={wardId}
            onChange={(e) => setWardId(e.target.value)}
            className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
          >
            <option value="">Select ward…</option>
            {wards.map((w) => (
              <option key={w.wardId} value={w.wardId}>
                {w.wardName}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="fld-146" className="block text-xs font-semibold text-gray-600 mb-1">
            Date
          </label>
          <DateSelect value={date} onChange={(v) => setDate(v)} />
        </div>
        {addable.length > 0 && (
          <div>
            <label htmlFor="fld-145" className="block text-xs font-semibold text-gray-600 mb-1">
              Add nurse
            </label>
            <select
              id="fld-145"
              value=""
              onChange={(e) => addNurse(e.target.value)}
              className="px-3 py-2 border border-gray-300 rounded-lg text-sm"
            >
              <option value="">Add a ward nurse…</option>
              {addable.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {summary && (
        <div className="grid grid-cols-4 sm:grid-cols-7 gap-2 mb-4">
          <Tile label="Present" value={summary.present} cls="bg-green-50 text-green-700" />
          <Tile label="Absent" value={summary.absent} cls="bg-red-50 text-red-700" />
          <Tile label="Half Day" value={summary.halfDay} cls="bg-amber-50 text-amber-700" />
          <Tile label="Leave" value={summary.leave} cls="bg-blue-50 text-blue-700" />
          <Tile label="Holiday" value={summary.holiday} cls="bg-purple-50 text-purple-700" />
          <Tile label="Late" value={summary.late} cls="bg-orange-50 text-orange-700" />
          <Tile label="Unmarked" value={summary.unmarked} cls="bg-gray-100 text-gray-600" />
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
              <th className="px-3 py-3">NURSE</th>
              <th className="px-3 py-3">SHIFT</th>
              <th className="px-3 py-3">STATUS</th>
              <th className="px-3 py-3">CHECK-IN</th>
              <th className="px-3 py-3">CHECK-OUT</th>
              <th className="px-3 py-3">REMARKS</th>
              <th className="px-3 py-3 text-right">ACTION</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-3 py-8 text-center text-gray-400">
                  {loading
                    ? 'Loading…'
                    : 'No nurses scheduled. Use "Add nurse" to mark leave/holiday.'}
                </td>
              </tr>
            )}
            {rows.map((r) => (
              <tr key={r.nurseProfileId} className="border-b border-gray-100">
                <td className="px-3 py-2 font-medium text-gray-900">{r.nurseName || '—'}</td>
                <td className="px-3 py-2 text-gray-500">
                  {r.shiftStartTime ? `${hhmm(r.shiftStartTime)}–${hhmm(r.shiftEndTime)}` : '—'}
                </td>
                <td className="px-3 py-2">
                  <select
                    value={r._status}
                    onChange={(e) => setRowField(r.nurseProfileId, '_status', e.target.value)}
                    className="px-2 py-1 border border-gray-300 rounded text-xs"
                  >
                    <option value="">—</option>
                    {STATUSES.map((s) => (
                      <option key={s} value={s}>
                        {STATUS_LABEL[s]}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="px-3 py-2">
                  <input
                    type="time"
                    value={r._checkIn}
                    onChange={(e) => setRowField(r.nurseProfileId, '_checkIn', e.target.value)}
                    className="px-2 py-1 border border-gray-300 rounded text-xs"
                  />
                </td>
                <td className="px-3 py-2">
                  <input
                    type="time"
                    value={r._checkOut}
                    onChange={(e) => setRowField(r.nurseProfileId, '_checkOut', e.target.value)}
                    className="px-2 py-1 border border-gray-300 rounded text-xs"
                  />
                </td>
                <td className="px-3 py-2">
                  <input
                    value={r._remarks}
                    onChange={(e) => setRowField(r.nurseProfileId, '_remarks', e.target.value)}
                    placeholder="Remark"
                    className="w-32 px-2 py-1 border border-gray-300 rounded text-xs"
                  />
                </td>
                <td className="px-3 py-2 text-right">
                  <button
                    onClick={() => saveRow(r)}
                    disabled={savingId === r.nurseProfileId}
                    className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800 disabled:bg-gray-400"
                  >
                    {savingId === r.nurseProfileId
                      ? 'Saving…'
                      : r.attendancePublicId
                        ? 'Update'
                        : 'Save'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AttendanceView;
