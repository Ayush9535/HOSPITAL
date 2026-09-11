import React from 'react';
import PrescriptionPresetsManager from './PrescriptionPresetsManager';
import PresetModalShell from './PresetModalShell';

/**
 * Modal wrapper around PrescriptionPresetsManager, used from ConsultationModal
 * so a doctor can manage prescription presets without leaving the consultation
 * screen.
 */
const ManagePrescriptionPresetsModal = ({ isOpen, onClose }) => (
  <PresetModalShell
    isOpen={isOpen}
    onClose={onClose}
    title="Manage Prescription Presets"
    titleId="manage-prescription-presets-title"
    maxWidthClass="max-w-2xl"
  >
    <PrescriptionPresetsManager />
  </PresetModalShell>
);

export default ManagePrescriptionPresetsModal;
