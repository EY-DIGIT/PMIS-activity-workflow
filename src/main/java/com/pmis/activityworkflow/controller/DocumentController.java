package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.DocumentEntity;
import com.pmis.activityworkflow.repository.DocumentRepository;
import com.pmis.activityworkflow.service.document.DocumentService;
import com.pmis.activityworkflow.service.document.DocumentService.DocumentMetadata;
import com.pmis.activityworkflow.web.models.RequestInfo;
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
