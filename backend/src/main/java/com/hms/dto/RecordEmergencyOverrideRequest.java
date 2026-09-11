package com.hms.dto;

import com.hms.entity.PreOpGate;
import lombok.Data;

import java.util.Set;

@Data
public class RecordEmergencyOverrideRequest {
    private String reason;
    private Set<PreOpGate> bypassedGates;
}
