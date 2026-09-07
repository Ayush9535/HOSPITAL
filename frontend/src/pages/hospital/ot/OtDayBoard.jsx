import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { useToast } from '../../../context/ToastContext';
import otService from '../../../services/otService';

/**
 * OtDayBoard - the theatres × time grid for one day.
 *
 * Rooms are columns, the day's hours are rows, and each scheduled case sits in its
 * theatre's column at its time, sized by estimated duration. Degrades to a single-column
 * list when the hospital has one theatre (or none, using the legacy view upstream).
 */
const START_HOUR = 7; // grid runs 07:00–21:00; cases outside clamp to the edges
const END_HOUR = 21;
const PX_PER_MIN = 1.1; // row height scale

const statusColor = (s) =>
  ({
    SCHEDULED: 'bg-blue-100 border-blue-300 text-blue-800',
    PRE_OP: 'bg-indigo-100 border-indigo-300 text-indigo-800',
    IN_PROGRESS: 'bg-green-100 border-green-300 text-green-800',
    COMPLETED: 'bg-gray-100 border-gray-300 text-gray-600',
  })[s] || 'bg-gray-100 border-gray-300 text-gray-600';

const OtDayBoard = ({ date, onSelect }) => {
  const { error: toastError } = useToast();
  const [rooms, setRooms] = useState([]);
  const [cases, setCases] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [r, list] = await Promise.all([
        otService.getRooms().catch(() => []),
        otService.getOtList(date).catch(() => []),
      ]);
      setRooms(Array.isArray(r) ? r : []);
      setCases(Array.isArray(list) ? list : []);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to load day board');
    } finally {
      setLoading(false);
    }
  }, [date, toastError]);

  useEffect(() => {
    load();
  }, [load]);

  const hours = useMemo(() => {
    const out = [];
    for (let h = START_HOUR; h <= END_HOUR; h++) out.push(h);
    return out;
  }, []);
  const gridHeight = (END_HOUR - START_HOUR) * 60 * PX_PER_MIN;

  const topFor = (dt) => {
    const d = new Date(dt);
    const mins = (d.getHours() - START_HOUR) * 60 + d.getMinutes();
    return Math.max(0, Math.min(mins, (END_HOUR - START_HOUR) * 60)) * PX_PER_MIN;
  };
  const heightFor = (c) => Math.max(28, (c.estimatedDurationMinutes || 60) * PX_PER_MIN);

  // Cases whose theatre matches a room (by name — the list carries otRoomName).
  const casesForRoom = (room) =>
    cases.filter((c) => (c.otRoomName || c.otWardName) === room.name && c.scheduledAt);
  const unroomed = cases.filter(
    (c) => c.scheduledAt && !rooms.some((r) => r.name === (c.otRoomName || c.otWardName))
  );

  if (loading) return <div className="text-center text-gray-400 py-16">Loading…</div>;
  if (rooms.length === 0) return null; // caller shows the list/legacy board instead

  return (
    <div className="overflow-x-auto border border-gray-200 rounded-xl bg-white">
      <div className="flex min-w-max">
        {/* time gutter */}
        <div className="w-14 shrink-0 border-r border-gray-100" style={{ height: gridHeight + 40 }}>
          <div className="h-10 border-b border-gray-100" />
          <div className="relative" style={{ height: gridHeight }}>
            {hours.map((h) => (
              <div
                key={h}
                className="absolute left-0 right-0 text-[10px] text-gray-400 pl-1"
                style={{ top: (h - START_HOUR) * 60 * PX_PER_MIN - 6 }}
              >
                {String(h).padStart(2, '0')}:00
              </div>
            ))}
          </div>
        </div>

        {rooms.map((room) => (
          <div key={room.publicId} className="w-48 shrink-0 border-r border-gray-100">
            <div className="h-10 flex items-center justify-center text-sm font-semibold text-gray-800 border-b border-gray-100 bg-gray-50">
              {room.name}
            </div>
            <div className="relative" style={{ height: gridHeight }}>
              {hours.map((h) => (
                <div
                  key={h}
                  className="absolute left-0 right-0 border-t border-gray-50"
                  style={{ top: (h - START_HOUR) * 60 * PX_PER_MIN }}
                />
              ))}
              {casesForRoom(room).map((c) => (
                <button
                  key={c.publicId}
                  onClick={() => onSelect && onSelect(c)}
                  className={`absolute left-1 right-1 rounded-lg border px-2 py-1 text-left overflow-hidden ${statusColor(c.status)}`}
                  style={{ top: topFor(c.scheduledAt), height: heightFor(c) }}
                >
                  <div className="text-xs font-semibold truncate">{c.patientName || 'Patient'}</div>
                  <div className="text-[10px] truncate">{c.procedureName}</div>
                  <div className="text-[10px] truncate opacity-75">{c.surgeonName}</div>
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>

      {unroomed.length > 0 && (
        <div className="border-t border-gray-100 p-3">
          <div className="text-xs font-semibold text-gray-500 mb-1">
            Scheduled without a theatre
          </div>
          <div className="flex flex-wrap gap-2">
            {unroomed.map((c) => (
              <button
                key={c.publicId}
                onClick={() => onSelect && onSelect(c)}
                className="px-2 py-1 rounded-lg border border-amber-300 bg-amber-50 text-xs text-amber-800"
              >
                {new Date(c.scheduledAt).toLocaleTimeString('en-IN', {
                  hour: '2-digit',
                  minute: '2-digit',
                })}{' '}
                · {c.patientName} · {c.procedureName}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default OtDayBoard;
