import React from 'react';
import InClinicPresetsManager from './InClinicPresetsManager';
import PresetModalShell from './PresetModalShell';

/**
 * Modal wrapper around InClinicPresetsManager, used from ConsultationModal so a doctor can
 * create/edit the in-clinic medicine bundles he dispenses without leaving the consultation —
 * the same affordance prescription and note presets already have.
 */
const ManageInClinicPresetsModal = ({ isOpen, onClose }) => (
  <PresetModalShell
    isOpen={isOpen}
    onClose={onClose}
    title="Manage In-Clinic Presets"
    titleId="manage-in-clinic-presets-title"
    maxWidthClass="max-w-2xl"
  >
    <InClinicPresetsManager />
  </PresetModalShell>
);

export default ManageInClinicPresetsModal;
