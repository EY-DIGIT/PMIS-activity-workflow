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

    /** Carrier for the form fields that travel alongside the file. */
    public record DocumentMetadata(
            String documentType,
            String activityId,
            String projectId,
            String businessService,
            String processInstanceId,
            String comment) {}
}
