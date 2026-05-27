package com.activityworkflow.repository;


import com.activityworkflow.entity.ProcessInstanceEntity;
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
     * Returns the most recent transition for the given (businessService, businessId)
     * — that row's currentState is the actual current state of the record.
     */
    @Query("""
           SELECT p FROM ProcessInstanceEntity p
            WHERE p.businessService = :businessService
              AND p.businessId      = :businessId
            ORDER BY p.auditDetails.createdTime DESC, p.uuid DESC
           """)
    List<ProcessInstanceEntity> findLatestList(@Param("businessService") String businessService,
                                               @Param("businessId") String businessId);

    default Optional<ProcessInstanceEntity> findLatest(String businessService, String businessId) {
        return findLatestList(businessService, businessId).stream().findFirst();
    }

    /** Full history, oldest first — used by GET /transitions/{businessService}/{businessId}. */
    List<ProcessInstanceEntity>
        findByBusinessServiceAndBusinessIdOrderByAuditDetails_CreatedTimeAsc(String businessService,
                                                                            String businessId);
}
