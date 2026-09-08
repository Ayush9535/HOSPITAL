package com.hms.security;

import com.hms.entity.HospitalSetting;
import com.hms.entity.NurseProfile;
import com.hms.repository.HospitalSettingRepository;
import com.hms.repository.NurseProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformingNurseResolverTest {

    @Mock HospitalSettingRepository hospitalSettingRepository;
    @Mock NurseProfileRepository nurseProfileRepository;
    @Mock SecurityContextHelper securityHelper;

    @InjectMocks PerformingNurseResolver resolver;

    private void loginMode(boolean on) {
        HospitalSetting s = new HospitalSetting(); s.setSeparateNurseLogin(on);
        when(hospitalSettingRepository.findByHospital_Id(7L)).thenReturn(Optional.of(s));
    }

    @Test
    void loginOn_usesLoggedInNurse_ignoresRequested() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        when(securityHelper.getCurrentUserId()).thenReturn(20L);
        loginMode(true);
        NurseProfile me = new NurseProfile(); me.setId(11L); me.setHospitalId(7L); me.setIsActive(true);
        when(nurseProfileRepository.findByUserId(20L)).thenReturn(Optional.of(me));
        assertThat(resolver.resolve(999L)).isEqualTo(11L);
    }

    @Test
    void loginOff_requiresRequestedId() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        loginMode(false);
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Performed By");
    }

    @Test
    void loginOff_validatesAndReturnsRequested() {
        when(securityHelper.getCurrentHospitalId()).thenReturn(7L);
        loginMode(false);
        NurseProfile p = new NurseProfile(); p.setId(12L); p.setHospitalId(7L); p.setIsActive(true);
        when(nurseProfileRepository.findById(12L)).thenReturn(Optional.of(p));
        assertThat(resolver.resolve(12L)).isEqualTo(12L);
    }
}
