import React, { useState, useEffect, useCallback } from 'react';
import { useToast } from '../../context/ToastContext';
import otService from '../../services/otService';
import { extractApiError } from '../../utils/apiError';

/**
 * OtRoomsCard - manage operation theatres.
 *
 * Replaces the "ward named OT" convention. Existing OT-named wards are OFFERED as
 * suggestions (the same heuristic matches "FOOT WARD"), never auto-converted.
 */
const OtRoomsCard = () => {
  const { success, error: toastError } = useToast();
  const [rooms, setRooms] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [turnover, setTurnover] = useState('15');
  const [editing, setEditing] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [r, s] = await Promise.all([
        otService.getRooms(),
        otService.getRoomSuggestions().catch(() => []),
      ]);
      setRooms(Array.isArray(r) ? r : []);
      setSuggestions(Array.isArray(s) ? s : []);
    } catch (e) {
      toastError(extractApiError(e, 'Failed to load theatres'));
    } finally {
      setLoading(false);
    }
  }, [toastError]);

  // Load once on mount; useToast identities change per render, so don't depend on them.
  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const add = async (roomName, sourceWardId) => {
    if (!roomName || !roomName.trim()) {
      toastError('Theatre name is required');
      return;
    }
    setBusy(true);
    try {
      const created = await otService.createRoom({
        name: roomName.trim(),
        turnoverMinutes: Number(turnover) || 15,
        sourceWardId: sourceWardId || null,
      });
      setRooms((prev) => [...prev, created]);
      setSuggestions((prev) => prev.filter((w) => w.wardId !== sourceWardId));
      setName('');
      success('Theatre added');
    } catch (e) {
      toastError(extractApiError(e, 'Failed to add theatre'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (room) => {
    setBusy(true);
    try {
      await otService.deactivateRoom(room.publicId);
      setRooms((prev) => prev.filter((r) => r.publicId !== room.publicId));
      success('Theatre removed');
    } catch (e) {
      toastError(extractApiError(e, 'Failed to deactivate theatre'));
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = async () => {
    if (!editing.name.trim()) {
      toastError('Theatre name is required');
      return;
    }
    setBusy(true);
    try {
      const updated = await otService.updateRoom(editing.publicId, {
        name: editing.name.trim(),
        turnoverMinutes: Number(editing.turnover) || 15,
      });
      setRooms((prev) => prev.map((room) => (room.publicId === updated.publicId ? updated : room)));
      setEditing(null);
      success('Theatre updated');
    } catch (e) {
      toastError(extractApiError(e, 'Failed to update theatre'));
    } finally {
      setBusy(false);
    }
  };

  if (loading)
    return (
      <div className="bg-white rounded-2xl border border-gray-200 p-6 text-gray-400">Loading…</div>
    );

  return (
    <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6 space-y-5">
      <div>
        <h3 className="text-lg font-semibold text-gray-900">OT Theatres</h3>
        <p className="text-xs text-gray-500 mt-1">
          The operation theatres reception can schedule into. Turnover is the cleaning and set-up
          time enforced between two cases in the same theatre.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <div className="flex-1 min-w-[180px]">
          <label htmlFor="fld-133" className="block text-xs font-medium text-gray-600 mb-1">
            Theatre name
          </label>
          <input
            id="fld-133"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. OT-1 / Main Theatre"
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
        </div>
        <div className="w-32">
          <label htmlFor="fld-132" className="block text-xs font-medium text-gray-600 mb-1">
            Turnover (min)
          </label>
          <input
            id="fld-132"
            type="number"
            min="0"
            value={turnover}
            onChange={(e) => setTurnover(e.target.value)}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
          />
        </div>
        <button
          type="button"
          disabled={busy}
          onClick={() => add(name)}
          className="px-4 py-2 rounded-lg text-sm font-semibold bg-gray-900 text-white hover:bg-gray-800 disabled:bg-gray-300"
        >
          + Add
        </button>
      </div>

      {rooms.length > 0 ? (
        <div className="border border-gray-200 rounded-lg divide-y divide-gray-100">
          {rooms.map((r) => (
            <div key={r.publicId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
              {editing?.publicId === r.publicId ? (
                <div className="flex flex-1 flex-wrap items-end gap-2">
                  <label className="flex-1 min-w-[160px] text-xs font-medium text-gray-600">
                    Theatre name
                    <input
                      aria-label="Edit theatre name"
                      value={editing.name}
                      onChange={(e) => setEditing((prev) => ({ ...prev, name: e.target.value }))}
                      className="mt-1 w-full px-2 py-1 border border-gray-300 rounded"
                    />
                  </label>
                  <label className="w-28 text-xs font-medium text-gray-600">
                    Turnover (min)
                    <input
                      aria-label="Edit theatre turnover"
                      type="number"
                      min="0"
                      value={editing.turnover}
                      onChange={(e) => setEditing((prev) => ({ ...prev, turnover: e.target.value }))}
                      className="mt-1 w-full px-2 py-1 border border-gray-300 rounded"
                    />
                  </label>
                  <button type="button" disabled={busy} onClick={saveEdit} className="text-xs text-primary-700 hover:text-primary-900">
                    Save
                  </button>
                  <button type="button" disabled={busy} onClick={() => setEditing(null)} className="text-xs text-gray-500 hover:text-gray-700">
                    Cancel
                  </button>
                </div>
              ) : (
              <div>
                <span className="font-semibold text-gray-800">{r.name}</span>
                <span className="ml-2 text-xs text-gray-400">turnover {r.turnoverMinutes} min</span>
                <span
                  className={`ml-2 text-xs px-2 py-0.5 rounded-full ${r.status === 'AVAILABLE' ? 'bg-green-50 text-green-700' : 'bg-amber-50 text-amber-700'}`}
                >
                  {r.status}
                </span>
              </div>
              )}
              {editing?.publicId !== r.publicId && <div className="flex items-center gap-3">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => setEditing({ publicId: r.publicId, name: r.name, turnover: String(r.turnoverMinutes) })}
                  className="text-xs text-primary-600 hover:text-primary-800"
                >
                  Edit
                </button>
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => remove(r)}
                  className="text-xs text-red-500 hover:text-red-700"
                >
                  Deactivate
                </button>
              </div>}
            </div>
          ))}
        </div>
      ) : (
        <p className="text-sm text-gray-400">
          No theatres yet. Add one above, or convert a suggested ward below.
        </p>
      )}

      {suggestions.length > 0 && (
        <div className="bg-amber-50 border border-amber-100 rounded-lg p-3">
          <p className="text-xs font-semibold text-amber-800 mb-2">
            Wards that look like theatres — confirm each (some, like &quot;FOOT WARD&quot;, only
            match by chance):
          </p>
          <div className="flex flex-wrap gap-2">
            {suggestions.map((w) => (
              <button
                key={w.wardId}
                type="button"
                disabled={busy}
                onClick={() => add(w.wardName, w.wardId)}
                className="px-3 py-1 text-xs font-medium bg-white border border-amber-300 rounded-full hover:bg-amber-100"
              >
                + {w.wardName}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default OtRoomsCard;
