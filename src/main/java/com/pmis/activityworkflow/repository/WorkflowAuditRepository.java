package com.pmis.activityworkflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;

import java.util.List;

@Repository
public interface WorkflowAuditRepository
        extends JpaRepository<WorkflowAuditEntity, String> {

    /** Audit trail for one record, oldest first. */
    List<WorkflowAuditEntity>
        findByBusinessServiceAndActivityIdOrderByCreatedTimeAsc(String businessService,
                                                               String activityId);

    /** Every attempt by a given user, newest first. */
    List<WorkflowAuditEntity> findByPerformedByUuidOrderByCreatedTimeDesc(String performedByUuid);

    /** All failed attempts for a workflow, newest first — useful for security review. */
    List<WorkflowAuditEntity>
        findByBusinessServiceAndOutcomeOrderByCreatedTimeDesc(String businessService,
                                                             String outcome);
}
