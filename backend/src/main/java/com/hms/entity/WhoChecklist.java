package com.hms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * WhoChecklist - the WHO Surgical Safety Checklist for one procedure, in its three phases.
 *
 * Sign-In (before induction), Time-Out (before incision), Sign-Out (before the patient
 * leaves theatre). The proposal collapsed these into one step; that is clinically wrong
 * and Sign-Out is where the sponge and instrument counts are confirmed -- omitting it is a
 * retained-instrument risk.
 *
 * The fields the hospital is measured on are columns, not JSON: WHO compliance and the
 * counts-correct rate must be a SQL query, and the state machine blocks on them.
 */
@Entity
@Table(name = "who_checklists",
        uniqueConstraints = @UniqueConstraint(name = "uk_who_surgery", columnNames = {"surgery_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WhoChecklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "surgery_id", nullable = false)
    private Long surgeryId;

    // Each phase is signed independently. A timestamp present means that phase is complete.
    @Column(name = "sign_in_at")
    private LocalDateTime signInAt;
    @Column(name = "sign_in_by_user_id")
    private Long signInByUserId;

    @Column(name = "time_out_at")
    private LocalDateTime timeOutAt;
    @Column(name = "time_out_by_user_id")
    private Long timeOutByUserId;

    @Column(name = "sign_out_at")
    private LocalDateTime signOutAt;
    @Column(name = "sign_out_by_user_id")
    private Long signOutByUserId;

    /** Confirmed at Sign-In: the surgical site is marked. */
    @Column(name = "site_marked")
    private Boolean siteMarked;

    /** Confirmed at Sign-Out: sponge and instrument counts are correct. */
    @Column(name = "counts_correct")
    private Boolean countsCorrect;
}
