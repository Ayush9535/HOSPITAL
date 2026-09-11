import React, { useEffect, useState, useCallback } from 'react';
import { useToast } from '../../../context/ToastContext';
import useOtPermissions from '../../../hooks/useOtPermissions';
import otService from '../../../services/otService';
import { backdropProps } from '../../../utils/modalA11y';

/**
 * RecoveryModal - PACU recovery for a completed case.
 *
 * The theatre is already free (the case is COMPLETED); this is a separate record. Only
 * shown when the hospital's RECOVERY_TRACKING policy asks for it — the server rejects
 * admission otherwise, and this surfaces that message rather than pretending.
 *
 * OT-P0B: admission now requires a recovery bay. The picker only lists bays the server
 * reports as unoccupied, and the button stays disabled until one is chosen — the UI must
 * not offer a transition it knows the server will refuse. Actions are also gated on the
 * caller's actual OT permissions (OT_RECOVERY to admit/observe, OT_TRANSFER to discharge):
 * a button that will always 403 is not shown, it is explained.
 */
const DESTINATIONS = ['WARD', 'ICU', 'HDU', 'HOME', 'MORTUARY'];
const fmt = (dt) =>
  dt ? new Date(dt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : null;

const RecoveryModal = ({ surgery, onClose }) => {
  const { success, error: toastError } = useToast();
  const { can, loaded: permsLoaded } = useOtPermissions();
  const sid = surgery.surgeryId;
  const [episode, setEpisode] = useState(null);
  const [bays, setBays] = useState([]);
  const [selectedBay, setSelectedBay] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [aldrete, setAldrete] = useState('');
  const [destination, setDestination] = useState('WARD');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [ep, bayList] = await Promise.all([
        otService.getRecovery(sid).catch(() => null),
        otService.getRecoveryBays().catch(() => []),
      ]);
      setEpisode(ep);
      setBays(Array.isArray(bayList) ? bayList : []);
    } finally {
      setLoading(false);
    }
  }, [sid]);

  useEffect(() => {
    load();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const availableBays = bays.filter((b) => !b.occupied);

  const admit = async () => {
    if (!selectedBay) return;
    setBusy(true);
    try {
      setEpisode(await otService.admitRecovery(sid, selectedBay));
      success('Admitted to recovery');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to admit to recovery');
      // The bay may have just been taken by another admission; refresh the list rather
      // than leave a selection the server has already rejected.
      load();
    } finally {
      setBusy(false);
    }
  };

  const observe = async () => {
    setBusy(true);
    try {
      await otService.observeRecovery(sid, { aldreteScore: aldrete ? Number(aldrete) : null });
      setAldrete('');
      success('Observation recorded');
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to record observation');
    } finally {
      setBusy(false);
    }
  };

  const discharge = async () => {
    setBusy(true);
    try {
      setEpisode(await otService.dischargeRecovery(sid, destination));
      success(`Discharged to ${destination}`);
    } catch (e) {
      toastError(e?.response?.data?.error || 'Failed to discharge');
    } finally {
      setBusy(false);
    }
  };

  const canAdmit = permsLoaded && can('OT_RECOVERY');
  const canDischarge = permsLoaded && can('OT_TRANSFER');

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-start justify-center z-50 p-4 overflow-y-auto"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-md my-8">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 className="text-lg font-bold text-gray-900">Recovery (PACU)</h2>
            <p className="text-xs text-gray-400">
              {surgery.patientName} · {surgery.procedureName}
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-700 text-2xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="px-6 py-4 space-y-4">
          {loading ? (
            <div className="text-center text-gray-400 py-6">Loading…</div>
          ) : !episode ? (
            <div className="space-y-3">
              {!canAdmit ? (
                <p className="text-sm text-gray-500 text-center">
                  You don&apos;t have permission to admit a patient to recovery. Ask your Hospital
                  Admin to grant OT_RECOVERY if this is part of your role.
                </p>
              ) : (
                <>
                  <p className="text-sm text-gray-500 text-center">
                    This patient is not in recovery yet. Choose a bay to admit them.
                  </p>
                  <div>
                    <label
                      htmlFor="recovery-bay-select"
                      className="block text-xs font-medium text-gray-600 mb-1"
                    >
                      Recovery bay
                    </label>
                    <select
                      id="recovery-bay-select"
                      value={selectedBay}
                      onChange={(e) => setSelectedBay(e.target.value)}
                      className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                    >
                      <option value="">
                        {availableBays.length === 0 ? 'No bay available' : 'Select a bay…'}
                      </option>
                      {availableBays.map((b) => (
                        <option key={b.publicId} value={b.publicId}>
                          {b.name}
                        </option>
                      ))}
                    </select>
                    {bays.length === 0 && (
                      <p className="text-xs text-amber-600 mt-1">
                        No recovery bays are configured for this hospital yet. Ask your Hospital
                        Admin to add one under OT Settings.
                      </p>
                    )}
                    {bays.length > 0 && availableBays.length === 0 && (
                      <p className="text-xs text-amber-600 mt-1">
                        Every recovery bay is currently occupied. This patient stays on the
                        post-op queue until one frees up.
                      </p>
                    )}
                  </div>
                  <button
                    disabled={busy || !selectedBay}
                    onClick={admit}
                    title={!selectedBay ? 'Select a recovery bay first' : undefined}
                    className="w-full px-4 py-2 rounded-lg text-sm font-semibold bg-gray-900 text-white hover:bg-gray-800 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    Admit to recovery
                  </button>
                </>
              )}
            </div>
          ) : (
            <>
              <div className="text-xs text-gray-500">
                Arrived {fmt(episode.arrivedAt)}
                {episode.dischargedAt && (
                  <>
                    {' '}
                    · Discharged {fmt(episode.dischargedAt)} → {episode.transferDestination}
                  </>
                )}
              </div>

              {!episode.dischargedAt && (
                <>
                  {canAdmit && (
                    <div className="flex items-end gap-2">
                      <div className="flex-1">
                        <label
                          htmlFor="fld-199"
                          className="block text-xs font-medium text-gray-600 mb-1"
                        >
                          Aldrete score (0–10)
                        </label>
                        <input
                          id="fld-199"
                          type="number"
                          min="0"
                          max="10"
                          value={aldrete}
                          onChange={(e) => setAldrete(e.target.value)}
                          className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                        />
                      </div>
                      <button
                        disabled={busy}
                        onClick={observe}
                        className="px-4 py-2 rounded-lg text-sm font-semibold border border-gray-300 text-gray-700 hover:bg-gray-50"
                      >
                        Record
                      </button>
                    </div>
                  )}

                  {canDischarge ? (
                    <div className="flex items-end gap-2 border-t border-gray-100 pt-3">
                      <div className="flex-1">
                        <label
                          htmlFor="fld-198"
                          className="block text-xs font-medium text-gray-600 mb-1"
                        >
                          Discharge to
                        </label>
                        <select
                          id="fld-198"
                          value={destination}
                          onChange={(e) => setDestination(e.target.value)}
                          className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
                        >
                          {DESTINATIONS.map((d) => (
                            <option key={d} value={d}>
                              {d}
                            </option>
                          ))}
                        </select>
                      </div>
                      <button
                        disabled={busy}
                        onClick={discharge}
                        className="px-4 py-2 rounded-lg text-sm font-semibold bg-gray-900 text-white hover:bg-gray-800"
                      >
                        Discharge
                      </button>
                    </div>
                  ) : (
                    <p className="text-xs text-gray-400 border-t border-gray-100 pt-3">
                      Discharging from recovery requires OT_TRANSFER, which you don&apos;t hold.
                    </p>
                  )}
                </>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
};

export default RecoveryModal;
