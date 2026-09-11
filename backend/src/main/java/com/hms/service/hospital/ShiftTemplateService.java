package com.hms.service.hospital;

import com.hms.dto.ShiftTemplateRequest;
import com.hms.entity.ShiftTemplate;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.ShiftTemplateRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
public class ShiftTemplateService {
    @Autowired private ShiftTemplateRepository repository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private NurseShiftScheduleService nurseShiftScheduleService;
    @Autowired private com.hms.security.HospitalWebSocketHandler webSocketHandler;

    @Transactional
    public ShiftTemplate create(ShiftTemplateRequest req) {
        Long hospitalId = requireHospitalId();
        validate(req);
        ShiftTemplate t = new ShiftTemplate();
        t.setHospitalId(hospitalId);
        t.setName(req.getName().trim());
        t.setStartTime(req.getStartTime());
        t.setEndTime(req.getEndTime());
        t.setIsActive(true);
        ShiftTemplate saved = repository.save(t);
        audit("SHIFT_TEMPLATE_CREATED", saved.getName(), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    public List<ShiftTemplate> list(boolean activeOnly) {
        Long hospitalId = requireHospitalId();
        return activeOnly ? repository.findByHospitalIdAndIsActiveTrueOrderByStartTimeAsc(hospitalId)
                          : repository.findByHospitalIdOrderByStartTimeAsc(hospitalId);
    }

    @Transactional
    public ShiftTemplate update(String publicId, ShiftTemplateRequest req) {
        Long hospitalId = requireHospitalId();
        ShiftTemplate t = require(publicId, hospitalId);
        validate(req);
        t.setName(req.getName().trim());
        t.setStartTime(req.getStartTime());
        t.setEndTime(req.getEndTime());
        // saveAndFlush (not save): applyTemplateChangeToFuture below runs a bulk
        // @Modifying(clearAutomatically=true) query. That clear() detaches this
        // still-dirty template before it would be flushed, silently dropping the
        // update. Flushing here forces the template UPDATE to the DB first.
        ShiftTemplate saved = repository.saveAndFlush(t);
        // Future schedules using this template pick up the new times; past unchanged.
        nurseShiftScheduleService.applyTemplateChangeToFuture(saved.getId(), saved.getStartTime(), saved.getEndTime());
        audit("SHIFT_TEMPLATE_UPDATED", saved.getName(), hospitalId, saved.getId());
        broadcastRefresh(hospitalId);
        return saved;
    }

    @Transactional
    public void deactivate(String publicId) {
        Long hospitalId = requireHospitalId();
        ShiftTemplate t = require(publicId, hospitalId);
        t.setIsActive(false);
        repository.save(t);
        audit("SHIFT_TEMPLATE_DEACTIVATED", t.getName(), hospitalId, t.getId());
        broadcastRefresh(hospitalId);
    }

    @Transactional
    public void activate(String publicId) {
        Long hospitalId = requireHospitalId();
        ShiftTemplate t = require(publicId, hospitalId);
        t.setIsActive(true);
        repository.save(t);
        audit("SHIFT_TEMPLATE_ACTIVATED", t.getName(), hospitalId, t.getId());
        broadcastRefresh(hospitalId);
    }

    private void validate(ShiftTemplateRequest req) {
        if (req.getName() == null || req.getName().trim().isEmpty()) throw new IllegalArgumentException("Name is required");
        LocalTime s = req.getStartTime(), e = req.getEndTime();
        if (s == null || e == null) throw new IllegalArgumentException("Start and end time are required");
        if (s.equals(e)) throw new IllegalArgumentException("Start and end time must differ");
    }
    private ShiftTemplate require(String publicId, Long hospitalId) {
        ShiftTemplate t = repository.findByPublicId(publicId).orElseThrow(() -> new IllegalArgumentException("Shift template not found"));
        if (!hospitalId.equals(t.getHospitalId())) throw new UnauthorizedException("Belongs to another hospital");
        return t;
    }
    private Long requireHospitalId() {
        Long h = securityHelper.getCurrentHospitalId();
        if (h == null) throw new UnauthorizedException("Hospital ID not found");
        return h;
    }
    private void audit(String a, String d, Long h, Long id) {
        try { auditLogService.logAction(a, d, securityHelper.getCurrentUserEmail(), h, "SHIFT_TEMPLATE", String.valueOf(id), null); } catch (Exception e) {}
    }
    private void broadcastRefresh(Long hospitalId) {
        try { webSocketHandler.broadcast(hospitalId, "{\"type\":\"REFRESH_DATA\"}"); } catch (Exception e) {}
    }
}
