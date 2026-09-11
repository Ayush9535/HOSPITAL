package com.hms.controller.hospital;

import jakarta.validation.Valid;

import com.hms.dto.CreateTaskRequest;
import com.hms.dto.UpdateTaskStatusRequest;
import com.hms.security.RequireModule;
import com.hms.service.hospital.ManualTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * ManualTaskController - bedside task management (Phase 1 Nurse module, M7).
 * Admins create and cancel tasks; assigned nurses progress and complete them.
 */
@RestController
@RequestMapping("/hospital/nurse-tasks")
@RequireModule("NURSING")
public class ManualTaskController {

    @Autowired
    private ManualTaskService manualTaskService;

    @PostMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> create(@Valid @RequestBody CreateTaskRequest req) {
        try {
            return ResponseEntity.ok(manualTaskService.createTask(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    @PreAuthorize("hasRole('HOSPITAL_ADMIN')")
    public ResponseEntity<?> listAll() {
        return ResponseEntity.ok(manualTaskService.listForHospital());
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('NURSE')")
    public ResponseEntity<?> listMy() {
        return ResponseEntity.ok(manualTaskService.listMyTasks());
    }

    @PutMapping("/{publicId}/status")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN', 'NURSE')")
    public ResponseEntity<?> updateStatus(@PathVariable String publicId, @Valid @RequestBody UpdateTaskStatusRequest req) {
        try {
            return ResponseEntity.ok(manualTaskService.updateStatus(publicId, req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
