package com.hms.service.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.PrescriptionPreset;
import com.hms.entity.PrescriptionPresetItem;
import com.hms.repository.PrescriptionPresetItemRepository;
import com.hms.repository.PrescriptionPresetRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PrescriptionPresetService {

    @Autowired
    private PrescriptionPresetRepository presetRepository;

    @Autowired
    private PrescriptionPresetItemRepository itemRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private PresetOwnershipSupport ownership;

    public List<PrescriptionPreset> listPresets() {
        return listPresets(PrescriptionPreset.PRESCRIPTION);
    }

    /** @param presetType PRESCRIPTION or IN_CLINIC — the two lists share this table. */
    public List<PrescriptionPreset> listPresets(String presetType) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        String type = normalizeType(presetType);
        if (ownership.isAdmin()) {
            return presetRepository.findByHospitalIdAndPresetTypeAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId, type);
        }
        return presetRepository.findVisibleToDoctorByType(hospitalId, ownership.currentDoctorIdOrNull(), type);
    }

    private String normalizeType(String presetType) {
        return PrescriptionPreset.IN_CLINIC.equalsIgnoreCase(presetType)
                ? PrescriptionPreset.IN_CLINIC
                : PrescriptionPreset.PRESCRIPTION;
    }

    public List<PrescriptionPresetItem> getItems(Long presetId) {
        return itemRepository.findByPresetIdOrderBySortOrderAsc(presetId);
    }

    @Transactional
    public PrescriptionPreset createPreset(String name, List<PrescriptionPresetItem> items, Long requestedDoctorId) {
        return createPreset(name, items, requestedDoctorId, PrescriptionPreset.PRESCRIPTION);
    }

    @Transactional
    public PrescriptionPreset createPreset(String name, List<PrescriptionPresetItem> items, Long requestedDoctorId,
                                           String presetType) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        String type = normalizeType(presetType);
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Preset name is required");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Preset must contain at least one medicine");
        }

        Long ownerDoctorId = ownership.resolveOwnerDoctorId(requestedDoctorId, hospitalId);

        int nextOrder = presetRepository
                .findByHospitalIdAndPresetTypeAndIsActiveTrueOrderByDisplayOrderAsc(hospitalId, type).size();

        PrescriptionPreset preset = new PrescriptionPreset();
        preset.setHospitalId(hospitalId);
        preset.setDoctorId(ownerDoctorId);
        preset.setName(name.trim());
        preset.setPresetType(type);
        preset.setDisplayOrder(nextOrder);
        preset.setIsActive(true);
        PrescriptionPreset saved = presetRepository.save(preset);

        saveItems(saved.getId(), items);
        ownership.notifyPresetsChanged(hospitalId);
        return saved;
    }

    /**
     * items == null means "leave the existing item list untouched" (e.g. a
     * name-only rename or a reorder); items == [] is rejected. Doctor assignment
     * is only changed by admins, and only on a real edit (name present) — reorder
     * calls send displayOrder only and must not disturb ownership.
     */
    @Transactional
    public PrescriptionPreset updatePreset(Long id, String name, List<PrescriptionPresetItem> items,
                                           Integer displayOrder, Long requestedDoctorId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        PrescriptionPreset preset = findEditablePreset(id, hospitalId);

        if (name != null && !name.trim().isEmpty()) {
            preset.setName(name.trim());
            preset.setDoctorId(ownership.resolveOwnerDoctorId(requestedDoctorId, hospitalId));
        }
        if (displayOrder != null) {
            preset.setDisplayOrder(displayOrder);
        }
        presetRepository.save(preset);

        if (items != null) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("Preset must contain at least one medicine");
            }
            itemRepository.deleteByPresetId(id);
            saveItems(id, items);
        }
        ownership.notifyPresetsChanged(hospitalId);
        return preset;
    }

    @Transactional
    public void deletePreset(Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        PrescriptionPreset preset = findEditablePreset(id, hospitalId);
        preset.setIsActive(false);
        presetRepository.save(preset);
        ownership.notifyPresetsChanged(hospitalId);
    }

    /**
     * Admins can edit any preset in the hospital; doctors only their own private
     * ones (shared presets are admin-managed and not editable by a doctor). This
     * holds for both preset types — in-clinic presets are managed by the admin and
     * by the doctor who dispenses them, from inside the consultation.
     */
    private PrescriptionPreset findEditablePreset(Long id, Long hospitalId) {
        if (ownership.isAdmin()) {
            return presetRepository.findByIdAndHospitalId(id, hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException("Preset not found"));
        }
        return presetRepository.findByIdAndHospitalIdAndDoctorId(id, hospitalId, ownership.currentDoctorIdOrNull())
                .orElseThrow(() -> new ResourceNotFoundException("Preset not found"));
    }

    private void saveItems(Long presetId, List<PrescriptionPresetItem> items) {
        int order = 0;
        for (PrescriptionPresetItem item : items) {
            PrescriptionPresetItem toSave = new PrescriptionPresetItem();
            toSave.setPresetId(presetId);
            toSave.setMedicineName(item.getMedicineName());
            toSave.setDosage(item.getDosage());
            toSave.setFrequency(item.getFrequency());
            toSave.setDuration(item.getDuration());
            toSave.setInstructions(item.getInstructions());
            // IN_CLINIC items carry the stock link + how many units to administer.
            toSave.setMedicineId(item.getMedicineId());
            toSave.setQuantity(item.getQuantity());
            toSave.setSortOrder(order++);
            itemRepository.save(toSave);
        }
    }
}
