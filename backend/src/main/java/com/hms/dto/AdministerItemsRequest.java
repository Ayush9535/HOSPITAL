package com.hms.dto;

import java.util.List;
import jakarta.validation.Valid;

public class AdministerItemsRequest {
    @Valid
    private List<ConsultationRequest.AdministeredItem> administeredItems;

    public List<ConsultationRequest.AdministeredItem> getAdministeredItems() {
        return administeredItems;
    }

    public void setAdministeredItems(List<ConsultationRequest.AdministeredItem> administeredItems) {
        this.administeredItems = administeredItems;
    }
}
