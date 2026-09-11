package com.hms.service.hospital;

import com.hms.dto.PatientDocumentDTO;
import com.hms.entity.Patient;
import com.hms.entity.PatientDocument;
import com.hms.exception.ResourceNotFoundException;
import com.hms.exception.UnauthorizedException;
import com.hms.repository.IpdAdmissionRepository;
import com.hms.repository.OpdRepository;
import com.hms.repository.PatientDocumentRepository;
import com.hms.repository.PatientRepository;
import com.hms.repository.UserRepository;
import com.hms.security.SecurityContextHelper;
import com.hms.service.AuditLogService;
import com.hms.service.storage.ClinicalDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Documents patients bring with them.
 *
 * <p>The file never becomes reachable by URL. It is written to private storage under a key this
 * system generates, and the only way back to it is this service, which resolves the document
 * against the caller's own facility before asking storage for anything.
 */
@Service
public class PatientDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(PatientDocumentService.class);

    /** Mirrors the configured multipart limit rather than inventing a second number. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", "pdf",
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    @Autowired private PatientDocumentRepository documentRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private OpdRepository opdRepository;
    @Autowired private IpdAdmissionRepository ipdAdmissionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SecurityContextHelper securityHelper;
    @Autowired private AuditLogService auditLogService;
    @Autowired private ClinicalDocumentStorage storage;
    @Autowired private com.hms.security.NurseAccessGuard nurseAccessGuard;

    private Long requireHospitalId() {
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) throw new UnauthorizedException("Hospital ID not found in context");
        return hospitalId;
    }

    /**
     * Records a document against a patient.
     *
     * <p>The bytes land in storage before the row exists, because the row must carry the key. The
     * two cannot share a transaction -- a filesystem does not roll back -- so a failure after the
     * write removes the file rather than leaving a document nobody can account for.
     */
    public PatientDocumentDTO upload(Long patientId, MultipartFile file, String documentType,
                                     String title, LocalDate reportDate, String source,
                                     String notes, Long opdId, Long ipdAdmissionId) {
        Long hospitalId = requireHospitalId();

        Patient patient = patientRepository
                .findByIdAndHospitalIdAndIsActiveTrue(patientId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        String type = requireDocumentType(documentType);
        String cleanTitle = requireTitle(title);
        String extension = validateFile(file);

        // Optional context, but only this patient's. A raw OPD or admission id that happens to
        // exist is not permission to file a document against it.
        if (opdId != null) {
            var opd = opdRepository.findByIdAndHospitalIdWithPatientAndDoctor(opdId, hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException("OPD visit not found"));
            if (opd.getPatient() == null || !patient.getId().equals(opd.getPatient().getId())) {
                throw new IllegalArgumentException("That OPD visit belongs to a different patient.");
            }
        }
        if (ipdAdmissionId != null) {
            var admission = ipdAdmissionRepository.findByIdAndHospitalId(ipdAdmissionId, hospitalId)
                    .orElseThrow(() -> new ResourceNotFoundException("IPD admission not found"));
            if (!patient.getId().equals(admission.getPatientId())) {
                throw new IllegalArgumentException("That admission belongs to a different patient.");
            }
        }

        ClinicalDocumentStorage.StoredDocument stored;
        try (InputStream content = file.getInputStream()) {
            stored = storage.store(
                    "h" + hospitalId, "p" + patient.getId(), extension, content, file.getSize());
        } catch (IOException e) {
            throw new IllegalArgumentException("The uploaded file could not be read.");
        }

        PatientDocument saved;
        try {
            PatientDocument document = new PatientDocument();
            document.setPublicId("doc-" + UUID.randomUUID());
            document.setHospitalId(hospitalId);
            document.setPatientId(patient.getId());
            document.setOpdId(opdId);
            document.setIpdAdmissionId(ipdAdmissionId);
            document.setDocumentType(type);
            document.setTitle(cleanTitle);
            document.setReportDate(reportDate);
            document.setSource(trimOrNull(source, 200));
            document.setNotes(trimOrNull(notes, 1000));
            document.setOriginalFileName(safeFileName(file.getOriginalFilename()));
            document.setMimeType(file.getContentType());
            document.setFileSizeBytes(stored.sizeBytes());
            document.setStorageKey(stored.storageKey());
            document.setUploadedByUserId(securityHelper.getCurrentUserId());
            document.setIsActive(true);

            saved = documentRepository.save(document);
        } catch (RuntimeException e) {
            // Only persistence failures reach here. The row never happened, so the bytes must not
            // linger as an orphan nothing refers to.
            storage.deleteQuietly(stored.storageKey());
            throw e;
        }

        // Past this point the document exists. Nothing below may delete its file: a response that
        // fails to build is a failed response, not a reason to destroy a stored clinical record.
        audit("PATIENT_DOCUMENT_UPLOADED",
                type + " '" + cleanTitle + "' uploaded for patient " + patient.getPublicId(),
                hospitalId, saved.getPublicId(), null);

        return new PatientDocumentDTO(saved, uploaderName(saved.getUploadedByUserId()));
    }

    /**
     * Nursing access follows the nursing rules, not merely the facility.
     *
     * <p>A nurse may read the records of patients they are looking after. Being employed by the
     * hospital is not by itself a reason to open any patient's file, so the existing guard --
     * ward scope, assignment, or covering for someone who has one -- decides, exactly as it does
     * for every other nursing record. Doctors, admins and reception are unaffected; the guard
     * returns immediately for them.
     */
    private void assertNursingScope(Long patientId, Long hospitalId) {
        String role = securityHelper.getCurrentUserRole();
        if (!"NURSE".equals(role) && !"NURSE_INCHARGE".equals(role)) return;

        List<com.hms.entity.IpdAdmission> admissions =
                ipdAdmissionRepository.findByHospitalIdAndPatientId(hospitalId, patientId);
        if (admissions.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not assigned to this patient");
        }
        org.springframework.security.access.AccessDeniedException refusal = null;
        for (com.hms.entity.IpdAdmission admission : admissions) {
            try {
                nurseAccessGuard.assertAssigned(admission.getId());
                return; // one admission they may see is enough
            } catch (org.springframework.security.access.AccessDeniedException e) {
                refusal = e;
            }
        }
        throw refusal;
    }

    /** A patient's documents, newest report first. Metadata only. */
    @Transactional(readOnly = true)
    public List<PatientDocumentDTO> listForPatient(Long patientId) {
        Long hospitalId = requireHospitalId();
        patientRepository.findByIdAndHospitalIdAndIsActiveTrue(patientId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        assertNursingScope(patientId, hospitalId);

        return documentRepository
                .findByHospitalIdAndPatientIdAndIsActiveTrueOrderByReportDateDescIdDesc(hospitalId, patientId)
                .stream()
                .map(d -> new PatientDocumentDTO(d, uploaderName(d.getUploadedByUserId())))
                .toList();
    }

    /** The document itself, resolved against the caller's facility before storage is touched. */
    @Transactional(readOnly = true)
    public PatientDocument requireReadableDocument(String documentPublicId) {
        Long hospitalId = requireHospitalId();
        PatientDocument document = documentRepository
                .findByPublicIdAndHospitalIdAndIsActiveTrue(documentPublicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
        assertNursingScope(document.getPatientId(), hospitalId);
        return document;
    }

    public InputStream openContent(PatientDocument document) {
        return storage.load(document.getStorageKey());
    }

    /**
     * Takes a document out of the working list. The row and the stored bytes both stay -- this is
     * a clinical record, and "filed in error" is itself worth keeping.
     */
    @Transactional
    public void archive(String documentPublicId, String reason) {
        Long hospitalId = requireHospitalId();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to archive a document.");
        }
        if (reason.trim().length() > 500) {
            throw new IllegalArgumentException("The archive reason is too long.");
        }

        PatientDocument document = documentRepository
                .findByPublicIdAndHospitalIdAndIsActiveTrue(documentPublicId, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        document.setIsActive(false);
        document.setArchivedByUserId(securityHelper.getCurrentUserId());
        document.setArchivedAt(LocalDateTime.now());
        document.setArchiveReason(reason.trim());
        documentRepository.save(document);

        audit("PATIENT_DOCUMENT_ARCHIVED",
                "Document '" + document.getTitle() + "' archived",
                hospitalId, document.getPublicId(), reason.trim());
    }

    // ---------------------------------------------------------------- validation

    /**
     * Server-side and authoritative, in the shape CsvUploads established.
     *
     * <p>Residual limitation, stated rather than hidden: this trusts the declared content type
     * and the extension agreeing with each other. Neither proves what the bytes are, so a
     * renamed file with a matching header claim would pass. Content sniffing would need a
     * library this project does not carry, and is worth its own decision.
     */
    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("File is too large. Maximum size is 5 MB.");
        }
        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT).trim();
        String extension = ALLOWED_TYPES.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException(
                    "Only PDF, JPEG, PNG and WebP files can be attached to a patient.");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The uploaded file has no name.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Set<String> acceptable = "jpg".equals(extension) ? Set.of(".jpg", ".jpeg") : Set.of("." + extension);
        if (acceptable.stream().noneMatch(lower::endsWith)) {
            throw new IllegalArgumentException(
                    "The file name does not match its type. Expected a " + extension + " file.");
        }
        assertContentMatches(extension, file);
        return extension;
    }

    /**
     * What the bytes actually are.
     *
     * <p>A declared content type and a file extension are both things the caller chose. These are
     * the first bytes of the file, which the caller would have to work considerably harder to
     * lie about. Deliberately small and dependency-free: four formats, checked at the only two
     * offsets that matter.
     */
    private static void assertContentMatches(String extension, MultipartFile file) {
        byte[] head = new byte[16];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw new IllegalArgumentException("The uploaded file could not be read.");
        }
        if (read < 12) {
            throw new IllegalArgumentException("That file does not look like a " + extension + ".");
        }
        boolean matches = switch (extension) {
            // %PDF
            case "pdf" -> head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44 && head[3] == 0x46;
            // JPEG SOI
            case "jpg" -> (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            // PNG signature
            case "png" -> (head[0] & 0xFF) == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47
                    && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A;
            // RIFF....WEBP
            case "webp" -> head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                    && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50;
            default -> false;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "That file is not a valid " + extension + ". Its contents do not match its type.");
        }
    }

    private static String requireDocumentType(String documentType) {
        String type = documentType == null ? "" : documentType.trim().toUpperCase(Locale.ROOT);
        if (!PatientDocument.TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown document type: " + documentType);
        }
        return type;
    }

    private static String requireTitle(String title) {
        String cleaned = title == null ? "" : title.trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException("A title is required.");
        if (cleaned.length() > 200) throw new IllegalArgumentException("The title is too long.");
        return cleaned;
    }

    /** Kept only as metadata; it never becomes part of a path. */
    private static String safeFileName(String original) {
        if (original == null || original.isBlank()) return "document";
        String base = original.replace("\\", "/");
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty()) return "document";
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private static String trimOrNull(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    /** Tenant-scoped: naming an uploader is not a reason to be able to read any user by id. */
    private String uploaderName(Long userId) {
        if (userId == null) return null;
        Long hospitalId = securityHelper.getCurrentHospitalId();
        if (hospitalId == null) return null;
        return userRepository.findByIdAndHospitalId(userId, hospitalId)
                .map(com.hms.entity.User::getName).orElse(null);
    }

    /** Best-effort, as everywhere else. Never records the file itself or where it sits. */
    private void audit(String action, String details, Long hospitalId, String documentPublicId, String reason) {
        try {
            auditLogService.logAction(action, details, securityHelper.getCurrentUserEmail(),
                    hospitalId, "PATIENT_DOCUMENT", documentPublicId, reason);
        } catch (Exception e) {
            logger.warn("Could not write the audit entry for a patient document action.");
        }
    }
}
