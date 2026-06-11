package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.WorkflowAuditEntity;
import com.pmis.activityworkflow.repository.WorkflowAuditRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only access to the workflow audit trail.
 *
 * Returns {@link WorkflowAuditEntity} rows directly — they're already a flat,
 * read-friendly shape, so no separate DTO is needed for this internal/ops view.
 */
@RestController
@RequestMapping("/activities/audit")
@Tag(name = "Workflow Audit", description = "Audit trail of every transition attempt")
@RequiredArgsConstructor
public class WorkflowAuditController {

    private final WorkflowAuditRepository auditRepository;

    @GetMapping("/{businessService}/{activityId}")
    @Operation(summary = "Full audit trail for an activityId (newest first)")
    public ResponseEntity<List<WorkflowAuditEntity>> byActivityId(
            @PathVariable String businessService,
            @PathVariable String activityId) {

        return ResponseEntity.ok(
                auditRepository.findByBusinessServiceAndActivityIdOrderByCreatedTimeDesc(
                        businessService, activityId));
    }

    @GetMapping("/user/{userUuid}")
    @Operation(summary = "All attempts made by a user (newest first)")
    public ResponseEntity<List<WorkflowAuditEntity>> byUser(@PathVariable String userUuid) {
        return ResponseEntity.ok(
                auditRepository.findByPerformedByUuidOrderByCreatedTimeDesc(userUuid));
    }

    @GetMapping("/{businessService}/failures")
    @Operation(summary = "All FAILED attempts for a workflow (newest first)")
    public ResponseEntity<List<WorkflowAuditEntity>> failures(
            @PathVariable String businessService) {

        return ResponseEntity.ok(
                auditRepository.findByBusinessServiceAndOutcomeOrderByCreatedTimeDesc(
                        businessService, "FAILED"));
    }
}