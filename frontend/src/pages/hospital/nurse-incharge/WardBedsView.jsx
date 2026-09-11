import React, { useState, useEffect, useCallback } from 'react';
import EmptyState from '../../../components/EmptyState';
import LoadingSpinner from '../../../components/LoadingSpinner';
import { useToast } from '../../../context/ToastContext';
import nurseService from '../../../services/nurseService';
import { backdropProps } from '../../../utils/modalA11y';

const STATUS_META = {
  available: { label: 'Available', badge: 'bg-green-100 text-green-700' },
  occupied: { label: 'Occupied', badge: 'bg-blue-100 text-blue-700' },
  cleaning: { label: 'Cleaning Required', badge: 'bg-amber-100 text-amber-700' },
  maintenance: { label: 'Under Maintenance', badge: 'bg-gray-200 text-gray-600' },
};

const fmtDate = (v) => {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return String(v);
  }
};

/**
 * WardBedsView - Nurse Incharge bed-status board (Nursing Mgmt Phase C3).
 * Ward selector -> grid of that ward's beds with a status badge and the
 * valid next-state actions (Mark Cleaned / Under Maintenance / Back to
 * Available), each confirmed via a small remarks prompt. A History button
 * per bed opens the bed's status-change audit trail.
 */
const WardBedsView = () => {
  const { success, error: toastError } = useToast();

  const [wards, setWards] = useState([]); // [{ wardId, wardName, beds: [] }]
  const [selectedWardId, setSelectedWardId] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshKey, setRefreshKey] = useState(0);

  const [actionFor, setActionFor] = useState(null); // { bed, action } | null
  const [historyFor, setHistoryFor] = useState(null); // bed | null

  const load = useCallback(() => {
    setLoading(true);
    nurseService
      .getMyWards()
      .then((data) => {
        const list = Array.isArray(data) ? data : [];
        setWards(list);
        setSelectedWardId((prev) => {
          if (prev != null && list.some((w) => w.wardId === prev)) return prev;
          return list[0]?.wardId ?? null;
        });
      })
      .catch((e) => toastError(e?.response?.data?.error || 'Failed to load wards'))
      .finally(() => setLoading(false));
  }, [toastError]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  const selectedWard = wards.find((w) => w.wardId === selectedWardId);
  const beds = selectedWard?.beds || [];

  const reload = () => setRefreshKey((k) => k + 1);

  if (loading) return <LoadingSpinner />;

  if (wards.length === 0) {
    return (
      <EmptyState
        icon={null}
        title="No wards found"
        message="You are not set as incharge of any ward yet."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="bg-white border border-gray-200 rounded-xl p-4 flex items-center gap-3">
        <div>
          <label htmlFor="fld-164" className="block text-xs font-medium text-gray-500 mb-1">
            Ward
          </label>
          <select
            id="fld-164"
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
      </div>

      {beds.length === 0 ? (
        <EmptyState icon={null} title="No beds" message="This ward has no beds configured." />
      ) : (
        <div className="bg-white border border-gray-200 rounded-xl overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="text-left text-xs font-semibold text-gray-500 border-b border-gray-200">
                <th className="px-4 py-3">BED</th>
                <th className="px-4 py-3">STATUS</th>
                <th className="px-4 py-3 text-right">ACTIONS</th>
              </tr>
            </thead>
            <tbody>
              {beds.map((b) => {
                const meta = STATUS_META[b.status] || {
                  label: b.status,
                  badge: 'bg-gray-100 text-gray-600',
                };
                return (
                  <tr key={b.bedId} className="border-b border-gray-100 hover:bg-gray-50">
                    <td className="px-4 py-3 font-medium text-gray-900">{b.bedCode}</td>
                    <td className="px-4 py-3">
                      <span
                        className={`px-2 py-0.5 text-[10px] font-bold rounded-full ${meta.badge}`}
                      >
                        {meta.label}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right whitespace-nowrap">
                      <div className="inline-flex items-center gap-2">
                        {b.status === 'cleaning' && (
                          <button
                            onClick={() => setActionFor({ bed: b, action: 'cleaned' })}
                            className="px-3 py-1 text-xs font-semibold rounded-lg bg-green-600 text-white hover:bg-green-700"
                          >
                            Mark Cleaned
                          </button>
                        )}
                        {b.status !== 'occupied' && (
                          <button
                            onClick={() => setActionFor({ bed: b, action: 'maintenance' })}
                            className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-700 text-white hover:bg-gray-800"
                          >
                            Under Maintenance
                          </button>
                        )}
                        {b.status === 'maintenance' && (
                          <button
                            onClick={() => setActionFor({ bed: b, action: 'available' })}
                            className="px-3 py-1 text-xs font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700"
                          >
                            Back to Available
                          </button>
                        )}
                        <button
                          onClick={() => setHistoryFor(b)}
                          className="px-3 py-1 text-xs font-semibold rounded-lg bg-gray-100 text-gray-700 hover:bg-gray-200"
                        >
                          History
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {actionFor && (
        <BedActionModal
          bed={actionFor.bed}
          action={actionFor.action}
          onClose={() => setActionFor(null)}
          onDone={() => {
            setActionFor(null);
            reload();
            success('Bed status updated');
          }}
          onError={(msg) => toastError(msg)}
        />
      )}

      {historyFor && (
        <BedHistoryModal
          bed={historyFor}
          onClose={() => setHistoryFor(null)}
          onError={(msg) => toastError(msg)}
        />
      )}
    </div>
  );
};

const ACTION_META = {
  cleaned: {
    title: 'Mark Bed Cleaned',
    call: (bedId, remarks) => nurseService.markBedCleaned(bedId, remarks),
  },
  maintenance: {
    title: 'Put Bed Under Maintenance',
    call: (bedId, remarks) => nurseService.markBedMaintenance(bedId, remarks),
  },
  available: {
    title: 'Return Bed to Available',
    call: (bedId, remarks) => nurseService.markBedAvailable(bedId, remarks),
  },
};

/** BedActionModal - tiny remarks prompt before a bed status transition. */
const BedActionModal = ({ bed, action, onClose, onDone, onError }) => {
  const [remarks, setRemarks] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const meta = ACTION_META[action];

  const handleSubmit = async () => {
    setSubmitting(true);
    try {
      await meta.call(bed.bedId, remarks.trim() || undefined);
      onDone();
    } catch (e) {
      onError(e?.response?.data?.error || 'Failed to update bed status');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-sm p-6 max-h-[90vh] overflow-y-auto">
        <h2 className="text-lg font-bold text-gray-900 mb-1">{meta.title}</h2>
        <p className="text-sm text-gray-500 mb-4">Bed {bed.bedCode}</p>

        <label htmlFor="fld-163" className="block text-xs font-medium text-gray-600 mb-1">
          Remarks (optional)
        </label>
        <textarea
          id="fld-163"
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
          rows={3}
          className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500"
          placeholder="Optional note..."
        />

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
            {submitting ? 'Saving…' : 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
};

/** BedHistoryModal - status-change audit trail for a bed. */
const BedHistoryModal = ({ bed, onClose, onError }) => {
  const [loading, setLoading] = useState(true);
  const [rows, setRows] = useState([]);

  useEffect(() => {
    let active = true;
    nurseService
      .getBedHistory(bed.bedId)
      .then((data) => {
        if (active) setRows(Array.isArray(data) ? data : []);
      })
      .catch((e) => {
        if (active) onError(e?.response?.data?.error || 'Failed to load bed history');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bed.bedId]);

  return (
    <div
      className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4"
      {...backdropProps(onClose)}
    >
      <div className="bg-white rounded-2xl w-full max-w-lg p-6 max-h-[80vh] overflow-y-auto">
        <h2 className="text-lg font-bold text-gray-900 mb-1">Bed History</h2>
        <p className="text-sm text-gray-500 mb-4">Bed {bed.bedCode}</p>

        {loading ? (
          <LoadingSpinner />
        ) : rows.length === 0 ? (
          <EmptyState icon={null} title="No history" message="No status changes recorded yet." />
        ) : (
          <ul className="space-y-3">
            {rows.map((r, i) => (
              <li key={i} className="border border-gray-200 rounded-lg p-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-semibold text-gray-800">
                    {STATUS_META[r.previousStatus]?.label || r.previousStatus || '—'}
                    {' → '}
                    {STATUS_META[r.newStatus]?.label || r.newStatus || '—'}
                  </span>
                  <span className="text-xs text-gray-400">{fmtDate(r.changedAt)}</span>
                </div>
                {r.remarks && <p className="text-xs text-gray-500 mt-1">{r.remarks}</p>}
              </li>
            ))}
          </ul>
        )}

        <div className="mt-6 flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-semibold rounded-lg border border-gray-300 text-gray-700 hover:bg-gray-100"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};

export default WardBedsView;
