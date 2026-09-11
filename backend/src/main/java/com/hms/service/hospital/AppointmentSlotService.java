package com.hms.service.hospital;

import com.hms.dto.AppointmentSlotRequest;
import com.hms.entity.AppointmentSlot;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.AppointmentSlotRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
public class AppointmentSlotService {
    @Autowired private AppointmentSlotRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public AppointmentSlot create(AppointmentSlotRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        AppointmentSlot s = new AppointmentSlot();
        s.setHospitalId(hospitalId);
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        s.setIsActive(true);
        AppointmentSlot saved = repository.save(s);
        audit("APPOINTMENT_SLOT_CREATED", describe(saved), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    public List<AppointmentSlot> list(boolean activeOnly) {
        Long hospitalId = requireHospitalId();
        return activeOnly ? repository.findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(hospitalId)
                          : repository.findByHospitalIdOrderByStartTimeAsc(hospitalId);
    }

    @Transactional
    public AppointmentSlot update(String publicId, AppointmentSlotRequest req) {
        Long hospitalId = requireHospitalId();
        AppointmentSlot s = require(publicId, hospitalId);
        validate(req);
        s.setStartTime(req.getStartTime());
        s.setEndTime(req.getEndTime());
        AppointmentSlot saved = repository.save(s);
        audit("APPOINTMENT_SLOT_UPDATED", describe(saved), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public void deactivate(String publicId) {
        Long hospitalId = requireHospitalId();
        AppointmentSlot s = require(publicId, hospitalId);
        s.setIsActive(false);
        repository.save(s);
        audit("APPOINTMENT_SLOT_DEACTIVATED", describe(s), hospitalId, s.getId());
        broadcastRefresh(hospitalId);
    }

    private void validate(AppointmentSlotRequest req) {
        LocalTime s = req.getStartTime(), e = req.getEndTime();
        if (s == null || e == null) throw new IllegalArgumentException("Start and end time are required");
        if (!e.isAfter(s)) throw new IllegalArgumentException("End time must be after start time");
    }
    private AppointmentSlot require(String publicId, Long hospitalId) {
        AppointmentSlot s = repository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Appointment slot not found"));
        if (!hospitalId.equals(s.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return s;
    }
    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }
    private String describe(AppointmentSlot s) {
        return s.getStartTime() + "-" + s.getEndTime();
    }
    private void audit(String a, String d, Long h, Long id) {
        try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "APPOINTMENT_SLOT", String.valueOf(id), null); } catch (Exception e) {}
    }
    private void broadcastRefresh(Long hospitalId) {
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {}
    }
}
