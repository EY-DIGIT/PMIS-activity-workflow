package com.pmis.activityworkflow.service.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.models.Role;
import com.pmis.activityworkflow.web.models.UserInfo;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload + persist a document.
 *
 * <p>The upload itself happens via the upstream milestone-comments API
 * (see {@link MilestoneCommentsClient}). On success we persist a row in
 * {@code aw_document} that references the upstream commentId.</p>
 *
 * <p>Order matters: the upstream call runs BEFORE the DB transaction, so
 * a failure never leaves an orphan row. If the upstream upload succeeds
 * but the DB save crashes, the comment exists upstream with no local
 * record — we log loudly so it can be reconciled.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final MilestoneCommentsClient milestoneCommentsClient;
    private final DocumentRepository documentRepository;

    public DocumentEntity uploadAndAttach(MultipartFile file,
                                          DocumentMetadata meta,
                                          RequestInfo requestInfo) {

        // 1. POST to upstream comments API (outside the DB tx)
        MilestoneCommentResult stored = milestoneCommentsClient.uploadComment(
                meta.activityId(), file, meta.comment());

        if (stored.getCommentId() == null) {
            throw new IllegalStateException(
                    "Upstream comments API accepted the upload but returned no commentId");
        }

        // 2. persist the row in its own transaction
        try {
            return persistRow(file, meta, requestInfo, stored);
        } catch (Exception ex) {
            log.error("Document upload SUCCEEDED upstream (commentId={}, milestoneId={}) "
                            + "but DB persist FAILED — manual cleanup may be required",
                    stored.getCommentId(), stored.getMilestoneId(), ex);
            throw ex;
        }
    }

    @Transactional
    protected DocumentEntity persistRow(MultipartFile file,
                                        DocumentMetadata meta,
                                        RequestInfo requestInfo,
                                        MilestoneCommentResult stored) {

        long now = System.currentTimeMillis();
        UserSnapshot u = userSnapshot(requestInfo);

        DocumentEntity row = DocumentEntity.builder()
                .uuid(UUID.randomUUID().toString())
                .docId(stored.getCommentId())            // upstream commentId is our docId
                .storeId(stored.getMilestoneId())        // upstream milestoneId is our storeId
                .fileUrl(stored.getFileUrl())
                .fileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .documentType(meta.documentType())
                .activityId(meta.activityId())
                .projectId(meta.projectId())
                .businessService(meta.businessService())
                .processInstanceId(meta.processInstanceId())
                .uploadedByUuid(u.uuid())
                .uploadedByUsername(u.username())
                .uploadedByRoles(u.roles())
                .comment(meta.comment())
                .createdAt(now)
                .updatedAt(now)
                .build();

        DocumentEntity saved = documentRepository.save(row);
        log.info("Document persisted: uuid={} commentId={} milestoneId={} activityId={} uploadedBy={}",
                saved.getUuid(), saved.getDocId(), saved.getStoreId(),
                saved.getActivityId(), u.uuid());
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

    /** Carrier for the form fields that travel alongside the file. */
    public record DocumentMetadata(
            String documentType,
            String activityId,
            String projectId,
            String businessService,
            String processInstanceId,
            String comment) {}
}
