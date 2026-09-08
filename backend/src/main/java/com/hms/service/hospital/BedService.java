package com.hms.service.hospital;

import com.hms.dto.BedResponse;
import com.hms.entity.Bed;
import com.hms.repository.BedRepository;
import com.hms.security.SecurityContextHelper;

import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BedService {

    private final BedRepository bedRepository;
    private final SecurityContextHelper securityHelper;

    public BedService(BedRepository bedRepository, SecurityContextHelper securityHelper) {
        this.bedRepository = bedRepository;
        this.securityHelper = securityHelper;
    }

    public List<BedResponse> getAvailableBeds(Long wardId) {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        List<Bed> list;
        if (wardId != null) {
            list = bedRepository.findByWardIdAndHospitalId(wardId, hospitalId);
            list = list.stream().filter(b -> "available".equals(b.getStatus())).collect(Collectors.toList());
        } else {
            list = bedRepository.findByHospitalIdAndStatus(hospitalId, "available");
        }
        return list.stream().map(b -> {
            BedResponse br = new BedResponse();
            br.setBedId(b.getBedId());
            br.setBedCode(b.getBedCode());
            br.setStatus(b.getStatus());
            return br;
        }).collect(Collectors.toList());
    }

}
