package com.hms.controller.publicapi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight public health endpoint used by external uptime probes (Render/UptimeRobot).
 *
 * This endpoint is intentionally public and minimal:
 * - It must remain unauthenticated so external monitoring services can probe the app.
 * - It must avoid any global response wrapping or tenant/user resolution to guarantee
 *   a stable plain-text 200 response.
 *
 * Render/UptimeRobot will perform a simple `GET /api/public/health` and expect
 * HTTP 200 with the exact body `HMS Backend Running`.
 */
@RestController
@RequestMapping("/api/public")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("HMS Backend Running");
    }
}
