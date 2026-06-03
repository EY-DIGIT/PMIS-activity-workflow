package com.pmis.activityworkflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.pmis.activityworkflow.entity.ActivityEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface ActivityRepository extends JpaRepository<ActivityEntity, String> {

    Optional<ActivityEntity> findByUuid(String uuid);

    List<ActivityEntity> findByActivityName(String activityName);

    List<ActivityEntity> findByBusinessModule(String businessModule);

    boolean existsByActivityName(String activityName);
}
