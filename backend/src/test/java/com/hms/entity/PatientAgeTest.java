package com.hms.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PatientAgeTest {

    @Test
    void getAge_birthdayAlreadyPassedThisYear_returnsFullYears() {
        Patient patient = new Patient();
        // If today is 2026-07-04, someone born 1990-01-15 has already had
        // their birthday this year — age is exactly 2026 - 1990 = 36.
        patient.setDateOfBirth(LocalDate.now().minusYears(36).minusMonths(1));

        assertThat(patient.getAge()).isEqualTo(36);
    }

    @Test
    void getAge_birthdayNotYetReachedThisYear_returnsOneLessThanYearDifference() {
        Patient patient = new Patient();
        // Born 36 years ago minus 1 day means the birthday this year hasn't
        // happened yet — age should be 35, not 36.
        patient.setDateOfBirth(LocalDate.now().minusYears(36).plusDays(1));

        assertThat(patient.getAge()).isEqualTo(35);
    }

    @Test
    void getAge_bornToday_returnsZero() {
        Patient patient = new Patient();
        patient.setDateOfBirth(LocalDate.now());

        assertThat(patient.getAge()).isEqualTo(0);
    }

    @Test
    void getAge_noDateOfBirth_returnsNull() {
        Patient patient = new Patient();

        assertThat(patient.getAge()).isNull();
    }
}
