import React, { useEffect, useState } from 'react';
import { useToast } from '../context/ToastContext';
import WardService from '../services/wardService';
import Button from './Button';
import { extractApiError } from '../utils/apiError';
import authService from '../services/authService';

const BedListDrawer = ({ open, ward, onClose, onStatusChange }) => {
  const { error: toastError } = useToast();
  const [beds, setBeds] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open && ward) fetchBeds();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, ward]);

  const fetchBeds = async () => {
    setLoading(true);
    try {
      const data = await WardService.getBeds(ward.wardId);
      setBeds(data);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const [updatingBedId, setUpdatingBedId] = useState(null);
  const canManageBeds = ['HOSPITAL_ADMIN', 'NURSE_INCHARGE'].includes(authService.getCurrentUser()?.role);

  const makeAvailable = async (bedId) => {
    if (updatingBedId) return;
    setUpdatingBedId(bedId);
    try {
      await WardService.updateBedStatus(bedId, 'Back to available');
      onStatusChange && onStatusChange();
      fetchBeds();
    } catch (e) {
      toastError(extractApiError(e, 'Failed to update bed status'));
    } finally {
      setUpdatingBedId(null);
    }
  };

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex">
      <button type="button" aria-label="Close" className="flex-1 bg-black/40" onClick={onClose} />
      <div className="w-96 bg-white p-4 shadow-xl h-full flex flex-col">
        <div className="flex items-center justify-between mb-4 shrink-0">
          <h3 className="text-lg font-semibold">Beds — {ward?.wardName}</h3>
          <button onClick={onClose} className="text-slate-500">
            Close
          </button>
        </div>

        {loading ? (
          <p>Loading...</p>
        ) : (
          <div className="space-y-3 flex-1 overflow-y-auto">
            {beds.length === 0 && <p className="text-sm text-slate-500">No beds found.</p>}
            {beds.map((b) => (
              <div key={b.bedId} className="flex items-center justify-between p-2 border rounded">
                <div>
                  <div className="font-medium">{b.bedCode}</div>
                  <div className="text-sm text-slate-500">{b.status}</div>
                </div>
                <div>
                  {canManageBeds && b.status === 'maintenance' && (
                    <Button
                      size="sm"
                      variant="success"
                      onClick={() => makeAvailable(b.bedId)}
                      disabled={!!updatingBedId}
                    >
                      {updatingBedId === b.bedId ? 'Updating...' : 'Make Available'}
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default BedListDrawer;
