package com.hms.dto;

import java.time.LocalDateTime;

public class IpdAdmissionSummaryDTO {
    private Long ipdId;
    private String ipdNumber;
    private String patientName;
    private Integer age;
    private String gender;
    private String wardName;
    private String bedNumber;
    private String doctorName;
    private LocalDateTime admissionDateTime;
    private String status;
    private Boolean admissionConfirmed;

    public Boolean getAdmissionConfirmed() { return admissionConfirmed; }
    public void setAdmissionConfirmed(Boolean admissionConfirmed) { this.admissionConfirmed = admissionConfirmed; }

    public Long getIpdId() { return ipdId; }
    public void setIpdId(Long ipdId) { this.ipdId = ipdId; }

    public String getIpdNumber() { return ipdNumber; }
    public void setIpdNumber(String ipdNumber) { this.ipdNumber = ipdNumber; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getWardName() { return wardName; }
    public void setWardName(String wardName) { this.wardName = wardName; }

    public String getBedNumber() { return bedNumber; }
    public void setBedNumber(String bedNumber) { this.bedNumber = bedNumber; }

    public String getDoctorName() { return doctorName; }
    public void setDoctorName(String doctorName) { this.doctorName = doctorName; }

    public LocalDateTime getAdmissionDateTime() { return admissionDateTime; }
    public void setAdmissionDateTime(LocalDateTime admissionDateTime) { this.admissionDateTime = admissionDateTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
