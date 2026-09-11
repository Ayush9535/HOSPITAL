import React from 'react';
import NotePresetsManager from './NotePresetsManager';
import PresetModalShell from './PresetModalShell';

/**
 * Modal wrapper around NotePresetsManager, used from ConsultationModal so a
 * doctor can manage quick notes without leaving the consultation screen.
 */
const ManageNotePresetsModal = ({
  isOpen,
  onClose,
  fieldType,
  title = 'Manage Quick Notes',
  managerProps = {},
}) => (
  <PresetModalShell
    isOpen={isOpen}
    onClose={onClose}
    title={title}
    titleId="manage-note-presets-title"
  >
    <NotePresetsManager fieldType={fieldType} {...managerProps} />
  </PresetModalShell>
);

export default ManageNotePresetsModal;
