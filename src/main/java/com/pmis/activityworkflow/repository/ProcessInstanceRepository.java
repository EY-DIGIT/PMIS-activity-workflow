package com.pmis.activityworkflow.repository;

import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessInstanceRepository
        extends JpaRepository<ProcessInstanceEntity, String> {

    /**
     * Returns the most recent transition for the given (businessService, activityId)
     * — that row's currentState is the actual current state of the record.
     */
    @Query("""
           SELECT p FROM ProcessInstanceEntity p
            WHERE p.businessService = :businessService
              AND p.activityId      = :activityId
            ORDER BY p.auditDetails.createdTime DESC, p.uuid DESC
           """)
    List<ProcessInstanceEntity> findLatestList(@Param("businessService") String businessService,
                                               @Param("activityId") String activityId);

    default Optional<ProcessInstanceEntity> findLatest(String businessService, String activityId) {
        return findLatestList(businessService, activityId).stream().findFirst();
    }

    /** Full history, oldest first — used by GET /transitions/{businessService}/{activityId}. */
    List<ProcessInstanceEntity>
        findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(String businessService,
                                                                            String activityId);

    /**
     * Latest SUBMIT transition for an activity — used by the approval inbox
     * to populate the "Submitted" timestamp. Returns the row, not just the
     * timestamp, in case the caller wants the actor too.
     */
    @Query("""
           SELECT p FROM ProcessInstanceEntity p
            WHERE p.businessService = :businessService
              AND p.activityId      = :activityId
              AND p.actionName      = 'SUBMIT'
            ORDER BY p.auditDetails.createdTime DESC, p.uuid DESC
           """)
    List<ProcessInstanceEntity> findLatestSubmits(@Param("businessService") String businessService,
                                                  @Param("activityId")      String activityId);

    default Optional<ProcessInstanceEntity> findLatestSubmit(String businessService, String activityId) {
        return findLatestSubmits(businessService, activityId).stream().findFirst();
    }
}
