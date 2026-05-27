package com.activityworkflow.service;

import com.activityworkflow.enrichment.ActivityEnrichmentService;
import com.activityworkflow.entity.ActivityEntity;
import com.activityworkflow.exception.ActivityNotFoundException;
import com.activityworkflow.exception.DuplicateActivityException;
import com.activityworkflow.mapper.ActivityMapper;
import com.activityworkflow.repository.ActivityRepository;
import com.activityworkflow.web.models.ActivityDTO;
import com.activityworkflow.web.request.ActivityRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ActivityService {

    private static final String DEFAULT_USER = "system";

    private final ActivityEnrichmentService enrichmentService;
    private final ActivityRepository repository;
    private final ActivityMapper mapper;

    /* ===================  Write  =================== */

    @Transactional
    public List<ActivityDTO> create(ActivityRequest request) {

        enrichmentService.enrichForCreate(request, DEFAULT_USER);

        // Reject duplicates up-front for a cleaner error than a DB unique-violation
        request.getActivities().forEach(dto -> {
            if (repository.existsByActivityName(dto.getActivityName())) {
                throw new DuplicateActivityException(
                        "Activity already exists with name: " + dto.getActivityName());
            }
        });

        List<ActivityEntity> toPersist = request.getActivities().stream()
                .map(mapper::toEntity)
                .collect(Collectors.toList());

        List<ActivityEntity> persisted = repository.saveAll(toPersist);

        log.info("Persisted {} Activity workflow(s)", persisted.size());

        return mapper.toDTOList(persisted);
    }

    /* ===================  Reads  =================== */

    @Transactional(readOnly = true)
    public ActivityDTO findByUuid(String uuid) {
        return repository.findByUuid(uuid)
                .map(mapper::toDTO)
                .orElseThrow(() -> new ActivityNotFoundException(
                        "Activity not found: " + uuid));
    }

    @Transactional(readOnly = true)
    public List<ActivityDTO> findAll() {
        return mapper.toDTOList(repository.findAll());
    }

    @Transactional(readOnly = true)
    public List<ActivityDTO> findByActivityName(String activityName) {
        return mapper.toDTOList(repository.findByActivityName(activityName));
    }

    @Transactional(readOnly = true)
    public List<ActivityDTO> findByBusinessModule(String businessModule) {
        return mapper.toDTOList(repository.findByBusinessModule(businessModule));
    }

    /* ===================  Delete  =================== */

    @Transactional
    public void delete(String uuid) {
        ActivityEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new ActivityNotFoundException(
                        "Activity not found: " + uuid));
        repository.delete(entity);
        log.info("Deleted Activity uuid={}", uuid);
    }
}
