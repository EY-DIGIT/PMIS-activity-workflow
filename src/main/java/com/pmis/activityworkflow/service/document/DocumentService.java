package com.pmis.activityworkflow.service.document;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.Role;
import com.pmis.activityworkflow.web.models.UserInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

/**
 * Upload + persist a document reference.
 *
 * <p>The file itself is POSTed to the upstream comments API
 * ({@link MilestoneCommentsClient}). Locally we keep just the upstream
 * comment id plus enough context to answer "who uploaded what comment for
 * which activity, when".</p>
 *
 * <p>Order matters — the upstream call runs BEFORE the DB transaction, so
 * a failure never leaves an orphan row. If the upstream upload succeeds
 * but the DB save crashes, the comment exists upstream with no local row;
 * we log loudly so it can be reconciled.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final MilestoneCommentsClient milestoneCommentsClient;
    private final DocumentRepository documentRepository;

    /**
     * Upload multiple files + comment for ONE division in a single upstream call.
     * All files land as {@code attachments[]} on the same upstream comment.
     * One {@code aw_document} row is created (keyed by the upstream comment id)
     * and tagged with {@code divisionCode} + {@code reviewerUuid} so the inbox
     * can filter correctly.
     *
     * @return a {@link MultiUploadResult} carrying the saved entity plus ALL
     *         attachment info (fileName, fileUrl, mimeType, sizeBytes) for every
     *         file that the upstream reported back.
     */
    public MultiUploadResult uploadMultipleAndAttach(List<MultipartFile> files,
                                                      String reviewerUuid,
                                                      DocumentMetadata meta,
                                                      RequestInfo requestInfo) {

        MilestoneCommentResult stored = milestoneCommentsClient.uploadCommentWithFiles(
                meta.activityId(), files, meta.comment());

        if (stored.getDocId() == null) {
            throw new IllegalStateException(
                    "Upstream comments API accepted the upload but returned no id");
        }

        try {
            long now = System.currentTimeMillis();
            UserSnapshot u = userSnapshot(requestInfo);

            DocumentEntity row = DocumentEntity.builder()
                    .uuid(UUID.randomUUID().toString())
                    .docId(stored.getDocId())
                    .activityId(stored.getActivityId() != null
                            ? stored.getActivityId() : meta.activityId())
                    .projectId(meta.projectId())
                    .businessService(meta.businessService())
                    .processInstanceId(meta.processInstanceId())
                    .documentType(meta.documentType())
                    .uploadedByUuid(u.uuid())
                    .uploadedByUsername(stored.getAuthorLogin() != null
                            ? stored.getAuthorLogin() : u.username())
                    .uploadedByEmail(stored.getAuthorEmail())
                    .uploadedByRoles(u.roles())
                    .divisionCode(meta.divisionCode())
                    .reviewerUuid(reviewerUuid)
                    .fileName(stored.getFileName())
                    .fileUrl(stored.getFileUrl())
                    .comment(meta.comment())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            DocumentEntity saved = documentRepository.save(row);
            log.info("Multi-file document persisted: uuid={} docId={} activityId={} "
                    + "divisionCode={} reviewerUuid={} files={} uploadedBy={}",
                    saved.getUuid(), saved.getDocId(), saved.getActivityId(),
                    saved.getDivisionCode(), reviewerUuid,
                    stored.getAttachments().size(), u.uuid());
            return new MultiUploadResult(saved, stored.getAttachments());

        } catch (Exception ex) {
            log.error("Multi-file upload SUCCEEDED upstream (docId={}, activityId={}) "
                            + "but DB persist FAILED — manual cleanup may be required",
                    stored.getDocId(), stored.getActivityId(), ex);
            throw ex;
        }
    }

    /** Carries the persisted entity + every attachment returned by the upstream. */
    public record MultiUploadResult(
            DocumentEntity entity,
            List<MilestoneCommentResult.Attachment> attachments) {}

    public DocumentEntity uploadAndAttach(MultipartFile file,
                                          DocumentMetadata meta,
                                          RequestInfo requestInfo) {

        // 1. POST to upstream — returns the comment id we'll store as docId.
        MilestoneCommentResult stored = milestoneCommentsClient.uploadComment(
                meta.activityId(), file, meta.comment());

        if (stored.getDocId() == null) {
            throw new IllegalStateException(
                    "Upstream comments API accepted the upload but returned no id");
        }

        // 2. Persist locally.
        try {
            return persistRow(meta, requestInfo, stored);
        } catch (Exception ex) {
            log.error("Document upload SUCCEEDED upstream (docId={}, activityId={}) "
                            + "but DB persist FAILED — manual cleanup may be required",
                    stored.getDocId(), stored.getActivityId(), ex);
            throw ex;
        }
    }

    @Transactional
    protected DocumentEntity persistRow(DocumentMetadata meta,
                                        RequestInfo requestInfo,
                                        MilestoneCommentResult stored) {

        long now = System.currentTimeMillis();
        UserSnapshot u = userSnapshot(requestInfo);

        DocumentEntity row = DocumentEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .docId(stored.getDocId())
                .activityId(stored.getActivityId() != null
                        ? stored.getActivityId() : meta.activityId())
                .projectId(meta.projectId())
                .businessService(meta.businessService())
                .processInstanceId(meta.processInstanceId())
                .documentType(meta.documentType())
                .uploadedByUuid(u.uuid())
                .uploadedByUsername(stored.getAuthorLogin() != null
                        ? stored.getAuthorLogin() : u.username())
                .uploadedByEmail(stored.getAuthorEmail())
                .uploadedByRoles(u.roles())
                .divisionCode(meta.divisionCode())
                .fileName(stored.getFileName())
                .fileUrl(stored.getFileUrl())
                .comment(meta.comment())
                .createdAt(now)
                .updatedAt(now)
                .build();

        DocumentEntity saved = documentRepository.save(row);
        log.info("Document persisted: uuid={} docId={} activityId={} uploadedBy={}",
                saved.getUuid(), saved.getDocId(), saved.getActivityId(), u.uuid());
        return saved;
    }

    /* ============================================================ */

    private UserSnapshot userSnapshot(RequestInfo info) {
        UserInfo u = Optional.ofNullable(info).map(RequestInfo::getUserInfo).orElse(null);
        if (u == null) {
            return new UserSnapshot("system", null, Collections.emptyList());
        }
        List<String> roles = (u.getRoles() == null ? Collections.<Role>emptyList() : u.getRoles())
                .stream()
                .map(Role::getCode)
                .filter(c -> c != null && !c.isBlank())
                .toList();
        return new UserSnapshot(
                u.getUuid() == null ? "system" : u.getUuid(),
                u.getUserName(),
                roles);
    }

    private record UserSnapshot(String uuid, String username, List<String> roles) {}

    /**
     * Find an existing pre-uploaded row by {@code docId} and stamp it with the
     * given {@code divisionCode} (and fill in any missing businessService/projectId).
     * If no row exists yet (legacy path where the frontend skipped pre-upload),
     * falls back to {@link #saveDocumentReference} which creates a new row —
     * though the fileUrl will be missing in that case.
     */
    @Transactional
    public DocumentEntity assignDivisionCode(String docId,
                                              String divisionCode,
                                              String reviewerUuid,
                                              String businessService,
                                              String projectId,
                                              DocumentMetadata fallbackMeta,
                                              RequestInfo requestInfo) {
        return documentRepository.findByDocId(docId)
                .map(doc -> {
                    doc.setDivisionCode(divisionCode);
                    doc.setReviewerUuid(reviewerUuid);
                    if (businessService != null && doc.getBusinessService() == null) {
                        doc.setBusinessService(businessService);
                    }
                    if (projectId != null && doc.getProjectId() == null) {
                        doc.setProjectId(projectId);
                    }
                    doc.setUpdatedAt(System.currentTimeMillis());
                    DocumentEntity updated = documentRepository.save(doc);
                    log.info("Document {} assigned divisionCode={} reviewerUuid={}",
                            docId, divisionCode, reviewerUuid);
                    return updated;
                })
                .orElseGet(() -> {
                    log.warn("No pre-uploaded row found for docId={}, creating reference "
                            + "(fileUrl will be missing)", docId);
                    return saveDocumentReference(docId, fallbackMeta, requestInfo);
                });
    }

    /**
     * Save a pre-existing document store reference without uploading anything.
     * Used when the frontend has already uploaded the file and passes back the
     * store ID (documentStoreId). Creates one {@link DocumentEntity} row that
     * links the store ID to its activity, division, and uploader.
     */
    @Transactional
    public DocumentEntity saveDocumentReference(String documentStoreId,
                                                DocumentMetadata meta,
                                                RequestInfo requestInfo) {
        long now = System.currentTimeMillis();
        UserSnapshot u = userSnapshot(requestInfo);

        DocumentEntity row = DocumentEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .docId(documentStoreId)
                .activityId(meta.activityId())
                .projectId(meta.projectId())
                .businessService(meta.businessService())
                .processInstanceId(meta.processInstanceId())
                .documentType(meta.documentType())
                .divisionCode(meta.divisionCode())
                .uploadedByUuid(u.uuid())
                .uploadedByUsername(u.username())
                .uploadedByRoles(u.roles())
                .fileUrl(documentStoreId)
                .comment(meta.comment())
                .createdAt(now)
                .updatedAt(now)
                .build();

        DocumentEntity saved = documentRepository.save(row);
        log.info("Document reference persisted: uuid={} docId={} activityId={} divisionCode={} uploadedBy={}",
                saved.getUuid(), saved.getDocId(), saved.getActivityId(),
                saved.getDivisionCode(), u.uuid());
        return saved;
    }

    /** Carrier for the form fields that travel alongside the file. */
    public record DocumentMetadata(
            String documentType,
            String activityId,
            String projectId,
            String businessService,
            String processInstanceId,
            String comment,
            String divisionCode) {

        /** Convenience constructor for callers that don't have a divisionCode. */
        public DocumentMetadata(String documentType, String activityId, String projectId,
                                String businessService, String processInstanceId, String comment) {
            this(documentType, activityId, projectId, businessService, processInstanceId, comment, null);
        }
    }
}
