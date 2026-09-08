import React, { useState, useEffect, useCallback, useMemo } from 'react';
import DateSelect from '../../components/DateSelect';
import { useToast } from '../../context/ToastContext';
import calendarService from '../../services/calendarService';
import { backdropProps } from '../../utils/modalA11y';

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];
const DOW = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const EVENT_TYPES = ['HOLIDAY', 'EVENT', 'NOTICE'];

const typePill = (type) => {
  if (type === 'HOLIDAY') return 'bg-red-50 text-red-700 border-red-100';
  if (type === 'EVENT') return 'bg-indigo-50 text-indigo-700 border-indigo-100';
  return 'bg-amber-50 text-amber-700 border-amber-100';
};

const hhmm = (t) => (t ? String(t).slice(0, 5) : '');

/**
 * HospitalCalendar - month grid + day drill-down + holiday/event CRUD
 * (Nursing Mgmt Phase G). Shared by the incharge and admin dashboards.
 */
const HospitalCalendar = () => {
  const { success, error: toastError } = useToast();
  const today = new Date();
  const [year, setYear] = useState(today.getFullYear());
  const [month, setMonth] = useState(today.getMonth() + 1); // 1..12
  const [days, setDays] = useState([]);
  const [loading, setLoading] = useState(false);

  const [selectedDate, setSelectedDate] = useState(null);
  const [dayDetail, setDayDetail] = useState(null);
  const [dayLoading, setDayLoading] = useState(false);

  const [eventFormOpen, setEventFormOpen] = useState(false);

  const loadMonth = useCallback(async () => {
    setLoading(true);
    try {
      setDays(await calendarService.getMonth(year, month));
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load calendar');
    } finally {
      setLoading(false);
    }
  }, [year, month, toastError]);

  useEffect(() => {
    loadMonth();
  }, [loadMonth]);

  const openDay = async (dateStr) => {
    setSelectedDate(dateStr);
    setDayLoading(true);
    setDayDetail(null);
    try {
      setDayDetail(await calendarService.getDay(dateStr));
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load day');
    } finally {
      setDayLoading(false);
    }
  };

  // Leading blanks so day 1 lands under the right weekday (Mon-first).
  const leadingBlanks = useMemo(() => {
    const firstDow = new Date(year, month - 1, 1).getDay(); // 0=Sun..6=Sat
    return firstDow === 0 ? 6 : firstDow - 1;
  }, [year, month]);

  const prevMonth = () => {
    if (month === 1) {
      setMonth(12);
      setYear((y) => y - 1);
    } else setMonth((m) => m - 1);
  };
  const nextMonth = () => {
    if (month === 12) {
      setMonth(1);
      setYear((y) => y + 1);
    } else setMonth((m) => m + 1);
  };
  const goToday = () => {
    setYear(today.getFullYear());
    setMonth(today.getMonth() + 1);
  };

  const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;

  return (
    <div className="p-4 space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-2">
          <button
            onClick={prevMonth}
            className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
          >
            ‹ Prev
          </button>
          <button
            onClick={goToday}
            className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
          >
            Today
          </button>
          <button
            onClick={nextMonth}
            className="px-2.5 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
          >
            Next ›
          </button>
          <span className="ml-2 text-lg font-bold text-gray-900">
            {MONTHS[month - 1]} {year}
          </span>
        </div>
        <button
          onClick={() => setEventFormOpen(true)}
          className="px-4 py-2 bg-gray-900 text-white text-sm font-semibold rounded-lg hover:bg-gray-800"
        >
          + Add Event
        </button>
      </div>

      <div className="bg-white border border-gray-200 rounded-2xl overflow-hidden">
        <div className="grid grid-cols-7 bg-gray-50 border-b border-gray-200">
          {DOW.map((d) => (
            <div key={d} className="px-2 py-2 text-xs font-semibold text-gray-500 text-center">
              {d}
            </div>
          ))}
        </div>
        {loading ? (
          <div className="p-8 text-center text-gray-500">Loading…</div>
        ) : (
          <div className="grid grid-cols-7">
            {Array.from({ length: leadingBlanks }).map((_, i) => (
              <div
                key={`b${i}`}
                className="min-h-[92px] border-b border-r border-gray-100 bg-gray-50/40"
              />
            ))}
            {days.map((day) => {
              const dateStr = String(day.date);
              const dayNum = Number(dateStr.slice(8, 10));
              const isToday = dateStr === todayStr;
              return (
                <button
                  key={dateStr}
                  onClick={() => openDay(dateStr)}
                  className={`min-h-[92px] border-b border-r border-gray-100 p-1.5 text-left align-top hover:bg-gray-50 transition-colors ${day.hasHoliday ? 'bg-red-50/40' : ''}`}
                >
                  <div
                    className={`text-xs font-semibold mb-1 ${isToday ? 'text-white bg-gray-900 rounded-full w-5 h-5 flex items-center justify-center' : 'text-gray-700'}`}
                  >
                    {dayNum}
                  </div>
                  {day.shiftCount > 0 && (
                    <div className="text-[10px] text-gray-600 mb-0.5">
                      {day.shiftCount} shift{day.shiftCount > 1 ? 's' : ''}
                    </div>
                  )}
                  {day.surgeryCount > 0 && (
                    <div className="text-[10px] text-purple-700 mb-0.5">
                      🔪 {day.surgeryCount} surgery
                    </div>
                  )}
                  {(day.events || []).slice(0, 2).map((e) => (
                    <div
                      key={e.publicId}
                      className={`text-[10px] px-1 py-0.5 mb-0.5 rounded border truncate ${typePill(e.type)}`}
                    >
                      {e.title}
                    </div>
                  ))}
                  {(day.events || []).length > 2 && (
                    <div className="text-[10px] text-gray-400">+{day.events.length - 2} more</div>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {selectedDate && (
        <DayPanel
          date={selectedDate}
          detail={dayDetail}
          loading={dayLoading}
          onClose={() => {
            setSelectedDate(null);
            setDayDetail(null);
          }}
          onChanged={() => {
            loadMonth();
            openDay(selectedDate);
          }}
        />
      )}

      {eventFormOpen && (
        <EventForm
          initialDate={selectedDate}
          onClose={() => setEventFormOpen(false)}
          onSaved={() => {
            setEventFormOpen(false);
            loadMonth();
            if (selectedDate) openDay(selectedDate);
            success('Event saved');
          }}
          onError={(msg) => toastError(msg)}
        />
      )}
    </div>
  );
};

const DayPanel = ({ date, detail, loading, onClose, onChanged }) => {
  const { success, error: toastError } = useToast();
  const del = async (publicId) => {
    try {
      await calendarService.deleteEvent(publicId);
      success('Event deleted');
      onChanged();
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to delete');
    }
  };
  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-40 flex justify-end z-50"
      {...backdropProps(onClose)}
    >
      <div className="bg-white w-full max-w-md h-full overflow-y-auto p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-bold text-gray-900">{date}</h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-700 text-xl">
            ✕
          </button>
        </div>
        {loading || !detail ? (
          <div className="text-gray-500">Loading…</div>
        ) : (
          <div className="space-y-5 text-sm">
            <section>
              <h3 className="font-semibold text-gray-700 mb-1">Nurses on shift</h3>
              {detail.shifts.length === 0 ? (
                <p className="text-gray-400">None</p>
              ) : (
                detail.shifts.map((s, i) => (
                  <div key={i} className="text-gray-700">
                    {s.nurseName} — {s.wardName} ({hhmm(s.startTime)}–{hhmm(s.endTime)})
                  </div>
                ))
              )}
            </section>
            <section>
              <h3 className="font-semibold text-gray-700 mb-1">Attendance</h3>
              <p className="text-gray-700">
                Present {detail.attendance.present} · Absent {detail.attendance.absent} · Leave{' '}
                {detail.attendance.onLeave}
              </p>
            </section>
            <section>
              <h3 className="font-semibold text-gray-700 mb-1">Surgeries</h3>
              {detail.surgeries.length === 0 ? (
                <p className="text-gray-400">None</p>
              ) : (
                detail.surgeries.map((s, i) => (
                  <div key={i} className="text-gray-700">
                    {hhmm(s.scheduledTime)} — {s.patientName} ({s.surgeonName || '—'})
                    {s.procedureName ? ` · ${s.procedureName}` : ''}
                    {' · '}
                    <span className="font-medium">{s.otRoomName || s.otWardName || '—'}</span>
                    {' · '}
                    {s.status}
                  </div>
                ))
              )}
            </section>
            <section>
              <h3 className="font-semibold text-gray-700 mb-1">Events</h3>
              {detail.events.length === 0 ? (
                <p className="text-gray-400">None</p>
              ) : (
                detail.events.map((e) => (
                  <div key={e.publicId} className="flex items-center justify-between">
                    <span className="text-gray-700">
                      {e.title} <span className="text-xs text-gray-400">({e.type})</span>
                    </span>
                    <button
                      onClick={() => del(e.publicId)}
                      className="text-red-600 text-xs hover:text-red-700"
                    >
                      Delete
                    </button>
                  </div>
                ))
              )}
            </section>
          </div>
        )}
      </div>
    </div>
  );
};

const EventForm = ({ initialDate, onClose, onSaved, onError }) => {
  const [title, setTitle] = useState('');
  const [eventType, setEventType] = useState('HOLIDAY');
  const [fromDate, setFromDate] = useState(initialDate || '');
  const [toDate, setToDate] = useState(initialDate || '');
  const [description, setDescription] = useState('');
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    if (!title.trim() || !fromDate || !toDate) {
      onError('Title and dates are required');
      return;
    }
    setSaving(true);
    try {
      await calendarService.createEvent({
        title: title.trim(),
        eventType,
        fromDate,
        toDate,
        description,
      });
      onSaved();
    } catch (e) {
      onError(e?.response?.data?.error || 'Failed to save event');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-md p-6">
        <h2 className="text-lg font-bold text-gray-900 mb-4">Add Calendar Event</h2>
        <div className="space-y-3">
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Title *"
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
          <select
            value={eventType}
            onChange={(e) => setEventType(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          >
            {EVENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label htmlFor="fld-117" className="block text-xs text-gray-500 mb-1">
                From
              </label>
              <DateSelect value={fromDate} onChange={(v) => setFromDate(v)} />
            </div>
            <div>
              <label htmlFor="fld-116" className="block text-xs text-gray-500 mb-1">
                To
              </label>
              <DateSelect value={toDate} onChange={(v) => setToDate(v)} />
            </div>
          </div>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Description (optional)"
            rows={2}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
        </div>
        <div className="mt-5 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={saving}
            className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={saving}
            className="px-4 py-2 text-sm bg-gray-900 text-white rounded-lg hover:bg-gray-800 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default HospitalCalendar;
