package com.hms.dto;

import jakarta.validation.Valid;

public class AddIpdFollowupRequest {
    private String diagnosis;
    private String notes;

    @Valid
    private java.util.List<ConsultationRequest.AdministeredItem> administeredItems;

    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public java.util.List<ConsultationRequest.AdministeredItem> getAdministeredItems() { return administeredItems; }
    public void setAdministeredItems(java.util.List<ConsultationRequest.AdministeredItem> administeredItems) { this.administeredItems = administeredItems; }
}
