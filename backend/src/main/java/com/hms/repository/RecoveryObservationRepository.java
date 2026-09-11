package com.hms.repository;

import com.hms.entity.RecoveryObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecoveryObservationRepository extends JpaRepository<RecoveryObservation, Long> {
    List<RecoveryObservation> findByEpisodeIdOrderByObservedAtAsc(Long episodeId);
}
