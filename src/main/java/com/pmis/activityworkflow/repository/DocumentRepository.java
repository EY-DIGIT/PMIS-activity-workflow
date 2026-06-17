package com.pmis.activityworkflow.repository;

import com.pmis.activityworkflow.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    List<DocumentEntity> findByBusinessServiceAndActivityIdOrderByCreatedAtAsc(
            String businessService, String activityId);

    List<DocumentEntity> findByActivityIdOrderByCreatedAtAsc(String activityId);

    List<DocumentEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<DocumentEntity> findByUploadedByUuidOrderByCreatedAtDesc(String uploadedByUuid);

    /** All documents for one activity scoped to a specific division. */
    List<DocumentEntity> findByActivityIdAndDivisionCodeOrderByCreatedAtAsc(
            String activityId, String divisionCode);

    /** All documents for one activity intended for a specific reviewer. */
    List<DocumentEntity> findByActivityIdAndReviewerUuidOrderByCreatedAtAsc(
            String activityId, String reviewerUuid);

    /** Idempotent re-upload check — if the same docId came back from the store. */
    Optional<DocumentEntity> findByDocId(String docId);
}
