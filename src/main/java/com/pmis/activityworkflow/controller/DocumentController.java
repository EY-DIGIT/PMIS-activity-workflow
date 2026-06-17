package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.service.document.DocumentService;
import com.pmis.activityworkflow.service.document.DocumentService.DocumentMetadata;
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

@RestController
@RequestMapping("/activities/documents")
@Tag(name = "Documents", description = "Upload + retrieve activity documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

    private final DocumentService documentService;
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
     * Pre-upload endpoint for the division-approval flow.
     *
     * <p>Call this <em>before</em> submitting
     * {@code POST /activities/parallel/request-division-approval}. Each file
     * is forwarded to the upstream comments API
     * ({@code POST /projects/api/v3/activities/{activityId}/comments}) and a
     * local {@code aw_document} row is created immediately (divisionCode stays
     * {@code null} until {@code requestDivisionApproval} stamps it).</p>
     *
     * <p>The returned {@code documentStoreId} is the upstream comment id —
     * pass it in {@code divisionApprovals[].documentStoreIds}.</p>
     *
     * <p>Form fields:
     * <ul>
     *   <li><b>file</b> – one or more files (repeat the part for multiple)</li>
     *   <li><b>activityId</b> – required; the activity these files belong to</li>
     * </ul>
     * </p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Pre-upload files; returns documentStoreId per file for division approval")
    public ResponseEntity<List<DocumentUploadResponse>> uploadForDivision(
            @RequestPart("file") List<MultipartFile> files,
            @RequestParam String activityId) {

        DocumentMetadata meta = new DocumentMetadata(
                "DIVISION_DOCUMENT", activityId, null, null, null, null);

        List<DocumentUploadResponse> responses = files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .map(file -> {
                    DocumentEntity saved = documentService.uploadAndAttach(file, meta, null);
                    return DocumentUploadResponse.builder()
                            .documentStoreId(saved.getDocId())
                            .fileName(saved.getFileName())
                            .fileUrl(saved.getFileUrl())
                            .activityId(saved.getActivityId())
                            .build();
                })
                .toList();

        return ResponseEntity.ok(responses);
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
