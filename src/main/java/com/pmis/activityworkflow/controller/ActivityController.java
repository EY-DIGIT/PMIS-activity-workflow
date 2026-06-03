package com.pmis.activityworkflow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.pmis.activityworkflow.service.ActivityService;
import com.pmis.activityworkflow.web.models.ActivityDTO;
import com.pmis.activityworkflow.web.request.ActivityRequest;
import com.pmis.activityworkflow.web.response.ActivityResponse;

import java.util.List;

@RestController
@RequestMapping("/activities")
@Tag(name = "Activities", description = "Workflow definitions")
@RequiredArgsConstructor
@Slf4j
public class ActivityController {

    private final ActivityService activityService;

    @PostMapping
    @Operation(summary = "Create one or more Activity workflows")
    public ResponseEntity<ActivityResponse> create(@Valid @RequestBody ActivityRequest request) {

        List<ActivityDTO> saved = activityService.create(request);
        return new ResponseEntity<>(
                ActivityResponse.builder().activities(saved).build(),
                HttpStatus.CREATED);
    }

    @GetMapping("/{uuid}")
    @Operation(summary = "Fetch an Activity by uuid")
    public ResponseEntity<ActivityDTO> getByUuid(
            @Parameter(description = "Activity uuid") @PathVariable String uuid) {

        return ResponseEntity.ok(activityService.findByUuid(uuid));
    }

    @GetMapping
    @Operation(summary = "List Activities — optional filters")
    public ResponseEntity<ActivityResponse> search(
            @RequestParam(required = false) String activityName,
            @RequestParam(required = false) String businessModule) {

        List<ActivityDTO> results;
        if (activityName != null && !activityName.isBlank()) {
            results = activityService.findByActivityName(activityName);
        } else if (businessModule != null && !businessModule.isBlank()) {
            results = activityService.findByBusinessModule(businessModule);
        } else {
            results = activityService.findAll();
        }
        return ResponseEntity.ok(
                ActivityResponse.builder().activities(results).build());
    }

    @DeleteMapping("/{uuid}")
    @Operation(summary = "Delete an Activity by uuid")
    public ResponseEntity<Void> delete(@PathVariable String uuid) {
        activityService.delete(uuid);
        return ResponseEntity.noContent().build();
    }
}
