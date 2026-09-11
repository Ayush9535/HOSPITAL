import React, { useState, useEffect, useCallback, useMemo } from 'react';
import DateSelect from '../../../components/DateSelect';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseScheduleService from '../../../services/nurseScheduleService';
import nurseService from '../../../services/nurseService';
import timeSlotService from '../../../services/timeSlotService';
import { backdropProps } from '../../../utils/modalA11y';

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const toYMD = (d) => {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${dd}`;
};

const getMonday = (d) => {
  const date = new Date(d);
  date.setHours(0, 0, 0, 0);
  const day = date.getDay(); // 0=Sun..6=Sat
  const diff = day === 0 ? -6 : 1 - day;
  date.setDate(date.getDate() + diff);
  return date;
};

const addDays = (d, n) => {
  const nd = new Date(d);
  nd.setDate(nd.getDate() + n);
  return nd;
};

const hhmm = (t) => (t ? String(t).slice(0, 5) : '');

/**
 * ShiftScheduleView - Nurse Incharge weekly shift scheduler (Nursing Mgmt Phase B2).
 * Ward selector + week navigator; grid of staff nurses x 7 days. Click a cell to
 * assign/clear a shift template for that nurse/day. "Range Fill" bulk-assigns a
 * nurse's shifts across a date range with optional day-of-week filtering.
 */
const ShiftScheduleView = () => {
  const { success, error: toastError } = useToast();

  const [wards, setWards] = useState([]); // [{ wardId, wardName }]
  const [selectedWardId, setSelectedWardId] = useState(null);
  const [wardsLoading, setWardsLoading] = useState(true);

  const [weekStart, setWeekStart] = useState(() => getMonday(new Date()));

  const [nurses, setNurses] = useState([]); // [{id,name}]
  const [schedules, setSchedules] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [gridLoading, setGridLoading] = useState(false);

  const [editingCell, setEditingCell] = useState(null); // { nurseProfileId, dateStr }
  const [saving, setSaving] = useState(false);

  const [rangeFillOpen, setRangeFillOpen] = useState(false);

  const days = useMemo(
    () => Array.from({ length: 7 }, (_, i) => addDays(weekStart, i)),
    [weekStart]
  );
  const fromStr = toYMD(days[0]);
  const toStr = toYMD(days[6]);

  // Load the incharge's wards (all wards for admin).
  useEffect(() => {
    let active = true;
    setWardsLoading(true);
    nurseService
      .getMyWards()
      .then((data) => {
        if (!active) return;
        const list = Array.isArray(data) ? data : [];
        const wardList = list.map((w) => ({
          wardId: w.wardId,
          wardName: w.wardName || `Ward ${w.wardId}`,
        }));
        setWards(wardList);
        setSelectedWardId((prev) => prev ?? wardList[0]?.wardId ?? null);
      })
      .catch((e) => {
        if (active) toastError(e?.response?.data?.error || 'Failed to load wards');
      })
      .finally(() => {
        if (active) setWardsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [toastError]);

  // Shift templates loaded once.
  useEffect(() => {
    let active = true;
    timeSlotService
      .listShiftTemplates(true)
      .then((data) => {
        if (active) setTemplates(Array.isArray(data) ? data : []);
      })
      .catch((e) => {
        if (active) toastError(e?.response?.data?.error || 'Failed to load shift templates');
      });
    return () => {
      active = false;
    };
  }, [toastError]);

  const loadGrid = useCallback(async () => {
    if (!selectedWardId) return;
    setGridLoading(true);
    try {
      const [nurseList, scheduleList] = await Promise.all([
        nurseService.getWardStaffNurses(selectedWardId),
        nurseScheduleService.getWard(selectedWardId, fromStr, toStr),
      ]);
      setNurses(Array.isArray(nurseList) ? nurseList : []);
      setSchedules(Array.isArray(scheduleList) ? scheduleList : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load schedule');
    } finally {
      setGridLoading(false);
    }
  }, [selectedWardId, fromStr, toStr, toastError]);

  useEffect(() => {
    loadGrid();
  }, [loadGrid]);

  const scheduleFor = (nurseProfileId, dateStr) =>
    schedules.find((s) => s.nurseProfileId === nurseProfileId && s.shiftDate === dateStr);

  const templateNameFor = (schedule) => {
    if (!schedule) return null;
    const t = templates.find((tpl) => tpl.id === schedule.shiftTemplateId);
    if (t) return t.name;
    if (schedule.startTime && schedule.endTime)
      return `${hhmm(schedule.startTime)}–${hhmm(schedule.endTime)}`;
    return null;
  };

  const handlePick = async (nurseProfileId, dateStr, value) => {
    const existing = scheduleFor(nurseProfileId, dateStr);
    setSaving(true);
    try {
      if (value === '') {
        if (existing) {
          await nurseScheduleService.remove(existing.publicId);
          success('Shift cleared');
        }
      } else {
        await nurseScheduleService.assign(nurseProfileId, dateStr, value);
        success('Shift assigned');
      }
      setEditingCell(null);
      await loadGrid();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to update shift');
    } finally {
      setSaving(false);
    }
  };

  const weekLabel = `${days[0].toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })} – ${days[6].toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}`;

  if (wardsLoading) return <LoadingSpinner />;

  if (wards.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No wards found"
        message="You don't have any wards with admitted patients yet."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap items-center gap-3 justify-between">
        <div className="flex items-center gap-3 flex-wrap">
          <div>
            <label htmlFor="fld-162" className="block text-xs font-medium text-gray-500 mb-1">
              Ward
            </label>
            <select
              id="fld-162"
              value={selectedWardId ?? ''}
              onChange={(e) => setSelectedWardId(Number(e.target.value))}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
            >
              {wards.map((w) => (
                <option key={w.wardId} value={w.wardId}>
                  {w.wardName}
                </option>
              ))}
            </select>
          </div>

          <div>
            <span className="block text-xs font-medium text-gray-500 mb-1">Week</span>
            <div className="flex items-center gap-1.5">
              <button
                onClick={() => setWeekStart((w) => addDays(w, -7))}
                className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
              >
                &larr; Prev
              </button>
              <button
                onClick={() => setWeekStart(getMonday(new Date()))}
                className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
              >
                This week
              </button>
              <button
                onClick={() => setWeekStart((w) => addDays(w, 7))}
                className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
              >
                Next &rarr;
              </button>
              <span className="ml-2 text-sm font-semibold text-gray-700">{weekLabel}</span>
            </div>
          </div>
        </div>

        <button
          onClick={() => setRangeFillOpen(true)}
          className="px-4 py-2 text-sm font-semibold rounded-lg bg-gray-900 text-white hover:bg-gray-800"
        >
          Range Fill
        </button>
      </div>

      {gridLoading ? (
        <LoadingSpinner />
      ) : nurses.length === 0 ? (
        <EmptyState
          icon={null}
          title="No staff nurses"
          message="This ward has no active staff nurses to schedule."
        />
      ) : (
        <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3 sticky left-0 bg-white">NURSE</th>
                {days.map((d, i) => (
                  <th key={i} className="px-3 py-3 text-center whitespace-nowrap">
                    {DAY_LABELS[i]}
                    <br />
                    <span className="font-normal text-gray-400">
                      {d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {nurses.map((n) => (
                <tr key={n.id} className="border-b border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900 whitespace-nowrap sticky left-0 bg-white">
                    {n.name}
                  </td>
                  {days.map((d, i) => {
                    const dateStr = toYMD(d);
                    const schedule = scheduleFor(n.id, dateStr);
                    const isEditing =
                      editingCell &&
                      editingCell.nurseProfileId === n.id &&
                      editingCell.dateStr === dateStr;
                    return (
                      <td key={i} className="px-2 py-2 text-center">
                        {isEditing ? (
                          <select
                            autoFocus
                            disabled={saving}
                            defaultValue=""
                            onChange={(e) => handlePick(n.id, dateStr, e.target.value)}
                            onBlur={() => setEditingCell(null)}
                            className="px-2 py-1 text-xs border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
                          >
                            <option value="" disabled>
                              Select…
                            </option>
                            {templates.map((t) => (
                              <option key={t.publicId} value={t.publicId}>
                                {t.name}
                              </option>
                            ))}
                            {schedule && <option value="">Clear</option>}
                          </select>
                        ) : (
                          <button
                            onClick={() => setEditingCell({ nurseProfileId: n.id, dateStr })}
                            className="w-full px-2 py-1.5 text-xs rounded-lg hover:bg-gray-100 text-gray-700"
                          >
                            {templateNameFor(schedule) || '—'}
                          </button>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {rangeFillOpen && (
        <RangeFillModal
          nurses={nurses}
          templates={templates}
          onClose={() => setRangeFillOpen(false)}
          onDone={() => {
            setRangeFillOpen(false);
            loadGrid();
          }}
        />
      )}
    </div>
  );
};

/** RangeFillModal - bulk-assign a nurse's shifts across a date range. */
const RangeFillModal = ({ nurses, templates, onClose, onDone }) => {
  const { success, error: toastError } = useToast();
  const [nurseProfileId, setNurseProfileId] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [daysOfWeek, setDaysOfWeek] = useState([]); // 1..7, Mon..Sun
  const [shiftTemplatePublicId, setShiftTemplatePublicId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const toggleDay = (dow) => {
    setDaysOfWeek((prev) => (prev.includes(dow) ? prev.filter((d) => d !== dow) : [...prev, dow]));
  };

  const handleSubmit = async () => {
    if (!nurseProfileId || !fromDate || !toDate || !shiftTemplatePublicId) {
      toastError('Please fill in all required fields');
      return;
    }
    setSubmitting(true);
    try {
      const result = await nurseScheduleService.rangeFill({
        nurseProfileId: Number(nurseProfileId),
        fromDate,
        toDate,
        shiftTemplatePublicId,
        daysOfWeek,
      });
      success(`Created ${result?.created ?? 0} shifts`);
      onDone();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to range-fill schedule');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-md p-6 max-h-[90vh] overflow-y-auto">
        <h2 className="text-lg font-bold text-gray-900 mb-4">Range Fill Shifts</h2>

        <div className="space-y-4">
          <div>
            <label htmlFor="fld-161" className="block text-xs font-medium text-gray-600 mb-1">
              Nurse
            </label>
            <select
              id="fld-161"
              value={nurseProfileId}
              onChange={(e) => setNurseProfileId(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500"
            >
              <option value="" disabled>
                Select nurse
              </option>
              {nurses.map((n) => (
                <option key={n.id} value={n.id}>
                  {n.name}
                </option>
              ))}
            </select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="fld-160" className="block text-xs font-medium text-gray-600 mb-1">
                From Date
              </label>
              <DateSelect value={fromDate} onChange={(v) => setFromDate(v)} />
            </div>
            <div>
              <label htmlFor="fld-159" className="block text-xs font-medium text-gray-600 mb-1">
                To Date
              </label>
              <DateSelect value={toDate} onChange={(v) => setToDate(v)} />
            </div>
          </div>

          <div>
            <span className="block text-xs font-medium text-gray-600 mb-1">
              Days of Week (none = all days)
            </span>
            <div className="flex flex-wrap gap-2">
              {DAY_LABELS.map((label, idx) => {
                const dow = idx + 1; // 1=Mon..7=Sun
                const checked = daysOfWeek.includes(dow);
                return (
                  <label
                    key={dow}
                    className={`px-2.5 py-1 text-xs font-semibold rounded-lg border cursor-pointer ${checked ? 'bg-gray-900 text-white border-gray-900' : 'bg-white text-gray-600 border-gray-300'}`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleDay(dow)}
                      className="hidden"
                    />
                    {label}
                  </label>
                );
              })}
            </div>
          </div>

          <div>
            <label htmlFor="fld-158" className="block text-xs font-medium text-gray-600 mb-1">
              Shift Template
            </label>
            <select
              id="fld-158"
              value={shiftTemplatePublicId}
              onChange={(e) => setShiftTemplatePublicId(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500"
            >
              <option value="" disabled>
                Select shift template
              </option>
              {templates.map((t) => (
                <option key={t.publicId} value={t.publicId}>
                  {t.name} ({hhmm(t.startTime)}–{hhmm(t.endTime)})
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className={`px-4 py-2 text-sm font-semibold rounded-lg text-white ${submitting ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-900 hover:bg-gray-800'}`}
          >
            {submitting ? 'Filling…' : 'Fill Schedule'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ShiftScheduleView;
