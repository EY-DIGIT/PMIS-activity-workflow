package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.service.assignments.ActivityAssignmentsClient;
import com.pmis.activityworkflow.service.assignments.AssignmentData;
import com.pmis.activityworkflow.service.document.DocumentService;
import com.pmis.activityworkflow.service.document.DocumentService.DocumentMetadata;
import com.pmis.activityworkflow.service.document.DocumentService.MultiUploadResult;
import com.pmis.activityworkflow.service.document.MilestoneCommentResult;
import com.pmis.activityworkflow.web.models.RequestInfo;
import com.pmis.activityworkflow.web.response.DocumentUploadResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/activities/documents")
@Tag(name = "Documents", description = "Upload + retrieve activity documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService documentService;
    private final ActivityAssignmentsClient assignmentsClient;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    /**
     * Multipart upload. The file goes to the external file-store; the
     * returned docId/storeId + form metadata are saved in {@code aw_document}.
     *
     * <p>The {@code requestInfo} part should be a JSON string carrying
     * {@code userInfo.uuid} and roles — same shape as on transition requests.</p>
     *
     * <p>Form fields (all parts of {@code multipart/form-data}):
     * <ul>
     *   <li><b>file</b> - required, the file itself</li>
     *   <li><b>requestInfo</b> - optional JSON; uploader identity</li>
     *   <li><b>businessService</b>, <b>activityId</b> - which record</li>
     *   <li><b>activityId</b>, <b>projectId</b> - correlation IDs</li>
     *   <li><b>documentType</b>, <b>comment</b>, <b>processInstanceId</b> - optional</li>
     * </ul>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document (forwards to external file-store)")
    public ResponseEntity<DocumentEntity> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "requestInfo", required = false) String requestInfoJson,
            @RequestParam(required = false) String businessService,
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String documentType,
            @RequestParam(required = false) String processInstanceId,
            @RequestParam(required = false) String comment) {

        RequestInfo requestInfo = parseRequestInfo(requestInfoJson);

        DocumentMetadata meta = new DocumentMetadata(
                documentType, activityId, projectId,
                businessService, processInstanceId, comment);

        DocumentEntity saved = documentService.uploadAndAttach(file, meta, requestInfo);
        return new ResponseEntity<>(saved, HttpStatus.CREATED);
    }

    /**
     * Per-division pre-upload endpoint for the "Request Concerned Division Approval" flow.
     *
     * <p>Call this <strong>once per division</strong> before submitting
     * {@code POST /activities/parallel/request-division-approval}.
     * All files for that division are uploaded together with the division's
     * comment in a single call to the upstream comments API
     * ({@code POST /projects/api/v3/activities/{activityId}/comments}), producing
     * ONE comment with multiple attachments. The returned {@code documentStoreId}
     * is the upstream comment id — pass it in
     * {@code divisionApprovals[].documentStoreIds}.</p>
     *
     * <p>The reviewer UUID is resolved automatically from the upstream assignments
     * API so the inbox can filter by user.</p>
     *
     * <p>Form fields:
     * <ul>
     *   <li><b>file</b> – one or more files (repeat part for each file)</li>
     *   <li><b>activityId</b> – required</li>
     *   <li><b>divisionId</b> – required (e.g. "tmd-i", "TMD-II")</li>
     *   <li><b>comment</b> – optional comment text for this division's reviewer</li>
     * </ul>
     * </p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Per-division file upload; returns one documentStoreId for all files + comment")
    public ResponseEntity<DocumentUploadResponse> uploadForDivision(
            @RequestPart(value = "file", required = false) List<MultipartFile> files,
            @RequestParam String activityId,
            @RequestParam String divisionId,
            @RequestParam(required = false) String comment) {

        // Detect owner-division upload — divisionId == "OWNER" (case-insensitive)
        boolean isOwnerDivision = "OWNER".equalsIgnoreCase(divisionId);

        String reviewerUuid = isOwnerDivision
                ? resolveOwnerReviewerUuid(activityId)
                : resolveReviewerUuid(activityId, divisionId);

        String documentType     = isOwnerDivision ? "OWNER_DOCUMENT"     : "DIVISION_DOCUMENT";
        String documentCategory = isOwnerDivision ? "OWNER_DIVISION"     : "CONCERNED_DIVISION";

        DocumentMetadata meta = new DocumentMetadata(
                documentType, activityId, null, null, null, comment, divisionId, documentCategory);

        MultiUploadResult result = documentService.uploadMultipleAndAttach(
                files == null ? List.of() : files, reviewerUuid, meta, null);

        List<DocumentUploadResponse.UploadedFile> uploadedFiles = result.attachments().stream()
                .map(a -> DocumentUploadResponse.UploadedFile.builder()
                        .fileName(a.getFileName())
                        .fileUrl(a.getFileUrl())
                        .mimeType(a.getMimeType())
                        .sizeBytes(a.getSizeBytes())
                        .build())
                .toList();

        DocumentUploadResponse response = DocumentUploadResponse.builder()
                .documentStoreId(result.entity().getDocId())
                .divisionId(divisionId)
                .activityId(result.entity().getActivityId())
                .comment(comment)
                .documentCategory(documentCategory)
                .uploadedAt(result.entity().getCreatedAt())
                .attachments(uploadedFiles)
                .build();

        return ResponseEntity.ok(response);
    }

    /** Look up ownerApprover[0].id from the assignments API. */
    private String resolveOwnerReviewerUuid(String activityId) {
        try {
            AssignmentData data = assignmentsClient.fetch(activityId);
            if (data.getOwnerApprover() == null || data.getOwnerApprover().isEmpty()) return null;
            return data.getOwnerApprover().get(0).getId();
        } catch (Exception ex) {
            log.warn("Could not resolve owner reviewer UUID for activity {}: {}",
                    activityId, ex.getMessage());
            return null;
        }
    }

    /** Look up divisionApprovers[divisionId][0].id from the assignments API. */
    private String resolveReviewerUuid(String activityId, String divisionId) {
        try {
            AssignmentData data = assignmentsClient.fetch(activityId);
            Map<String, List<AssignmentData.UserRef>> approvers = data.getDivisionApprovers();
            if (approvers == null) return null;
            return approvers.entrySet().stream()
                    .filter(e -> divisionId.equalsIgnoreCase(e.getKey()))
                    .map(Map.Entry::getValue)
                    .filter(list -> list != null && !list.isEmpty())
                    .map(list -> list.get(0).getId())
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve reviewer UUID for division {} / activity {}: {}",
                    divisionId, activityId, ex.getMessage());
            return null;
        }
    }

    /**
     * Remove a pre-uploaded document so it no longer appears in the approval
     * inbox. Use the {@code documentStoreId} returned by the upload endpoint.
     *
     * <p>Only the local {@code aw_document} row is removed; the actual
     * file/comment on the upstream service is not affected.</p>
     */
    @DeleteMapping("/store/{docId}")
    @Operation(summary = "Delete a document reference by its store ID (removes from inbox)")
    public ResponseEntity<Void> deleteByDocId(@PathVariable String docId) {
        documentService.deleteByDocId(docId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Fetch one document by its local uuid")
    public ResponseEntity<DocumentEntity> get(@PathVariable String uuid) {
        return documentRepository.findById(uuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/activity/{businessService}/{activityId}")
    @Operation(summary = "All documents attached to a record (oldest first)")
    public ResponseEntity<List<DocumentEntity>> forActivityId(
            @PathVariable String businessService,
            @PathVariable String activityId) {
        return ResponseEntity.ok(
                documentRepository.findByBusinessServiceAndActivityIdOrderByCreatedAtAsc(
                        businessService, activityId));
    }

    @GetMapping("/activity/{activityId}")
    @Operation(summary = "All documents for an activityId")
    public ResponseEntity<List<DocumentEntity>> forActivity(@PathVariable String activityId) {
        return ResponseEntity.ok(documentRepository.findByActivityIdOrderByCreatedAtAsc(activityId));
    }

    @GetMapping("/project/{projectId}")
    @Operation(summary = "All documents for a projectId (newest first)")
    public ResponseEntity<List<DocumentEntity>> forProject(@PathVariable String projectId) {
        return ResponseEntity.ok(documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
    }

    /* ============================================================ */

    /** Parse the requestInfo form field if present; tolerate malformed JSON. */
    private RequestInfo parseRequestInfo(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, RequestInfo.class);
        } catch (JsonProcessingException ex) {
            log.warn("Could not parse 'requestInfo' form field as JSON, uploader will be 'system': {}",
                    ex.getMessage());
            return null;
        }
    }
}
