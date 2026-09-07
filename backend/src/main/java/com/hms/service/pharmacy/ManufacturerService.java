package com.hms.service.pharmacy;

import com.hms.exception.ResourceNotFoundException;

import com.hms.dto.pharmacy.ManufacturerRequest;
import com.hms.entity.pharmacy.Manufacturer;
import com.hms.repository.pharmacy.ManufacturerRepository;
import com.hms.security.SecurityContextHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManufacturerService {

    @Autowired private com.hms.service.RealtimeNotifier notifier;

    @Autowired
    private ManufacturerRepository manufacturerRepository;

    @Autowired
    private SecurityContextHelper securityHelper;

    @Transactional
    public Manufacturer createManufacturer(ManufacturerRequest req) {
        Manufacturer m = new Manufacturer();
        m.setHospitalId(securityHelper.getCurrentHospitalId());
        m.setManufacturerName(req.getManufacturerName());
        m.setContactPerson(req.getContactPerson());
        m.setPhone(req.getPhone());
        m.setEmail(req.getEmail());
        m.setAddress(req.getAddress());
        m.setLicenseNumber(req.getLicenseNumber());
        m.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);
        Manufacturer saved = manufacturerRepository.save(m);
        notifier.refresh(securityHelper.getCurrentHospitalId());
        return saved;
    }

    public Page<Manufacturer> getAll(String search, Pageable pageable) {
        Long hid = securityHelper.getCurrentHospitalId();
        if (search != null && !search.trim().isEmpty()) {
            return manufacturerRepository.findByHospitalIdAndManufacturerNameContainingIgnoreCase(hid, search, pageable);
        }
        return manufacturerRepository.findByHospitalId(hid, pageable);
    }

    public Manufacturer getById(Long id) {
        return manufacturerRepository.findByIdAndHospitalId(id, securityHelper.getCurrentHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturer not found"));
    }

    @Transactional
    public Manufacturer update(Long id, ManufacturerRequest req) {
        Manufacturer m = getById(id);
        m.setManufacturerName(req.getManufacturerName());
        m.setContactPerson(req.getContactPerson());
        m.setPhone(req.getPhone());
        m.setEmail(req.getEmail());
        m.setAddress(req.getAddress());
        m.setLicenseNumber(req.getLicenseNumber());
        if (req.getIsActive() != null) m.setIsActive(req.getIsActive());
        Manufacturer saved = manufacturerRepository.save(m);
        notifier.refresh(securityHelper.getCurrentHospitalId());
        return saved;
    }

    @Transactional
    public Manufacturer toggleStatus(Long id) {
        Manufacturer m = getById(id);
        m.setIsActive(!m.getIsActive());
        Manufacturer saved = manufacturerRepository.save(m);
        notifier.refresh(securityHelper.getCurrentHospitalId());
        return saved;
    }
}
