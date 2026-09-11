package com.hms.controller.hospital;

import com.hms.dto.PatientDocumentDTO;
import com.hms.entity.PatientDocument;
import com.hms.service.hospital.PatientDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Documents a patient brought in, and the only way to read one back.
 *
 * <p>There is no static path to any of these files. A client holds a document's publicId and asks
 * for it; this resolves it against the caller's own facility, and only then does anything read
 * from storage. Knowing an id from another hospital gets the same answer as knowing nothing.
 */
@RestController
@RequestMapping({"/hospital", "/clinic"})
// A pharmacy facility holds no patient record to attach a report to. Without this, a
// PHARMACY-tenant admin reaches these routes simply because the controller is CORE.
@com.hms.security.TenantType({com.hms.entity.HospitalType.HOSPITAL, com.hms.entity.HospitalType.CLINIC})
public class PatientDocumentController {

    @Autowired private PatientDocumentService documentService;

    /** Reception takes the paperwork at the desk, so reception can file it. */
    @PostMapping(value = "/patients/{patientId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST')")
    public ResponseEntity<?> upload(
            @PathVariable Long patientId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            @RequestParam("title") String title,
            @RequestParam(value = "reportDate", required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso =
                    org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate reportDate,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "opdId", required = false) Long opdId,
            @RequestParam(value = "ipdAdmissionId", required = false) Long ipdAdmissionId) {

        PatientDocumentDTO saved = documentService.upload(
                patientId, file, documentType, title, reportDate, source, notes, opdId, ipdAdmissionId);
        return ResponseEntity.ok(saved);
    }

    /** Metadata only. Nurses read patient records, so nurses can see what is on file. */
    @GetMapping("/patients/{patientId}/documents")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<List<PatientDocumentDTO>> list(@PathVariable Long patientId) {
        return ResponseEntity.ok(documentService.listForPatient(patientId));
    }

    /**
     * The bytes, streamed through this application.
     *
     * <p>Inline disposition so a browser can preview a report without downloading it, with the
     * stored name quoted rather than interpolated into a path.
     */
    @GetMapping("/patient-documents/{documentPublicId}/content")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR','RECEPTIONIST','NURSE','NURSE_INCHARGE')")
    public ResponseEntity<InputStreamResource> content(@PathVariable String documentPublicId) {
        PatientDocument document = documentService.requireReadableDocument(documentPublicId);
        InputStreamResource body = new InputStreamResource(documentService.openContent(document));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getMimeType()))
                .contentLength(document.getFileSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + headerSafe(document.getOriginalFileName()) + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    /** Filed in error, superseded, wrong patient. The record and the file both stay. */
    @PostMapping("/patient-documents/{documentPublicId}/archive")
    @PreAuthorize("hasAnyRole('HOSPITAL_ADMIN','DOCTOR')")
    public ResponseEntity<?> archive(@PathVariable String documentPublicId,
                                     @RequestBody(required = false) ArchiveRequest body) {
        documentService.archive(documentPublicId, body == null ? null : body.getReason());
        return ResponseEntity.ok(Map.of("message", "Document archived"));
    }

    /** Quotes and control characters out: this value goes into a response header. */
    private static String headerSafe(String fileName) {
        if (fileName == null || fileName.isBlank()) return "document";
        return fileName.replaceAll("[\"\\\\\\p{Cntrl}]", "_");
    }

    public static class ArchiveRequest {
        private String reason;
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
