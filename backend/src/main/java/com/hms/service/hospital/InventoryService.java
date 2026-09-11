package com.hms.service.hospital;

import com.hms.entity.Medicine;
import com.hms.repository.MedicineRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ConflictException;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class InventoryService {

    @Autowired
    private MedicineRepository medicineRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    public List<Medicine> getInventory() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return medicineRepository.findByHospitalId(hospitalId);
    }

    public List<Medicine> getLowStockMedicines() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        return medicineRepository.findLowStock(hospitalId);
    }

    /**
     * Add stock to a medicine owned by the caller's facility.
     *
     * <p>Tenant stock and the global catalogue are deliberately NOT the same operation. The
     * previous check skipped itself entirely when {@code medicine.getHospitalId() == null}, so a
     * global catalogue row could have tenant stock written onto it by any caller. A stock
     * mutation always requires an owning facility; a global row has none and is never a valid
     * target here.
     */
    @Transactional
    public Medicine updateStock(Long medicineId, Integer quantityAdded) {
        if (quantityAdded == null || quantityAdded <= 0) {
            throw new IllegalArgumentException("Quantity to add must be a positive number");
        }
        Long hospitalId = requireHospitalId();
        Medicine medicine = requireOwnedMedicine(medicineId, hospitalId);
        medicine.setStockQuantity(medicine.getStockQuantity() + quantityAdded);
        return medicineRepository.save(medicine);
    }

    /**
     * Remove stock from a medicine owned by the caller's facility.
     *
     * <p>This previously loaded the medicine by raw id with no tenant check at all, so a caller in
     * one facility could decrement another facility's stock by supplying its id -- and it was
     * listed in TenantScopingArchTest's allowlist as reviewed-and-safe, which kept the
     * architecture test green over the hole.
     *
     * <p>The decrement is a conditional atomic UPDATE rather than read-check-write: two concurrent
     * callers taking the last units would both pass an in-memory check and the second save would
     * silently overwrite the first (lost update, and stock able to go negative).
     */
    @Transactional
    public void deductStock(Long medicineId, Integer quantityDeducted) {
        if (quantityDeducted == null || quantityDeducted <= 0) {
            throw new IllegalArgumentException("Quantity to deduct must be a positive number");
        }
        Long hospitalId = requireHospitalId();
        Medicine medicine = requireOwnedMedicine(medicineId, hospitalId);

        int updated = medicineRepository.deductStockAtomically(medicineId, hospitalId, quantityDeducted);
        if (updated == 0) {
            throw new ConflictException("Insufficient stock for: " + medicine.getName());
        }
    }

    private Medicine requireOwnedMedicine(Long medicineId, Long hospitalId) {
        return medicineRepository.findByIdAndHospitalId(medicineId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Medicine not found"));
    }

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) {
            throw new UnauthorizedException("Hospital ID not found in context");
        }
        return hospitalId;
    }

    // Dispensing by medicine NAME used to live here. It searched the facility's inventory for a
    // free-text prescription name and took meds.get(0) -- whichever row the query happened to
    // return first -- then decremented it. Two rows for the same drug (different strengths, a
    // duplicate entry, a typo) meant stock came off an arbitrary one, and a name with no match
    // failed a dispense that had physically already happened. Stock now moves only through
    // MedicineStockService against a medicine someone actually chose; see
    // PharmacyController#dispenseMedicine.
}

