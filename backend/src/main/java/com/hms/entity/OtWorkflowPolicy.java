package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OtWorkflowPolicy - one hospital's override of one workflow policy, optionally scoped to
 * a case priority (so emergencies can waive approval and financial clearance).
 *
 * Overrides only. An absent row means OtPolicies.defaultValue(key). Resolution prefers a
 * row matching the case's priority scope, then falls back to ANY, then to the default.
 */
@Entity
@Table(name = "ot_workflow_policies",
        uniqueConstraints = @UniqueConstraint(name = "uk_ot_policy",
                columnNames = {"hospital_id", "policy_key", "priority_scope"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtWorkflowPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "policy_key", nullable = false, length = 40)
    private String policyKey;

    @Column(name = "priority_scope", nullable = false, length = 10)
    private String priorityScope = "ANY";

    @Column(name = "value", nullable = false, length = 120)
    private String value;
}
