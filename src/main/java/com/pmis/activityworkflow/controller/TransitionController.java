package com.pmis.activityworkflow.controller;

import com.pmis.activityworkflow.entity.ProcessInstanceEntity;
import com.pmis.activityworkflow.repository.ProcessInstanceRepository;
import com.pmis.activityworkflow.service.WorkflowTransitionService;
import com.pmis.activityworkflow.web.models.AuditDetailsDTO;
import com.pmis.activityworkflow.web.models.ProcessInstanceDTO;
import com.pmis.activityworkflow.web.request.TransitionRequest;
import com.pmis.activityworkflow.web.response.TransitionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/activities")
@Tag(name = "Workflow Transitions", description = "Fire transitions and read history")
@RequiredArgsConstructor
public class TransitionController {

    private final WorkflowTransitionService transitionService;
    private final ProcessInstanceRepository processRepository;

    /**
     * Mirrors Digit's {@code POST /egov-wf/process/_transition} — same JSON
     * envelope, same response shape.
     */
    @PostMapping("/process/_transition")
    @Operation(summary = "Fire one or more transitions (Digit-compatible)")
    public ResponseEntity<TransitionResponse> transition(
            @Valid @RequestBody TransitionRequest request) {

        List<ProcessInstanceDTO> result = transitionService.transition(request);
        return new ResponseEntity<>(
                TransitionResponse.builder().processInstances(result).build(),
                HttpStatus.OK);
    }

    /**
     * Full transition history for a activityId, oldest first.
     */
    @GetMapping("/process/_search/{businessService}/{activityId}")
    @Operation(summary = "Get transition history for a activityId")
    public ResponseEntity<TransitionResponse> history(
            @PathVariable String businessService,
            @PathVariable String activityId) {

        List<ProcessInstanceDTO> history = processRepository
                .findByBusinessServiceAndActivityIdOrderByAuditDetails_CreatedTimeAsc(
                        businessService, activityId)
                .stream()
                .map(this::toDTO)
                .toList();

        return ResponseEntity.ok(
                TransitionResponse.builder().processInstances(history).build());
    }

    private ProcessInstanceDTO toDTO(ProcessInstanceEntity e) {
        return ProcessInstanceDTO.builder()
                .id(e.getUuid())
                .businessService(e.getBusinessService())
                .activityId(e.getActivityId())
                .moduleName(e.getModuleName())
                .action(e.getActionName())
                .comment(e.getComment())
                .previousStatus(e.getPreviousState())
                .stateSla(e.getSla())
                .auditDetails(AuditDetailsDTO.builder()
                        .createdBy(e.getAuditDetails().getCreatedBy())
                        .createdTime(e.getAuditDetails().getCreatedTime())
                        .lastModifiedBy(e.getAuditDetails().getLastModifiedBy())
                        .lastModifiedTime(e.getAuditDetails().getLastModifiedTime())
                        .build())
                .build();
    }
}
