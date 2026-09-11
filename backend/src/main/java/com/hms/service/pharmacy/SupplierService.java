package com.hms.service.pharmacy;

import com.hms.dto.pharmacy.SupplierRequest;
import com.hms.entity.pharmacy.Supplier;
import com.hms.repository.pharmacy.SupplierRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;

@Service
public class SupplierService {

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Autowired
    private AuditLogService auditLogService;

    @Transactional
    public Supplier createSupplier(SupplierRequest req) {
        Supplier s = new Supplier();
        Long hid = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();
        s.setHospitalId(hid);
        s.setBranchId(branchId);
        s.setSupplierName(req.getSupplierName());
        s.setContactPerson(req.getContactPerson());
        s.setPhone(req.getPhone());
        s.setEmail(req.getEmail());
        s.setAddress(req.getAddress());
        s.setGstNumber(req.getGstNumber());
        s.setPanNumber(req.getPanNumber());
        s.setDrugLicenseNumber(req.getDrugLicenseNumber());
        s.setCreditDays(req.getCreditDays() != null ? req.getCreditDays() : 0);
        s.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);
        s = supplierRepository.save(s);
        notifier.refresh(securityHelper.getCurrentHospitalId());

        auditLogService.logAction(
            "SUPPLIER_CREATED",
            "Supplier '" + s.getSupplierName() + "' created",
            securityHelper.getCurrentUserEmail(),
            hid, branchId,
            "SUPPLIER", String.valueOf(s.getId()), null
        );
        return s;
    }

    public Page<Supplier> getAll(String search, Pageable pageable) {
        Long hid = securityHelper.getCurrentHospitalId();
        if (hid == null) {
            throw new UnauthorizedException("Unauthenticated request - hospital ID missing");
        }
        String s = (search != null && !search.trim().isEmpty()) ? search.trim() : null;
        return supplierRepository.findScoped(hid, securityHelper.getCurrentBranchId(), s, pageable);
    }

    public Supplier getById(Long id) {
        return supplierRepository.findByIdScoped(id, securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
    }

    @Transactional
    public Supplier update(Long id, SupplierRequest req) {
        Supplier s = getById(id);
        s.setSupplierName(req.getSupplierName());
        s.setContactPerson(req.getContactPerson());
        s.setPhone(req.getPhone());
        s.setEmail(req.getEmail());
        s.setAddress(req.getAddress());
        s.setGstNumber(req.getGstNumber());
        s.setPanNumber(req.getPanNumber());
        s.setDrugLicenseNumber(req.getDrugLicenseNumber());
        s.setCreditDays(req.getCreditDays());
        if (req.getIsActive() != null)
            s.setIsActive(req.getIsActive());
        s = supplierRepository.save(s);
        notifier.refresh(securityHelper.getCurrentHospitalId());

        auditLogService.logAction(
            "SUPPLIER_UPDATED",
            "Supplier '" + s.getSupplierName() + "' updated",
            securityHelper.getCurrentUserEmail(),
            securityHelper.getCurrentHospitalId(), securityHelper.getCurrentBranchId(),
            "SUPPLIER", String.valueOf(s.getId()), null
        );
        return s;
    }

    @Transactional
    public void delete(Long id) {
        Supplier s = getById(id); // enforces tenant ownership + existence
        String supplierName = s.getSupplierName();
        Long hid = securityHelper.getCurrentHospitalId();
        Long branchId = securityHelper.getCurrentBranchId();
        supplierRepository.delete(s);

        auditLogService.logAction(
            "SUPPLIER_DELETED",
            "Supplier '" + supplierName + "' deleted",
            securityHelper.getCurrentUserEmail(),
            hid, branchId,
            "SUPPLIER", String.valueOf(id), null
        );
    }
}
