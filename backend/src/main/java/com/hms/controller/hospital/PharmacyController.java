package com.hms.controller.hospital;

import com.hms.exception.ResourceNotFoundException;

import com.hms.entity.Medicine;
import com.hms.entity.Prescription;
import com.hms.repository.PrescriptionRepository;
import com.hms.service.hospital.InventoryService;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/hospital/pharmacy", "/clinic/pharmacy", "/pharmacy/pharmacy"})
public class PharmacyController {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private com.hms.service.hospital.MedicineStockService medicineStockService;

    @Autowired
    private com.hms.repository.StockMovementRepository stockMovementRepository;

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private com.hms.repository.MedicalRecordRepository medicalRecordRepository;

    @Autowired
    private com.hms.repository.PatientRepository patientRepository;

    @Autowired
    private com.hms.repository.DoctorRepository doctorRepository;

    // --- Inventory Management ---

    @GetMapping("/inventory")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getInventory() {
        return ResponseEntity.ok(inventoryService.getInventory());
    }

    @GetMapping("/inventory/low-stock")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getLowStock() {
        return ResponseEntity.ok(inventoryService.getLowStockMedicines());
    }

    @PostMapping("/inventory/stock")
    @PreAuthorize("hasRole('PHARMACIST')")
    public ResponseEntity<?> updateStock(@RequestBody Map<String, Object> request) {
        Long medicineId = ((Number) request.get("medicineId")).longValue();
        Integer quantity = ((Number) request.get("quantity")).intValue();
        return ResponseEntity.ok(inventoryService.updateStock(medicineId, quantity));
    }

    // --- Dispensing ---

    /**
     * Get all pending prescription items
     * Grouping by Patient/Consultation might be better for UI, but flat list is
     * easier for V1 API.
     * We'll return a DTO to make it displayable.
     */
    @GetMapping("/prescriptions/pending")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> getPendingPrescriptions() {
        Long hospitalId = securityHelper.getCurrentHospitalId();

        // 1. Optimized fetch strictly targeting Active state under the hospital_id
        List<Prescription> active = prescriptionRepository.findByHospitalIdAndStatus(hospitalId, "ACTIVE");

        if (active.isEmpty()) {
            return ResponseEntity.ok(new java.util.ArrayList<>());
        }

        // 2. Mass-fetch Medical Records to bypass O(N) Select latency
        java.util.Set<Long> recordIds = active.stream()
                .map(Prescription::getMedicalRecordId)
                .collect(Collectors.toSet());
        
        java.util.Map<Long, com.hms.entity.MedicalRecord> recordMap = medicalRecordRepository.findAllById(recordIds).stream()
                .collect(Collectors.toMap(com.hms.entity.MedicalRecord::getId, r -> r, (r1, r2) -> r1));

        // 3. Mass-fetch Patients & Doctors
        java.util.Set<Long> patientIds = recordMap.values().stream()
                .map(com.hms.entity.MedicalRecord::getPatientId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        java.util.Set<Long> doctorIds = recordMap.values().stream()
                .map(com.hms.entity.MedicalRecord::getDoctorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        java.util.Map<Long, com.hms.entity.Patient> patientMap = patientIds.isEmpty() ? new java.util.HashMap<>() :
                patientRepository.findAllById(patientIds).stream()
                .collect(Collectors.toMap(com.hms.entity.Patient::getId, p -> p, (p1, p2) -> p1));

        java.util.Map<Long, com.hms.entity.Doctor> doctorMap = doctorIds.isEmpty() ? new java.util.HashMap<>() :
                doctorRepository.findAllById(doctorIds).stream()
                .collect(Collectors.toMap(com.hms.entity.Doctor::getId, d -> d, (d1, d2) -> d1));

        // 4. One query for everything already issued against these orders, rather than one per row.
        java.util.List<Long> prescriptionIds = active.stream().map(Prescription::getId).toList();
        java.util.Map<Long, Integer> dispensedByPrescription = new java.util.HashMap<>();
        for (Object[] pair : stockMovementRepository.dispensedForPrescriptions(hospitalId, prescriptionIds)) {
            dispensedByPrescription.put(((Number) pair[0]).longValue(), ((Number) pair[1]).intValue());
        }

        // 5. Rapid memory correlation mapping
        List<Map<String, Object>> response = active.stream().map(p -> {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", p.getId());
            map.put("medicalRecordId", p.getMedicalRecordId());
            map.put("medicineName", p.getMedicineName());
            map.put("dosage", p.getDosage());
            map.put("frequency", p.getFrequency());
            map.put("duration", p.getDuration());
            map.put("instructions", p.getInstructions());
            map.put("createdAt", p.getCreatedAt());
            map.put("status", p.getStatus());

            // What inventory can say about this order. UNLINKED means nobody has chosen a stock
            // row for it: unknown, not zero, and the order is still perfectly dispensable once
            // someone picks the medicine.
            map.put("medicineId", p.getMedicineId());
            map.put("quantityDispensed", dispensedByPrescription.getOrDefault(p.getId(), 0));
            if (p.getMedicineId() == null) {
                map.put("inventoryStatus", "UNLINKED");
            } else {
                try {
                    int available = medicineStockService.availableQuantityFor(p.getMedicineId(), hospitalId);
                    map.put("availableQuantity", available);
                    map.put("earliestExpiry",
                            medicineStockService.earliestUsableExpiry(p.getMedicineId(), hospitalId));
                    map.put("inventoryStatus", available <= 0 ? "LINKED_NO_STOCK" : "LINKED_AVAILABLE");
                } catch (RuntimeException e) {
                    // The linked medicine has since been removed from inventory.
                    map.put("inventoryStatus", "UNLINKED");
                }
            }

            com.hms.entity.MedicalRecord record = recordMap.get(p.getMedicalRecordId());
            if (record != null) {
                com.hms.entity.Patient patient = patientMap.get(record.getPatientId());
                if (patient != null) {
                    map.put("patientName", patient.getName());
                    map.put("patientAge", patient.getAge());
                    map.put("patientGender", patient.getGender());
                }

                com.hms.entity.Doctor doctor = doctorMap.get(record.getDoctorId());
                if (doctor != null) {
                    map.put("doctorName", doctor.getName());
                }
                
                map.put("diagnosis", record.getDiagnosis());
                map.put("notes", record.getTreatmentNotes());
                map.put("symptoms", record.getSymptoms());

                // Resolve the visit source
                String prescriptionSource = "OPD"; // Default fallback
                if (record.getIpdAdmissionId() != null || "IPD".equalsIgnoreCase(record.getVisitType())) {
                    prescriptionSource = "IPD";
                } else if (record.getAppointmentId() != null) {
                    prescriptionSource = "APPOINTMENT";
                } else if (record.getOpdId() != null) {
                    prescriptionSource = "OPD";
                }
                map.put("prescriptionSource", prescriptionSource);
            }

            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * The facility's medicines with their usable stock, for choosing what to hand over.
     *
     * <p>Exists so an order written as free text can be reconciled to a real inventory row by a
     * person. Deliberately returns candidates rather than a single answer: the old dispense path
     * searched by the prescription's text and silently took whichever row came back first.
     */
    @GetMapping("/dispense/medicines")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> dispensableMedicines(@RequestParam(required = false) String query) {
        return ResponseEntity.ok(medicineStockService.dispensableOptions(query));
    }

    /**
     * Dispense against a prescription: the one event that takes medicine stock off the shelf.
     *
     * <p>Ordering does not decrement and administering does not decrement again -- a prescription
     * is an instruction, a MAR entry is a clinical record, and only this hands out physical
     * stock. Keeping the decrement here is what stops one course of treatment being deducted
     * twice.
     *
     * <p>The quantity and the medicine both come from the request, because neither can be
     * derived. This used to call {@code dispenseMedicine(p.getMedicineName(), 1)}: it took the
     * prescription's free-text name, searched inventory for it, took whichever row sorted first,
     * and removed exactly one unit regardless of what was handed over. A five-day course left the
     * shelf and the system recorded a single unit -- against a medicine nobody had confirmed was
     * the right one. There is no rule that turns "1-0-1" and "5 Days" into a unit count, so the
     * pharmacist states what they dispensed.
     */
    @PostMapping("/dispense/{prescriptionId}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'HOSPITAL_ADMIN')")
    public ResponseEntity<?> dispenseMedicine(@PathVariable Long prescriptionId,
            @Valid @RequestBody DispenseRequest req) {
        // Scope to the caller's hospital: an unscoped findById let a pharmacist dispense
        // another hospital's prescription (and deduct stock against it) by guessing the id.
        Long dispenseHospitalId = securityHelper.getCurrentHospitalId();
        Prescription p = prescriptionRepository.findById(prescriptionId)
                .filter(x -> x.getHospitalId() != null && x.getHospitalId().equals(dispenseHospitalId))
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));

        // Deliberately no "already dispensed" check, and deliberately no write to the
        // prescription's status below.
        //
        // Prescription.status is the CLINICAL state of the order -- ACTIVE, STOPPED, COMPLETED --
        // and it is what puts the medicine on the nurse's chart. Stamping it DISPENSED took a
        // live order off that chart the moment pharmacy handed the drugs to the ward, so the
        // nurse could no longer record having given it: a stock event silently ended a course of
        // treatment. Handing stock out and finishing a course are different facts.
        //
        // It also assumed a course is dispensed exactly once. Issuing three days now and the rest
        // on Thursday is ordinary; what must not happen is the SAME issue being posted twice, and
        // that is what the idempotency key below is for.
        // Which medicine leaves the shelf: the one already linked to the order, or the one the
        // pharmacist selects now for an order written as free text. Reconciling an unlinked order
        // is a decision a person makes; it is recorded on the order so it is not made twice.
        Long medicineId = p.getMedicineId() != null ? p.getMedicineId() : req.getMedicineId();
        if (medicineId == null) {
            throw new IllegalArgumentException(
                    "This prescription is not linked to an inventory medicine. "
                    + "Select the medicine being dispensed.");
        }

        java.util.List<com.hms.service.hospital.MedicineStockService.BatchAllocation> taken = medicineStockService.consumeFefo(
                medicineId, req.getQuantity(), com.hms.entity.StockMovement.DISPENSE,
                "PRESCRIPTION", p.getId(), req.getIdempotencyKey(), req.getRemarks());

        if (p.getMedicineId() == null) {
            p.setMedicineId(medicineId);
            prescriptionRepository.save(p);
        }

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("prescriptionId", p.getId());
        body.put("medicineId", medicineId);
        body.put("quantityDispensed", req.getQuantity());
        body.put("batches", taken);
        return ResponseEntity.ok(body);
    }

    /** What the pharmacist actually handed over. */
    @lombok.Data
    public static class DispenseRequest {
        @jakarta.validation.constraints.NotNull(message = "Dispensed quantity is required")
        @jakarta.validation.constraints.Min(value = 1, message = "Dispensed quantity must be at least 1")
        private Integer quantity;

        /** Required only when the prescription carries no inventory link of its own. */
        private Long medicineId;

        /**
         * Makes a retried or double-submitted dispense post stock once. The client sends the same
         * key for what it considers one act of dispensing.
         */
        @jakarta.validation.constraints.Size(max = 100)
        private String idempotencyKey;

        @jakarta.validation.constraints.Size(max = 255)
        private String remarks;
    }
}
