package com.hms.controller.platform;

import com.hms.dto.UserSummaryDTO;
import com.hms.service.platform.PlatformUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/platform/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformUserController {

    @Autowired
    private PlatformUserService userService;

    @GetMapping
    public ResponseEntity<Page<UserSummaryDTO>> getAllUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String hospitalId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(userService.getAllUsers(role, hospitalId, search, pageable));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetUserPassword(@PathVariable String id) {
        Map<String, String> credentials = userService.resetUserPassword(id);
        return ResponseEntity.ok(credentials);
    }

}
