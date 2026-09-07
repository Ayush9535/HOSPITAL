    @PutMapping("/{id}/change-bed")
    @PreAuthorize("hasRole('RECEPTIONIST')")
    public ResponseEntity<?> changeBed(@PathVariable("id") Long id, @RequestParam("newBedId") Long newBedId) {
        try {
            IpdAdmission updated = ipdAdmissionService.changeBed(id, newBedId);
            return ResponseEntity.ok(updated);
        } catch (org.springframework.security.access.AccessDeniedException ade) {
            return ResponseEntity.status(403).body("Access denied");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
