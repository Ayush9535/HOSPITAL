package com.hms.security;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build-time guard against the cross-tenant IDOR class of bug.
 *
 * The audits repeatedly found controllers/services loading a tenant-owned entity by its raw
 * numeric id (repository.findById) and forgetting to check the owning hospital. A runtime test
 * (CrossTenantIsolationTest) covers the endpoints we already know about; this static test
 * covers the ones we don't yet, by freezing the set of production methods that call a
 * repository lookup-by-id. When a new one appears the build fails, forcing a reviewer to
 * confirm the new lookup is tenant-scoped before it can ship.
 *
 * This does not prove each call is safe — ArchUnit cannot see whether a hospitalId check
 * follows. It turns "someone silently added an unscoped findById" into a review gate, which is
 * exactly the event that produced the vulnerabilities. Same philosophy as
 * ClinicPharmacyIsolationTest's frozen golden set: to change the set, edit ALLOWLIST
 * deliberately — that edit is the review.
 */
class TenantScopingArchTest {

    /** Spring Data read-by-id methods. Custom scoped finders (findByIdAndHospitalId…) are fine. */
    private static final Set<String> LOOKUP_METHODS = Set.of(
            "findById", "getById", "getOne", "getReferenceById");

    // Production methods known to call a repository lookup-by-id. A new entry means a new lookup
    // was added: confirm it is tenant-scoped (a *AndHospitalId finder, or entity.getHospitalId()
    // checked against securityHelper.getCurrentHospitalId()) and covered by
    // CrossTenantIsolationTest, THEN add it here. Frozen 2026-07-13.
    private static final Set<String> ALLOWLIST = new TreeSet<>(Set.of(
            "AdmissionFormService#prefillDraft",
            "AdmissionFormService#requireAdmission",
            "AdmissionFormService#withBranding",
            "AppointmentService#createAppointment",
            "AppointmentService#validateOpdAccess",
            "BedController#requireBedForWardAccess",
            "BedService#updateStatus",
            "BedStatusService#change",
            "BedStatusService#history",
            "BillingController#downloadReceipt",
            "BillingController#getIpdBill",
            "BillingController#payBilling",
            "BillingController#updateBillItems",
            "BillingController#validateBillingAccess",
            "BillingPdfService#generateBillingReceiptPdf",
            "BillingSchedulerService#processAdmissionCharge",
            "BillingService#autoGenerateOpdBill",
            "BillingService#createOpdBill",
            "BillingService#recalculateTotal",
            "BillingService#updateStatus",
            "BillingService#validateBillingAccess",
            "ClinicalPdfService#generateIpdPrescriptionPdf",
            "ClinicalPdfService#generatePrescriptionPdf",
            "ConsultationNotePresetController#doctorNameOrNull",
            "DoctorController#downloadPrescription",
            "DoctorController#downloadPrescriptionByOpd",
            "DoctorService#submitConsultation",
            "FaqController#getFaqs",
            "HospitalDashboardService#getOverview",
            "HospitalAuthService#getHospitalFees",
            "HospitalAuthService#getProfile",
            "HospitalAuthService#login",
            "HospitalAuthService#updateBarcodeSetting",
            "HospitalAuthService#updateHospitalFees",
            "HospitalAuthService#updateHospitalOperationsSettings",
            "HospitalAuthService#updateOtInchargeSetting",
            "HospitalAuthService#updatePrintAndPaymentSettings",
            "HospitalAuthService#updateProfile",
            "HospitalAuthService#updateSeparateNurseLoginSetting",
            "HospitalCalendarService#dayDetail",
            "HospitalCalendarService#scopedSurgeries",
            "HospitalInventoryService#collectAndValidateStocks",
            "HospitalInventoryService#deleteInventoryItem",
            "HospitalInventoryService#updateInventoryItem",
            "HospitalServiceService#getItemNamesForService",
            "HospitalStatsController#downloadPatientActivityPdf",
            "HospitalTicketController#createTicket",
            "IcuAlertEvaluator#evaluateVitals",
            "IcuAlertEvaluator#recipientsFor",
            "IcuInfusionService#correctRate",
            "IcuInfusionService#requireAdmission",
            "IcuInfusionService#resolvePrescription",
            "IcuIoService#requireAdmission",
            "IcuSeverityScoreService#requireAdmission",
            "IcuVentilatorService#requireAdmission",
            "InitialAssessmentService#requireAdmission",
            "InventoryTransactionService#getTransactionHistory",
            "IpdAdmissionService#addIpdFollowup",
            "IpdAdmissionService#addIpdPrescription",
            // Remaining by-id load here is hospitalRepository.findById(hospitalId) where the id
            // comes from the authenticated token, not the request. The request-controlled
            // medicine lookup that used to make this entry false-safe is now
            // findByIdAndHospitalId (see IpdMedicineTenantIsolationTest).
            "IpdAdmissionService#administerItems",
            "IpdAdmissionService#administerHospitalItems",
            "IpdAdmissionService#admitFromOpd",
            "IpdAdmissionService#changeBed",
            "IpdAdmissionService#confirmDischarge",
            "IpdAdmissionService#getAdmittedIpdSummariesForCurrentUser",
            "IpdAdmissionService#getIpdAdmissionDetails",
            "IpdAdmissionService#hasNursingModule",
            "IpdAdmissionService#listIpdAdmissions",
            "IpdAdmissionService#listMyIpdAdmissionsForDoctor",
            "IpdAdmissionService#requireOwnedAdmission",
            "IpdAdmissionService#stopPrescription",
            "ManualTaskService#createTask",
            "MedicationAdministrationService#requireAdmission",
            "MedicineService#deleteCatalogMedicine",
            "MedicineService#deleteInventoryMedicine",
            "MedicineService#updateCatalogMedicine",
            "MedicineService#updateInventoryMedicine",
            "NurseAssignmentService#assignNurse",
            "NurseAssignmentService#autoAssignForAdmission",
            "NurseAssignmentService#getAssignmentOverview",
            "NurseAssignmentService#requireActiveAdmission",
            "NurseAssignmentService#requireNurse",
            "NurseAttendanceService#getSheet",
            "NurseAttendanceService#mark",
            "NurseCoverageService#coveredUserIds",
            "NurseCoverageService#effectiveWardId",
            "NurseCoverageService#effectiveWardNurses",
            "NurseCoverageService#removeSubstitution",
            "NurseCoverageService#requireNurse",
            "NurseService#demote",
            "NurseService#promote",
            "NurseService#requireProfile",
            "NurseService#toNurseView",
            "NurseService#validateWard",
            "NurseShiftScheduleService#decorate",
            "NurseShiftScheduleService#requireNurse",
            "NurseWorkspaceService#assignPatientNurse",
            "NurseWorkspaceService#buildMyPatient",
            "NurseWorkspaceService#getMyNurses",
            "NurseWorkspaceService#getMyWards",
            "NurseWorkspaceService#buildPatientDetail",
            "NurseWorkspaceService#getWardPatients",
            "NursingNoteService#requireAdmission",
            "OpdController#downloadOpdReportPdf",
            "OpdController#getOpdDocumentsPdf",
            "OpdController#getOpdPdf",
            "OpdService#createOpd",
            "OpdService#getDoctorName",
            "OpdService#getPatientNameAndCustomIdAndPublicId",
            "OtRoomService#requireRoomById",
            "PatientController#downloadPatientsReportPdf",
            "PatientService#getIpdMedicinesPdf",
            "PatientService#getIpdPrescriptionPdf",
            "PatientService#getOpdMedicinesPdf",
            "PatientService#getPatientById",
            "PharmacyBranchService#delete",
            "PharmacyBranchService#resetPassword",
            "PharmacyController#dispenseMedicine",
            "PharmacySaleController#downloadReceipt",
            "PharmacySaleService#processPatientReturn",
            "PlatformFAQService#getFAQById",
            "PlatformFaqController#getFaq",
            "PlatformFaqController#updateFaq",
            "PlatformHospitalService#createHospital",
            "PlatformInventoryItemByTypeService#getItemByIdAndType",
            "PlatformPlanService#getSubscriptionInfo",
            "PlatformPlanService#propagateModulesToSubscribers",
            "PlatformTicketController#getTicket",
            "PlatformTicketController#updateTicketStatus",
            "PlatformTicketService#getTicketById",
            "PlatformTicketService#updateTicketStatus",
            "PlatformUserService#resetUserPassword",
            "PrescriptionPresetController#doctorNameOrNull",
            "PresetOwnershipSupport#sanitizeAssignedDoctorId",
            "RecoveryService#requireSurgery",
            "SugarChartService#requireAdmission",
            "SurgeryExecutionService#requireSurgery",
            "SurgeryFormService#requireAdmission",
            "SurgeryFormService#requireSurgery",
            "SurgeryService#decorate",
            "SurgeryService#notifyNurse",
            "SurgeryService#requireAdmission",
            "SurgeryService#schedule",
            "SurgeryTeamService#remove",
            "SurgeryTeamService#requireSurgery",
            "VitalsService#requireAdmission",
            "VulnerabilityAssessmentService#requireAdmission",
            "WardService#deleteWard",
            "WardService#setIncharge",
            "WardService#updateWard"));

    @Test
    void noUnreviewedRepositoryLookupById() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.hms.controller", "com.hms.service");

        Set<String> actual = new TreeSet<>();
        for (JavaClass c : classes) {
            for (JavaMethod m : c.getMethods()) {
                // Skip compiler-synthetic methods. Lambda/access$ method names carry a
                // numeric index (e.g. lambda$11) that javac assigns non-deterministically
                // across builds, which made this guard flaky (the frozen allowlist could
                // never match). Direct repository lookups in real methods are still checked;
                // the rare findById inside a lambda is covered by CrossTenantIsolationTest.
                if (m.getName().contains("lambda$") || m.getName().contains("access$")) {
                    continue;
                }
                for (JavaMethodCall call : m.getMethodCallsFromSelf()) {
                    AccessTarget.MethodCallTarget t = call.getTarget();
                    if (LOOKUP_METHODS.contains(t.getName())
                            && t.getOwner().getSimpleName().endsWith("Repository")) {
                        actual.add(c.getSimpleName() + "#" + m.getName());
                    }
                }
            }
        }

        Set<String> unreviewed = new TreeSet<>(actual);
        unreviewed.removeAll(ALLOWLIST);

        // On failure only, print the full current inventory so ALLOWLIST can be re-frozen.
        if (!unreviewed.isEmpty()) {
            System.out.println("=== ARCH: full repository lookup-by-id inventory (" + actual.size() + ") ===");
            actual.forEach(s -> System.out.println("            \"" + s + "\","));
        }
        assertThat(unreviewed)
                .as("New repository lookup-by-id call site(s). Each loads an entity by a raw id: "
                  + "ensure it is tenant-scoped (use a *AndHospitalId finder, or compare "
                  + "entity.getHospitalId() to securityHelper.getCurrentHospitalId()), add a "
                  + "cross-tenant case to CrossTenantIsolationTest, then add the method to "
                  + "ALLOWLIST in TenantScopingArchTest.")
                .isEmpty();
    }
}
