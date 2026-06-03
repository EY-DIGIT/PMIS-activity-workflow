package com.pmis.activityworkflow.repository;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowAuditRepository
        extends JpaRepository<WorkflowAuditEntity, String> {

    /** Audit trail for one record, oldest first. */
    List<WorkflowAuditEntity>
        findByBusinessServiceAndActivityIdOrderByCreatedTimeAsc(String businessService,
                                                               String activityId);

    /**
     * Audit trail for an activity, oldest first — businessService not required.
     * An activity belongs to exactly one workflow in this system, so the
     * activityId alone is enough to uniquely identify the timeline.
     */
    List<WorkflowAuditEntity>
        findByActivityIdOrderByCreatedTimeAsc(String activityId);

    /** Every attempt by a given user, newest first. */
    List<WorkflowAuditEntity> findByPerformedByUuidOrderByCreatedTimeDesc(String performedByUuid);

    /** All failed attempts for a workflow, newest first — useful for security review. */
    List<WorkflowAuditEntity>
        findByBusinessServiceAndOutcomeOrderByCreatedTimeDesc(String businessService,
                                                             String outcome);
}
