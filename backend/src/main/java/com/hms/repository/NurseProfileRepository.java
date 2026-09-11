package com.hms.repository;

import com.hms.entity.NurseProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NurseProfileRepository extends JpaRepository<NurseProfile, Long> {
    Optional<NurseProfile> findByEmail(String email);
    Optional<NurseProfile> findByEmailAndHospitalId(String email, Long hospitalId);
    Optional<NurseProfile> findByUserId(Long userId);
    Optional<NurseProfile> findByPublicId(String publicId);
    Optional<NurseProfile> findByIdAndHospitalId(Long id, Long hospitalId);

    /** Count active nurses currently assigned to a ward (blocks ward deletion). */
    long countByWardIdAndIsActiveTrue(Long wardId);

    /** Active, on-shift nurses assigned to a ward (candidates for auto-assignment). */
    java.util.List<NurseProfile> findByWardIdAndIsActiveTrueAndOnShiftTrue(Long wardId);

    /** Active, non-incharge staff nurses assigned to a ward (candidates for Phase A auto-assignment). */
    java.util.List<NurseProfile> findByWardIdAndIsInchargeFalseAndIsActiveTrue(Long wardId);
}
