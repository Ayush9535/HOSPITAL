package com.hms.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "opd")
public class Opd {

    public enum VisitType { NEW, FOLLOWUP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", unique = true)
    private String caseId;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "receptionist_id")
    private User receptionist;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    // Vitals captured per OPD (can change every visit)
    @Column(name = "bp")
    private String bp;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "pulse")
    private Integer pulse;

    @Column(name = "weight")
    private Double weight;

    /** Height in cm, captured with the OPD vitals. */
    @Column(name = "height")
    private Double height;

    @Column(name = "spo2")
    private Integer spo2;

    /** JSON map of hospital-defined custom vitals, keyed by vital_key. e.g. {"GRBS":"110"} */
    @Column(name = "custom_vitals", columnDefinition = "text")
    private String customVitals;

    @Column(name = "problem", columnDefinition = "text")
    private String problem;

    @Enumerated(EnumType.STRING)
    @Column(name = "visit_type")
    private VisitType visitType;

    /**
     * The clinical state of the OPD case — never its payment state (a bill can be paid before the
     * doctor is even seen; see hospital_settings.bill_payment_timing).
     *
     *  QUEUED    — case created, patient waiting for the doctor. Shown as "In Queue".
     *  COMPLETED — the doctor finished the consultation. Set by DoctorService, not by payment.
     *  IN_IPD    — the case was admitted and continues as an IPD admission.
     *  CONSULTED — LEGACY. Nothing writes it. It used to mean "doctor done, bill not yet collected",
     *              back when payment was what marked a case COMPLETED. Kept only so old rows still
     *              deserialise; DatabaseMigrationRunner promotes them to COMPLETED on boot.
     */
    public enum Status { QUEUED, CONSULTED, COMPLETED, IN_IPD }

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.QUEUED;

    @Column(name = "ipd_admit_recommended", nullable = false)
    private Boolean ipdAdmitRecommended = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Opd() {
        this.createdAt = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }
    public User getReceptionist() { return receptionist; }
    public void setReceptionist(User receptionist) { this.receptionist = receptionist; }
    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }
    public String getBp() { return bp; }
    public void setBp(String bp) { this.bp = bp; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getPulse() { return pulse; }
    public void setPulse(Integer pulse) { this.pulse = pulse; }
    public Double getWeight() { return weight; }
    public void setWeight(Double weight) { this.weight = weight; }
    public Integer getSpo2() { return spo2; }
    public void setSpo2(Integer spo2) { this.spo2 = spo2; }
    public String getProblem() { return problem; }
    public void setProblem(String problem) { this.problem = problem; }
    public VisitType getVisitType() { return visitType; }
    public void setVisitType(VisitType visitType) { this.visitType = visitType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Boolean getIpdAdmitRecommended() { return ipdAdmitRecommended; }
    public void setIpdAdmitRecommended(Boolean ipdAdmitRecommended) { this.ipdAdmitRecommended = ipdAdmitRecommended; }
    public Double getHeight() { return height; }
    public void setHeight(Double height) { this.height = height; }
    public String getCustomVitals() { return customVitals; }
    public void setCustomVitals(String customVitals) { this.customVitals = customVitals; }
}
