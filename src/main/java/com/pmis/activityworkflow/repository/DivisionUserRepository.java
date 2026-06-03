package com.pmis.activityworkflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pmis.activityworkflow.entity.DivisionUserEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface DivisionUserRepository extends JpaRepository<DivisionUserEntity, String> {

    /** All collaborators across all divisions for one record + state. */
    List<DivisionUserEntity>
        findByBusinessServiceAndActivityIdAndStateName(String businessService,
                                                       String activityId,
                                                       String stateName);

    /** Collaborators for one specific division at one record + state. */
    List<DivisionUserEntity>
        findByBusinessServiceAndActivityIdAndStateNameAndDivisionCode(
                String businessService, String activityId, String stateName, String divisionCode);

    /** Idempotency check on re-seed. */
    Optional<DivisionUserEntity>
        findByBusinessServiceAndActivityIdAndStateNameAndDivisionCodeAndUserUuid(
                String businessService, String activityId, String stateName,
                String divisionCode, String userUuid);
}
