import React, { useEffect, useState } from 'react';
import BedListDrawer from '../../components/BedListDrawer';
import ConfirmationModal from '../../components/ConfirmationModal';
import WardCard from '../../components/WardCard';
import WardModal from '../../components/WardModal';
import { useToast } from '../../context/ToastContext';
import authService from '../../services/authService';
import hospitalService from '../../services/hospitalService';
import WardService from '../../services/wardService';
import { extractApiError } from '../../utils/apiError';

const WardsAndBeds = () => {
  const { success, error: toastError } = useToast();
  const [wards, setWards] = useState([]);
  const [nurseIncharges, setNurseIncharges] = useState([]);
  const [loading, setLoading] = useState(false);
  const [selectedWard, setSelectedWard] = useState(null);
  const [showBeds, setShowBeds] = useState(false);
  const [editWard, setEditWard] = useState(null);
  const [editOpen, setEditOpen] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [confirmState, setConfirmState] = useState({
    open: false,
    title: '',
    message: '',
    onConfirm: null,
  });

  // The nurse-incharge picker is a nursing feature: /hospital/nurses is @RequireModule("NURSING").
  // Without the module the call is rejected, so don't make it — and hide the picker.
  const nursingEnabled = (authService.getCurrentUser()?.modules || []).includes('NURSING');

  useEffect(() => {
    fetchWards();
    if (nursingEnabled) fetchNurseIncharges();
  }, [nursingEnabled]);

  const fetchWards = async () => {
    setLoading(true);
    try {
      const data = await WardService.getWards();
      setWards(data);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const fetchNurseIncharges = async () => {
    try {
      const data = await hospitalService.getNurses('', 0, 500);
      const list = data?.content || data || [];
      setNurseIncharges(list.filter((n) => n.isIncharge));
    } catch (e) {
      console.error(e);
    }
  };

  const onSetIncharge = async (ward, nurseProfileId) => {
    try {
      await hospitalService.setWardIncharge(ward.wardId, nurseProfileId);
      success('Ward incharge updated');
      await fetchWards();
    } catch (e) {
      toastError(extractApiError(e, 'Failed to update ward incharge'));
    }
  };

  const onViewBeds = (ward) => {
    setSelectedWard(ward);
    setShowBeds(true);
  };

  const onEdit = (ward) => {
    setEditWard(ward);
    setEditOpen(true);
  };

  const onDelete = (ward) => {
    setConfirmState({
      open: true,
      title: 'Delete Ward',
      message: `Delete ward "${ward.wardName}"? This will also delete all its unoccupied beds.`,
      onConfirm: async () => {
        setDeleting(ward.wardId);
        try {
          await WardService.deleteWard(ward.wardId);
          await fetchWards();
        } finally {
          setDeleting(null);
        }
      },
    });
  };

  return (
    <div>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {loading && <div>Loading wards...</div>}
        {!loading && wards.length === 0 && (
          <div className="text-slate-500">
            No wards found. Use &quot;Create Ward&quot; to add one.
          </div>
        )}

        {wards.map((w) => (
          <WardCard
            key={w.wardId}
            ward={w}
            onViewBeds={onViewBeds}
            onEdit={onEdit}
            onDelete={onDelete}
            deleting={deleting === w.wardId}
            inchargeOptions={nurseIncharges}
            onSetIncharge={nursingEnabled ? onSetIncharge : undefined}
          />
        ))}
      </div>

      <BedListDrawer
        open={showBeds}
        ward={selectedWard}
        onClose={() => setShowBeds(false)}
        onStatusChange={fetchWards}
      />

      <WardModal
        open={editOpen}
        initial={editWard}
        onClose={() => {
          setEditOpen(false);
          setEditWard(null);
        }}
        onSaved={() => {
          setEditOpen(false);
          setEditWard(null);
          fetchWards();
        }}
      />

      <ConfirmationModal
        isOpen={confirmState.open}
        title={confirmState.title}
        message={confirmState.message}
        onConfirm={confirmState.onConfirm}
        onCancel={() => setConfirmState({ open: false })}
      />
    </div>
  );
};

export default WardsAndBeds;
