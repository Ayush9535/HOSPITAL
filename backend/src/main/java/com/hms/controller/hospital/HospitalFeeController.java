package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.HospitalFeeDTO;
import com.hms.entity.HospitalFee;
import com.hms.repository.HospitalFeeRepository;
import com.hms.security.RequireModule;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/hospital/settings/fees/custom", "/clinic/settings/fees/custom", "/pharmacy/settings/fees/custom"})
@RequireModule("BILLING")
public class HospitalFeeController {

    @Autowired
    private HospitalFeeRepository hospitalFeeRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @GetMapping
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'RECEPTIONIST', 'DOCTOR')")
    public ResponseEntity<?> getCustomFees() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        List<HospitalFee> fees = hospitalFeeRepository.findByHospitalIdAndIsActiveTrue(hospitalId);
        List<HospitalFeeDTO> dtos = fees.stream()
                .map(f -> new HospitalFeeDTO(f.getId(), f.getName(), f.getDefaultAmount()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> addCustomFee(@Valid @RequestBody HospitalFeeDTO dto) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Fee name is required");
        }

        HospitalFee fee = new HospitalFee();
        fee.setHospitalId(hospitalId);
        fee.setName(dto.getName().trim());
        fee.setDefaultAmount(dto.getDefaultAmount());
        fee.setIsActive(true);

        HospitalFee saved = hospitalFeeRepository.save(fee);
        return ResponseEntity.ok(new HospitalFeeDTO(saved.getId(), saved.getName(), saved.getDefaultAmount()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> updateCustomFee(@PathVariable Long id, @Valid @RequestBody HospitalFeeDTO dto) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalFee fee = hospitalFeeRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Fee not found"));

        if (dto.getName() != null && !dto.getName().trim().isEmpty()) {
            fee.setName(dto.getName().trim());
        }
        fee.setDefaultAmount(dto.getDefaultAmount());

        HospitalFee saved = hospitalFeeRepository.save(fee);
        return ResponseEntity.ok(new HospitalFeeDTO(saved.getId(), saved.getName(), saved.getDefaultAmount()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> deleteCustomFee(@PathVariable Long id) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        HospitalFee fee = hospitalFeeRepository.findByIdAndHospitalId(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Fee not found"));

        fee.setIsActive(false);
        hospitalFeeRepository.save(fee);
        return ResponseEntity.ok("Fee deleted successfully");
    }
}
