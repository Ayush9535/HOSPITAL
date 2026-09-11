package com.hms.dto;

import com.hms.entity.AnaesthesiaClearanceOutcome;
import lombok.Data;

@Data
public class RecordAnaesthesiaClearanceRequest {
    private AnaesthesiaClearanceOutcome outcome;
    private String conditionsComments;
}
