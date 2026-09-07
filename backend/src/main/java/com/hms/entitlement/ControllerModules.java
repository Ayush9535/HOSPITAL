package com.hms.entitlement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which module each controller belongs to.
 *
 * <p>The point of writing this down is the fence around it: {@code EntitlementRegistryArchTest}
 * fails if a controller exists that is not listed here. A developer adding a controller has to say
 * which capability it is part of, and that is a deliberate edit rather than an omission — which is
 * how the current gaps arose. Eight of nine OT controllers are module-gated and the ninth is not;
 * OPD's visit endpoints reach three facility types while its vitals settings reach two. Nobody
 * decided either of those.
 *
 * <p><b>Declaration only.</b> Nothing reads this at runtime yet. It is the input the gating
 * checkpoints will use, and until then its only job is to make the next omission fail a test.
 */
public final class ControllerModules {

    private ControllerModules() {
    }

    private static final Map<String, String> BY_CONTROLLER = new LinkedHashMap<>();

    private static void put(String module, String... controllers) {
        for (String c : controllers) {
            BY_CONTROLLER.put(c, module);
        }
    }

    static {
        // CORE — every tenant, no plan grants it.
        put(EntitlementRegistry.CORE,
                "PatientController", "DoctorController", "ReceptionistController",
                "HospitalAuthController", "HospitalAuditController", "HospitalTicketController",
                "FaqController", "ConsultationNotePresetController", "PrescriptionPresetController",
                "PharmacistController",
                // The admin's own front page. Its optional analytics are gated block by block
                // inside the response, so the endpoint itself belongs to every hospital.
                "HospitalDashboardController",
                // Documents a patient brings belong to the patient, not to any module.
                "PatientDocumentController");

        put(EntitlementRegistry.OPD, "OpdController", "VitalSettingsController",
                // Follow-ups are the tail of an OPD consultation, so they follow OPD.
                "FollowUpController");
        put(EntitlementRegistry.APPOINTMENTS, "AppointmentController");

        // IPD carries wards and beds; admission forms are an inpatient record.
        put(EntitlementRegistry.IPD, "IpdAdmissionController");
        put(EntitlementRegistry.WARDS, "WardController");
        put(EntitlementRegistry.BEDS, "BedController");

        // Admission-scoped clinical records. Granted by IPD, authored under NURSING.
        put(EntitlementRegistry.CLINICAL_RECORDS,
                "VitalsController", "NursingNoteController", "SugarChartController",
                "InitialAssessmentController", "VulnerabilityAssessmentController",
                "MedicationAdminController", "AdmissionFormController", "FormAccessController");

        put(EntitlementRegistry.NURSING,
                "NurseController", "NurseInchargeController", "NurseAssignmentController",
                "NurseAttendanceController", "NurseScheduleController", "NurseCoverageController",
                "NurseWorkspaceController", "ManualTaskController", "TimeSlotController",
                "HospitalCalendarController", "NotificationController");

        put(EntitlementRegistry.OT,
                "SurgeryController", "SurgeryFormController", "SurgeryTeamController",
                "SurgeryExecutionController", "RecoveryController", "OtRoomController",
                "OtPolicyController", "OtPermissionController", "OtInchargeController");

        // Critical care. Hospital-only and never aliased to /clinic or /pharmacy.
        // Critical care. Hospital-only and never aliased to /clinic or /pharmacy. ICU clinical
        // records sit here rather than under CLINICAL_RECORDS: ICU is separately sellable, so a
        // hospital with IPD but without ICU must not reach them through ordinary record access.
        put(EntitlementRegistry.ICU, "IcuDashboardController", "IcuStayController",
                "IcuIoController", "IcuInfusionController", "IcuVentilatorController",
                "IcuVentilatorParameterController", "IcuSeverityScoreController",
                "IcuScoreTypeSettingController", "IcuAlertThresholdController");

        put(EntitlementRegistry.BILLING,
                "BillingController", "HospitalFeeController", "HospitalServiceController");

        put(EntitlementRegistry.PHARMACY,
                "PharmacyController", "PharmacySaleController", "PurchaseController",
                "InventoryController", "MedicineMasterController", "SupplierController",
                "ManufacturerController", "MedicineCategoryController", "PharmacyReportsController");
        put(EntitlementRegistry.PHARMACY_BRANCH, "PharmacyBranchController");

        put(EntitlementRegistry.MEDICAL_INVENTORY, "MedicineController");
        put(EntitlementRegistry.HOSPITAL_INVENTORY, "HospitalInventoryController");
        put(EntitlementRegistry.REPORTS, "HospitalStatsController");
    }

    /**
     * Controllers that are outside tenant entitlement entirely: the platform tier answers to
     * SUPER_ADMIN, and the health check answers to nobody.
     */
    public static final Set<String> NOT_TENANT_SCOPED = Set.of(
            "PlatformAuthController", "PlatformHospitalController", "PlatformPlanController",
            "PlatformUserController", "PlatformTicketController", "PlatformFaqController",
            "PlatformMedicineController", "PlatformInventoryItemController", "PlatformAuditController",
            "HealthController");

    /** The module this controller belongs to, or null if it has not been declared. */
    public static String moduleOf(String controllerSimpleName) {
        return BY_CONTROLLER.get(controllerSimpleName);
    }

    public static Map<String, String> all() {
        return Map.copyOf(BY_CONTROLLER);
    }
}
